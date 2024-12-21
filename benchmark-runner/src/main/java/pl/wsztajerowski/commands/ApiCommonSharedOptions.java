package pl.wsztajerowski.commands;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
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

    @Option(names = "--result-path", description = "Local path or path within S3 bucket to save benchmark results. Default value: ISO 8601 format of UTC current date-time.")
    Path resultPath;

    @Option(names = {"-id","--request-id"}, description = "Request ID. Default value: ISO 8601 format of UTC current date-time.")
    String requestId;

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
        return new CommonSharedOptions(nonNullResultPath, nonNullRequestId, tagMap);
    }

    public URI getMongoConnectionString() {
        return mongoConnectionString;
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
