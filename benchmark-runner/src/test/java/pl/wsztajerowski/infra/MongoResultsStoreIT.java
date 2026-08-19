package pl.wsztajerowski.infra;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.TestcontainersWithS3AndMongoBaseIT;
import pl.wsztajerowski.entities.MongoMeasurementDocument;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MongoResultsStoreIT extends TestcontainersWithS3AndMongoBaseIT {

    @Test
    void writesOneDocumentPerMeasurement() {
        var store = new MongoResultsStore(datastore());

        store.write(List.of(
            StoredMeasurementFixtures.jmh("methodOne"),
            StoredMeasurementFixtures.jmh("methodTwo")));

        assertThat(datastore().find(MongoMeasurementDocument.class).count()).isEqualTo(2);
    }

    @Test
    void aRepeatedWriteIsIdempotent() {
        var store = new MongoResultsStore(datastore());
        var measurement = StoredMeasurementFixtures.jmh("methodOne");

        store.write(List.of(measurement));
        store.write(List.of(measurement));

        assertThat(datastore().find(MongoMeasurementDocument.class).count())
            .as("the same measurement written twice must not produce two documents")
            .isEqualTo(1);
    }
}
