package pl.wsztajerowski.commands;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.model.RunId;
import pl.wsztajerowski.baas.model.RunLayout;
import pl.wsztajerowski.baas.model.TagKeys;
import pl.wsztajerowski.infra.ResultsStore;
import pl.wsztajerowski.infra.ResultsStoreBuilder;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.S3Options;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

@Command
public class ApiCommonSharedOptions {
    @Option(names = "--tag")
    Map<String, String> tags;

    @Option(names = "--result-path", description = "Local path or path within S3 bucket to save benchmark results. Default value: value of Request ID option.")
    Path resultPath;

    @Option(names = {"-id","--request-id"}, description = "Request ID. Default value: a generated run identifier, minted from the run's instant.")
    String requestId;

    @Option(names = "--created-at",
        description = "The run's instant, supplied by the launching CLI so the run identifier and the "
            + "stored timestamp cannot disagree. Default: now.")
    String createdAt;

    @Option(names = "--project", description = "Project name; composes the results partition key.")
    String project;

    @Option(names = "--results-table", description = "DynamoDB table holding benchmark measurements.")
    String resultsTableName;

    @Option(names = "--no-database", description = "Discard measurements instead of storing them. Explicit opt-in; absent configuration is an error.")
    boolean noDatabase;

    @Option(names = "--dynamodb-endpoint",
        defaultValue = "${AWS_ENDPOINT_URL_DYNAMODB}",
        description = "Custom DynamoDB endpoint, for LocalStack.")
    URI dynamoDbEndpoint;

    @Option(names = {"--mongo-connection-string", "-m"},
        defaultValue = "${MONGO_CONNECTION_STRING}",
        description = "MongoDB connection string - you could provide it as a option value or put in MONGO_CONNECTION_STRING env variable. For details see: https://www.mongodb.com/docs/manual/reference/connection-string/")
    URI mongoConnectionString;

    @CommandLine.ArgGroup(exclusive = false)
    ApiS3Options s3Options;

    static class ApiS3Options {
        @Option(names = "--s3-bucket", required = true, description = "S3 bucket name where benchmark will be placed.")
        String s3BucketName;

        @Option(names = "--s3-service-endpoint",
            defaultValue = "${AWS_ENDPOINT_URL_S3}",
            description = "Custom S3 Service endpoint")
        URI s3ServiceEndpoint;
    }

    private Instant resolvedCreatedAt;

    /**
     * Read once and cached. The runner already captures one timestamp per run rather than one per
     * result — a per-result clock read would make two results from the same run differ by a stray
     * millisecond — and this extends that single read one hop out, to the machine that named the run.
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
        String nonNullRequestId = getRequestId();
        String resolvedProject = getProject();
        Path nonNullResultPath = Optional.ofNullable(resultPath)
            .orElseGet(() -> Path.of(RunLayout.runPrefix(resolvedProject, nonNullRequestId)));
        Map<String, String> tagMap = Optional.ofNullable(tags)
            .orElse(Collections.emptyMap());
        return new CommonSharedOptions(
            nonNullResultPath, nonNullRequestId, getCreatedAt(), resolvedProject, tagMap);
    }

    /**
     * Minted from the run's own instant rather than from a second clock read, so the identifier's
     * timestamp and the stored {@code createdAt} are the same value rather than two nearby ones.
     */
    public String getRequestId() {
        return (requestId == null || requestId.isBlank()) ? RunId.generate(getCreatedAt()) : requestId;
    }

    /**
     * Falls back to a {@code project} tag so {@code baas run}'s existing {@code --tag project=…}
     * keeps working, and then stops. No {@code unknown} placeholder: it silently writes measurements
     * to a partition nobody queries — CI has been doing exactly that — and under the unified layout
     * it would also scatter a run's S3 artifacts under a placeholder prefix.
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

    public String getResultsTableName() {
        return resultsTableName;
    }

    public boolean isNoDatabase() {
        return noDatabase;
    }

    public URI getDynamoDbEndpoint() {
        return dynamoDbEndpoint;
    }

    public URI getMongoConnectionString() {
        return mongoConnectionString;
    }

    public ResultsStore buildResultsStore() {
        return ResultsStoreBuilder.builder()
            .withTableName(resultsTableName)
            .withConnectionString(mongoConnectionString)
            .withNoDatabase(noDatabase)
            .withDynamoDbEndpoint(dynamoDbEndpoint)
            .build();
    }

    public S3Options getS3Options() {
        String s3BucketName = Optional.ofNullable(s3Options)
            .map(s3Options -> s3Options.s3BucketName)
            .orElse("");
        URI s3ServiceEndpoint = Optional.ofNullable(s3Options)
            .map(s3Options -> s3Options.s3ServiceEndpoint)
            .orElse(null);
        return new S3Options(s3BucketName, s3ServiceEndpoint);
    }
}
