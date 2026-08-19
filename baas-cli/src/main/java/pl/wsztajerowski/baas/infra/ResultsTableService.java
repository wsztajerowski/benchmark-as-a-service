package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/** Table-level checks the admin commands need. Reads and writes of measurements live in the CLI's results package. */
public class ResultsTableService {

    private final DynamoDbClient dynamoDb;

    public ResultsTableService(DynamoDbClient dynamoDb) {
        this.dynamoDb = dynamoDb;
    }

    /**
     * Mirrors {@code S3UploadService.bucketExists}: a genuine "not there" is false, and anything
     * else — most importantly an access denial — is treated as "there", so the setup pre-check
     * never waves through a table it merely failed to see.
     */
    public boolean tableExists(String tableName) {
        try {
            dynamoDb.describeTable(r -> r.tableName(tableName));
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }
}
