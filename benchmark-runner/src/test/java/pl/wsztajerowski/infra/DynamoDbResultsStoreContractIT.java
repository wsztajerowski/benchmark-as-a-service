package pl.wsztajerowski.infra;

import pl.wsztajerowski.TestcontainersWithDynamoDbBaseIT;

class DynamoDbResultsStoreContractIT extends TestcontainersWithDynamoDbBaseIT
    implements ResultsStoreContractTest {

    @Override
    public ResultsStore store() {
        return new DynamoDbResultsStore(dynamoDbClient, TEST_TABLE_NAME);
    }

    @Override
    public long storedCount() {
        return countItemsInTestTable();
    }
}
