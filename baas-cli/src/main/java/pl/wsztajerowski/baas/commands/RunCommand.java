package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.BaasVersion;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.StatusLine;
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
import pl.wsztajerowski.baas.model.RunItem;
import pl.wsztajerowski.baas.model.RunStatus;
import pl.wsztajerowski.baas.model.RunLayout;
import pl.wsztajerowski.baas.model.TagKeys;
import pl.wsztajerowski.baas.results.ResultRow;
import pl.wsztajerowski.baas.results.ResultsQueryService;
import pl.wsztajerowski.baas.results.ResultsTable;
import pl.wsztajerowski.baas.runs.DynamoDbRunRecorder;
import pl.wsztajerowski.baas.runs.RunSession;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Pattern;

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
        "  baas run --benchmark-jar target/b.jar --project my-bench \\",
        "    jmh -- MyBenchmark -f 1 -wi 1 -i 3",
        "  baas run --benchmark-jar target/b.jar --project my-bench \\",
        "    --instance-type c6i.4xlarge jmh -- MyBenchmark -f 1",
        "",
        "--project can be derived from the JAR's git repository instead:",
        "  baas config set --git-resolve-project true",
        "",
        "Measurements and the run's status go to the DynamoDB results table; see",
        "them with baas results and baas runs list. S3 receives process output,",
        "logs, profiler artifacts and the verbatim result JSON. An unresolvable",
        "table fails before anything is launched."
    },
    separator = " "
)
public class RunCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(RunCommand.class);

    private static final List<String> VALID_TYPES = List.of("jmh", "jmh-with-async", "jmh-with-prof", "jcstress");

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    /**
     * The poll loop's status line while one is shown. Volatile because the shutdown hook reads it
     * from another thread to clear the line before logging the termination.
     */
    private volatile StatusLine statusLine;

    /**
     * The run's lifecycle once it has been named — reserve, launch, poll, stop. Volatile because
     * the shutdown hook reads it from another thread.
     */
    private volatile RunSession session;

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

    @Option(names = "--watchdog-margin",
        description = "Seconds the self-termination watchdog waits beyond --timeout (overrides config "
            + "default; minimum " + BaasConfig.MIN_WATCHDOG_MARGIN_SECONDS + ").")
    Integer watchdogMarginSeconds;

    @Option(names = "--tag", description = "Tag recorded on the stored benchmark result (key=value), not just "
        + "the EC2 instance — including branch and commit, which are never derived. Rejected for "
        + "machine-observed keys (imageVersion, instanceType, jdk, jvmVendor, cpuModel, cpuArch, type) — those are "
        + "captured on the instance so a result's tags can't disagree with its own environment.json.")
    Map<String, String> extraTags = new LinkedHashMap<>();

    @Option(names = "--project", description = "Project name for the results partition. Required unless "
        + "git.resolveProject is enabled, which derives it from the repository holding --benchmark-jar.")
    String project;

    // No --image-version and no --ami-id: exactly one image is maintained, so there is nothing to
    // select between, and an override could only name an image whose results are not comparable.
    @Option(names = "--format", defaultValue = "text",
        description = "Run summary format: text (default) or json. json writes one object to "
            + "standard output — on the failure path too, which is when the run id is most "
            + "needed — while diagnostics stay on standard error.")
    String format;

    /**
     * `run`/`results`/`config show` are meant to run under BaasCliOperatorRole. When no
     * operator profile is configured they fall through to the default credential chain
     * rather than reusing `aws.profile`, which holds deployer credentials.
     */
    public static Optional<String> operatorCredentialsWarning(BaasConfig config) {
        return operatorCredentialsWarning(config, System.getenv());
    }

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

    /**
     * Package-private overload taking the environment explicitly, so a test does not read the real
     * one.
     *
     * <p>Silent when credentials already arrive from the environment. The warning's own advice —
     * {@code baas config set --operator-profile} — is not merely redundant there but wrong: in
     * continuous integration the credentials come from an OIDC federation the job performed, and
     * there is no profile to name. Falling through to the default credential chain is what the
     * method's own contract calls correct in that case, so warning about it trained the reader to
     * ignore a warning that still matters on a laptop.
     */
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
    public static Optional<RunnerImage> resolveRunnerImage(ImageBuilderService images, String prefix) {
        return images.currentImage("/" + prefix + "/runner/ami-id");
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

    private ConfigService configService() {
        return BaasApp.configService(spec);
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
        // Anything but json used to mean text, so `--format jsno` silently printed no summary
        // for a pipeline expecting one.
        if (format != null && !"text".equalsIgnoreCase(format) && !jsonSummary()) {
            logger.error("Unknown --format '{}'. Valid: text, json.", format);
            return 2;
        }
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

        BaasConfig config = configService().load();

        // Resolved before any AWS call — the runner-image lookup and the S3 upload both come later
        // in this method, and neither should run for a request that is going to fail anyway because
        // it can't be attributed to a project. resolveProject() throws IllegalStateException with a
        // message naming --project when none was passed and none can be derived, or when the name
        // holds a character a GitHub repository name cannot.
        String resolvedProject = resolveProject(config);
        summaryProject = resolvedProject;

        // Same reasoning as resolveProject() above, and deliberately before the runner-image lookup
        // and the upload: a run that cannot say where its measurements go is going to fail anyway.
        String resolvedTable = resolveResultsTable(config);
        String resolvedInstanceType = instanceType != null ? instanceType : config.getEc2().getDefaultInstanceType();
        Timings timings = resolveTimings(config);
        int resolvedTimeout = timings.timeoutSeconds();
        int resolvedWallClock = timings.watchdogSeconds();
        logger.debug("Resolved run parameters: instanceType={}, timeout={}s, watchdog={}s, project={}, params={}",
            resolvedInstanceType, resolvedTimeout, resolvedWallClock, resolvedProject, benchmarkParams);

        operatorCredentialsWarning(config).ifPresent(logger::warn);
        var factory = new AwsClientFactory(
            config.getAws().resolveRegion(), config.getAws().resolveOperatorProfile());

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
                new ImageBuilderService(imageBuilder, ec2, ssm), config.getPrefix());

            if (resolved.isEmpty()) {
                logger.error("""
                        No runner image is published for this account ({}).
                          Build one:  baas admin build-image
                        Nothing was launched — the runner boots from a purpose-built AMI and \
                        there is no boot-time install path.""",
                    config.getAws().resolveRegion());
                return 1;
            }
            runnerImage = resolved.get();
        }
        logger.debug("Resolved runner AMI: {} (image version {})",
            runnerImage.amiId(), runnerImage.imageVersion());

        // Before naming the run and before any upload: a torn-down or wrong installation used to
        // upload the benchmark JAR and only then fail here. Resolved per run rather than cached in
        // config: replacing RunnerSecurityGroup moves its id, and a stored copy then names a group
        // that no longer exists. The operator role already holds cloudformation:DescribeStacks on
        // its own stack, so this costs one call.
        Map<String, String> networking = resolveNetworking(factory, config);

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

        // 5. Build user-data. The run item's sort key is built here, once, by ResultKeys, and
        //    handed to the instance verbatim; see UserDataScriptBuilder's RUN_SORT_KEY.
        Map<String, String> runnerTags =
            buildRunnerTags(benchmarkType, resolvedProject);
        RunItem run = new RunItem(runId, resolvedProject, runInstant, resultPath, resolvedInstanceType,
            RunStatus.LAUNCHING, null, null, runnerTags, null);
        String userData = new UserDataScriptBuilder().build(
            config.getAws().resolveRegion(), config.bucket(),
            benchmarkType, runId, resultPath, createdAt, benchmarkJarKey,
            resolvedTimeout, resolvedWallClock,
            runnerImage.imageVersion(), runnerImage.amiId(), runnerJarS3Key,
            resolvedTable, run.sortKey(), benchmarkParams, runnerTags);
        // The script is what actually decides whether a run works; when a runner dies before it
        // can upload cloud-init-output.log, this is the only place left to look.
        logger.debug("Generated user-data script:\n{}", userData);

        // The session's clients are deliberately never closed: the shutdown hook may still be
        // using them while the main thread unwinds, and the JVM is exiting either way.
        var ec2Instances = new Ec2ProvisioningService(factory.ec2());
        var current = new RunSession(run,
            new DynamoDbRunRecorder(factory.dynamoDb(), resolvedTable),
            new DynamoDbRunRecorder(factory.dynamoDb(STOP_WRITE_TIMEOUT), resolvedTable),
            ec2Instances);

        // 6. Reserve the run before launching it. An instance without a record is exactly the
        //    invisible run `baas runs` exists to show, so a reservation that cannot be written
        //    launches nothing.
        try {
            current.reserve();
        } catch (RuntimeException e) {
            logger.error("""
                Could not record run {} in the results table, so nothing was launched: {}
                If the operator role lacks dynamodb:UpdateItem, the installation predates run \
                tracking — update it with `baas admin setup`.""", runId, e.getMessage());
            return 1;
        }
        session = current;

        // 7. Shutdown hook, registered before the launch: an interrupt while RunInstances is in
        //    flight still finds the instance, by its run-id tag. Once the run has ended the
        //    instance terminates itself, and the watchdog backs it up; terminating it from here as
        //    well would cut off its final cloud-init-output.log upload. This layer is for a CLI
        //    that stops while the run is still in flight.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Cleared first, or the termination message would be drawn over by the status line.
            var line = statusLine;
            if (line != null) {
                line.close();
            }
            var active = session;
            if (active != null && !active.ended()) {
                active.stop(RunStatus.CANCELLED);
            } else if (active != null) {
                logger.debug("Run ended; instance {} terminates itself.", active.instanceId());
            }
        }));

        // 8. Launch. It carries only the fixed tags (see Ec2ProvisioningService#instanceTags):
        //    every caller --tag, and the observed imageVersion/instanceType, reach the stored
        //    result through the runner's own --tag options in user-data, which is the only place
        //    `baas results` reads. See
        //    UserDataScriptBuilderTest#passesEnvironmentTagsToTheRunnerNotJustToTheInstance.
        logger.info("Launching EC2 instance ({}) from {}...", resolvedInstanceType, runnerImage.amiId());
        String instanceId;
        try {
            instanceId = ec2Instances.runInstance(
                runnerImage.amiId(), resolvedInstanceType,
                networking.get("SubnetId"), networking.get("SecurityGroupId"),
                config.runnerInstanceProfile(),
                userData, runId);
        } catch (RuntimeException e) {
            recordLaunchFailure(factory, config, current, e, runnerImage.amiId(),
                resolvedInstanceType, networking.get("SubnetId"));
            return 1;
        }
        summaryInstanceId = instanceId;
        logger.info("Instance launched: {}", instanceId);
        logger.info("Run ID: {}", runId);
        if (current.confirmLaunched(instanceId) == RunSession.Confirmation.CANCELLED_WHILE_LAUNCHING) {
            return 1;
        }

        // 9. Poll
        return poll(factory, config, current, resolvedWallClock);
    }

    /** Bounds the status write that precedes a termination; see AwsClientFactory#dynamoDb(Duration). */
    static final java.time.Duration STOP_WRITE_TIMEOUT = java.time.Duration.ofSeconds(5);

    /**
     * A launch that failed leaves no instance and so no boot log. What there is to keep is in the
     * exception and in the request, so both go to the run's prefix, where {@code baas download}
     * finds them, and the error code goes on the run item, where {@code baas runs list} shows it.
     * Both are best effort: the launch error is reported whether or not they land, since a launch
     * often fails for the same reason they would — the network or the credentials.
     */
    private void recordLaunchFailure(AwsClientFactory factory, BaasConfig config, RunSession current,
                                     RuntimeException error, String amiId, String instanceType,
                                     String subnetId) {
        String errorCode = null;
        String requestId = null;
        if (error instanceof AwsServiceException aws) {
            requestId = aws.requestId();
            if (aws.awsErrorDetails() != null) {
                errorCode = aws.awsErrorDetails().errorCode();
            }
        }
        RunItem run = current.run();
        logger.error("Launching the instance for run {} failed{}: {}", run.runId(),
            errorCode == null ? "" : " (" + errorCode + ")", error.getMessage());
        current.recordLaunchFailed(errorCode);
        String report = String.join("\n",
            "runId: " + run.runId(),
            "time: " + Instant.now(),
            "errorCode: " + (errorCode == null ? "" : errorCode),
            "awsRequestId: " + (requestId == null ? "" : requestId),
            "instanceType: " + instanceType,
            "amiId: " + amiId,
            "subnetId: " + subnetId,
            "message: " + error.getMessage(),
            "");
        try (var s3 = factory.s3()) {
            new S3UploadService(s3).putText(config.bucket(),
                RunLayout.launchErrorKey(run.project(), run.runId()), report);
            logger.info("Launch error recorded: baas download {}", run.runId());
        } catch (RuntimeException e) {
            logger.warn("Could not upload {} ({})", RunLayout.LAUNCH_ERROR_NAME, e.getMessage());
        }
    }

    private int poll(AwsClientFactory factory, BaasConfig config, RunSession current, int capSeconds)
        throws InterruptedException {
        RunItem run = current.run();
        String logPath = "s3://" + config.bucket() + "/" + run.resultPath() + "/cloud-init-output.log";
        RunSession.Outcome outcome;
        try (var line = openStatusLine()) {
            outcome = current.await(capSeconds, 15_000, System::currentTimeMillis, Thread::sleep,
                () -> measurementsStored(factory, config, run.runId()),
                (state, elapsed) -> {
                    if (line != null) {
                        line.update(statusText(state, elapsed, current.instanceId()));
                    } else {
                        logger.info("Still running ({})... elapsed: {}s", state, elapsed);
                    }
                });
            // The run is over: a line still saying "running" beside its results is wrong, and
            // would be redrawn under every row of the table printed next.
            closeIfOpen(line);
        }
        return report(outcome, factory, config, run.runId(), current.instanceId(), logPath);
    }

    /** Says what the outcome was, shows the results of a completed run, and returns its exit code. */
    private int report(RunSession.Outcome outcome, AwsClientFactory factory, BaasConfig config,
                       String runId, String instanceId, String logPath) {
        String status = outcome.status();
        logger.info("Run status: {}", status);
        if (outcome.completed()) {
            showResults(factory, config, runId);
        } else if (status.startsWith(RunStatus.FAILED_PREFIX)) {
            logger.error("Benchmark failed. Runner log: {}", logPath);
        } else if (RunStatus.TIMED_OUT.equals(status)) {
            logger.error("Run {} timed out. Runner log (if the instance got far enough to upload "
                + "it): {}", runId, logPath);
        } else if (RunStatus.CANCELLED.equals(status)) {
            logger.error("Run {} was cancelled.", runId);
        } else if (RunSession.Outcome.STATUS_LOST.equals(status)) {
            logger.error("Instance {} terminated without recording its final status, but the run "
                + "stored measurements: baas results --request-id {}\nRunner log: {}",
                instanceId, runId, logPath);
        } else {
            logger.error("Instance {} terminated without recording a final status — the runner "
                + "died before finishing.\nRunner log (present only if the instance got far "
                + "enough to upload it): {}", instanceId, logPath);
        }
        return outcome.exitCode();
    }

    /** Asked only when the instance is gone without a final status; a failed read counts as none. */
    private boolean measurementsStored(AwsClientFactory factory, BaasConfig config, String runId) {
        try (var results = new ResultsQueryService(factory.dynamoDb(), config.resultsTable())) {
            return !results.queryByRequestId(runId).isEmpty();
        } catch (RuntimeException e) {
            logger.debug("Could not check for stored measurements: {}", e.getMessage());
            return false;
        }
    }

    /**
     * A status line only on an interactive terminal and never under {@code --format json}: there
     * standard output belongs to the summary object, and progress stays on the log lines CI reads.
     * {@code null} otherwise, which the poll loop takes as "log as before".
     */
    StatusLine openStatusLine() {
        if (jsonSummary()) {
            return null;
        }
        statusLine = console().openStatusLine();
        return statusLine;
    }

    private static void closeIfOpen(StatusLine line) {
        if (line != null) {
            line.close();
        }
    }

    /** Short enough never to wrap an 80-column terminal, so a redraw stays on one row. */
    static String statusText(String state, long elapsedSeconds, String instanceId) {
        return String.format(Locale.ROOT, "%s · %s elapsed · %s",
            state, formatElapsed(elapsedSeconds), instanceId);
    }

    public static String formatElapsed(long seconds) {
        long h = seconds / 3600;
        long m = seconds % 3600 / 60;
        long s = seconds % 60;
        return h > 0
            ? String.format(Locale.ROOT, "%dh %02dm %02ds", h, m, s)
            : String.format(Locale.ROOT, "%dm %02ds", m, s);
    }

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }

    /**
     * Reads the same path {@code baas results} does, so the post-run summary can never disagree
     * with what a later query reports.
     *
     * <p>A benchmark that has already run and terminated must not be reported as failed over a
     * summary, so a failed read is a warning.
     */
    private void showResults(AwsClientFactory factory, BaasConfig config, String runId) {
        String tableName = config.resultsTable();
        try (var results = new ResultsQueryService(factory.dynamoDb(), tableName)) {
            var rows = results.queryByRequestId(runId);
            reportRunResults(rows, runId, r -> ResultsTable.print(console(), r));
        } catch (Exception e) {
            logger.warn("Could not fetch results from the results table: {}", e.getMessage());
        }
    }

    /** Shared with {@code baas results}, which must resolve the same partition. */
    static String projectFromToplevel(String toplevel) {
        return GitProject.fromToplevel(toplevel);
    }

    /**
     * {@code --project}, else — only when the operator enabled {@code git.resolveProject} — the
     * repository holding the benchmark JAR. Anchored on the JAR, not the working directory: the JAR
     * is what is being measured, and the shell's directory is incidental. {@code branch} and
     * {@code commit} are deliberately not derived at all; they arrive as {@code --tag}s or not at all.
     */
    String resolveProject(BaasConfig config) {
        if (project != null && !project.isBlank()) return requireValidProject(project, "--project");
        if (!config.getGit().isResolveProject()) {
            throw new IllegalStateException(
                "No project named. Pass --project <name>, or let baas derive it from the benchmark "
                    + "JAR's git repository: baas config set --git-resolve-project true");
        }
        Path jarDir = benchmarkJar.toAbsolutePath().normalize().getParent();
        String derived = jarDir != null && Files.isDirectory(jarDir) ? GitProject.repositoryName(jarDir) : null;
        if (derived == null) {
            throw new IllegalStateException(
                "Cannot determine the project name: " + benchmarkJar + " is not inside a git repository. "
                    + "Pass --project <name>.");
        }
        return requireValidProject(derived, "The benchmark JAR's repository directory");
    }

    /**
     * The characters GitHub allows in a repository name, so any repository's name passes. The
     * project names an S3 prefix, a DynamoDB partition and a line of the instance's user-data;
     * anything wider is a typo here and a parse failure there, the latter before the watchdog runs.
     */
    static final Pattern PROJECT_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    static String requireValidProject(String name, String source) {
        if (PROJECT_NAME.matcher(name).matches()) return name;
        throw new IllegalStateException(source + " gives project name '" + name + "', which may "
            + "contain only letters, digits, '.', '_' and '-'."
            + (source.startsWith("--") ? "" : " Pass --project <name>."));
    }

    /** The benchmark timeout and the watchdog bound — which is also the CLI's poll cap. */
    record Timings(int timeoutSeconds, int watchdogSeconds) {}

    /** Flags over configuration, for both values; the bound always derived, never taken as given. */
    Timings resolveTimings(BaasConfig config) {
        int timeout = timeoutSeconds != null ? timeoutSeconds : config.getEc2().getBenchmarkTimeoutSeconds();
        int margin = watchdogMarginSeconds != null
            ? watchdogMarginSeconds : config.getEc2().getWatchdogMarginSeconds();
        return new Timings(timeout, watchdogBound(timeout, margin));
    }

    /**
     * The one place the watchdog bound is computed. It feeds both the instance's self-termination
     * delay and this CLI's poll cap, so the two cannot drift, and being relative it can never fall
     * below the benchmark's own timeout.
     */
    static int watchdogBound(int timeoutSeconds, int marginSeconds) {
        if (marginSeconds < BaasConfig.MIN_WATCHDOG_MARGIN_SECONDS) {
            throw new IllegalArgumentException(
                "The watchdog margin must be at least " + BaasConfig.MIN_WATCHDOG_MARGIN_SECONDS
                    + " seconds, to cover boot and the final upload; got " + marginSeconds
                    + ". Nothing was launched.");
        }
        return timeoutSeconds + marginSeconds;
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
     * The results table this run records its status in and stores its measurements to.
     *
     * <p>Resolved before the runner-image lookup and before anything is uploaded or launched, like
     * every other precondition this command checks early: discovering it later costs a paid
     * instance. There is no option to go without one. Every run records its status in the table,
     * so a run with no table could not be seen at all — and the {@code --no-database} that once
     * discarded measurements had outlived any use.
     */
    static String resolveResultsTable(BaasConfig config) {
        if (config.getPrefix() == null || config.getPrefix().isBlank()) {
            throw new IllegalStateException("""
                No installation is configured, so this run has nowhere to record its status or \
                store its measurements.
                  Adopt one:  baas config sync --name baas-<accountId>
                Nothing was built or launched.""");
        }
        return config.resultsTable();
    }

    /**
     * Extracted from call() so it can be tested without AWS. Caller tags come first so a
     * deliberate --tag project=... still wins over the derived value. A caller tag colliding with
     * a {@link #RESERVED_TAG_KEYS reserved key} is rejected rather than silently dropped or
     * allowed to override — a silently discarded tag is its own surprise.
     */
    Map<String, String> buildRunnerTags(String benchmarkType, String project) {
        return buildRunnerTags(benchmarkType, project, System.getenv());
    }

    /**
     * Package-private overload taking the environment explicitly, for the same testability reason
     * as {@link #operatorCredentialsWarning(BaasConfig, Map)} — {@code source} is derived from it, and a test that read the
     * real environment would say {@code local} on a laptop and {@code ci} in CI.
     */
    Map<String, String> buildRunnerTags(String benchmarkType, String project, Map<String, String> environment) {
        List<String> collided = RESERVED_TAG_KEYS.stream().filter(extraTags::containsKey).toList();
        if (!collided.isEmpty()) {
            throw new IllegalArgumentException(
                "--tag " + String.join(", ", collided) + " cannot be set from the command line: "
                    + (collided.size() == 1 ? "it is" : "they are")
                    + " observed on the instance (or derived from the benchmark type), and a "
                    + "caller override would let a result's tags disagree with its own "
                    + "environment.json. Reserved keys: " + String.join(", ", RESERVED_TAG_KEYS)
                    + ". project, commit, branch and source remain caller-settable.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(TagKeys.PROJECT, project);
        // commit and branch come only from the caller's --tag. Absent stays absent, never
        // "unknown": a placeholder value is indistinguishable from a real one at query time, which
        // is how RESULT#unknown grew.
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
     * <p>The table goes to the {@link Console} — deliberately, since a table is a command
     * payload rather than a diagnostic. But so is the JSON summary, and two payloads on
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
     * One object on the {@link Console}, never coloured, following {@code ResultsCommand.printJson}'s rule exactly:
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
        console().printf(
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
