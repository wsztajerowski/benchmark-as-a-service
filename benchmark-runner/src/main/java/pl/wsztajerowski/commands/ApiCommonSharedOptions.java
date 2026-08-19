package pl.wsztajerowski.commands;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
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

    @Option(names = {"-id","--request-id"}, description = "Request ID. Default value: ISO 8601 format of UTC current date-time.")
    String requestId;

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

    public CommonSharedOptions getRequestOptions(){
        String nonNullRequestId = Optional.ofNullable(requestId)
            .orElseGet(() -> Instant.now().toString());
        Path nonNullResultPath = Optional.ofNullable(resultPath)
            .orElse(Path.of(nonNullRequestId));
        Map<String, String> tagMap = Optional.ofNullable(tags)
            .orElse(Collections.emptyMap());
        return new CommonSharedOptions(nonNullResultPath, nonNullRequestId, getProject(), tagMap);
    }

    /**
     * Falls back to a {@code project} tag so {@code baas run}'s existing {@code --tag project=…}
     * keeps working, then to {@code unknown} — a blank project would be rejected by
     * {@code StoredMeasurement} after the benchmark has already been paid for and run.
     */
    public String getProject() {
        if (project != null && !project.isBlank()) {
            return project;
        }
        return Optional.ofNullable(tags)
            .map(t -> t.get("project"))
            .filter(value -> !value.isBlank())
            .orElse("unknown");
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
