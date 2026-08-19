package pl.wsztajerowski.infra;

import dev.morphia.Datastore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.MongoMeasurementDocument;

import java.util.List;

/**
 * Retained so {@code benchmark-runner} keeps working as a standalone JAR against a user's own
 * MongoDB. BaaS itself never selects this adapter — inside BaaS, DynamoDB is the only store.
 */
public class MongoResultsStore implements ResultsStore {
    private static final Logger logger = LoggerFactory.getLogger(MongoResultsStore.class);

    private final Datastore datastore;

    public MongoResultsStore(Datastore datastore) {
        this.datastore = datastore;
    }

    @Override
    public void write(List<StoredMeasurement> measurements) {
        if (measurements == null || measurements.isEmpty()) {
            logger.warn("No measurements to store.");
            return;
        }
        try {
            for (StoredMeasurement measurement : measurements) {
                String id = ResultKeys.partitionKey(measurement.project())
                    + "|" + ResultKeys.sortKey(measurement);
                datastore.save(new MongoMeasurementDocument(id, measurement));
            }
            logger.info("Stored {} measurement(s) in MongoDB.", measurements.size());
        } catch (RuntimeException e) {
            throw new ResultsStoreException(
                "Failed to store " + measurements.size() + " measurement(s) in MongoDB", e);
        }
    }
}
