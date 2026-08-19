package pl.wsztajerowski.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.MeasurementItemMapper;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One item per measurement, batched per run.
 *
 * <p>{@code BatchWriteItem} reports throttling and partial success by RETURNING the leftovers in
 * {@code unprocessedItems} rather than failing, so ignoring that field loses measurements while the
 * call looks successful. The remainder is resubmitted with backoff, and anything still unprocessed
 * at the end is fatal — a run that cannot store its results must exit non-zero.
 */
public class DynamoDbResultsStore implements ResultsStore {
    private static final Logger logger = LoggerFactory.getLogger(DynamoDbResultsStore.class);

    private static final int MAX_BATCH_SIZE = 25;
    private static final int MAX_ATTEMPTS = 5;
    private static final long INITIAL_BACKOFF_MILLIS = 100;

    private final DynamoDbClient client;
    private final String tableName;

    public DynamoDbResultsStore(DynamoDbClient client, String tableName) {
        this.client = client;
        this.tableName = tableName;
    }

    @Override
    public void write(List<StoredMeasurement> measurements) {
        if (measurements == null || measurements.isEmpty()) {
            logger.warn("No measurements to store.");
            return;
        }
        List<WriteRequest> requests = measurements.stream()
            .map(MeasurementItemMapper::toItem)
            .map(item -> WriteRequest.builder()
                .putRequest(PutRequest.builder().item(item).build())
                .build())
            .toList();

        for (int start = 0; start < requests.size(); start += MAX_BATCH_SIZE) {
            int end = Math.min(start + MAX_BATCH_SIZE, requests.size());
            writeBatchWithRetries(new ArrayList<>(requests.subList(start, end)));
        }
        logger.info("Stored {} measurement(s) in DynamoDB table {}.", measurements.size(), tableName);
    }

    private void writeBatchWithRetries(List<WriteRequest> batch) {
        List<WriteRequest> pending = batch;
        long backoff = INITIAL_BACKOFF_MILLIS;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            BatchWriteItemResponse response;
            try {
                response = client.batchWriteItem(BatchWriteItemRequest.builder()
                    .requestItems(Map.of(tableName, pending))
                    .build());
            } catch (RuntimeException e) {
                throw new ResultsStoreException(
                    "Failed to write " + pending.size() + " measurement(s) to " + tableName, e);
            }

            Map<String, List<WriteRequest>> unprocessed = response.unprocessedItems();
            if (unprocessed == null || unprocessed.get(tableName) == null
                || unprocessed.get(tableName).isEmpty()) {
                return;
            }

            pending = unprocessed.get(tableName);
            logger.warn("{} item(s) unprocessed on attempt {}/{}; retrying in {}ms",
                pending.size(), attempt, MAX_ATTEMPTS, backoff);
            sleep(backoff);
            backoff *= 2;
        }

        throw new ResultsStoreException(
            pending.size() + " measurement(s) remained unprocessed after " + MAX_ATTEMPTS
                + " attempts against " + tableName + ". Results were NOT stored.");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResultsStoreException("Interrupted while retrying a DynamoDB batch write", e);
        }
    }
}
