package pl.wsztajerowski.infra;

import dev.morphia.Datastore;
import dev.morphia.UpdateOptions;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.updates.UpdateOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static dev.morphia.query.filters.Filters.eq;
import static dev.morphia.query.updates.UpdateOperators.set;
import static java.util.Objects.requireNonNull;

public class DocumentDbService implements DatabaseService {
    protected final Datastore datastore;

    DocumentDbService(Datastore datastore) {
        this.datastore = datastore;
    }

    public <T> void save(T entity) {
        datastore
            .insert(entity);
    }

    public <T> DocumentDbUpsertService<T> upsert(Class<T> entity) {
        return new DocumentDbUpsertService<>(entity);
    }

    public class DocumentDbUpsertService<T> implements UpsertService<T> {
        private final Logger logger = LoggerFactory.getLogger(DocumentDbUpsertService.class);
        private final Class<T> entity;
        private Filter filter;

        private final Map<String, Object> updates = new HashMap<>();


        public DocumentDbUpsertService(Class<T> entity) {
            this.entity = entity;
        }

        public DocumentDbUpsertService<T> byFieldValue(String fieldName, Object fieldValue) {
            filter = eq(fieldName, fieldValue);
            return this;
        }

        public DocumentDbUpsertService<T> setValue(String fieldName, Object fieldValue) {
            updates.put(fieldName, fieldValue);
            return this;
        }

        public void execute(){
            requireNonNull(filter, "Please set filter (e.g. by using byFieldValue method) before executing this method!");
            logger.debug("Updating document: {}", filter);
            UpdateOperator[] updateOperators = updates.entrySet()
                .stream()
                .map(entry -> set(entry.getKey(), entry.getValue()))
                .toArray(UpdateOperator[]::new);
            datastore
                .find(entity)
                .filter(filter)
                .update(new UpdateOptions().upsert(true),
                    updateOperators);
        }
    }
}
