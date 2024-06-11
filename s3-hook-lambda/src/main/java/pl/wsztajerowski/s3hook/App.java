package pl.wsztajerowski.s3hook;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;

public class App implements RequestHandler<S3Event, String> {
    private final S3Client s3Client = DependencyFactory.s3Client();
    private final SsmClient ssmClient = DependencyFactory.ssmClient();

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        try {
            LambdaLogger logger = context.getLogger();
            S3EventNotification.S3Entity s3 = s3Event.getRecords().getFirst().getS3();
            String bucket = s3.getBucket().getName();
            String key = s3.getObject().getKey();

            ResponseBytes<GetObjectResponse> object = getObject(bucket, key);
            logger.log("Successfully retrieved %s/%s of type %s \n".formatted(bucket, key, object.response().contentType()));
            String s3RequestBody = new String(object.asByteArray());
            logger.log("S3 Request content: %s \n".formatted(s3RequestBody));

            String ssmParamPrefix = System.getenv("SSM_PARAM_PREFIX");
            try (HttpClient client = HttpClient.newHttpClient()) {
                String org = getParaValue("/%s/github/org".formatted(ssmParamPrefix));
                String repo = getParaValue("/%s/github/repo".formatted(ssmParamPrefix));
                String workflowId = getParaValue("/%s/github/workflowid".formatted(ssmParamPrefix));
                String requestBody = benchmarkRequestBody("main", s3RequestBody);
                URI serverUri = benchmarkWorkflowUri(org, repo, workflowId);
                logger.log("Request URI: %s \n".formatted(serverUri));
                String githubToken = getParaValue("/%s/github/token".formatted(ssmParamPrefix));
                HttpRequest request = buildHttpRequest(requestBody, serverUri, githubToken);
                HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
                logger.log("GHA Response status: %d, GHA Response body: %s \n".formatted(httpResponse.statusCode(), httpResponse.body()));
            }

            return "Ok";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static HttpRequest buildHttpRequest(String requestBody, URI serverUri, String authBearerToken) {
        return HttpRequest.newBuilder()
            .uri(serverUri)
            .header("Authorization", "Bearer %s".formatted(authBearerToken))
            .header("Content-Type", "application/json")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .POST(BodyPublishers.ofString(requestBody))
            .build();
    }

    private static String benchmarkRequestBody(String workflowBranch, String s3RequestBody) {
        return """
            {
              "ref": "%s",
              "inputs": %s
            }""".formatted(workflowBranch, s3RequestBody);
    }

    private static URI benchmarkWorkflowUri(String org, String repo, String workflowId) {
        return URI.create("https://api.github.com/repos/%s/%s/actions/workflows/%s/dispatches"
            .formatted(org, repo, workflowId));
    }

    private ResponseBytes<GetObjectResponse> getObject(String bucket, String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build();
        return s3Client.getObject(getObjectRequest, ResponseTransformer.toBytes());
    }

    private String getParaValue(String paraName) {
        GetParameterRequest parameterRequest = GetParameterRequest.builder()
            .withDecryption(true)
            .name(paraName)
            .build();

        GetParameterResponse parameterResponse = ssmClient.getParameter(parameterRequest);
        return parameterResponse.parameter().value();
    }
}
