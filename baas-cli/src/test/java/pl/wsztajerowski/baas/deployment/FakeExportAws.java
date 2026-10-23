package pl.wsztajerowski.baas.deployment;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * An in-memory deployment: one table, one bucket, one stack's parameters. Records each call by name,
 * in order, so a test can pin what happened before what. Thread-safe where the pool calls it.
 */
class FakeExportAws implements ExportAws {

    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final List<Map<String, AttributeValue>> table = Collections.synchronizedList(new ArrayList<>());
    final Map<String, byte[]> bucket = new ConcurrentHashMap<>();
    final Map<String, String> parameters = new HashMap<>();
    String account = "123456789012";
    boolean tableExists = true;
    boolean bucketExists = true;
    RuntimeException failScan;
    RuntimeException failDownload;
    /** Keys whose downloaded copy is cut short, as a dropped connection would leave it. */
    final List<String> truncated = new ArrayList<>();

    @Override
    public String account() {
        calls.add("account");
        return account;
    }

    @Override
    public void scan(String table, Consumer<Map<String, AttributeValue>> item) {
        calls.add("scan " + table);
        if (failScan != null) {
            throw failScan;
        }
        List.copyOf(this.table).forEach(item);
    }

    @Override
    public List<StoredObject> list(String bucket, String prefix) {
        calls.add("list " + bucket + " " + prefix);
        return new TreeMap<>(this.bucket).entrySet().stream()
            .filter(e -> e.getKey().startsWith(prefix))
            .map(e -> new StoredObject(e.getKey(), e.getValue().length))
            .toList();
    }

    @Override
    public void download(String bucket, String key, Path destination) {
        calls.add("download " + key);
        if (failDownload != null) {
            throw failDownload;
        }
        byte[] bytes = this.bucket.get(key);
        if (truncated.contains(key)) {
            bytes = java.util.Arrays.copyOf(bytes, bytes.length / 2);
        }
        try {
            Files.createDirectories(destination.getParent());
            Files.write(destination, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, String> stackParameters(String stack) {
        calls.add("stackParameters " + stack);
        return Map.copyOf(parameters);
    }

    @Override
    public boolean tableExists(String table) {
        calls.add("tableExists " + table);
        return tableExists;
    }

    @Override
    public boolean bucketExists(String bucket) {
        calls.add("bucketExists " + bucket);
        return bucketExists;
    }

    @Override
    public boolean putItemIfAbsent(String table, Map<String, AttributeValue> item) {
        calls.add("putItem");
        synchronized (this.table) {
            var key = List.of(item.get("pk"), item.get("sk"));
            boolean present = this.table.stream().anyMatch(i -> List.of(i.get("pk"), i.get("sk")).equals(key));
            if (present) {
                return false;
            }
            this.table.add(Map.copyOf(item));
            return true;
        }
    }

    @Override
    public boolean putObjectIfAbsent(String bucket, String key, Path file) {
        calls.add("putObject " + key);
        try {
            return this.bucket.putIfAbsent(key, Files.readAllBytes(file)) == null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
    }

    static Map<String, AttributeValue> measurement(String project, String sk) {
        return Map.of(
            "pk", AttributeValue.fromS("RESULT#" + project),
            "sk", AttributeValue.fromS(sk),
            "score", AttributeValue.fromN("1234.500"),
            "params", AttributeValue.fromM(Map.of("size", AttributeValue.fromS("10"))));
    }

    static Map<String, AttributeValue> job(String sk) {
        return Map.of(
            "pk", AttributeValue.fromS("JOB"),
            "sk", AttributeValue.fromS(sk),
            "status", AttributeValue.fromS("completed"),
            "watchdogSeconds", AttributeValue.fromN("7500"));
    }
}
