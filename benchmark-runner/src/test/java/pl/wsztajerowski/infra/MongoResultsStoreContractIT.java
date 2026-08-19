package pl.wsztajerowski.infra;

import pl.wsztajerowski.TestcontainersWithS3AndMongoBaseIT;
import pl.wsztajerowski.entities.MongoMeasurementDocument;

class MongoResultsStoreContractIT extends TestcontainersWithS3AndMongoBaseIT
    implements ResultsStoreContractTest {

    @Override
    public ResultsStore store() {
        return new MongoResultsStore(datastore());
    }

    @Override
    public long storedCount() {
        return datastore().find(MongoMeasurementDocument.class).count();
    }
}
