package pl.wsztajerowski.baas.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
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
import java.util.concurrent.Callable;

@Command(
    name = "run",
    mixinStandardHelpOptions = true,
    description = "Build a benchmark JAR, launch an EC2 runner, and poll for results.",
    separator = " "
)
public class RunCommand implements Callable<Integer> {

    private static final List<String> VALID_TYPES = List.of("jmh", "jmh-with-async", "jmh-with-prof", "jcstress");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Parameters(index = "0", paramLabel = "<type>",
        description = "Benchmark type: jmh, jmh-with-async, jmh-with-prof, jcstress.")
    String benchmarkType;

    @Parameters(index = "1..*", paramLabel = "PARAMS", description = "Parameters forwarded to benchmark-runner.jar.")
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
            System.err.println("Unknown benchmark type '" + benchmarkType + "'. Valid: " + VALID_TYPES);
            return 1;
        }

        BaasConfig config = configService.load();
        String resolvedInstanceType = instanceType != null ? instanceType : config.getEc2().getDefaultInstanceType();
        int resolvedTimeout = timeoutSeconds != null ? timeoutSeconds : config.getEc2().getBenchmarkTimeoutSeconds();
        int resolvedWallClock = wallClockSeconds != null ? wallClockSeconds
            : (timeoutSeconds != null ? timeoutSeconds + 300 : config.getEc2().getWallClockHardKillSeconds());
        String resolvedBranch = branch != null ? branch : currentGitBranch();

        // 1. Build
        if (!skipBuild) {
            runMavenBuild();
        }

        // 2. Determine JAR path
        Path jarPath = benchmarkJar != null ? benchmarkJar : Path.of(config.getBenchmark().getJarPath());
        if (!jarPath.toFile().exists()) {
            System.err.println("Benchmark JAR not found: " + jarPath);
            System.err.println("Run without --skip-build or specify --benchmark-jar.");
            return 1;
        }

        // 3. Generate IDs
        String timestamp = TS.format(LocalDateTime.now());
        String requestId = benchmarkType + "-" + timestamp;
        String resultPath = resolvedBranch + "/" + benchmarkType + "/" + timestamp;

        operatorCredentialsWarning(config).ifPresent(System.err::println);
        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

        // 4. Upload JARs
        System.out.println("Uploading benchmark JAR to S3...");
        String benchmarkJarKey = "runs/" + requestId + "/benchmark.jar";
        try (var s3 = factory.s3()) {
            new S3UploadService(s3).upload(jarPath, config.getAws().getBucket(), benchmarkJarKey);
        }

        String runnerJarS3Key = null;
        if (runnerJar != null) {
            System.out.println("Uploading runner JAR to S3...");
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

        // 6. Build user-data
        String userData = new UserDataScriptBuilder().build(
            config.getAws().getRegion(), config.getAws().getBucket(), config.getPrefix(),
            benchmarkType, requestId, resultPath, resolvedTimeout, resolvedWallClock,
            config.getBenchmark().getAsyncProfilerVersion(), runnerJarS3Key,
            benchmarkParams);

        // 7. Launch instance
        System.out.println("Launching EC2 instance (" + resolvedInstanceType + ")...");
        String instanceId;
        try (var ec2 = factory.ec2()) {
            instanceId = new Ec2ProvisioningService(ec2).runInstance(
                amiId, resolvedInstanceType,
                config.getAws().getSubnetId(), config.getAws().getSecurityGroupId(),
                config.getAws().getRunnerInstanceProfileName(),
                userData, requestId, extraTags);
        }
        System.out.println("Instance launched: " + instanceId);
        System.out.println("Request ID: " + requestId);

        // 8. Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Terminating instance " + instanceId + " ...");
            try (var ec2 = factory.ec2()) {
                new Ec2ProvisioningService(ec2).terminateInstance(instanceId);
            }
        }));

        // 9. Poll
        return poll(factory, config, requestId, resultPath, resolvedWallClock);
    }

    private int poll(AwsClientFactory factory, BaasConfig config,
                     String requestId, String resultPath, int wallClockSeconds) throws InterruptedException {
        long startMs = System.currentTimeMillis();
        long timeoutMs = (long) wallClockSeconds * 1000;
        String statusKey = resultPath + "/run-status";

        while (true) {
            long elapsed = (System.currentTimeMillis() - startMs) / 1000;
            if (elapsed * 1000 > timeoutMs) {
                System.err.println("Client-side wall-clock cap exceeded (" + wallClockSeconds + "s). Exiting poll.");
                return 1;
            }

            Optional<String> status;
            try (var s3 = factory.s3()) {
                status = new S3UploadService(s3).getObjectIfExists(config.getAws().getBucket(), statusKey);
            }

            if (status.isPresent()) {
                String body = status.get().trim();
                System.out.println("Run status: " + body);
                if ("completed".equals(body)) {
                    showResults(factory, config, requestId);
                    return 0;
                } else if (body.startsWith("failed:")) {
                    System.err.println("Benchmark failed. Check S3: s3://" + config.getAws().getBucket() + "/" + resultPath + "/");
                    return 1;
                }
            } else {
                System.out.printf("Still running... elapsed: %ds%n", elapsed);
            }

            Thread.sleep(15_000);
        }
    }

    private void showResults(AwsClientFactory factory, BaasConfig config, String requestId) {
        try (var ssm = factory.ssm()) {
            Optional<String> mongoUri = new SsmService(ssm)
                .getParameterOptional("/" + config.getPrefix() + "/mongo/connection-string");
            if (mongoUri.isEmpty()) {
                System.out.println("No MongoDB URI configured — skipping results display.");
                return;
            }
            try (var results = new ResultsQueryService(mongoUri.get())) {
                var rows = results.queryByRequestId(requestId);
                System.out.println();
                System.out.println("Results for request: " + requestId);
                results.printTable(rows);
            }
        } catch (Exception e) {
            System.err.println("Warning: could not fetch results from MongoDB: " + e.getMessage());
        }
    }

    private void runMavenBuild() throws IOException, InterruptedException {
        System.out.println("Building benchmark JAR (mvn clean package -q)...");
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
