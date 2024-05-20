package pl.symentis.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.symentis.services.options.CommonSharedOptions;

import java.net.URI;

@Command
public class ApiCommonSharedOptions {

    @Option(names = {"--mongo-connection-string", "-m"},
        defaultValue = "${MONGO_CONNECTION_STRING}",
        description = "MongoDB connection string - you could provide it as a option value or put in MONGO_CONNECTION_STRING env variable. For details see: https://www.mongodb.com/docs/manual/reference/connection-string/")
    URI mongoConnectionString;

    @Option(names = "--s3-service-endpoint",
        description = "Custom S3 Service endpoint")
    URI s3ServiceEndpoint;

    @Option(names = "--s3-bucket", defaultValue = "java-wonderland", description = "S3 bucket name where benchmark will be placed.")
    String s3BucketName;

    @Option(names = "--s3-result-prefix", required = true, description = "Path within S3 bucket to save benchmark results.")
    String s3ResultPrefix;

    @Option(names = {"-id","--request-id"}, required = true, description = "Request ID")
    String requestId;

    public CommonSharedOptions getValues(){
        return new CommonSharedOptions(s3BucketName, s3ResultPrefix, requestId);
    }

    public URI getMongoConnectionString() {
        return mongoConnectionString;
    }

    public URI getS3ServiceEndpoint() {
        return s3ServiceEndpoint;
    }
}
