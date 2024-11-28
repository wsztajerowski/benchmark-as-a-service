package pl.wsztajerowski.infra;

public interface DatabaseService {

    <T> void save(T entity);
    <T> UpsertService<T> upsert(Class<T> entity);

    interface UpsertService<T> {
        UpsertService<T> byFieldValue(String fieldName, Object fieldValue);
        UpsertService<T> setValue(String fieldName, Object fieldValue);
        void execute();
    }
}
