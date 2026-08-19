# DynamoDB Results Store — Implementation Plan (Phase 3: §5 + supporting §8)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `benchmark-runner` the ability to write measurements to DynamoDB through a storage-neutral port, with the MongoDB adapter refactored onto the same port — without cutting anything over.

**Architecture:** `DatabaseService` (a Mongo-shaped interface with `save` and an `upsert` builder) is replaced by `ResultsStore`, a port that speaks the domain: it accepts a list of `StoredMeasurement` from `baas-model` and writes it. Two adapters implement it — DynamoDB and MongoDB — and a builder selects exactly one. The four subcommand services map their result types into `StoredMeasurement` and hand the list to the port.

**Tech Stack:** Java 25, Maven multi-module, AWS SDK v2 (BOM-managed), Morphia + `mongodb-driver-sync` (retained), JUnit 6, AssertJ, Testcontainers 2.x, LocalStack.

---

## Scope: build the write path, cut nothing over

After this phase the runner **still writes to MongoDB on every real run**. The DynamoDB adapter exists, is selectable, and is covered by integration tests — but `UserDataScriptBuilder` still passes a Mongo connection string and no table name, so nothing changes in production.

The cutover is §13, and it lands only after §9 has migrated history. See the execution-order table at the top of `tasks.md`.

**Covered here:** 5.1–5.12, and 8.1–8.5, 8.7, 8.9, 8.10, 8.11.
**Deliberately excluded:** 8.6 (query tests — needs §6's query layer) and 8.8 (download test — needs 7.7).

## Global Constraints

- **`pom.xml` version stays `0.0.0-semantically-released`.** Never bump by hand.
- **`baas-model` must stay Mongo-free.** A maven-enforcer rule bans `org.mongodb:*`, `dev.morphia:*` and `dev.morphia.morphia:*` there, bound to `validate`. `benchmark-runner` keeps both — the ban is scoped to the model module only.
- **`mvn -pl benchmark-runner verify` alone FAILS by design.** It needs the `fake-jmh-benchmarks` and `fake-stress-tests` shaded JARs in the local repo. A fresh worktree also needs `mvn -pl baas-model install -DskipTests` once.
- **`ASYNC_PATH` must point at a library that exists on the build machine.** The `/app/...` value in older notes is the on-instance Linux path; using it makes `JmhWithAsyncProfilerSubcommandServiceIT` **silently skip** rather than fail. On this machine: `/Users/wiktor/workspace/async-profiler/lib/libasyncProfiler.dylib`.
- **Morphia auto-maps everything under `pl.wsztajerowski.entities`** — the retained Mongo adapter depends on it, so new entity classes must live there.
- **JUnit 6 (`6.0.2`) and Testcontainers 2.x.** Integration tests pin `mongo:7.0.5`.
- **S3 is written before the store.** A run that fails at the store must still leave its artifacts behind.
- **Do not touch `UserDataScriptBuilder`, the 27017 egress rule, or any Mongo SSM grant.** Those are §13 and §14.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `benchmark-runner/.../infra/ResultsStore.java` | The port: write-only, domain-shaped | Create |
| `benchmark-runner/.../infra/ResultsStoreException.java` | Fatal store failure | Create |
| `benchmark-runner/.../infra/DynamoDbResultsStore.java` | DynamoDB adapter, batched, retrying | Create |
| `benchmark-runner/.../infra/MongoResultsStore.java` | MongoDB adapter on the same port | Create (from `DocumentDbService`) |
| `benchmark-runner/.../infra/NoOpResultsStore.java` | Explicit discard, only via `--no-database` | Create (from `NoOpDatabaseService`) |
| `benchmark-runner/.../infra/ResultsStoreBuilder.java` | Selects one adapter, fails on ambiguity | Create (from `DatabaseServiceBuilder`) |
| `DatabaseService`, `DocumentDbService`, `NoOpDatabaseService`, `DatabaseServiceBuilder` | Superseded | Delete |
| `benchmark-runner/.../entities/MongoMeasurementDocument.java` | Morphia entity wrapping a measurement | Create |
| `benchmark-runner/.../results/JmhMeasurementMapper.java` | `JmhResult` → `StoredMeasurement` | Create |
| `benchmark-runner/.../results/JCStressMeasurementMapper.java` | `JCStressResult` → `StoredMeasurement` | Create |
| `benchmark-runner/.../services/*SubcommandService.java` | Four write paths | Modify |
| `benchmark-runner/.../commands/ApiCommonSharedOptions.java` | New options | Modify |
| `benchmark-runner/src/test/.../TestcontainersWithDynamoDbBaseIT.java` | LocalStack with DynamoDB | Create |
| `benchmark-runner/src/test/.../infra/ResultsStoreContractTest.java` | One suite, both adapters | Create |
| `docker-compose.yaml`, `jmh-with-profiler.sh`, `jmh-with-async.sh` | Local dev | Modify |

---

## Task 1: The `ResultsStore` port, and deleting `upsert`

Covers 5.2 and 5.3.

**Files:**
- Create: `benchmark-runner/src/main/java/pl/wsztajerowski/infra/ResultsStore.java`
- Create: `benchmark-runner/src/main/java/pl/wsztajerowski/infra/ResultsStoreException.java`
- Create: `benchmark-runner/src/main/java/pl/wsztajerowski/infra/NoOpResultsStore.java`
- Delete: `benchmark-runner/src/main/java/pl/wsztajerowski/infra/DatabaseService.java`, `NoOpDatabaseService.java`
- Test: `benchmark-runner/src/test/java/pl/wsztajerowski/infra/NoOpResultsStoreTest.java`

**Interfaces:**
- Consumes: `pl.wsztajerowski.baas.model.StoredMeasurement` from `baas-model`.
- Produces: `ResultsStore.write(List<StoredMeasurement>)` returning `void`, throwing `ResultsStoreException`. Tasks 2, 5 and 6 implement and select it; Task 8 calls it.

**Why `upsert` goes.** `DatabaseService.upsert` exposes a Mongo-shaped update-operator builder (`byFieldValue`/`setValue`/`execute`) and is called by **none** of the four subcommand services. Porting a field-path surface for zero callers would leak Mongo semantics into a port that must serve both adapters.

**Why the port takes a `List`.** One item per measurement, batched per run: `BatchWriteItem` takes at most 25 items, and a JMH run produces several measurements. A single-item `write` would push the batching decision into every caller.

- [ ] **Step 1: Write the failing test**

```java
package pl.wsztajerowski.infra;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

class NoOpResultsStoreTest {

    @Test
    void discardsWithoutThrowing() {
        assertThatCode(() -> new NoOpResultsStore().write(List.of()))
            .doesNotThrowAnyException();
    }

    @Test
    void toleratesANullListRatherThanFailingLate() {
        assertThatCode(() -> new NoOpResultsStore().write(null))
            .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -pl baas-model install -DskipTests && mvn -pl benchmark-runner test -Dtest=NoOpResultsStoreTest`
Expected: FAIL to compile — `ResultsStore` and `NoOpResultsStore` do not exist.

- [ ] **Step 3: Create the port and its exception**

```java
package pl.wsztajerowski.infra;

import pl.wsztajerowski.baas.model.StoredMeasurement;

import java.util.List;

/**
 * Where a run's measurements go. The port speaks the domain, not storage: each adapter owns its
 * physical layout, so one item per measurement maps cleanly to one document per measurement and
 * nothing DynamoDB-specific leaks through.
 *
 * <p>Write-only by design. Reads belong to {@code baas-cli}, which never learns MongoDB exists.
 */
public interface ResultsStore {

    /**
     * Writes every measurement from one run, or throws. Partial success is never reported — a
     * caller that sees no exception may assume every measurement landed.
     *
     * @throws ResultsStoreException when the write ultimately fails after any configured retries
     */
    void write(List<StoredMeasurement> measurements);
}
```

```java
package pl.wsztajerowski.infra;

/** Thrown when a run's measurements could not be stored. Fatal: the run must exit non-zero. */
public class ResultsStoreException extends RuntimeException {

    public ResultsStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public ResultsStoreException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Create the no-op store**

```java
package pl.wsztajerowski.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.StoredMeasurement;

import java.util.List;

/**
 * Discards measurements. Selected ONLY by an explicit {@code --no-database}, never as a fallback
 * for absent configuration — the previous behaviour, where an empty connection string silently
 * selected a no-op, let a paid run report success while throwing its measurements away.
 */
public class NoOpResultsStore implements ResultsStore {
    private static final Logger logger = LoggerFactory.getLogger(NoOpResultsStore.class);

    @Override
    public void write(List<StoredMeasurement> measurements) {
        int count = measurements == null ? 0 : measurements.size();
        logger.warn("--no-database: discarding {} measurement(s). Nothing was stored.", count);
    }
}
```

- [ ] **Step 5: Confirm `upsert` has no callers, then delete the old interfaces**

Run: `grep -rn "\.upsert(" benchmark-runner/src/main`
Expected: no matches. **If this finds a caller, stop and report it** — the deletion assumption is wrong.

Then delete `DatabaseService.java` and `NoOpDatabaseService.java`. Leave `DocumentDbService.java` and `DatabaseServiceBuilder.java` for now — Tasks 2 and 6 replace them, and deleting them here breaks compilation before their replacements exist. The module will not fully compile until Task 8; that is expected, so run only the named test class until then.

- [ ] **Step 6: Run and confirm green**

Run: `mvn -pl benchmark-runner test -Dtest=NoOpResultsStoreTest`
Expected: PASS, 2 tests.

- [ ] **Step 7: Commit**

```bash
git add benchmark-runner/src/main/java/pl/wsztajerowski/infra/ benchmark-runner/src/test/java/pl/wsztajerowski/infra/
git commit -m "feat(runner): add a storage-neutral ResultsStore port and delete the upsert surface"
```

---

## Task 2: The MongoDB adapter on the new port

Covers 5.5.

**Files:**
- Create: `benchmark-runner/src/main/java/pl/wsztajerowski/infra/MongoResultsStore.java`
- Create: `benchmark-runner/src/main/java/pl/wsztajerowski/entities/MongoMeasurementDocument.java`
- Create: `benchmark-runner/src/test/java/pl/wsztajerowski/infra/StoredMeasurementFixtures.java`
- Delete: `benchmark-runner/src/main/java/pl/wsztajerowski/infra/DocumentDbService.java`
- Test: `benchmark-runner/src/test/java/pl/wsztajerowski/infra/MongoResultsStoreIT.java`

**Interfaces:**
- Consumes: `ResultsStore` from Task 1; `StoredMeasurement` and `ResultKeys` from `baas-model`.
- Produces: `MongoResultsStore(Datastore datastore)` implementing `ResultsStore`; test fixture `StoredMeasurementFixtures.jmh(String method)`. Task 6's builder constructs the store; Tasks 5 and 9 reuse the fixture.

**Why MongoDB survives at all.** `benchmark-runner` is a standalone artifact — one JAR, no stack, no CLI — and that deployment must keep working against a user's own MongoDB. Inside BaaS, DynamoDB becomes the only store. That is what makes the retained adapter free of consequences elsewhere: BaaS never selects Mongo, so §14 can eventually drop 27017 egress and `baas-cli` never needs a Mongo read path.

**Store the neutral shape, not the old entities.** Writing `StoredMeasurement` (wrapped in a Morphia entity for its `@Id`) rather than reconstructing `JmhBenchmark`/`JCStressTest` keeps one shape alive instead of two. Two shapes is the drift this whole change exists to remove.

- [ ] **Step 1: Create the shared test fixture**

`benchmark-runner/src/test/java/pl/wsztajerowski/infra/StoredMeasurementFixtures.java`:

```java
package pl.wsztajerowski.infra;

import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.StoredMeasurement;

import java.time.Instant;
import java.util.Map;

final class StoredMeasurementFixtures {

    private StoredMeasurementFixtures() {}

    static StoredMeasurement jmh(String method) {
        return new StoredMeasurement(
            "lynx-journal",
            "jmh-20260819_090000",
            Instant.parse("2026-08-19T09:00:00.000Z"),
            MeasurementKind.JMH,
            "pl.wsztajerowski.fake.Incrementing_Synchronized",
            method,
            "thrpt",
            1234.5,
            67.8,
            "ops/s",
            Map.of(),
            null,
            Map.of("project", "lynx-journal", "type", "jmh"),
            "main/jmh/20260819_090000",
            "main/jmh/20260819_090000/jmh-result.json",
            "main/jmh/20260819_090000/environment.json");
    }
}
```

- [ ] **Step 2: Write the failing integration test**

```java
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
```

**Read `TestcontainersWithS3AndMongoBaseIT` first.** If it does not already expose a `datastore()` accessor, add one following whatever it already provides — do not restructure the class.

- [ ] **Step 3: Run and confirm failure**

Run: `mvn -pl benchmark-runner test -Dtest=MongoResultsStoreIT`
Expected: FAIL to compile — `MongoResultsStore` does not exist.

- [ ] **Step 4: Create the Morphia entity**

Must live in `pl.wsztajerowski.entities` — Morphia auto-maps that package, and a class outside it is never mapped.

```java
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
```

- [ ] **Step 5: Implement the adapter**

```java
package pl.wsztajerowski.infra;

import dev.morphia.Datastore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.MongoMeasurementDocument;

import java.util.List;

/**
 * Retained so {@code benchmark-runner} keeps working as a standalone JAR against a user's own
 * MongoDB. BaaS itself never selects this adapter — inside BaaS, DynamoDB is the only store.
 */
public class MongoResultsStore implements ResultsStore {
    private static final Logger logger = LoggerFactory.getLogger(MongoResultsStore.class);

    private final Datastore datastore;

    public MongoResultsStore(Datastore datastore) {
        this.datastore = datastore;
    }

    @Override
    public void write(List<StoredMeasurement> measurements) {
        if (measurements == null || measurements.isEmpty()) {
            logger.warn("No measurements to store.");
            return;
        }
        try {
            for (StoredMeasurement measurement : measurements) {
                String id = ResultKeys.partitionKey(measurement.project())
                    + "|" + ResultKeys.sortKey(measurement);
                datastore.save(new MongoMeasurementDocument(id, measurement));
            }
            logger.info("Stored {} measurement(s) in MongoDB.", measurements.size());
        } catch (RuntimeException e) {
            throw new ResultsStoreException(
                "Failed to store " + measurements.size() + " measurement(s) in MongoDB", e);
        }
    }
}
```

Note `datastore.save` rather than `insert` — `save` upserts on `@Id`, which is what makes the idempotency test pass. The old `DocumentDbService` used `insert`, which would throw a duplicate-key error on a repeat.

Then delete `DocumentDbService.java`.

- [ ] **Step 6: Run and confirm green**

Run: `mvn -pl benchmark-runner test -Dtest=MongoResultsStoreIT`
Expected: PASS, 2 tests. Requires Docker.

- [ ] **Step 7: Commit**

```bash
git add benchmark-runner/src/main/java/pl/wsztajerowski/ benchmark-runner/src/test/java/pl/wsztajerowski/infra/
git commit -m "feat(runner): refactor the MongoDB adapter onto the ResultsStore port"
```

---

## Task 3: Map `JmhResult` into `StoredMeasurement`

Covers 5.8.

**Files:**
- Create: `benchmark-runner/src/main/java/pl/wsztajerowski/results/JmhMeasurementMapper.java`
- Test: `benchmark-runner/src/test/java/pl/wsztajerowski/results/JmhMeasurementMapperTest.java`

**Interfaces:**
- Consumes: `pl.wsztajerowski.entities.jmh.JmhResult`.
- Produces: `JmhMeasurementMapper.toMeasurement(JmhResult result, String project, String requestId, Instant createdAt, Map<String,String> tags, String resultPath, String resultJsonKey, String environmentJsonKey)` returning `StoredMeasurement`. Task 7 calls it.

**Two details that will bite if missed:**

1. **JMH gives one fully-qualified string; the model wants two fields.** `jmhResult.benchmark()` is
   `pl.wsztajerowski.fake.Incrementing_Synchronized.incrementUsingSynchronized` — class and method
   joined by a dot. `StoredMeasurement` needs `benchmarkClass` and `benchmarkMethod` separately, and
   `ResultKeys` builds the sort key from both. Split on the **last** dot.
2. **What is dropped, and why.** `rawData` and `scorePercentiles` do not go in the item — they
   dominate a JMH result's size and DynamoDB caps an item at 400 KB. Full fidelity stays in the
   verbatim result JSON in S3, reachable via `resultJsonKey` (Task 7). `secondaryMetrics` is reduced
   to score and unit.

**Do not add a non-finite guard here.** `MeasurementItemMapper` already drops non-finite values on the way into DynamoDB; duplicating it would hide the case from the test that covers it.

- [ ] **Step 1: Read the real types first**

Run: `cat benchmark-runner/src/main/java/pl/wsztajerowski/entities/jmh/JmhResult.java benchmark-runner/src/main/java/pl/wsztajerowski/entities/jmh/Metric.java`

The accessor names used below (`benchmark()`, `mode()`, `primaryMetric()`, `score()`, `scoreError()`, `scoreUnit()`, `secondaryMetrics()`) are the expected shape. **If the real records differ, adapt and say so in your report** — do not invent a shape.

- [ ] **Step 2: Write the failing tests**

```java
package pl.wsztajerowski.results;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.MeasurementKind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JmhMeasurementMapperTest {

    @Test
    void splitsTheFullyQualifiedBenchmarkIntoClassAndMethod() {
        var measurement = map("pl.wsztajerowski.fake.Incrementing_Synchronized.incrementUsingSynchronized");

        assertThat(measurement.benchmarkClass()).isEqualTo("pl.wsztajerowski.fake.Incrementing_Synchronized");
        assertThat(measurement.benchmarkMethod()).isEqualTo("incrementUsingSynchronized");
    }

    @Test
    void splitsOnTheLastDotSoNestedClassesSurvive() {
        var measurement = map("com.example.Outer$Inner.someMethod");

        assertThat(measurement.benchmarkClass()).isEqualTo("com.example.Outer$Inner");
        assertThat(measurement.benchmarkMethod()).isEqualTo("someMethod");
    }

    @Test
    void aBenchmarkNameWithNoDotIsRejectedRatherThanSilentlyHalved() {
        assertThatThrownBy(() -> map("nodothere"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nodothere");
    }

    @Test
    void carriesTheKindAndTheS3Pointers() {
        var measurement = map("com.example.Bench.method");

        assertThat(measurement.kind()).isEqualTo(MeasurementKind.JMH);
        assertThat(measurement.resultJsonKey()).isEqualTo("main/jmh/ts/jmh-result.json");
        assertThat(measurement.environmentJsonKey()).isEqualTo("main/jmh/ts/environment.json");
    }
}
```

Add a private `map(String benchmarkName)` helper that builds a minimal `JmhResult` — using whatever constructor or builder the real record provides — and calls the mapper with `project="p"`, `requestId="r"`, `createdAt=Instant.parse("2026-08-19T09:00:00.000Z")`, `tags=Map.of()`, `resultPath="main/jmh/ts"`, `resultJsonKey="main/jmh/ts/jmh-result.json"`, `environmentJsonKey="main/jmh/ts/environment.json"`.

- [ ] **Step 3: Run and confirm failure**

Run: `mvn -pl benchmark-runner test -Dtest=JmhMeasurementMapperTest`
Expected: FAIL to compile — `JmhMeasurementMapper` does not exist.

- [ ] **Step 4: Implement the mapper**

```java
package pl.wsztajerowski.results;

import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.SecondaryMetric;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.jmh.JmhResult;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps JMH's parsing type into the stored shape. {@code rawData} and {@code scorePercentiles} are
 * deliberately dropped — they dominate a JMH result's size and DynamoDB caps an item at 400 KB, so
 * full fidelity lives in the verbatim result JSON in S3 and {@code resultJsonKey} points at it.
 */
public final class JmhMeasurementMapper {

    private JmhMeasurementMapper() {}

    public static StoredMeasurement toMeasurement(
        JmhResult result, String project, String requestId, Instant createdAt,
        Map<String, String> tags, String resultPath, String resultJsonKey, String environmentJsonKey) {

        String fullyQualified = result.benchmark();
        int lastDot = fullyQualified.lastIndexOf('.');
        if (lastDot < 0) {
            throw new IllegalArgumentException(
                "Benchmark name has no class/method separator: " + fullyQualified);
        }

        var primary = result.primaryMetric();
        return new StoredMeasurement(
            project,
            requestId,
            createdAt,
            MeasurementKind.JMH,
            fullyQualified.substring(0, lastDot),
            fullyQualified.substring(lastDot + 1),
            result.mode(),
            primary == null ? null : primary.score(),
            primary == null ? null : primary.scoreError(),
            primary == null ? null : primary.scoreUnit(),
            secondaryMetrics(result),
            null,
            tags,
            resultPath,
            resultJsonKey,
            environmentJsonKey);
    }

    private static Map<String, SecondaryMetric> secondaryMetrics(JmhResult result) {
        if (result.secondaryMetrics() == null) {
            return Map.of();
        }
        return result.secondaryMetrics().entrySet().stream()
            .filter(entry -> entry.getValue() != null)
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new SecondaryMetric(entry.getValue().score(), entry.getValue().scoreUnit())));
    }
}
```

- [ ] **Step 5: Run and confirm green**

Run: `mvn -pl benchmark-runner test -Dtest=JmhMeasurementMapperTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add benchmark-runner/src/main/java/pl/wsztajerowski/results/ benchmark-runner/src/test/java/pl/wsztajerowski/results/
git commit -m "feat(runner): map JmhResult into the stored measurement shape"
```

---

## Task 4: Map `JCStressResult` into `StoredMeasurement`

Covers 5.9.

**Files:**
- Create: `benchmark-runner/src/main/java/pl/wsztajerowski/results/JCStressMeasurementMapper.java`
- Test: `benchmark-runner/src/test/java/pl/wsztajerowski/results/JCStressMeasurementMapperTest.java`

**Interfaces:**
- Consumes: `pl.wsztajerowski.entities.jcstress.JCStressResult`.
- Produces: `JCStressMeasurementMapper.toMeasurement(JCStressResult result, String project, String requestId, Instant createdAt, Map<String,String> tags, String resultPath, String environmentJsonKey)` returning one `StoredMeasurement`. There is **no `resultJsonKey`** — JCStress produces HTML, not a result JSON.

**One summary, not one item per test.** `JCStressResult` reports counts for everything but **names only non-passing tests** — passing ones are counted, never named. Per-test items would therefore cover failures only, and the result files are already in S3. So a JCStress run produces exactly **one** measurement, with `benchmarkClass`, `benchmarkMethod`, `mode`, `score`, `scoreError` and `scoreUnit` all null and the counts in `JcstressSummary`.

- [ ] **Step 1: Read the real types first**

Run: `cat benchmark-runner/src/main/java/pl/wsztajerowski/entities/jcstress/JCStressResult.java benchmark-runner/src/main/java/pl/wsztajerowski/entities/jcstress/JCStressResultBuilder.java`

The accessor names below are the expected shape. Adapt to the real record and report any difference. The module already has a builder — use it in the test rather than inventing a constructor.

- [ ] **Step 2: Write the failing tests**

```java
package pl.wsztajerowski.results;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.MeasurementKind;

import static org.assertj.core.api.Assertions.assertThat;

class JCStressMeasurementMapperTest {

    @Test
    void producesOneMeasurementWithNoBenchmarkCoordinates() {
        var measurement = map();

        assertThat(measurement.kind()).isEqualTo(MeasurementKind.JCSTRESS);
        assertThat(measurement.benchmarkClass()).isNull();
        assertThat(measurement.benchmarkMethod()).isNull();
        assertThat(measurement.mode()).isNull();
        assertThat(measurement.score()).isNull();
    }

    @Test
    void carriesTheCountsAndTheThreeTestMaps() {
        var summary = map().jcstress();

        assertThat(summary.totalTests()).isEqualTo(12);
        assertThat(summary.passedTests()).isEqualTo(10);
        assertThat(summary.failedTests()).isEqualTo(1);
        assertThat(summary.errorTests()).isEqualTo(1);
        assertThat(summary.failed()).containsKey("SomeFailingTest");
        assertThat(summary.errors()).containsKey("SomeErroringTest");
    }

    @Test
    void hasNoResultJsonKeyBecauseJcstressProducesHtml() {
        assertThat(map().resultJsonKey()).isNull();
    }
}
```

- [ ] **Step 3: Run and confirm failure**

Run: `mvn -pl benchmark-runner test -Dtest=JCStressMeasurementMapperTest`
Expected: FAIL to compile.

- [ ] **Step 4: Implement the mapper**

```java
package pl.wsztajerowski.results;

import pl.wsztajerowski.baas.model.JcstressSummary;
import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.jcstress.JCStressResult;

import java.time.Instant;
import java.util.Map;

/**
 * One measurement per JCStress run, not per test. JCStress names only non-passing tests — passing
 * ones are counted, never named — so per-test items would cover failures only, and the full result
 * files are already in S3 under the run's result path.
 */
public final class JCStressMeasurementMapper {

    private JCStressMeasurementMapper() {}

    public static StoredMeasurement toMeasurement(
        JCStressResult result, String project, String requestId, Instant createdAt,
        Map<String, String> tags, String resultPath, String environmentJsonKey) {

        return new StoredMeasurement(
            project,
            requestId,
            createdAt,
            MeasurementKind.JCSTRESS,
            null, null, null, null, null, null,
            Map.of(),
            new JcstressSummary(
                result.totalTests(),
                result.passedTests(),
                result.failedTests(),
                result.errorTests(),
                result.failed(),
                result.errors(),
                result.interesting()),
            tags,
            resultPath,
            null,
            environmentJsonKey);
    }
}
```

`JcstressSummary` null-defaults its three maps, so a null from `JCStressResult` is safe.

- [ ] **Step 5: Run and confirm green**

Run: `mvn -pl benchmark-runner test -Dtest=JCStressMeasurementMapperTest`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add benchmark-runner/src/main/java/pl/wsztajerowski/results/ benchmark-runner/src/test/java/pl/wsztajerowski/results/
git commit -m "feat(runner): map JCStressResult into a single stored measurement"
```

---

## Task 5: The DynamoDB adapter, batched and retrying

Covers 5.1, 5.4 and 5.6.

**Files:**
- Modify: `benchmark-runner/pom.xml`
- Create: `benchmark-runner/src/main/java/pl/wsztajerowski/infra/DynamoDbResultsStore.java`
- Test: `benchmark-runner/src/test/java/pl/wsztajerowski/infra/DynamoDbResultsStoreTest.java`
- Test: `benchmark-runner/src/test/java/pl/wsztajerowski/infra/RecordingDynamoDbClient.java`

**Interfaces:**
- Consumes: `ResultsStore`, `ResultsStoreException` from Task 1; `MeasurementItemMapper`, `StoredMeasurement` from `baas-model`; `StoredMeasurementFixtures` from Task 2.
- Produces: `DynamoDbResultsStore(DynamoDbClient client, String tableName)` implementing `ResultsStore`. Task 6's builder constructs it; Tasks 9 and 10 test it against LocalStack.

**Why `BatchWriteItem` in chunks of 25.** That is the hard API limit, and a JMH run with several methods or modes exceeds it easily.

**Why the retry loop is not optional.** `BatchWriteItem` reports throttling and partial success by **returning** the leftovers in `unprocessedItems` rather than failing. Ignoring that field loses measurements while the call looks successful — precisely the silent-loss class this project's invariants exist to prevent.

- [ ] **Step 1: Add the dependencies**

In `benchmark-runner/pom.xml`, inside `<dependencies>`:

```xml
        <dependency>
            <groupId>pl.wsztajerowski</groupId>
            <artifactId>baas-model</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>dynamodb</artifactId>
        </dependency>
```

No `<version>` on the SDK — the root pom imports the AWS SDK BOM. **Keep `mongodb-driver-sync` and `morphia-core` exactly as they are**; the retained adapter needs both.

- [ ] **Step 2: Write the fake client**

A hand-written fake keeps the retry logic testable without Docker.

```java
package pl.wsztajerowski.infra;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Records batch sizes and can simulate unprocessed items, without needing LocalStack. */
class RecordingDynamoDbClient implements DynamoDbClient {

    private final List<Integer> batchSizes = new ArrayList<>();
    private int unprocessedOnFirstCall = 0;
    private int alwaysUnprocessed = 0;

    void returnUnprocessedOnFirstCall(int count) {
        this.unprocessedOnFirstCall = count;
    }

    void alwaysReturnUnprocessed(int count) {
        this.alwaysUnprocessed = count;
    }

    List<Integer> batchSizes() {
        return batchSizes;
    }

    int callCount() {
        return batchSizes.size();
    }

    @Override
    public BatchWriteItemResponse batchWriteItem(BatchWriteItemRequest request) {
        List<WriteRequest> submitted = request.requestItems().values().iterator().next();
        batchSizes.add(submitted.size());
        String table = request.requestItems().keySet().iterator().next();

        int leftover = alwaysUnprocessed > 0 ? alwaysUnprocessed
            : (batchSizes.size() == 1 ? unprocessedOnFirstCall : 0);

        if (leftover <= 0) {
            return BatchWriteItemResponse.builder().build();
        }
        return BatchWriteItemResponse.builder()
            .unprocessedItems(Map.of(table, submitted.subList(0, Math.min(leftover, submitted.size()))))
            .build();
    }

    @Override
    public String serviceName() {
        return "dynamodb";
    }

    @Override
    public void close() {
        // nothing to release
    }
}
```

If `DynamoDbClient` is an interface with many default methods this compiles as-is; if the compiler demands more, implement only what it names and let everything else inherit.

- [ ] **Step 3: Write the failing tests**

```java
package pl.wsztajerowski.infra;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamoDbResultsStoreTest {

    @Test
    void splitsMoreThanTwentyFiveMeasurementsAcrossBatches() {
        var client = new RecordingDynamoDbClient();

        new DynamoDbResultsStore(client, "results").write(
            IntStream.range(0, 30)
                .mapToObj(i -> StoredMeasurementFixtures.jmh("method" + i))
                .toList());

        assertThat(client.batchSizes())
            .as("BatchWriteItem caps at 25 items")
            .containsExactly(25, 5);
    }

    @Test
    void retriesUnprocessedItemsRatherThanLosingThem() {
        var client = new RecordingDynamoDbClient();
        client.returnUnprocessedOnFirstCall(2);

        new DynamoDbResultsStore(client, "results").write(List.of(
            StoredMeasurementFixtures.jmh("one"),
            StoredMeasurementFixtures.jmh("two")));

        assertThat(client.callCount())
            .as("unprocessed items must be resubmitted, not dropped")
            .isEqualTo(2);
    }

    @Test
    void throwsWhenItemsRemainUnprocessedAfterEveryRetry() {
        var client = new RecordingDynamoDbClient();
        client.alwaysReturnUnprocessed(1);

        assertThatThrownBy(() ->
            new DynamoDbResultsStore(client, "results").write(List.of(StoredMeasurementFixtures.jmh("one"))))
            .isInstanceOf(ResultsStoreException.class)
            .hasMessageContaining("unprocessed");
    }

    @Test
    void anEmptyListWritesNothingAndDoesNotThrow() {
        var client = new RecordingDynamoDbClient();

        new DynamoDbResultsStore(client, "results").write(List.of());

        assertThat(client.callCount()).isZero();
    }
}
```

- [ ] **Step 4: Run and confirm failure**

Run: `mvn -pl benchmark-runner test -Dtest=DynamoDbResultsStoreTest`
Expected: FAIL to compile — `DynamoDbResultsStore` does not exist.

- [ ] **Step 5: Implement the adapter**

```java
package pl.wsztajerowski.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.MeasurementItemMapper;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One item per measurement, batched per run.
 *
 * <p>{@code BatchWriteItem} reports throttling and partial success by RETURNING the leftovers in
 * {@code unprocessedItems} rather than failing, so ignoring that field loses measurements while the
 * call looks successful. The remainder is resubmitted with backoff, and anything still unprocessed
 * at the end is fatal — a run that cannot store its results must exit non-zero.
 */
public class DynamoDbResultsStore implements ResultsStore {
    private static final Logger logger = LoggerFactory.getLogger(DynamoDbResultsStore.class);

    private static final int MAX_BATCH_SIZE = 25;
    private static final int MAX_ATTEMPTS = 5;
    private static final long INITIAL_BACKOFF_MILLIS = 100;

    private final DynamoDbClient client;
    private final String tableName;

    public DynamoDbResultsStore(DynamoDbClient client, String tableName) {
        this.client = client;
        this.tableName = tableName;
    }

    @Override
    public void write(List<StoredMeasurement> measurements) {
        if (measurements == null || measurements.isEmpty()) {
            logger.warn("No measurements to store.");
            return;
        }
        List<WriteRequest> requests = measurements.stream()
            .map(MeasurementItemMapper::toItem)
            .map(item -> WriteRequest.builder()
                .putRequest(PutRequest.builder().item(item).build())
                .build())
            .toList();

        for (int start = 0; start < requests.size(); start += MAX_BATCH_SIZE) {
            int end = Math.min(start + MAX_BATCH_SIZE, requests.size());
            writeBatchWithRetries(new ArrayList<>(requests.subList(start, end)));
        }
        logger.info("Stored {} measurement(s) in DynamoDB table {}.", measurements.size(), tableName);
    }

    private void writeBatchWithRetries(List<WriteRequest> batch) {
        List<WriteRequest> pending = batch;
        long backoff = INITIAL_BACKOFF_MILLIS;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            BatchWriteItemResponse response;
            try {
                response = client.batchWriteItem(BatchWriteItemRequest.builder()
                    .requestItems(Map.of(tableName, pending))
                    .build());
            } catch (RuntimeException e) {
                throw new ResultsStoreException(
                    "Failed to write " + pending.size() + " measurement(s) to " + tableName, e);
            }

            Map<String, List<WriteRequest>> unprocessed = response.unprocessedItems();
            if (unprocessed == null || unprocessed.get(tableName) == null
                || unprocessed.get(tableName).isEmpty()) {
                return;
            }

            pending = unprocessed.get(tableName);
            logger.warn("{} item(s) unprocessed on attempt {}/{}; retrying in {}ms",
                pending.size(), attempt, MAX_ATTEMPTS, backoff);
            sleep(backoff);
            backoff *= 2;
        }

        throw new ResultsStoreException(
            pending.size() + " measurement(s) remained unprocessed after " + MAX_ATTEMPTS
                + " attempts against " + tableName + ". Results were NOT stored.");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResultsStoreException("Interrupted while retrying a DynamoDB batch write", e);
        }
    }
}
```

- [ ] **Step 6: Run and confirm green**

Run: `mvn -pl benchmark-runner test -Dtest=DynamoDbResultsStoreTest`
Expected: PASS, 4 tests. The retry test sleeps ~100ms; that is intentional and cheap.

- [ ] **Step 7: Commit**

```bash
git add benchmark-runner/pom.xml benchmark-runner/src/main/java/pl/wsztajerowski/infra/ benchmark-runner/src/test/java/pl/wsztajerowski/infra/
git commit -m "feat(runner): add a batching, retrying DynamoDB results store"
```

---

## Task 6: Select exactly one adapter, and fail on ambiguity

Covers 5.7.

**Files:**
- Create: `benchmark-runner/src/main/java/pl/wsztajerowski/infra/ResultsStoreBuilder.java`
- Delete: `benchmark-runner/src/main/java/pl/wsztajerowski/infra/DatabaseServiceBuilder.java`
- Test: `benchmark-runner/src/test/java/pl/wsztajerowski/infra/ResultsStoreBuilderTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1, 2 and 5.
- Produces: `ResultsStoreBuilder.builder().withTableName(String).withConnectionString(URI).withNoDatabase(boolean).withDynamoDbEndpoint(URI).build()` returning `ResultsStore`. Task 8 calls it.

**The behaviour change that matters.** Today `DatabaseServiceBuilder` returns `NoOpDatabaseService` when the connection string is null or empty — so a misconfigured run reports success and throws its measurements away. That is the documented trap. Absent configuration now becomes a **hard failure**, and discarding requires naming the intent with `--no-database`.

This is a **breaking change for standalone `benchmark-runner` users** who relied on the lenient behaviour. Deliberate; note it in your report so it reaches release notes.

- [ ] **Step 1: Write the failing tests**

```java
package pl.wsztajerowski.infra;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultsStoreBuilderTest {

    @Test
    void aTableNameSelectsDynamoDb() {
        assertThat(ResultsStoreBuilder.builder().withTableName("results").build())
            .isInstanceOf(DynamoDbResultsStore.class);
    }

    @Test
    void noDatabaseSelectsTheExplicitDiscardStore() {
        assertThat(ResultsStoreBuilder.builder().withNoDatabase(true).build())
            .isInstanceOf(NoOpResultsStore.class);
    }

    @Test
    void absentConfigurationIsAHardFailureRatherThanASilentDiscard() {
        assertThatThrownBy(() -> ResultsStoreBuilder.builder().build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("--no-database");
    }

    @Test
    void anEmptyConnectionStringIsAlsoAHardFailure() {
        assertThatThrownBy(() -> ResultsStoreBuilder.builder()
            .withConnectionString(URI.create("")).build())
            .as("the previous behaviour silently discarded measurements here")
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void namingBothStoresIsRejectedRatherThanPickingOne() {
        assertThatThrownBy(() -> ResultsStoreBuilder.builder()
            .withTableName("results")
            .withConnectionString(URI.create("mongodb://localhost:27017/baas"))
            .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("both");
    }

    @Test
    void noDatabaseAlongsideAStoreIsRejected() {
        assertThatThrownBy(() -> ResultsStoreBuilder.builder()
            .withTableName("results")
            .withNoDatabase(true)
            .build())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aConnectionStringWithoutADatabaseNameIsRejected() {
        assertThatThrownBy(() -> ResultsStoreBuilder.builder()
            .withConnectionString(URI.create("mongodb://localhost:27017"))
            .build())
            .hasMessageContaining("database name");
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -pl benchmark-runner test -Dtest=ResultsStoreBuilderTest`
Expected: FAIL to compile.

- [ ] **Step 3: Implement the builder**

Structure: count how many of `{tableName, connectionString, noDatabase}` are set. Zero → `IllegalStateException` whose message names `--no-database`. More than one → `IllegalStateException` containing the word "both". Exactly one → construct the matching adapter.

Carry over the existing database-name check from `DatabaseServiceBuilder` verbatim — a Mongo connection string must still carry a database name, and that message is already good:

```java
        requireNonNull(database, "Connection string has to contain database name! Please provide connection string in form: mongodb://server:port/database_name");
```

For DynamoDB, apply `endpointOverride` when `withDynamoDbEndpoint` is set, so LocalStack works in Task 9:

```java
        var clientBuilder = DynamoDbClient.builder();
        if (dynamoDbEndpoint != null) {
            clientBuilder.endpointOverride(dynamoDbEndpoint);
        }
        return new DynamoDbResultsStore(clientBuilder.build(), tableName);
```

Then delete `DatabaseServiceBuilder.java`.

- [ ] **Step 4: Run and confirm green**

Run: `mvn -pl benchmark-runner test -Dtest=ResultsStoreBuilderTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add benchmark-runner/src/main/java/pl/wsztajerowski/infra/ benchmark-runner/src/test/java/pl/wsztajerowski/infra/
git commit -m "feat(runner): select one results store, and make absent configuration fatal"
```

---

## Task 7: Upload the verbatim JMH result JSON, and order the writes S3-first

Covers 5.10 and 5.11.

**Files:**
- Modify: `benchmark-runner/src/main/java/pl/wsztajerowski/services/JmhSubcommandService.java`
- Modify: `JmhWithProfilerSubcommandService.java`, `JmhWithAsyncProfilerSubcommandService.java`, `JCStressSubcommandService.java`
- Test: extend the existing JMH integration test

**Interfaces:**
- Produces: the S3 key `<resultPath>/jmh-result.json`, recorded on every measurement from that run as `resultJsonKey`.

**Why this exists.** The item is deliberately thin — `rawData` and `scorePercentiles` are dropped — so something must keep full fidelity retrievable. Uploading the unmodified JMH result JSON also closes the documented gap that "measurements live only in MongoDB, there is no `result.json`".

**Why S3 first.** A run that fails at the store must still leave its artifacts behind — the whole point of the S3 layout is that a failed run stays diagnosable. Store-first would lose the JSON along with the measurements.

- [ ] **Step 1: Write the failing test**

Extend the existing JMH integration test (read it first and follow its harness):

```java
    @Test
    void uploadsTheVerbatimResultJsonSoTheThinItemStaysDefensible() {
        // run the service against the fake benchmark, then:
        assertThat(listObjectsInTestBucket().toString())
            .contains("jmh-result.json");
    }
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -pl benchmark-runner test -Dtest=JmhSubcommandServiceIT`
Expected: FAIL — no `jmh-result.json` in the bucket.

- [ ] **Step 3: Upload the JSON and compute `createdAt` once per run**

In `JmhSubcommandService`, after the benchmark process exits and **before** the result loop:

```java
        String resultJsonKey = storageService.uploadFile(
            jmhOptions.outputOptions().machineReadableOutput(),
            commonOptions.resultPath().resolve("jmh-result.json"));
        Instant createdAt = Instant.now();
```

Match `storageService`'s real method name and signature — read `StorageService` first.

`createdAt` moves **out** of the loop. It is currently `OffsetDateTime.now(ZoneOffset.UTC).toLocalDateTime()` computed per result, which both loses the zone and makes two results from one run differ by a stray millisecond. One timestamp per run is correct: the sort key already differentiates measurements by `benchmarkClass`, `benchmarkMethod` and `mode`.

Then build a `List<StoredMeasurement>` from the loop via `JmhMeasurementMapper.toMeasurement(...)` and call `resultsStore.write(measurements)` **once**, after the loop.

**Known limitation — record it, do not fix it here.** Two results differing only by `@Param` values collide on the sort key, because params are not part of it. This is pre-existing: the Mongo `_id` had the same shape, and `options=<params>` is still captured as a tag. **Do not change the key shape to fix it** — §9 migrates history onto the current shape, and changing it afterwards means redoing the migration. Raise it in your report so it can be decided deliberately.

- [ ] **Step 4: Order the writes S3-first in all four services**

Read each of the four services and ensure every S3 upload completes before `resultsStore.write(...)`. Move the store call to the end where it is not already last.

- [ ] **Step 5: Run and confirm green**

Run: `mvn -pl benchmark-runner test -Dtest=JmhSubcommandServiceIT`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add benchmark-runner/src/main/java/pl/wsztajerowski/services/ benchmark-runner/src/test/java/pl/wsztajerowski/services/
git commit -m "feat(runner): upload the verbatim JMH result JSON and write S3 before the store"
```

---

## Task 8: Wire the new options and store through the command layer

Covers 5.12.

**Files:**
- Modify: `benchmark-runner/src/main/java/pl/wsztajerowski/commands/ApiCommonSharedOptions.java`
- Modify: the four `*SubcommandServiceBuilder` classes and their services
- Test: `benchmark-runner/src/test/java/pl/wsztajerowski/commands/ApiCommonSharedOptionsTest.java`

**Interfaces:**
- Consumes: `ResultsStoreBuilder` from Task 6.
- Produces: options `--results-table`, `--no-database`, `--dynamodb-endpoint`, `--project`; and `ApiCommonSharedOptions.buildResultsStore()` returning `ResultsStore`.

**`--project` becomes first-class.** `StoredMeasurement` requires a non-blank `project` — it composes the partition key. `baas run` already forwards `--tag project=<name>`, but the runner needs it as a value, not dug out of the tag map.

- [ ] **Step 1: Write the failing tests**

```java
package pl.wsztajerowski.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class ApiCommonSharedOptionsTest {

    @Test
    void parsesTheResultsTableAndProject() {
        var options = parse("--results-table", "baas-abc-results", "--project", "lynx-journal");

        assertThat(options.getResultsTableName()).isEqualTo("baas-abc-results");
        assertThat(options.getProject()).isEqualTo("lynx-journal");
    }

    @Test
    void parsesNoDatabase() {
        assertThat(parse("--no-database", "--project", "p").isNoDatabase()).isTrue();
    }

    @Test
    void theMongoConnectionStringOptionSurvivesForStandaloneUse() {
        var options = parse("--mongo-connection-string", "mongodb://h:27017/db", "--project", "p");

        assertThat(options.getMongoConnectionString().toString()).contains("27017");
    }

    private static ApiCommonSharedOptions parse(String... args) {
        var options = new ApiCommonSharedOptions();
        new CommandLine(options).parseArgs(args);
        return options;
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -pl benchmark-runner test -Dtest=ApiCommonSharedOptionsTest`
Expected: FAIL to compile — the accessors do not exist.

- [ ] **Step 3: Add the options**

```java
    @Option(names = "--results-table", description = "DynamoDB table holding benchmark measurements.")
    String resultsTableName;

    @Option(names = "--no-database", description = "Discard measurements instead of storing them. Explicit opt-in; absent configuration is an error.")
    boolean noDatabase;

    @Option(names = "--dynamodb-endpoint", defaultValue = "${AWS_ENDPOINT_URL_DYNAMODB}", description = "Custom DynamoDB endpoint, for LocalStack.")
    URI dynamoDbEndpoint;

    @Option(names = "--project", description = "Project name; composes the results partition key.")
    String project;
```

Add matching accessors plus `buildResultsStore()` delegating to `ResultsStoreBuilder`. **Keep `--mongo-connection-string`** — it is how the standalone deployment selects Mongo, and §13 removes only the *CLI's* use of it.

- [ ] **Step 4: Replace `DatabaseService` with `ResultsStore` throughout the services**

Change the field type, constructor parameter and call site in each of the four `*SubcommandService` classes and their builders. The call becomes `resultsStore.write(measurements)` with a list built from the Task 3 and Task 4 mappers.

- [ ] **Step 5: Run the whole module**

Run: `mvn -pl benchmark-runner test`
Expected: PASS. This is the first point at which the module compiles fully again.

- [ ] **Step 6: Commit**

```bash
git add benchmark-runner/src/main/java/pl/wsztajerowski/ benchmark-runner/src/test/java/pl/wsztajerowski/
git commit -m "feat(runner): wire the results store and its options through the command layer"
```

---

## Task 9: LocalStack with DynamoDB, and one contract suite for both adapters

Covers 8.1, 8.2 and 8.3.

**Files:**
- Create: `benchmark-runner/src/test/java/pl/wsztajerowski/TestcontainersWithDynamoDbBaseIT.java`
- Create: `benchmark-runner/src/test/java/pl/wsztajerowski/infra/ResultsStoreContractTest.java`
- Create: `DynamoDbResultsStoreContractIT.java`, `MongoResultsStoreContractIT.java`
- Keep unchanged: `TestcontainersWithS3AndMongoBaseIT.java`, `MongoDbTestHelpers.java` (that is 8.2)

**Interfaces:**
- Produces: abstract `ResultsStoreContractTest` with `protected abstract ResultsStore store()` and `protected abstract long storedCount()`, plus one concrete subclass per adapter.

**Note on LocalStack.** `TestcontainersWithS3BaseIT` pins `localstack/localstack:0.12.16` and enables only `Service.S3`. Add `Service.DYNAMODB`. **Verify that version actually serves DynamoDB** — if it does not, bump the image and say so in your report rather than working around it. Create the table in `@BeforeEach` with `pk`/`sk` String keys and the `requestId-index` GSI on `gsi1pk`/`gsi1sk`, matching `infra/cf-template-core.yaml`.

- [ ] **Step 1: Write the contract suite**

```java
package pl.wsztajerowski.infra;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.StoredMeasurement;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One suite, both adapters. The port promises the same observable behaviour regardless of backing
 * store, and the only way that stays true is to write the test once and run it twice.
 */
abstract class ResultsStoreContractTest {

    protected abstract ResultsStore store();

    protected abstract long storedCount();

    @Test
    void writesOneRecordPerMeasurement() {
        store().write(List.of(
            StoredMeasurementFixtures.jmh("one"),
            StoredMeasurementFixtures.jmh("two")));

        assertThat(storedCount()).isEqualTo(2);
    }

    @Test
    void aRepeatedWriteIsIdempotent() {
        StoredMeasurement measurement = StoredMeasurementFixtures.jmh("one");

        store().write(List.of(measurement));
        store().write(List.of(measurement));

        assertThat(storedCount())
            .as("a re-run of the same measurement must not double-count it")
            .isEqualTo(1);
    }

    @Test
    void anEmptyWriteStoresNothingAndDoesNotThrow() {
        store().write(List.of());

        assertThat(storedCount()).isZero();
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -pl benchmark-runner test -Dtest='*ContractIT'`
Expected: FAIL — the concrete subclasses do not exist.

- [ ] **Step 3: Add the LocalStack base class and both subclasses**

Mirror `TestcontainersWithS3BaseIT`'s structure for `TestcontainersWithDynamoDbBaseIT`, adding `Service.DYNAMODB` and creating the table. Then write the two subclasses, each supplying `store()` and `storedCount()`.

- [ ] **Step 4: Run and confirm green**

Run: `mvn -pl benchmark-runner test -Dtest='*ContractIT'`
Expected: PASS, 6 tests (3 × 2 adapters). Requires Docker.

- [ ] **Step 5: Commit**

```bash
git add benchmark-runner/src/test/java/pl/wsztajerowski/
git commit -m "test(runner): add LocalStack DynamoDB and one store contract suite for both adapters"
```

---

## Task 10: Integration tests for the run-level guarantees

Covers 8.4, 8.5 and 8.7.

**Files:**
- Create: `benchmark-runner/src/test/java/pl/wsztajerowski/services/JmhStoreIntegrationIT.java`

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void aStoredRunProducesOneItemPerMeasurementAndNoOthers() {
        runJmhServiceAgainstTheFakeBenchmark();

        var items = scanTable();
        assertThat(items).hasSize(expectedMeasurementCount);
        assertThat(items)
            .as("exactly one item per measurement — the design has no derived index items")
            .allSatisfy(item -> assertThat(item.get("pk").s()).startsWith("RESULT#"));
    }

    @Test
    void aStoreFailureExitsNonZeroAndLeavesS3ArtifactsIntact() {
        pointTheStoreAtATableThatDoesNotExist();

        assertThatThrownBy(this::runJmhServiceAgainstTheFakeBenchmark)
            .isInstanceOf(ResultsStoreException.class);

        assertThat(listObjectsInTestBucket().toString())
            .as("a failed run must still be diagnosable from its S3 artifacts")
            .contains("jmh-result.json");
    }
```

`scanTable()` is a test helper using `DynamoDbClient.scan` — acceptable in a test even though production code never scans. Fill in the harness following the existing service ITs.

- [ ] **Step 2: Run, confirm failure, implement, confirm green**

Run: `mvn -pl benchmark-runner test -Dtest=JmhStoreIntegrationIT`

- [ ] **Step 3: Commit**

```bash
git add benchmark-runner/src/test/java/pl/wsztajerowski/services/
git commit -m "test(runner): cover one-item-per-measurement and store-failure ordering"
```

---

## Task 11: Local development environment and the full reactor

Covers 8.9, 8.10 and 8.11.

**Files:**
- Modify: `docker-compose.yaml`, `jmh-with-profiler.sh`, `jmh-with-async.sh`, `openspec/changes/dynamodb-results-store/tasks.md`

- [ ] **Step 1: Update `docker-compose.yaml`**

Drop `mongo-express`, add `dynamodb` to LocalStack's `SERVICES`, keep `mongo`. There is **no init container** — the bucket and any SSM parameters are created by hand, and the table now needs the same. Put the `awslocal dynamodb create-table` invocation in a comment beside the service, matching the key schema in `infra/cf-template-core.yaml`.

- [ ] **Step 2: Update the two scripts**

Both currently pass a Mongo connection string. Give each a table name or `--no-database` — the silent fallback no longer exists, so an unchanged script would now fail with the builder's error.

- [ ] **Step 3: Run the full reactor synchronously**

```bash
ASYNC_PATH=/Users/wiktor/workspace/async-profiler/lib/libasyncProfiler.dylib mvn clean verify
```

Expected: BUILD SUCCESS across all 6 modules, with `JmhWithAsyncProfilerSubcommandServiceIT` **running, not skipped**. **Wait for it in the same turn — do not background it.** Three agents on this change have lost work that way.

- [ ] **Step 4: Tick the completed tasks**

Tick 5.1–5.12 and 8.1–8.5, 8.7, 8.9, 8.10, 8.11 in `tasks.md`. **Leave 8.6 and 8.8 open** — they need §6 and 7.7 — and annotate each with why, following the style already used for 4.3 and 4.8.

```bash
git add docker-compose.yaml jmh-with-profiler.sh jmh-with-async.sh openspec/changes/dynamodb-results-store/tasks.md
git commit -m "chore: point local dev at DynamoDB and mark §5 complete"
```

---

## Self-review notes

**Spec coverage.** 5.1 T5 · 5.2 T1 · 5.3 T1 · 5.4 T5 · 5.5 T2 · 5.6 T5 · 5.7 T6 · 5.8 T3 · 5.9 T4 · 5.10 T7 · 5.11 T7 · 5.12 T8 · 8.1 T9 · 8.2 T9 · 8.3 T9 · 8.4 T10 · 8.5 T2+T9 · 8.7 T10 · 8.9 T11 · 8.10 T11 · 8.11 T11. 8.6 and 8.8 excluded with reasons.

**Known gaps, deliberately not fixed here:**

- **`@Param` collision.** Two results differing only by `@Param` values share a sort key. Pre-existing — the Mongo `_id` had the same shape — and `options=<params>` remains a tag. Task 7 raises it. Deciding it means changing the key shape, which must not happen after §9 migrates.
- **Accessor names on `JmhResult`, `Metric` and `JCStressResult`** are the expected shape and must be checked against the real records. Tasks 3 and 4 open with a read step for exactly this reason.
- **LocalStack 0.12.16** may predate usable DynamoDB support. Task 9 says to verify and bump rather than work around.
