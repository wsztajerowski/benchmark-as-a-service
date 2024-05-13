package pl.wsztajerowski.s3lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.CompletedFileDownload;
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest;
import software.amazon.awssdk.transfer.s3.model.FileDownload;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class App implements RequestHandler<S3Event, String> {
    private static final Logger logger = LoggerFactory.getLogger(App.class);
    private final S3Client s3Client = DependencyFactory.s3Client();
    private final S3TransferManager transferManager = DependencyFactory.s3TransferManager();

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        try {
            S3EventNotification.S3Entity s3 = s3Event.getRecords().getFirst().getS3();
            String bucket = s3.getBucket().getName();
            String key = s3.getObject().getKey();

            logger.info("Source bucket: {}, key: {}", bucket, key);
            URI uri = s3Client.serviceClientConfiguration().endpointOverride().orElse(null);
            logger.info("URI: {}", uri);

            ResponseBytes<GetObjectResponse> object = getObject(bucket, key);
            logger.info("Successfully retrieved {}/{} of type {}", bucket, key, object.response().contentType());
            byte[] byteArray = object.asByteArray();
            String request = new String(byteArray);
            JSONObject jo = new JSONObject(request);
            logger.info("File content: {}", jo);

            String runnerTarget = jo.getString("runnerTarget");
            CompletedFileDownload downloadResult = downloadFileFromS3(bucket, runnerTarget, Paths.get("/tmp/runner.jar"));
            logger.info("Content length [{}]", downloadResult.response().contentLength());

            String benchmarkTarget = jo.getString("benchmarkTarget");
            CompletedFileDownload downloadResult2 = downloadFileFromS3(bucket, benchmarkTarget, Paths.get("/tmp/benchmark.jar"));
            logger.info("Content length [{}]", downloadResult2.response().contentLength());

            List<String> commands = List.of("java", "-jar", "/tmp/runner.jar", "jcstress", "-r", "/tmp/jcstress-results",
                "-m", "mongodb://docker.for.mac.localhost:27017/local_test", "--process-output", "/tmp/jcstress.txt",
                "--benchmark-path", "/tmp/benchmark.jar","--s3-bucket", bucket, "--s3-result-prefix", "lambda", "--request-id", "req-1");
            ProcessBuilder processBuilder = new ProcessBuilder(commands);
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            return "Ok: " + exitCode;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private CompletedFileDownload downloadFileFromS3(String bucket, String key, Path targetPath) {
        DownloadFileRequest downloadFileRequest = DownloadFileRequest.builder()
            .getObjectRequest(b -> b.bucket(bucket).key(key))
            .destination(targetPath)
            .build();

        FileDownload downloadFile = transferManager.downloadFile(downloadFileRequest);

        return downloadFile.completionFuture().join();
    }

    private ResponseBytes<GetObjectResponse> getObject(String bucket, String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build();
        return s3Client.getObject(getObjectRequest, ResponseTransformer.toBytes());
    }
}
