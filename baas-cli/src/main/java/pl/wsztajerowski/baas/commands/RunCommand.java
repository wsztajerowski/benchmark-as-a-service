package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import pl.wsztajerowski.baas.BaasVersion;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;
import pl.wsztajerowski.baas.infra.Ec2ProvisioningService;
import pl.wsztajerowski.baas.infra.ImageBuilderService;
import pl.wsztajerowski.baas.infra.RunnerImage;
import pl.wsztajerowski.baas.infra.RunnerJarResolver;
import pl.wsztajerowski.baas.infra.S3UploadService;
import pl.wsztajerowski.baas.infra.SsmService;
import pl.wsztajerowski.baas.infra.UserDataScriptBuilder;
import pl.wsztajerowski.baas.model.RunId;
import pl.wsztajerowski.baas.model.RunLayout;
import pl.wsztajerowski.baas.model.TagKeys;
import pl.wsztajerowski.baas.results.ResultRow;
import pl.wsztajerowski.baas.results.ResultsQueryService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

@Command(
    name = "run",
    mixinStandardHelpOptions = true,
    description = "Launch an EC2 runner for a pre-built benchmark JAR, and poll for results.",
    // Lines are kept under 80 columns: picocli wraps the footer at the usage width and
    // re-wrapping mid-sentence makes the -- rule harder to read than no footer at all.
    footer = {
        "",
        "Put -- before the benchmark parameters. Everything after it goes verbatim",
        "to benchmark-runner.jar. Without it, JMH flags are parsed as baas options",
        "and the command fails with: Unknown options: '-f', '-wi', '-i'",
        "",
        "  baas run jmh -- MyBenchmark -f 1 -wi 1 -i 3",
        "  baas run --instance-type c6i.4xlarge jmh -- MyBenchmark -f 1",
        "",
        "Measurements go to the DynamoDB results table. S3 receives process output,",
        "logs, profiler artifacts, the verbatim result JSON and the run-status",
        "sentinel. Discarding measurements needs an explicit --no-database; an",
        "unresolvable table fails before anything is launched."
    },
    separator = " "
)
public class RunCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(RunCommand.class);

    private static final List<String> VALID_TYPES = List.of("jmh", "jmh-with-async", "jmh-with-prof", "jcstress");

    @Mixin LoggingMixin loggingMixin;

    @Parameters(index = "0", paramLabel = "<type>",
        description = "Benchmark type: jmh, jmh-with-async, jmh-with-prof, jcstress.")
    String benchmarkType;

    @Parameters(index = "1..*", paramLabel = "PARAMS",
        description = "Parameters forwarded to benchmark-runner.jar. Must follow a -- separator.")
    List<String> benchmarkParams = new ArrayList<>();

    @Option(names = "--benchmark-jar", required = true,
        description = "Path to the pre-built benchmark JAR. Required — baas run builds nothing.")
    Path benchmarkJar;

    @Option(names = "--runner-jar", description = "Local runner JAR to upload for this run instead of "
        + "pinning the release matching this CLI's version. Required from an unreleased build.")
    Path runnerJar;

    @Option(names = "--instance-type", description = "EC2 instance type (overrides config default).")
    String instanceType;

    @Option(names = "--timeout", description = "Benchmark process timeout in seconds.")
    Integer timeoutSeconds;

    @Option(names = "--max-wall-clock", description = "Absolute wall-clock cap in seconds.")
    Integer wallClockSeconds;

    @Option(names = "--tag", description = "Tag recorded on the stored benchmark result (key=value), not just "
        + "the EC2 instance. Rejected for machine-observed keys (imageVersion, instanceType, jdk, cpuModel, "
        + "cpuArch, type) — those are captured on the instance so a result's tags can't disagree with its "
        + "own environment.json.")
    Map<String, String> extraTags = new LinkedHashMap<>();

    @Option(names = "--branch", description = "Branch recorded as the run's branch tag (defaults to the current git branch).")
    String branch;

    @Option(names = "--commit",
        description = "Commit recorded as the run's commit tag (defaults to the current git commit).")
    String commit;

    @Option(names = "--project", description = "Project name for the results partition (defaults to the git repository name).")
    String project;

    @Option(names = "--no-database", description = "Discard measurements instead of storing them. "
        + "Explicit opt-in: without it, an unresolvable results table fails before provisioning.")
    boolean noDatabase;

    // No --image-version: exactly one image is maintained, so there is nothing to select between.
    @Option(names = "--ami-id", description = "Launch from this AMI instead of the published runner image.")
    String amiIdOverride;

    @Option(names = "--format", defaultValue = "text",
        description = "Run summary format: text (default) or json. json writes one object to "
            + "standard output — on the failure path too, which is when the run id is most "
            + "needed — while diagnostics stay on standard error.")
    String format;

    private final ConfigService configService = new ConfigService();

    /**
     * `run`/`results`/`config show` are meant to run under BaasCliOperatorRole. When no
     * operator profile is configured they fall through to the default credential chain
     * rather than reusing `aws.profile`, which holds deployer credentials.
     */
    public static Optional<String> operatorCredentialsWarning(BaasConfig config) {
        return operatorCredentialsWarning(config, System.getenv());
    }

    /**
     * Package-private overload taking the environment explicitly, for the same testability reason
     * as {@link #resolveCommit(Path)}.
     *
     * <p>Silent when credentials already arrive from the environment. The warning's own advice —
     * {@code baas config set --operator-profile} — is not merely redundant there but wrong: in
     * continuous integration the credentials come from an OIDC federation the job performed, and
     * there is no profile to name. Falling through to the default credential chain is what the
     * method's own contract calls correct in that case, so warning about it trained the reader to
     * ignore a warning that still matters on a laptop.
     */
    /**
     * The runner subnet and security group, read from the installation's stack outputs.
     *
     * <p>Not cached in {@code ~/.baas/config.yaml}: editing {@code RunnerSecurityGroup}'s
     * {@code GroupDescription} replaces the security group and changes its id, and a stored copy
     * then points at a group that no longer exists. Resolving here removes that failure rather
     * than documenting it.
     */
    private static Map<String, String> resolveNetworking(AwsClientFactory factory, BaasConfig config) {
        Map<String, String> outputs;
        try (var cf = factory.cloudFormation()) {
            outputs = new CloudFormationService(cf).getStackOutputs(config.stackName());
        }
        List<String> missing = new ArrayList<>();
        for (String key : List.of("SubnetId", "SecurityGroupId")) {
            String value = outputs.get(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "Stack %s did not report %s. Run `baas admin setup`, or point this machine at a "
                    .formatted(config.stackName(), String.join(", ", missing))
                    + "deployed installation with `baas config sync --name <prefix>`.");
        }
        return outputs;
    }

    static Optional<String> operatorCredentialsWarning(BaasConfig config, Map<String, String> environment) {
        if (config.getAws().getOperatorProfile() != null || hasAmbientCredentials(environment)) {
            return Optional.empty();
        }
        return Optional.of(
            "No aws.operatorProfile configured — using the default AWS credential chain. "
                + "Set one with: baas config set --operator-profile <profile-name>");
    }

    /**
     * Whether the default credential chain will find credentials without consulting a profile:
     * explicit keys, a web identity (what {@code configure-aws-credentials} sets up for OIDC),
     * container credentials, or a profile already named in the environment.
     */
    private static boolean hasAmbientCredentials(Map<String, String> environment) {
        return isTruthy(environment.get("AWS_ACCESS_KEY_ID"))
            || isTruthy(environment.get("AWS_WEB_IDENTITY_TOKEN_FILE"))
            || isTruthy(environment.get("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"))
            || isTruthy(environment.get("AWS_CONTAINER_CREDENTIALS_FULL_URI"))
            || isTruthy(environment.get("AWS_PROFILE"));
    }

    /**
     * The AMI a run will launch from, or empty when there is none to launch from.
     *
     * <p>Empty is a hard stop, not a fallback: `baas run` has no boot-time install path, and
     * inventing one would mean two provisioning paths whose results are silently incomparable.
     * Resolution happens before the JAR upload so a missing image costs nothing.
     */
    public static Optional<RunnerImage> resolveRunnerImage(
        ImageBuilderService images, String prefix, String amiIdOverride) {
        return amiIdOverride != null
            ? images.describeImage(amiIdOverride)
            : images.currentImage("/" + prefix + "/runner/ami-id");
    }

    /**
     * What the JSON summary reports. Populated as the run reaches each fact rather than assembled
     * at the end, because the failure path has to be able to print whatever is known so far: a run
     * that died after launching still has an id, and that id is the whole point of the option.
     */
    String summaryRunId;
    String summaryProject;
    String summaryResultPath;
    String summaryInstanceId;

    boolean jsonSummary() {
        return "json".equalsIgnoreCase(format);
    }

    @Override
    public Integer call() throws Exception {
        int exitCode = 1;
        try {
            exitCode = execute();
            return exitCode;
        } finally {
            // In a finally so it also covers the paths that throw — resolveProject() and
            // resolveResultsTable() both do. Those fail before a run exists, so the object
            // carries nulls; a consumer still gets one parseable object saying it failed rather
            // than empty output it has to special-case.
            if (jsonSummary()) {
                printRunSummary(exitCode);
            }
        }
    }

    private Integer execute() throws Exception {
        if (!VALID_TYPES.contains(benchmarkType)) {
            logger.error("Unknown benchmark type '{}'. Valid: {}", benchmarkType, VALID_TYPES);
            return 1;
        }

        // Checked first, before resolving the project, the results table, the runner image or
        // anything else: a run that cannot name the runner JAR it will execute is going to fail
        // anyway, and there is deliberately no fallback — two provisioning paths produce silently
        // incomparable results.
        if (runnerJar == null && !BaasVersion.isReleased()) {
            logger.error("""
                This is an unreleased build ({}), so there is no runner release to pin to.
                  Run against a local build:  baas run --runner-jar <path> ...
                Nothing was built or launched.""", BaasVersion.current());
            return 1;
        }

        // Resolved before any AWS call — the runner-image lookup and the S3 upload both come later
        // in this method, and neither should run for a request that is going to fail anyway because
        // it can't be attributed to a project. resolveProject() throws IllegalStateException with a
        // message naming --project when this isn't a git repository and none was passed.
        String resolvedProject = resolveProject();
        summaryProject = resolvedProject;

        BaasConfig config = configService.load();
        // Same reasoning as resolveProject() above, and deliberately before the runner-image lookup
        // and the upload: a run that cannot say where its measurements go is going to fail anyway.
        String resolvedTable = resolveResultsTable(config, noDatabase).orElse(null);
        String resolvedInstanceType = instanceType != null ? instanceType : config.getEc2().getDefaultInstanceType();
        int resolvedTimeout = timeoutSeconds != null ? timeoutSeconds : config.getEc2().getBenchmarkTimeoutSeconds();
        int resolvedWallClock = wallClockSeconds != null ? wallClockSeconds
            : (timeoutSeconds != null ? timeoutSeconds + 300 : config.getEc2().getWallClockHardKillSeconds());
        String resolvedBranch = resolveBranch();
        String resolvedCommit = resolveCommit();
        logger.debug("Resolved run parameters: instanceType={}, timeout={}s, wallClock={}s, branch={}, project={}, params={}",
            resolvedInstanceType, resolvedTimeout, resolvedWallClock, resolvedBranch, resolvedProject, benchmarkParams);

        operatorCredentialsWarning(config).ifPresent(logger::warn);
        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

        // 1. The JAR is named, never derived. Checked before the image lookup and before any
        //    upload, like every other precondition this command has.
        if (!benchmarkJar.toFile().exists()) {
            logger.error("Benchmark JAR not found: {}\nBuild it first, then pass --benchmark-jar.",
                benchmarkJar);
            return 1;
        }
        Path jarPath = benchmarkJar;

        // 2. Resolve the runner image, before anything is uploaded or launched. A missing image
        //    is a hard stop — there is no fallback to AL2023 + yum, since two provisioning paths
        //    would produce silently incomparable results — so discovering it here costs two API
        //    calls rather than an upload that would only fail afterward.
        RunnerImage runnerImage;
        try (var imageBuilder = factory.imageBuilder(); var ec2 = factory.ec2(); var ssm = factory.ssm()) {
            var resolved = resolveRunnerImage(
                new ImageBuilderService(imageBuilder, ec2, ssm), config.getPrefix(), amiIdOverride);

            if (resolved.isEmpty()) {
                if (amiIdOverride != null) {
                    logger.error("AMI {} does not exist in {}. Nothing was launched.",
                        amiIdOverride, config.getAws().getRegion());
                } else {
                    logger.error("""
                            No runner image is published for this account ({}).
                              Build one:  baas admin build-image
                            Nothing was launched — the runner boots from a purpose-built AMI and \
                            there is no boot-time install path.""",
                        config.getAws().getRegion());
                }
                return 1;
            }
            runnerImage = resolved.get();
        }
        logger.debug("Resolved runner AMI: {} (image version {})",
            runnerImage.amiId(), runnerImage.imageVersion());

        // 3. Name the run. One clock read: the instant travels into the identifier, into the S3
        //    prefix and on to the runner as --created-at, so the prefix name and the stored
        //    timestamp are the same value rather than two values that happen to be close.
        Instant runInstant = Instant.now();
        String runId = RunId.generate(runInstant);
        String createdAt = runInstant.toString();
        String resultPath = RunLayout.runPrefix(resolvedProject, runId);
        summaryRunId = runId;
        summaryResultPath = resultPath;
        logger.info("Run {} — results will land under s3://{}/{}",
            runId, config.bucket(), resultPath);

        // 4. Upload JARs into the run's own prefix, so one prefix holds the whole run.
        logger.info("Uploading benchmark JAR to S3...");
        String benchmarkJarKey = RunLayout.benchmarkJarKey(resolvedProject, runId);
        try (var s3 = factory.s3()) {
            new S3UploadService(s3).upload(jarPath, config.bucket(), benchmarkJarKey);
        }

        // The instance's only runner-JAR source. A --runner-jar override stays per-run under the
        // run's own input/, so releases/ holds released artifacts only.
        String runnerJarS3Key;
        if (runnerJar != null) {
            logger.info("Uploading runner JAR to S3...");
            runnerJarS3Key = RunLayout.runnerJarOverrideKey(resolvedProject, runId);
            try (var s3 = factory.s3()) {
                new S3UploadService(s3).upload(runnerJar, config.bucket(), runnerJarS3Key);
            }
            logger.info("Runner JAR: {} (local override, not a pinned release)", runnerJar);
        } else {
            try (var s3 = factory.s3()) {
                runnerJarS3Key = RunnerJarResolver.resolve(s3, config.bucket(),
                    BaasVersion.current(), config.getRunner().getSourceRepo());
            }
            // Which runner build a run executed is the first thing anyone comparing two results
            // asks, so it is reported on every run — not only on the one that seeded the slot.
            logger.info("Runner JAR: {} (pinned to CLI version {})",
                runnerJarS3Key, BaasVersion.current());
        }

        // 5. Build user-data
        Map<String, String> runnerTags =
            buildRunnerTags(benchmarkType, resolvedProject, resolvedCommit, resolvedBranch);
        String userData = new UserDataScriptBuilder().build(
            config.getAws().getRegion(), config.bucket(),
            benchmarkType, runId, resultPath, createdAt, benchmarkJarKey,
            resolvedTimeout, resolvedWallClock,
            runnerImage.imageVersion(), runnerImage.amiId(), runnerJarS3Key,
            resolvedTable, noDatabase, benchmarkParams, runnerTags);
        // The script is what actually decides whether a run works; when a runner dies before it
        // can upload cloud-init-output.log, this is the only place left to look.
        logger.debug("Generated user-data script:\n{}", userData);

        // 6. Launch instance. These are EC2 *instance* tags — console visibility and the
        //    `baas-role` scoping that RunnerRole's TerminateInstances condition depends on. They
        //    are NOT what `baas results` reads: ResultsQueryService reads
        //    benchmarkMetadata.tags, which is populated only by the runner's own --tag options,
        //    emitted by UserDataScriptBuilder from the values observed on the instance. Tagging
        //    the instance instead is how every stored result ended up with a null imageVersion
        //    once already; the tier-1 comparison then silently never fires. Don't treat the two
        //    lines below as covering that — see
        //    UserDataScriptBuilderTest#passesEnvironmentTagsToTheRunnerNotJustToTheInstance.
        logger.info("Launching EC2 instance ({}) from {}...", resolvedInstanceType, runnerImage.amiId());
        Map<String, String> tags = new LinkedHashMap<>(extraTags);
        tags.putIfAbsent("instanceType", resolvedInstanceType);
        if (runnerImage.imageVersion() != null) {
            tags.putIfAbsent("imageVersion", runnerImage.imageVersion());
        }

        // Resolved per run rather than cached in config: replacing RunnerSecurityGroup moves its
        // id, and a stored copy then names a group that no longer exists. The operator role
        // already holds cloudformation:DescribeStacks on its own stack, so this costs one call.
        Map<String, String> networking = resolveNetworking(factory, config);

        String instanceId;
        try (var ec2 = factory.ec2()) {
            instanceId = new Ec2ProvisioningService(ec2).runInstance(
                runnerImage.amiId(), resolvedInstanceType,
                networking.get("SubnetId"), networking.get("SecurityGroupId"),
                config.runnerInstanceProfile(),
                userData, runId, tags);
        }
        summaryInstanceId = instanceId;
        logger.info("Instance launched: {}", instanceId);
        logger.info("Run ID: {}", runId);

        // 7. Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Terminating instance {} ...", instanceId);
            try (var ec2 = factory.ec2()) {
                new Ec2ProvisioningService(ec2).terminateInstance(instanceId);
            }
        }));

        // 8. Poll
        return poll(factory, config, instanceId, runId, resultPath, resolvedWallClock);
    }

    private int poll(AwsClientFactory factory, BaasConfig config, String instanceId,
                     String runId, String resultPath, int wallClockSeconds) throws InterruptedException {
        long startMs = System.currentTimeMillis();
        long timeoutMs = (long) wallClockSeconds * 1000;
        String bucket = config.bucket();
        String statusKey = resultPath + "/run-status";
        String logPath = "s3://" + bucket + "/" + resultPath + "/cloud-init-output.log";

        // Built once, not per iteration: every client construction re-resolves the
        // profile, and with a role-assuming operator profile that means a fresh
        // sts:AssumeRole — hundreds of them over a long run.
        try (var s3 = factory.s3(); var ec2 = factory.ec2()) {
            var storage = new S3UploadService(s3);
            var provisioning = new Ec2ProvisioningService(ec2);

            while (true) {
                long elapsed = (System.currentTimeMillis() - startMs) / 1000;
                if (elapsed * 1000 > timeoutMs) {
                    logger.error("Client-side wall-clock cap exceeded ({}s). Exiting poll.", wallClockSeconds);
                    return 1;
                }

                Optional<String> status = storage.getObjectIfExists(bucket, statusKey);
                if (status.isPresent()) {
                    var exitCode = exitCodeFor(status.get().trim(), factory, config, runId, logPath);
                    if (exitCode.isPresent()) {
                        return exitCode.getAsInt();
                    }
                } else {
                    String state = provisioning.instanceState(instanceId);
                    if ("terminated".equals(state) || "shutting-down".equals(state)) {
                        // The sentinel is written moments before the instance terminates, so a
                        // poll landing in that window sees a dead instance and no status yet.
                        // Re-read once before reporting a successful run as a failure.
                        var lateStatus = storage.getObjectIfExists(bucket, statusKey);
                        if (lateStatus.isPresent()) {
                            var exitCode = exitCodeFor(lateStatus.get().trim(), factory, config, runId, logPath);
                            if (exitCode.isPresent()) {
                                return exitCode.getAsInt();
                            }
                        }
                        logger.error("Instance {} is {} but wrote no run-status sentinel — the runner "
                            + "died before finishing.\nRunner log (present only if the instance got "
                            + "far enough to upload it): {}", instanceId, state, logPath);
                        return 1;
                    }
                    logger.info("Still running ({})... elapsed: {}s", state, elapsed);
                }

                Thread.sleep(15_000);
            }
        }
    }

    /** Maps a run-status sentinel to an exit code, or empty while the run is still in flight. */
    private OptionalInt exitCodeFor(String body, AwsClientFactory factory, BaasConfig config,
                                    String runId, String logPath) {
        logger.info("Run status: {}", body);
        if ("completed".equals(body)) {
            showResults(factory, config, runId);
            return OptionalInt.of(0);
        }
        if (body.startsWith("failed:")) {
            logger.error("Benchmark failed. Runner log: {}", logPath);
            return OptionalInt.of(1);
        }
        return OptionalInt.empty();
    }

    /**
     * Reads the same path {@code baas results} does, so the post-run summary can never disagree
     * with what a later query reports.
     *
     * <p>A missing table name cannot normally get this far — {@link #resolveResultsTable} rejects
     * it before provisioning — but {@code --no-database} reaches here with nothing to show, and a
     * benchmark that has already run and terminated must not be reported as failed over a summary.
     */
    private void showResults(AwsClientFactory factory, BaasConfig config, String runId) {
        if (noDatabase) {
            logger.info("--no-database: the runner stored nothing, so there is no result to show.");
            return;
        }
        String tableName = config.resultsTable();
        try (var results = new ResultsQueryService(factory.dynamoDb(), tableName)) {
            var rows = results.queryByRequestId(runId);
            reportRunResults(rows, runId, results::printTable);
        } catch (Exception e) {
            logger.warn("Could not fetch results from the results table: {}", e.getMessage());
        }
    }

    private String currentGitBranch() {
        return currentGitBranch(Path.of(".").toAbsolutePath().normalize());
    }

    /**
     * Package-private overload for the same testability reason as {@link #resolveProject(Path)}.
     *
     * <p>Routed through {@link #gitOutput(Path, String...)} — which is {@link GitProject#gitOutput}
     * underneath and checks the subprocess exit code — rather than a hand-rolled
     * {@code ProcessBuilder} with {@code redirectErrorStream(true)}. The previous implementation
     * merged stderr into the captured output and never inspected the exit code, so outside a git
     * repository it returned {@code "fatal: not a git repository (or any of the parent
     * directories): .git"} as if it were a branch name — worse than the {@code "unknown"} this
     * change replaced, since a git error message would have landed in the only query surface the
     * tool has. {@link #currentGitCommit(Path)} already got this for free; the two are now
     * symmetric.
     */
    String currentGitBranch(Path workingDir) {
        String branch = gitOutput(workingDir, "git", "rev-parse", "--abbrev-ref", "HEAD");
        return branch != null && !branch.isBlank() ? branch : null;
    }

    /** Shared with {@code baas results}, which must resolve the same partition. */
    static String projectFromToplevel(String toplevel) {
        return GitProject.fromToplevel(toplevel);
    }

    private String gitOutput(String... args) {
        return gitOutput(Path.of(".").toAbsolutePath().normalize(), args);
    }

    /**
     * Package-private overload taking an explicit working directory. Real callers always go
     * through the no-arg overload above; this one exists so
     * {@link #resolveProject(Path)}'s "not a git repository" throw can be exercised by a real
     * git invocation in a test, without needing to leave this repository (which is always a git
     * repo at test time).
     */
    String gitOutput(Path workingDir, String... args) {
        return GitProject.gitOutput(workingDir, args);
    }

    private String resolveProject() {
        return resolveProject(Path.of(".").toAbsolutePath().normalize());
    }

    /** Package-private overload for the same testability reason as {@link #gitOutput(Path, String...)}. */
    String resolveProject(Path workingDir) {
        if (project != null && !project.isBlank()) return project;
        String derived = GitProject.repositoryName(workingDir);
        if (derived == null) {
            throw new IllegalStateException(
                "Cannot determine the project name: not inside a git repository. Pass --project <name>.");
        }
        return derived;
    }

    private String currentGitCommit() {
        return currentGitCommit(Path.of(".").toAbsolutePath().normalize());
    }

    /** Package-private overload for the same testability reason as {@link #resolveProject(Path)}. */
    String currentGitCommit(Path workingDir) {
        String commit = gitOutput(workingDir, "git", "rev-parse", "HEAD");
        return commit != null && !commit.isBlank() ? commit : null;
    }

    private String resolveBranch() {
        return resolveBranch(Path.of(".").toAbsolutePath().normalize());
    }

    /**
     * Package-private overload for the same testability reason as {@link #resolveProject(Path)}.
     *
     * <p>An explicit {@code --branch ""} is blank-checked the same way {@link #resolveProject}
     * blank-checks {@code --project}: a blank override is treated as not supplied and falls through
     * to derivation, rather than being stored as an empty-string tag. An empty string is a
     * placeholder standing in for an unknown value, same as the {@code "unknown"} this change
     * already removed.
     */
    String resolveBranch(Path workingDir) {
        return (branch != null && !branch.isBlank()) ? branch : currentGitBranch(workingDir);
    }

    private String resolveCommit() {
        return resolveCommit(Path.of(".").toAbsolutePath().normalize());
    }

    /** Package-private overload for the same testability reason as {@link #resolveBranch(Path)}. */
    String resolveCommit(Path workingDir) {
        return (commit != null && !commit.isBlank()) ? commit : currentGitCommit(workingDir);
    }

    /**
     * Tag keys populated from values observed on the instance, plus {@code type} — derived from
     * the executed subcommand, not from anything measured, but the same defect class: a caller
     * override would make a JMH run report {@code type=jcstress} while the manifest and the
     * actual subcommand disagree. A result's tags must never be able to disagree with that same
     * run's {@code environment.json} (see {@code UserDataScriptBuilder}'s {@code --tag} block),
     * so {@link #buildRunnerTags} rejects a caller {@code --tag} for any of these outright rather
     * than silently dropping or overriding it. {@code project}, {@code commit} and {@code branch}
     * are deliberately NOT in this set — design.md specifies the caller wins for those.
     *
     * <p>Defined once in baas-model so the CLI and the runner cannot drift apart.
     */
    static final List<String> RESERVED_TAG_KEYS = TagKeys.MACHINE_OBSERVED;

    /**
     * The results table this run will write to, or empty when {@code --no-database} was passed.
     *
     * <p>Resolved before the runner-image lookup and before anything is uploaded or launched, like
     * every other precondition this command checks early: discovering it later costs a paid
     * instance. There is no silent fallback. Before the cutover, an unset store selected a no-op
     * adapter and the run reported success while the measurements were discarded; that behaviour
     * still exists, but it now has to be asked for by name.
     */
    static Optional<String> resolveResultsTable(BaasConfig config, boolean noDatabase) {
        if (noDatabase) {
            return Optional.empty();
        }
        if (config.getPrefix() == null || config.getPrefix().isBlank()) {
            throw new IllegalStateException("""
                No installation is configured, so this run has nowhere to store its measurements.
                  Adopt one:              baas config sync --name baas-<accountId>
                  Or discard the results: baas run --no-database ...
                Nothing was built or launched.""");
        }
        return Optional.of(config.resultsTable());
    }

    /**
     * Extracted from call() so it can be tested without AWS. Caller tags come first so a
     * deliberate --tag project=... still wins over the derived value. A caller tag colliding with
     * a {@link #RESERVED_TAG_KEYS reserved key} is rejected rather than silently dropped or
     * allowed to override — a silently discarded tag is its own surprise.
     */
    Map<String, String> buildRunnerTags(String benchmarkType, String project, String commit, String branch) {
        return buildRunnerTags(benchmarkType, project, commit, branch, System.getenv());
    }

    /**
     * Package-private overload taking the environment explicitly, for the same testability reason
     * as {@link #resolveCommit(Path)} — {@code source} is derived from it, and a test that read the
     * real environment would say {@code local} on a laptop and {@code ci} in CI.
     */
    Map<String, String> buildRunnerTags(String benchmarkType, String project, String commit,
                                        String branch, Map<String, String> environment) {
        List<String> collided = RESERVED_TAG_KEYS.stream().filter(extraTags::containsKey).toList();
        if (!collided.isEmpty()) {
            throw new IllegalArgumentException(
                "--tag " + String.join(", ", collided) + " cannot be set from the command line: "
                    + (collided.size() == 1 ? "it is" : "they are")
                    + " observed on the instance (or derived from the benchmark type), and a "
                    + "caller override would let a result's tags disagree with its own "
                    + "environment.json. Reserved keys: " + String.join(", ", RESERVED_TAG_KEYS)
                    + ". --project, --commit and --branch remain overridable.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(TagKeys.PROJECT, project);
        // An unresolvable commit or branch is absent, not "unknown". A placeholder value is
        // indistinguishable from a real one at query time, which is how RESULT#unknown grew.
        if (commit != null) {
            tags.put(TagKeys.COMMIT, commit);
        }
        if (branch != null) {
            tags.put(TagKeys.BRANCH, branch);
        }
        tags.put(TagKeys.TYPE, benchmarkType);
        tags.put(TagKeys.SOURCE, deriveSource(environment));
        tags.putAll(extraTags);
        return tags;
    }

    /**
     * How this run was triggered. Not a reserved key: the instance never observes it, so a forged
     * value misleads nobody about the measurement environment — which is the only thing
     * {@link #RESERVED_TAG_KEYS} exists to protect. Deriving it rather than leaving it to a
     * convention tag is what makes absence meaningful: a key only present when someone types it
     * would make {@code --group-by source} unreliable in exactly the direction that matters.
     *
     * <p>{@code CI} is the cross-vendor convention and GitHub Actions sets both it and
     * {@code GITHUB_ACTIONS}; {@code CI=false} is honoured because some environments set it that
     * way to opt out.
     */
    static String deriveSource(Map<String, String> environment) {
        return isTruthy(environment.get("CI")) || isTruthy(environment.get("GITHUB_ACTIONS"))
            ? TagKeys.SOURCE_CI
            : TagKeys.SOURCE_LOCAL;
    }

    /**
     * The post-run summary, which under {@code --format json} must not be printed at all.
     *
     * <p>{@code printTable} writes to {@code System.out} — deliberately, since a table is a
     * command payload rather than a diagnostic. But so is the JSON summary, and two payloads on
     * one stream is not a stream anyone can parse: the table lands first and
     * {@code baas run --format json | jq} fails on the very first token. Caught by a real CI run,
     * which read an empty run id and then queried {@code --request-id ""}.
     *
     * <p>The rows are not folded into the summary object. They are a separate concern with a
     * separate command — the object carries the run id precisely so
     * {@code baas results --request-id} can fetch them.
     *
     * @param printTable passed as a function so this is testable without an AWS client
     */
    void reportRunResults(List<ResultRow> rows, String runId, Consumer<List<ResultRow>> printTable) {
        if (jsonSummary()) {
            logger.info("Run {} stored {} measurement(s) — baas results --request-id {}",
                runId, rows.size(), runId);
            return;
        }
        // Named, not just shown: this is the value `baas download <runId>` takes.
        logger.info("Results for run {} (baas download {}):", runId, runId);
        printTable.accept(rows);
    }

    /**
     * One object on {@code System.out}, following {@code ResultsCommand.printJson}'s rule exactly:
     * the payload goes to standard output and every diagnostic to the logger, so
     * {@code baas run --format json | jq} is not corrupted by a timestamped log line — including
     * under {@code -v}.
     *
     * <p>Printed on both outcomes. A failed run is precisely when a continuous-integration job
     * needs the id: to {@code baas download} it and surface {@code cloud-init-output.log}, the
     * documented place to start when a run dies before producing output. The command's exit code
     * is unchanged by this — it still exits non-zero when the run failed.
     *
     * <p>Formatted with {@link Locale#ROOT} for the same reason the results formatters are: a
     * comma-decimal locale would emit text that is not JSON, silently and only on some machines.
     */
    void printRunSummary(int exitCode) {
        System.out.printf(Locale.ROOT,
            "{\"runId\":%s,\"project\":%s,\"resultPath\":%s,\"status\":\"%s\","
                + "\"exitCode\":%d,\"instanceId\":%s}%n",
            jsonString(summaryRunId), jsonString(summaryProject), jsonString(summaryResultPath),
            exitCode == 0 ? "completed" : "failed", exitCode, jsonString(summaryInstanceId));
    }

    /** A JSON string literal, or the {@code null} literal — absent is not the empty string. */
    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static boolean isTruthy(String value) {
        return value != null && !value.isBlank() && !"false".equalsIgnoreCase(value.strip());
    }
}
