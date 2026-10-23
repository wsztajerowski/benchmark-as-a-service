package pl.wsztajerowski.baas.deployment;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every AWS call {@code baas admin deployment export} and {@code import} make, under deployer
 * credentials, in the shape of {@code TeardownAws}: the commands keep the order — the manifest
 * last, nothing overwritten — and a hand-written fake pins it. Downloads and uploads run on a
 * small pool, so implementations are called from several threads at once.
 */
interface ExportAws extends AutoCloseable {

    /** One object in a listing, with the size a downloaded copy is checked against. */
    record StoredObject(String key, long size) {
    }

    /** The account the credentials belong to, recorded in the manifest and shown before an import. */
    String account();

    /** Every item of the table, page by page, as stored. */
    void scan(String table, Consumer<Map<String, AttributeValue>> item);

    List<StoredObject> list(String bucket, String prefix);

    /** Creates parent directories as needed. */
    void download(String bucket, String key, Path destination);

    Map<String, String> stackParameters(String stack);

    boolean tableExists(String table);

    boolean bucketExists(String bucket);

    /** Writes the item unless one with its key exists; returns whether it wrote. */
    boolean putItemIfAbsent(String table, Map<String, AttributeValue> item);

    /** Writes the object unless one with its key exists; returns whether it wrote. */
    boolean putObjectIfAbsent(String bucket, String key, Path file);

    @Override
    void close();
}
