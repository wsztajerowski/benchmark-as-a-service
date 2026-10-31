package pl.wsztajerowski.commands;

import pl.wsztajerowski.baas.model.RunnerArgs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.model.Jdks;
import pl.wsztajerowski.baas.model.JobId;
import pl.wsztajerowski.baas.model.JobLayout;
import pl.wsztajerowski.baas.model.TagKeys;
import pl.wsztajerowski.infra.ResultsStore;
import pl.wsztajerowski.infra.ResultsStoreBuilder;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.S3Options;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Command
public class ApiCommonSharedOptions {
    @Option(names = RunnerArgs.TAG)
    Map<String, String> tags;

    @Option(names = RunnerArgs.RESULT_PATH, description = "Local path or path within S3 bucket to save benchmark results. Default value: value of Request ID option.")
    Path resultPath;

    @Option(names = {RunnerArgs.JOB_ID_SHORT, RunnerArgs.JOB_ID}, description = "Request ID. Default value: a generated job identifier, minted from the job's instant.")
    String jobId;

    @Option(names = RunnerArgs.CREATED_AT,
        description = "The job's instant, supplied by the launching CLI so the job identifier and the "
            + "stored timestamp cannot disagree. Default: now.")
    String createdAt;

    @Option(names = RunnerArgs.PROJECT, description = "Project name; composes the results partition key.")
    String project;

    @Option(names = RunnerArgs.RESULTS_TABLE, required = true, description = "DynamoDB table holding benchmark measurements.")
    String resultsTableName;

    @Option(names = RunnerArgs.DYNAMODB_ENDPOINT,
        defaultValue = "${AWS_ENDPOINT_URL_DYNAMODB}",
        description = "Custom DynamoDB endpoint, for LocalStack.")
    URI dynamoDbEndpoint;

    @Option(names = RunnerArgs.S3_BUCKET, required = true, description = "S3 bucket the job's artifacts are written to.")
    String s3BucketName;

    @Option(names = RunnerArgs.S3_SERVICE_ENDPOINT,
        defaultValue = "${AWS_ENDPOINT_URL_S3}",
        description = "Custom S3 Service endpoint")
    URI s3ServiceEndpoint;

    @Option(names = RunnerArgs.JDK,
        description = "Id of the JDK under --jdks-root the benchmark process runs on. Default: java on PATH.")
    String jdk;

    @Option(names = RunnerArgs.JDKS_ROOT, hidden = true, defaultValue = Jdks.ROOT)
    Path jdksRoot;

    private Instant resolvedCreatedAt;

    /**
     * Read once and cached. The runner already captures one timestamp per job rather than one per
     * result — a per-result clock read would make two results from the same job differ by a stray
     * millisecond — and this extends that single read one hop out, to the machine that named the job.
     */
    public Instant getCreatedAt() {
        if (resolvedCreatedAt == null) {
            resolvedCreatedAt = (createdAt == null || createdAt.isBlank())
                ? Instant.now()
                : Instant.parse(createdAt);
        }
        return resolvedCreatedAt;
    }

    public CommonSharedOptions getRequestOptions(){
        String nonNullJobId = getJobId();
        String resolvedProject = getProject();
        Path nonNullResultPath = Optional.ofNullable(resultPath)
            .orElseGet(() -> Path.of(JobLayout.jobPrefix(resolvedProject, nonNullJobId)));
        Map<String, String> tagMap = Optional.ofNullable(tags)
            .orElse(Collections.emptyMap());
        return new CommonSharedOptions(
            nonNullResultPath, nonNullJobId, getCreatedAt(), resolvedProject, tagMap);
    }

    /**
     * Minted from the job's own instant rather than from a second clock read, so the identifier's
     * timestamp and the stored {@code createdAt} are the same value rather than two nearby ones.
     */
    public String getJobId() {
        return (jobId == null || jobId.isBlank()) ? JobId.generate(getCreatedAt()) : jobId;
    }

    /**
     * Falls back to a {@code project} tag so {@code baas run}'s existing {@code --tag project=…}
     * keeps working, and then stops. No {@code unknown} placeholder: it silently writes measurements
     * to a partition nobody queries — CI has been doing exactly that — and under the unified layout
     * it would also scatter a job's S3 artifacts under a placeholder prefix.
     */
    public String getProject() {
        if (project != null && !project.isBlank()) {
            return project;
        }
        String tagged = Optional.ofNullable(tags)
            .map(t -> t.get(TagKeys.PROJECT))
            .filter(value -> !value.isBlank())
            .orElse(null);
        if (tagged != null) {
            return tagged;
        }
        throw new IllegalStateException(
            "Cannot determine the project: pass --project <name> (or --tag project=<name>).");
    }

    /**
     * The {@code java} the benchmark process starts with: the selected JDK's, or {@code java} on
     * {@code PATH} when the job selected none (an image from before JDK selection). Refuses an id that
     * is malformed or has no {@code bin/java} on this image before anything runs; the CLI checked the
     * image's published list, so this is the second line.
     */
    public String javaExecutable() {
        if (jdk == null) {
            return "java";
        }
        Path java = jdksRoot.resolve(jdk).resolve("bin").resolve("java");
        if (!Jdks.isValidId(jdk) || !Files.isExecutable(java)) {
            throw new IllegalStateException("No JDK '%s' on this image: %s is not there. JDKs under %s: %s."
                .formatted(jdk, java, jdksRoot, installedIds()));
        }
        return java.toString();
    }

    private String installedIds() {
        try (Stream<Path> entries = Files.list(jdksRoot)) {
            return String.join(", ", entries.map(path -> path.getFileName().toString()).sorted().toList());
        } catch (IOException e) {
            return "none";
        }
    }

    public ResultsStore buildResultsStore() {
        return ResultsStoreBuilder.builder()
            .withTableName(resultsTableName)
            .withDynamoDbEndpoint(dynamoDbEndpoint)
            .build();
    }

    public S3Options getS3Options() {
        return new S3Options(s3BucketName, s3ServiceEndpoint);
    }
}
