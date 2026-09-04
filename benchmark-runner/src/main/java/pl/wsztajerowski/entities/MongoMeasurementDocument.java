package pl.wsztajerowski.entities;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import pl.wsztajerowski.baas.model.StoredMeasurement;

/**
 * Wraps a measurement so Morphia has an {@code @Id} to key on. The id is the same {@code pk}/{@code
 * sk} pair the DynamoDB adapter uses, so a repeated write replaces rather than duplicates — the
 * property {@code PutItem} gives the other adapter for free.
 */
@Entity("measurements")
public class MongoMeasurementDocument {

    @Id
    private String id;
    private StoredMeasurement measurement;

    @SuppressWarnings("unused") // Morphia requires a no-arg constructor
    private MongoMeasurementDocument() {}

    public MongoMeasurementDocument(String id, StoredMeasurement measurement) {
        this.id = id;
        this.measurement = measurement;
    }

    public String getId() {
        return id;
    }

    public StoredMeasurement getMeasurement() {
        return measurement;
    }
}
