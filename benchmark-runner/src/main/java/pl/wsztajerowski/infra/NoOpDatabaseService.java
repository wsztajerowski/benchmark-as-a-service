package pl.wsztajerowski.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoOpDatabaseService implements DatabaseService {
    private static final Logger logger = LoggerFactory.getLogger(NoOpDatabaseService.class);

    @Override
    public <T> void save(T entity) {
        logger.info("NoOp service - no db operation");
    }

    @Override
    public <T> UpsertService<T> upsert(Class<T> entity) {
        return new NoOpUpsertService<>();
    }

    static class NoOpUpsertService<T> implements UpsertService<T> {
        private static final Logger logger = LoggerFactory.getLogger(NoOpUpsertService.class);
        @Override
        public UpsertService<T> byFieldValue(String fieldName, Object fieldValue) {
            return this;
        }

        @Override
        public UpsertService<T> setValue(String fieldName, Object fieldValue) {
            return this;
        }

        @Override
        public void execute() {
            logger.info("NoOp service - no db operation");
        }
    }
}
