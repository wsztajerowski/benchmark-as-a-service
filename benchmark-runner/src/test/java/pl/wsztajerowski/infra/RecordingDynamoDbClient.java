package pl.wsztajerowski.infra;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Records batch sizes and can simulate unprocessed items, without needing LocalStack. */
class RecordingDynamoDbClient implements DynamoDbClient {

    private final List<Integer> batchSizes = new ArrayList<>();
    private int unprocessedOnFirstCall = 0;
    private int alwaysUnprocessed = 0;

    void returnUnprocessedOnFirstCall(int count) {
        this.unprocessedOnFirstCall = count;
    }

    void alwaysReturnUnprocessed(int count) {
        this.alwaysUnprocessed = count;
    }

    List<Integer> batchSizes() {
        return batchSizes;
    }

    int callCount() {
        return batchSizes.size();
    }

    @Override
    public BatchWriteItemResponse batchWriteItem(BatchWriteItemRequest request) {
        List<WriteRequest> submitted = request.requestItems().values().iterator().next();
        batchSizes.add(submitted.size());
        String table = request.requestItems().keySet().iterator().next();

        int leftover = alwaysUnprocessed > 0 ? alwaysUnprocessed
            : (batchSizes.size() == 1 ? unprocessedOnFirstCall : 0);

        if (leftover <= 0) {
            return BatchWriteItemResponse.builder().build();
        }
        return BatchWriteItemResponse.builder()
            .unprocessedItems(Map.of(table, submitted.subList(0, Math.min(leftover, submitted.size()))))
            .build();
    }

    @Override
    public String serviceName() {
        return "dynamodb";
    }

    @Override
    public void close() {
        // nothing to release
    }
}
