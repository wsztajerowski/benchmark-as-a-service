package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.Ec2ProvisioningService;
import pl.wsztajerowski.baas.infra.S3UploadService;
import pl.wsztajerowski.baas.infra.SsmService;
import pl.wsztajerowski.baas.infra.UserDataScriptBuilder;
import pl.wsztajerowski.baas.results.ResultsQueryService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
        "Measurements go to MongoDB. S3 receives process output, logs, profiler",
        "artifacts and the run-status sentinel. With no MongoDB URI the runner",
        "uses a no-op database and the measurements are discarded."
    },
    separator = " "
)
public class RunCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(RunCommand.class);

    private static final List<String> VALID_TYPES = List.of("jmh", "jmh-with-async", "jmh-with-prof", "jcstress");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Mixin LoggingMixin loggingMixin;

    @Parameters(index = "0", paramLabel = "<type>",
        description = "Benchmark type: jmh, jmh-with-async, jmh-with-prof, jcstress.")
    String benchmarkType;

    @Parameters(index = "1..*", paramLabel = "PARAMS",
        description = "Parameters forwarded to benchmark-runner.jar. Must follow a -- separator.")
    List<String> benchmarkParams = new ArrayList<>();

    @Option(names = "--benchmark-jar", description = "Path to the benchmark JAR (overrides config jarPath).")
    Path benchmarkJar;

    @Option(names = "--runner-jar", description = "Local runner JAR to upload instead of downloading from GitHub Releases.")
    Path runnerJar;

    @Option(names = "--skip-build", description = "Skip mvn build step.")
    boolean skipBuild;

    @Option(names = "--instance-type", description = "EC2 instance type (overrides config default).")
    String instanceType;

    @Option(names = "--timeout", description = "Benchmark process timeout in seconds.")
    Integer timeoutSeconds;

    @Option(names = "--max-wall-clock", description = "Absolute wall-clock cap in seconds.")
    Integer wallClockSeconds;

    @Option(names = "--tag", description = "Extra EC2 instance tags (key=value).")
    Map<String, String> extraTags = new LinkedHashMap<>();

    @Option(names = "--branch", description = "Branch label for result path (defaults to current git branch).")
    String branch;

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

    @Override
    public Integer call() throws Exception {
        if (!VALID_TYPES.contains(benchmarkType)) {
            logger.error("Unknown benchmark type '{}'. Valid: {}", benchmarkType, VALID_TYPES);
            return 1;
        }

        BaasConfig config = configService.load();
        String resolvedInstanceType = instanceType != null ? instanceType : config.getEc2().getDefaultInstanceType();
        int resolvedTimeout = timeoutSeconds != null ? timeoutSeconds : config.getEc2().getBenchmarkTimeoutSeconds();
        int resolvedWallClock = wallClockSeconds != null ? wallClockSeconds
            : (timeoutSeconds != null ? timeoutSeconds + 300 : config.getEc2().getWallClockHardKillSeconds());
        String resolvedBranch = branch != null ? branch : currentGitBranch();
        logger.debug("Resolved run parameters: instanceType={}, timeout={}s, wallClock={}s, branch={}, params={}",
            resolvedInstanceType, resolvedTimeout, resolvedWallClock, resolvedBranch, benchmarkParams);

        // 1. Build
        if (!skipBuild) {
            runMavenBuild();
        }

        // 2. Determine JAR path
        Path jarPath = benchmarkJar != null ? benchmarkJar : Path.of(config.getBenchmark().getJarPath());
        if (!jarPath.toFile().exists()) {
            logger.error("Benchmark JAR not found: {}\nRun without --skip-build or specify --benchmark-jar.", jarPath);
            return 1;
        }

        // 3. Generate IDs
        String timestamp = TS.format(LocalDateTime.now());
        String requestId = benchmarkType + "-" + timestamp;
        String resultPath = resolvedBranch + "/" + benchmarkType + "/" + timestamp;
        logger.debug("Results will land under s3://{}/{}", config.getAws().getBucket(), resultPath);

        operatorCredentialsWarning(config).ifPresent(logger::warn);
        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

        // 4. Upload JARs
        logger.info("Uploading benchmark JAR to S3...");
        String benchmarkJarKey = "runs/" + requestId + "/benchmark.jar";
        try (var s3 = factory.s3()) {
            new S3UploadService(s3).upload(jarPath, config.getAws().getBucket(), benchmarkJarKey);
        }

        String runnerJarS3Key = null;
        if (runnerJar != null) {
            logger.info("Uploading runner JAR to S3...");
            runnerJarS3Key = "runs/" + requestId + "/runner.jar";
            try (var s3 = factory.s3()) {
                new S3UploadService(s3).upload(runnerJar, config.getAws().getBucket(), runnerJarS3Key);
            }
        }

        // 5. Get AMI
        String amiId;
        try (var ssm = factory.ssm()) {
            amiId = new SsmService(ssm).getParameter(
                "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64");
        }
        logger.debug("Resolved AL2023 AMI: {}", amiId);

        // 6. Build user-data
        String userData = new UserDataScriptBuilder().build(
            config.getAws().getRegion(), config.getAws().getBucket(), config.getPrefix(),
            benchmarkType, requestId, resultPath, resolvedTimeout, resolvedWallClock,
            config.getBenchmark().getAsyncProfilerVersion(), runnerJarS3Key,
            benchmarkParams);
        // The script is what actually decides whether a run works; when a runner dies before it
        // can upload cloud-init-output.log, this is the only place left to look.
        logger.debug("Generated user-data script:\n{}", userData);

        // 7. Launch instance
        logger.info("Launching EC2 instance ({})...", resolvedInstanceType);
        String instanceId;
        try (var ec2 = factory.ec2()) {
            instanceId = new Ec2ProvisioningService(ec2).runInstance(
                amiId, resolvedInstanceType,
                config.getAws().getSubnetId(), config.getAws().getSecurityGroupId(),
                config.getAws().getRunnerInstanceProfileName(),
                userData, requestId, extraTags);
        }
        logger.info("Instance launched: {}", instanceId);
        logger.info("Request ID: {}", requestId);

        // 8. Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Terminating instance {} ...", instanceId);
            try (var ec2 = factory.ec2()) {
                new Ec2ProvisioningService(ec2).terminateInstance(instanceId);
            }
        }));

        // 9. Poll
        return poll(factory, config, instanceId, requestId, resultPath, resolvedWallClock);
    }

    private int poll(AwsClientFactory factory, BaasConfig config, String instanceId,
                     String requestId, String resultPath, int wallClockSeconds) throws InterruptedException {
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
                    var exitCode = exitCodeFor(status.get().trim(), factory, config, requestId, logPath);
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
                            var exitCode = exitCodeFor(lateStatus.get().trim(), factory, config, requestId, logPath);
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
                                    String requestId, String logPath) {
        logger.info("Run status: {}", body);
        if ("completed".equals(body)) {
            showResults(factory, config, requestId);
            return OptionalInt.of(0);
        }
        if (body.startsWith("failed:")) {
            logger.error("Benchmark failed. Runner log: {}", logPath);
            return OptionalInt.of(1);
        }
        return OptionalInt.empty();
    }

    private void showResults(AwsClientFactory factory, BaasConfig config, String requestId) {
        try (var ssm = factory.ssm()) {
            Optional<String> mongoUri = new SsmService(ssm)
                .getParameterOptional("/" + config.getPrefix() + "/mongo/connection-string");
            if (mongoUri.isEmpty()) {
                logger.warn("No MongoDB URI configured — skipping results display.");
                return;
            }
            try (var results = new ResultsQueryService(mongoUri.get())) {
                var rows = results.queryByRequestId(requestId);
                logger.info("Results for request: {}", requestId);
                results.printTable(rows);
            }
        } catch (Exception e) {
            logger.warn("Could not fetch results from MongoDB: {}", e.getMessage());
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
}
