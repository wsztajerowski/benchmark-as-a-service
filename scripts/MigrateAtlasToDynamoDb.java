import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import pl.wsztajerowski.baas.model.JcstressSummary;
import pl.wsztajerowski.baas.model.MeasurementItemMapper;
import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.baas.model.SecondaryMetric;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Throwaway migration: MongoDB Atlas -> the DynamoDB results table. Run through
 * {@code scripts/migrate-atlas-to-dynamodb}, which resolves the connection string; deleted by
 * task 10.3 once the migration is verified.
 *
 * <p>Run as a single-file source program against the shaded runner JAR, which already carries the
 * Mongo driver, the DynamoDB SDK and {@code baas-model}. That is the point: the keys and the item
 * layout come from {@link ResultKeys} and {@link MeasurementItemMapper}, the same classes the
 * runner writes with, so a migrated row cannot be keyed differently from a live one. Re-deriving
 * either here is how a migration silently lands rows that {@code baas results} never returns.
 *
 * <p>Idempotent by construction: every write is a {@code PutItem} on {@code (pk, sk)} derived from
 * the source document, so a repeated or interrupted run overwrites rather than duplicates.
 */
public class MigrateAtlasToDynamoDb {

    private static final String JMH_COLLECTION = "jmh_benchmarks";
    private static final String JCSTRESS_COLLECTION = "jcstress_tests";

    /**
     * Rows with no {@code project} tag. NOT bare {@code unknown}: {@code RunCommand} already uses
     * that for a missing git commit, and two meanings sharing one string read identically in a
     * results table.
     */
    private static final String UNKNOWN_PROJECT = "unknown-migrated";

    private static final int BATCH_SIZE = 25;

    public static void main(String[] args) throws Exception {
        boolean dryRun = false;
        boolean verify = false;
        String table = null;
        int limit = Integer.MAX_VALUE;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run" -> dryRun = true;
                case "--verify" -> verify = true;
                case "--table" -> table = args[++i];
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("""
                        Usage: scripts/migrate-atlas-to-dynamodb [--dry-run|--verify] [--table <name>] [--limit <n>]
                          --dry-run  report what would be written, per collection and per project partition
                          --verify   read every migrated row back and compare scores; writes nothing
                          --table    results table (default: from BAAS_RESULTS_TABLE)
                          --limit    stop after n documents per collection (smoke tests)""");
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        String mongoUri = System.getenv("MONGO_CONNECTION_STRING");
        if (mongoUri == null || mongoUri.isBlank()) {
            throw new IllegalStateException(
                "MONGO_CONNECTION_STRING is not set. Run scripts/migrate-atlas-to-dynamodb, which "
                    + "resolves it from SSM — the URI must never travel in argv, where `ps` shows it.");
        }
        if (table == null) {
            table = System.getenv("BAAS_RESULTS_TABLE");
        }
        if (!dryRun && (table == null || table.isBlank())) {
            throw new IllegalStateException("No results table given: pass --table or set BAAS_RESULTS_TABLE.");
        }

        String databaseName = new ConnectionString(mongoUri).getDatabase();
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalStateException("The connection string must name a database.");
        }

        String mode = dryRun ? "DRY RUN — nothing will be written"
            : verify ? "VERIFYING against " + table
            : "MIGRATING into " + table;
        System.out.println(mode + " (database " + databaseName + ")");

        var report = new Report();
        List<StoredMeasurement> measurements = new ArrayList<>();

        try (MongoClient mongo = MongoClients.create(mongoUri)) {
            MongoDatabase database = mongo.getDatabase(databaseName);
            collect(database.getCollection(JMH_COLLECTION), JMH_COLLECTION, limit, report, measurements,
                MigrateAtlasToDynamoDb::jmhMeasurement);
            collect(database.getCollection(JCSTRESS_COLLECTION), JCSTRESS_COLLECTION, limit, report, measurements,
                MigrateAtlasToDynamoDb::jcstressMeasurement);
        }

        report.print();

        if (dryRun) {
            System.out.println("\nNothing written. Re-run without --dry-run to migrate.");
            return;
        }
        if (measurements.isEmpty()) {
            System.out.println("\nNothing to " + (verify ? "verify." : "write."));
            return;
        }

        boolean verified = true;
        try (DynamoDbClient dynamo = DynamoDbClient.create()) {
            if (verify) {
                verified = verify(dynamo, table, measurements);
            } else {
                int written = write(dynamo, table,
                    measurements.stream().map(MeasurementItemMapper::toItem).toList());
                System.out.println("\nWrote " + written + " item(s) into " + table + ".");
            }
        }
        if (!verified) {
            System.exit(1);
        }
    }

    /**
     * Reads every source row back out of the table by its own key and compares the score. Needs
     * only {@code GetItem}, which the operator role holds — so the check can be run by whoever
     * asks the question, not only by the principal that did the writing.
     */
    private static boolean verify(DynamoDbClient dynamo, String table, List<StoredMeasurement> measurements) {
        int matched = 0;
        List<String> problems = new ArrayList<>();
        for (StoredMeasurement measurement : measurements) {
            Map<String, AttributeValue> key = Map.of(
                MeasurementItemMapper.PK, AttributeValue.fromS(ResultKeys.partitionKey(measurement.project())),
                MeasurementItemMapper.SK, AttributeValue.fromS(ResultKeys.sortKey(measurement)));
            Map<String, AttributeValue> stored = dynamo.getItem(
                GetItemRequest.builder().tableName(table).key(key).consistentRead(true).build()).item();

            if (stored == null || stored.isEmpty()) {
                problems.add("MISSING  " + key.get(MeasurementItemMapper.SK).s());
                continue;
            }
            StoredMeasurement read = MeasurementItemMapper.fromItem(stored);
            if (!sameScore(measurement.score(), read.score())) {
                problems.add("SCORE    " + key.get(MeasurementItemMapper.SK).s()
                    + " atlas=" + measurement.score() + " table=" + read.score());
                continue;
            }
            if (!measurement.tags().equals(read.tags())) {
                problems.add("TAGS     " + key.get(MeasurementItemMapper.SK).s());
                continue;
            }
            matched++;
        }

        System.out.println("\nVerified " + matched + "/" + measurements.size() + " row(s).");
        problems.forEach(problem -> System.out.println("  " + problem));
        return problems.isEmpty();
    }

    /** A non-finite score is dropped on the way in, so absent-on-both is a match, not a loss. */
    private static boolean sameScore(Double source, Double stored) {
        if (source == null || !Double.isFinite(source)) {
            return stored == null;
        }
        return stored != null && source.doubleValue() == stored.doubleValue();
    }

    private interface Mapper {
        StoredMeasurement map(Document document);
    }

    private static void collect(MongoCollection<Document> collection, String name, int limit,
                                Report report, List<StoredMeasurement> measurements, Mapper mapper) {
        int read = 0;
        for (Document document : collection.find().limit(limit == Integer.MAX_VALUE ? 0 : limit)) {
            read++;
            try {
                StoredMeasurement measurement = mapper.map(document);
                // Mapped here rather than at write time so a document that cannot become a valid
                // item is reported by --dry-run, where it costs nothing, instead of halfway
                // through a real migration.
                MeasurementItemMapper.toItem(measurement);
                measurements.add(measurement);
                report.mapped(name, measurement);
            } catch (RuntimeException e) {
                report.skipped(name, String.valueOf(document.get("_id")), e.getMessage());
            }
        }
        report.read(name, read);
    }

    /**
     * The JMH {@code _id} is the composite {@code (requestId, benchmarkName, benchmarkType)}, and
     * {@code benchmarkType} there is JMH's <em>mode</em> — the historical writer passed
     * {@code jmhResult.mode()} into that field. The sort key carries mode, so defaulting it would
     * collide a {@code -bm thrpt,avgt} run's two rows onto one key.
     */
    private static StoredMeasurement jmhMeasurement(Document document) {
        Document id = required(document, "_id", Document.class);
        Document result = required(document, "jmhResult", Document.class);
        Document metadata = document.get("benchmarkMetadata", Document.class);

        String benchmark = firstNonBlank(id.getString("benchmarkName"), result.getString("benchmark"));
        if (benchmark == null) {
            throw new IllegalArgumentException("no benchmark name in _id or jmhResult");
        }
        int lastDot = benchmark.lastIndexOf('.');
        if (lastDot < 0) {
            throw new IllegalArgumentException("benchmark name has no class/method separator: " + benchmark);
        }

        Document primary = result.get("primaryMetric", Document.class);
        Map<String, String> tags = tagsOf(metadata);

        return new StoredMeasurement(
            projectOf(tags),
            required(id, "requestId", String.class),
            createdAt(document, metadata),
            MeasurementKind.JMH,
            benchmark.substring(0, lastDot),
            benchmark.substring(lastDot + 1),
            firstNonBlank(id.getString("benchmarkType"), result.getString("mode")),
            primary == null ? null : doubleOf(primary.get("score")),
            primary == null ? null : doubleOf(primary.get("scoreError")),
            primary == null ? null : primary.getString("scoreUnit"),
            secondaryMetrics(result),
            null,
            tags,
            resultPath(metadata),
            // No verbatim result JSON exists for a pre-cutover run: uploading it is part of the
            // same change that introduced this table, so historical rows have nothing to point at.
            null,
            null,
            null);
    }

    /** The JCStress {@code _id} is the bare {@code requestId}, not a composite. */
    private static StoredMeasurement jcstressMeasurement(Document document) {
        Document result = required(document, "result", Document.class);
        Document metadata = document.get("metadata", Document.class);
        Map<String, String> tags = tagsOf(metadata);

        Map<String, String> failed = stringMap(result.get("testsWithFailedResults", Document.class));
        Map<String, String> errors = stringMap(result.get("testsWithErrorResults", Document.class));
        Map<String, String> interesting = stringMap(result.get("testsWithInterestingResults", Document.class));

        return new StoredMeasurement(
            projectOf(tags),
            required(document, "_id", String.class),
            createdAt(document, metadata),
            MeasurementKind.JCSTRESS,
            null, null, null, null, null, null,
            Map.of(),
            new JcstressSummary(
                intOf(result.get("totalTests")),
                intOf(result.get("passedTests")),
                failed.size(),
                errors.size(),
                failed, errors, interesting),
            tags,
            resultPath(metadata),
            null, null, null);
    }

    /**
     * Kept as-is, including {@code source} on the 36 CI rows that carry it: unknown keys are
     * permitted by design and warned about only when nothing carries them, and discarding
     * provenance is irreversible once Atlas is gone.
     */
    private static Map<String, String> tagsOf(Document metadata) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (metadata == null) {
            return tags;
        }
        Document source = metadata.get("tags", Document.class);
        if (source == null) {
            return tags;
        }
        source.forEach((key, value) -> {
            if (value != null) {
                tags.put(key, String.valueOf(value));
            }
        });
        return tags;
    }

    private static String projectOf(Map<String, String> tags) {
        String project = tags.get("project");
        return project == null || project.isBlank() ? UNKNOWN_PROJECT : project;
    }

    /**
     * Morphia wrote {@code LocalDateTime} as a BSON date, but the collection predates more than
     * one writer, so a string form is accepted too. Normalised to UTC and truncated to
     * milliseconds, matching what {@code StoredMeasurement} does to a live measurement — the sort
     * key carries exactly three fractional digits, and a wider value would key differently.
     *
     * <p>Looked up in the metadata sub-document <em>and</em> at the document root. An earlier
     * schema put {@code createdAt} on the root, and 6 of the 123 historical JMH documents still
     * carry it there — all of them real `lynx-journal` measurements. Reading only one location
     * silently skipped them, which the dry run caught: the whole point of migrating before Atlas
     * is decommissioned is that dropping a row is irreversible.
     */
    private static Instant createdAt(Document document, Document metadata) {
        Object value = metadata == null ? null : metadata.get("createdAt");
        if (value == null) {
            value = document.get("createdAt");
        }
        if (value == null) {
            throw new IllegalArgumentException("no createdAt in benchmarkMetadata or at the root");
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Number epochMillis) {
            return Instant.ofEpochMilli(epochMillis.longValue());
        }
        String text = String.valueOf(value);
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        }
    }

    /**
     * Historical documents carry no result path of their own. The profiler output paths do encode
     * it — they are S3 keys under {@code <branch>/<type>/<timestamp>/} — so the run's prefix is
     * recoverable for the rows that have them, which is what makes {@code baas run-artifacts}
     * work against migrated history. Rows without profiler output get null rather than a guess.
     */
    private static String resultPath(Document metadata) {
        Document paths = metadata == null ? null : metadata.get("profilerOutputPaths", Document.class);
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        for (Object value : paths.values()) {
            String[] segments = String.valueOf(value).split("/");
            if (segments.length >= 4) {
                return segments[0] + "/" + segments[1] + "/" + segments[2];
            }
        }
        return null;
    }

    private static Map<String, SecondaryMetric> secondaryMetrics(Document result) {
        Document metrics = result.get("secondaryMetrics", Document.class);
        if (metrics == null) {
            return Map.of();
        }
        Map<String, SecondaryMetric> mapped = new LinkedHashMap<>();
        metrics.forEach((name, value) -> {
            if (value instanceof Document metric) {
                Double score = doubleOf(metric.get("score"));
                String unit = metric.getString("scoreUnit");
                // SecondaryMetric.score is primitive: a null score would NPE here rather than at
                // the write, and unit-less entries are rejected by DynamoDB as empty attributes.
                if (score != null && unit != null) {
                    mapped.put(name, new SecondaryMetric(score, unit));
                }
            }
        });
        return mapped;
    }

    private static int write(DynamoDbClient dynamo, String table, List<Map<String, AttributeValue>> items)
        throws InterruptedException {
        int written = 0;
        for (int start = 0; start < items.size(); start += BATCH_SIZE) {
            List<WriteRequest> batch = items.subList(start, Math.min(start + BATCH_SIZE, items.size()))
                .stream()
                .map(item -> WriteRequest.builder().putRequest(PutRequest.builder().item(item).build()).build())
                .toList();

            Map<String, List<WriteRequest>> pending = Map.of(table, batch);
            for (int attempt = 0; !pending.isEmpty(); attempt++) {
                if (attempt > 0) {
                    Thread.sleep(Math.min(1000L * (1L << (attempt - 1)), 8000L));
                }
                BatchWriteItemResponse response = dynamo.batchWriteItem(
                    BatchWriteItemRequest.builder().requestItems(pending).build());
                pending = response.unprocessedItems();
                if (attempt > 6) {
                    throw new IllegalStateException(
                        "DynamoDB kept returning unprocessed items; re-run — the migration is idempotent.");
                }
            }
            written += batch.size();
            System.out.println("  wrote " + written + "/" + items.size());
        }
        return written;
    }

    private static <T> T required(Document document, String field, Class<T> type) {
        T value = document.get(field, type);
        if (value == null) {
            throw new IllegalArgumentException("missing " + field);
        }
        return value;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null || second.isBlank() ? null : second;
    }

    private static Double doubleOf(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Map<String, String> stringMap(Document document) {
        Map<String, String> mapped = new LinkedHashMap<>();
        if (document != null) {
            document.forEach((key, value) -> mapped.put(key, String.valueOf(value)));
        }
        return mapped;
    }

    /** Counts per collection and per derived partition — the shape §9.5 asks a dry run to report. */
    private static final class Report {
        private final Map<String, Integer> readPerCollection = new TreeMap<>();
        private final Map<String, Integer> mappedPerCollection = new TreeMap<>();
        private final Map<String, Integer> perProject = new TreeMap<>();
        private final Map<String, Integer> withResultPath = new TreeMap<>();
        private final List<String> skipped = new ArrayList<>();

        void read(String collection, int count) {
            readPerCollection.merge(collection, count, Integer::sum);
        }

        void mapped(String collection, StoredMeasurement measurement) {
            mappedPerCollection.merge(collection, 1, Integer::sum);
            perProject.merge(ResultKeys.partitionKey(measurement.project()), 1, Integer::sum);
            if (measurement.resultPath() != null) {
                withResultPath.merge(collection, 1, Integer::sum);
            }
        }

        void skipped(String collection, String id, String reason) {
            skipped.add(collection + " " + id + ": " + reason);
        }

        void print() {
            System.out.println("\nDocuments read:");
            readPerCollection.forEach((collection, count) -> System.out.printf(
                "  %-18s %5d read, %5d mappable, %5d with a recoverable result path%n",
                collection, count, mappedPerCollection.getOrDefault(collection, 0),
                withResultPath.getOrDefault(collection, 0)));

            System.out.println("\nItems per partition:");
            perProject.forEach((partition, count) ->
                System.out.printf("  %-40s %5d%n", partition, count));

            if (!skipped.isEmpty()) {
                System.out.println("\nSkipped " + skipped.size() + " document(s):");
                skipped.forEach(entry -> System.out.println("  " + entry));
            }
        }
    }
}
