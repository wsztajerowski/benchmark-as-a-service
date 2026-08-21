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
import pl.wsztajerowski.baas.results.ResultsQueryService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Callable;

@Command(
    name = "run",
    mixinStandardHelpOptions = true,
    description = "Build a benchmark JAR, launch an EC2 runner, and poll for results.",
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

    @Option(names = "--benchmark-jar", description = "Path to the benchmark JAR (overrides config jarPath).")
    Path benchmarkJar;

    @Option(names = "--runner-jar", description = "Local runner JAR to upload for this run instead of "
        + "pinning the release matching this CLI's version. Required from an unreleased build.")
    Path runnerJar;

    @Option(names = "--skip-build", description = "Skip mvn build step.")
    boolean skipBuild;

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

    @Option(names = "--project", description = "Project name for the results partition (defaults to the git repository name).")
    String project;

    @Option(names = "--no-database", description = "Discard measurements instead of storing them. "
        + "Explicit opt-in: without it, an unresolvable results table fails before provisioning.")
    boolean noDatabase;

    // No --image-version: exactly one image is maintained, so there is nothing to select between.
    @Option(names = "--ami-id", description = "Launch from this AMI instead of the published runner image.")
    String amiIdOverride;

    private final ConfigService configService = new ConfigService();

    /**
     * `run`/`results`/`config show` are meant to run under BaasCliOperatorRole. When no
     * operator profile is configured they fall through to the default credential chain
     * rather than reusing `aws.profile`, which holds deployer credentials.
     */
    public static Optional<String> operatorCredentialsWarning(BaasConfig config) {
        if (config.getAws().getOperatorProfile() != null) {
            return Optional.empty();
        }
        return Optional.of(
            "No aws.operatorProfile configured — using the default AWS credential chain. "
                + "Set one with: baas config set --operator-profile <profile-name>");
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

    @Override
    public Integer call() throws Exception {
        if (!VALID_TYPES.contains(benchmarkType)) {
            logger.error("Unknown benchmark type '{}'. Valid: {}", benchmarkType, VALID_TYPES);
            return 1;
        }

        // Before the Maven build and before any upload, for the same reason the runner image and
        // the results table are: a run that cannot name the runner JAR it will execute is going to
        // fail anyway, and there is deliberately no fallback — two provisioning paths produce
        // silently incomparable results.
        if (runnerJar == null && !BaasVersion.isReleased()) {
            logger.error("""
                This is an unreleased build ({}), so there is no runner release to pin to.
                  Run against a local build:  baas run --runner-jar <path> ...
                Nothing was built or launched.""", BaasVersion.current());
            return 1;
        }

        // Resolved before any AWS call — a Maven build and an S3 upload both come later in this
        // method, and neither should run for a request that is going to fail anyway because it
        // can't be attributed to a project. resolveProject() throws IllegalStateException with a
        // message naming --project when this isn't a git repository and none was passed.
        String resolvedProject = resolveProject();

        BaasConfig config = configService.load();
        // Same reasoning as resolveProject() above, and deliberately before the build and the
        // upload: a run that cannot say where its measurements go is going to fail anyway.
        String resolvedTable = resolveResultsTable(config, noDatabase).orElse(null);
        String resolvedInstanceType = instanceType != null ? instanceType : config.getEc2().getDefaultInstanceType();
        int resolvedTimeout = timeoutSeconds != null ? timeoutSeconds : config.getEc2().getBenchmarkTimeoutSeconds();
        int resolvedWallClock = wallClockSeconds != null ? wallClockSeconds
            : (timeoutSeconds != null ? timeoutSeconds + 300 : config.getEc2().getWallClockHardKillSeconds());
        String resolvedBranch = branch != null ? branch : currentGitBranch();
        logger.debug("Resolved run parameters: instanceType={}, timeout={}s, wallClock={}s, branch={}, project={}, params={}",
            resolvedInstanceType, resolvedTimeout, resolvedWallClock, resolvedBranch, resolvedProject, benchmarkParams);

        operatorCredentialsWarning(config).ifPresent(logger::warn);
        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

        // 1. Resolve the runner image, before the build and before anything is uploaded or
        //    launched. A missing image is a hard stop — there is no fallback to AL2023 + yum,
        //    since two provisioning paths would produce silently incomparable results — so
        //    discovering it here costs two API calls rather than a full Maven build first.
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

        // 2. Build
        if (!skipBuild) {
            runMavenBuild();
        }

        // 3. Determine JAR path
        Path jarPath = benchmarkJar != null ? benchmarkJar : Path.of(config.getBenchmark().getJarPath());
        if (!jarPath.toFile().exists()) {
            logger.error("Benchmark JAR not found: {}\nRun without --skip-build or specify --benchmark-jar.", jarPath);
            return 1;
        }

        // 4. Name the run. One clock read: the instant travels into the identifier, into the S3
        //    prefix and on to the runner as --created-at, so the prefix name and the stored
        //    timestamp are the same value rather than two values that happen to be close.
        Instant runInstant = Instant.now();
        String runId = RunId.generate(runInstant);
        String createdAt = runInstant.toString();
        String resultPath = RunLayout.runPrefix(resolvedProject, runId);
        String inputPrefix = RunLayout.inputPrefix(resolvedProject, runId);
        logger.info("Run {} — results will land under s3://{}/{}",
            runId, config.getAws().getBucket(), resultPath);

        // 5. Upload JARs into the run's own prefix, so one prefix holds the whole run.
        logger.info("Uploading benchmark JAR to S3...");
        String benchmarkJarKey = inputPrefix + "/benchmark.jar";
        try (var s3 = factory.s3()) {
            new S3UploadService(s3).upload(jarPath, config.getAws().getBucket(), benchmarkJarKey);
        }

        // The instance's only runner-JAR source. A --runner-jar override stays per-run under the
        // run's own input/, so releases/ holds released artifacts only.
        String runnerJarS3Key;
        if (runnerJar != null) {
            logger.info("Uploading runner JAR to S3...");
            runnerJarS3Key = inputPrefix + "/runner.jar";
            try (var s3 = factory.s3()) {
                new S3UploadService(s3).upload(runnerJar, config.getAws().getBucket(), runnerJarS3Key);
            }
            logger.info("Runner JAR: {} (local override, not a pinned release)", runnerJar);
        } else {
            try (var s3 = factory.s3()) {
                runnerJarS3Key = RunnerJarResolver.resolve(s3, config.getAws().getBucket(),
                    BaasVersion.current(), config.getRunner().getSourceRepo());
            }
            // Which runner build a run executed is the first thing anyone comparing two results
            // asks, so it is reported on every run — not only on the one that seeded the slot.
            logger.info("Runner JAR: {} (pinned to CLI version {})",
                runnerJarS3Key, BaasVersion.current());
        }

        // 6. Build user-data
        Map<String, String> runnerTags =
            buildRunnerTags(benchmarkType, resolvedProject, currentGitCommit(), resolvedBranch);
        String userData = new UserDataScriptBuilder().build(
            config.getAws().getRegion(), config.getAws().getBucket(),
            benchmarkType, runId, resultPath, createdAt, benchmarkJarKey,
            resolvedTimeout, resolvedWallClock,
            runnerImage.imageVersion(), runnerImage.amiId(), runnerJarS3Key,
            resolvedTable, noDatabase, benchmarkParams, runnerTags);
        // The script is what actually decides whether a run works; when a runner dies before it
        // can upload cloud-init-output.log, this is the only place left to look.
        logger.debug("Generated user-data script:\n{}", userData);

        // 7. Launch instance. These are EC2 *instance* tags — console visibility and the
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

        String instanceId;
        try (var ec2 = factory.ec2()) {
            instanceId = new Ec2ProvisioningService(ec2).runInstance(
                runnerImage.amiId(), resolvedInstanceType,
                config.getAws().getSubnetId(), config.getAws().getSecurityGroupId(),
                config.getAws().getRunnerInstanceProfileName(),
                userData, runId, tags);
        }
        logger.info("Instance launched: {}", instanceId);
        logger.info("Run ID: {}", runId);

        // 8. Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Terminating instance {} ...", instanceId);
            try (var ec2 = factory.ec2()) {
                new Ec2ProvisioningService(ec2).terminateInstance(instanceId);
            }
        }));

        // 9. Poll
        return poll(factory, config, instanceId, runId, resultPath, resolvedWallClock);
    }

    private int poll(AwsClientFactory factory, BaasConfig config, String instanceId,
                     String runId, String resultPath, int wallClockSeconds) throws InterruptedException {
        long startMs = System.currentTimeMillis();
        long timeoutMs = (long) wallClockSeconds * 1000;
        String bucket = config.getAws().getBucket();
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
        String tableName = config.getAws().getResultsTable();
        try (var results = new ResultsQueryService(factory.dynamoDb(), tableName)) {
            var rows = results.queryByRequestId(runId);
            // Named, not just shown: this is the value `baas download <runId>` takes.
            logger.info("Results for run {} (baas download {}):", runId, runId);
            results.printTable(rows);
        } catch (Exception e) {
            logger.warn("Could not fetch results from the results table: {}", e.getMessage());
        }
    }

    private void runMavenBuild() throws IOException, InterruptedException {
        logger.info("Building benchmark JAR (mvn clean package -q)...");
        var pb = new ProcessBuilder("mvn", "clean", "package", "-q", "-DskipTests")
            .inheritIO()
            .directory(Path.of(".").toAbsolutePath().normalize().toFile());
        int exit = pb.start().waitFor();
        if (exit != 0) throw new RuntimeException("Maven build failed with exit code " + exit);
    }

    private String currentGitBranch() {
        try {
            var pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .redirectErrorStream(true);
            var proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            return out.isEmpty() ? "unknown" : out;
        } catch (Exception e) {
            return "unknown";
        }
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
        String commit = gitOutput("git", "rev-parse", "HEAD");
        return commit != null ? commit : "unknown";
    }

    /**
     * Tag keys populated from values observed on the instance, plus {@code type} — derived from
     * the executed subcommand, not from anything measured, but the same defect class: a caller
     * override would make a JMH run report {@code type=jcstress} while the manifest and the
     * actual subcommand disagree. A result's tags must never be able to disagree with that same
     * run's {@code environment.json} (see {@code UserDataScriptBuilder}'s {@code --tag} block),
     * so {@link #buildRunnerTags} rejects a caller {@code --tag} for any of these outright rather
     * than silently dropping or overriding it. {@code project} and {@code commit} are
     * deliberately NOT in this set — design.md specifies the caller wins for those.
     *
     * <p>Defined once in baas-model so the CLI and the runner cannot drift apart.
     */
    static final List<String> RESERVED_TAG_KEYS = TagKeys.MACHINE_OBSERVED;

    /**
     * The results table this run will write to, or empty when {@code --no-database} was passed.
     *
     * <p>Resolved before the Maven build and before anything is uploaded or launched, for the same
     * reason the runner image is: discovering it later costs a paid instance. There is no silent
     * fallback. Before the cutover, an unset store selected a no-op adapter and the run reported
     * success while the measurements were discarded; that behaviour still exists, but it now has
     * to be asked for by name.
     */
    static Optional<String> resolveResultsTable(BaasConfig config, boolean noDatabase) {
        if (noDatabase) {
            return Optional.empty();
        }
        String table = config.getAws().getResultsTable();
        if (table == null || table.isBlank()) {
            throw new IllegalStateException("""
                No results table configured, so this run has nowhere to store its measurements.
                  Sync it from the stack:  baas config sync --core-stack-name %s
                  Or discard the results:  baas run --no-database ...
                Nothing was built or launched."""
                .formatted(config.getAws().getCoreStackName()));
        }
        return Optional.of(table);
    }

    /**
     * Extracted from call() so it can be tested without AWS. Caller tags come first so a
     * deliberate --tag project=... still wins over the derived value. A caller tag colliding with
     * a {@link #RESERVED_TAG_KEYS reserved key} is rejected rather than silently dropped or
     * allowed to override — a silently discarded tag is its own surprise.
     */
    Map<String, String> buildRunnerTags(String benchmarkType, String project, String commit, String branch) {
        List<String> collided = RESERVED_TAG_KEYS.stream().filter(extraTags::containsKey).toList();
        if (!collided.isEmpty()) {
            throw new IllegalArgumentException(
                "--tag " + String.join(", ", collided) + " cannot be set from the command line: "
                    + (collided.size() == 1 ? "it is" : "they are")
                    + " observed on the instance (or derived from the benchmark type), and a "
                    + "caller override would let a result's tags disagree with its own "
                    + "environment.json. Reserved keys: " + String.join(", ", RESERVED_TAG_KEYS)
                    + ". --project and --commit remain overridable.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(TagKeys.PROJECT, project);
        tags.put(TagKeys.COMMIT, commit);
        tags.put(TagKeys.BRANCH, branch);
        tags.put(TagKeys.TYPE, benchmarkType);
        tags.putAll(extraTags);
        return tags;
    }
}
