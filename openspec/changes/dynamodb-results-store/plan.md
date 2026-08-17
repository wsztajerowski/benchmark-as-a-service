# DynamoDB Results Store — Implementation Plan (Phase 2: §3 + §4-additive)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the shared `baas-model` module and provision the DynamoDB table, gateway endpoint and IAM grants — without touching Atlas, so every existing run keeps working.

**Architecture:** `baas-model` is a new Mongo-free module holding the stored measurement shape, key encoding, timestamp formatting, tag vocabulary and an explicit `Map<String, AttributeValue>` mapper. It sits ahead of `benchmark-runner` and `baas-cli` in the reactor so an incompatible change breaks compilation rather than returning zero rows. CloudFormation gains the table, a free gateway endpoint and scoped IAM — all additive, nothing removed.

**Tech Stack:** Java 25, Maven multi-module, AWS SDK v2 (BOM-managed at the root), JUnit 6, AssertJ, CloudFormation.

---

## ⚠️ SEQUENCING HAZARD — why §4 is split

`tasks.md` §4 lists ten items as one block. **Two of them must not ship in this phase:**

| Task | Why it is deferred |
|---|---|
| **4.3** Remove TCP 27017 egress from `RunnerSecurityGroup` | The runner still writes to Atlas until §5 lands. `CLAUDE.md` states plainly: *"omitting 27017 makes every run fail at the database write."* Deploying 4.3 now breaks **every run** until the cutover completes. |
| **4.8** Remove Mongo SSM grants | Same reason — user-data still fetches the connection string from SSM until §7.5. Removing the grant strands the runner. |

`design.md`'s own Migration Plan resolves this: step 2 is *"Ship the table, gateway endpoint and IAM changes; deploy with `baas admin setup`. **Atlas is untouched.**"* — and the Mongo removal is step 7, after the cutover. `tasks.md` §4's ordering contradicts `design.md`; **`design.md` governs.**

Both tasks move to Phase 4. Do not tick 4.3 or 4.8 in this phase.

---

## Scope and phase map

This plan covers §3 and the additive part of §4 only. The remainder is mapped at the bottom; each phase needs its own plan, because a plan should produce working, testable software on its own and §5–§7 form a single atomic cutover that cannot be split.

| Phase | Sections | Deployable alone? | Blocked by |
|---|---|---|---|
| 1 ✅ done | §1, §2 | yes | — |
| **2 — this plan** | §3, §4 minus 4.3/4.8 | yes, and inert | — |
| 3 | §9 migration | yes | D2 + `source` mapping |
| 4 | §5, §6, §7, §8, 4.3, 4.8 | **atomic** — must land together | Phase 2 deployed |
| 5 | §10, §11, §12 | yes | Phase 4 verified live |

Phase 3 (migrate history) precedes Phase 4 (cut over) deliberately — that is `design.md`'s order, and it means the table already holds history when reads switch.

## Global Constraints

- **`pom.xml` version stays `0.0.0-semantically-released`.** Never bump by hand; `release.yml` sets it.
- **`baas-model` must stay Mongo-free.** It lands on `baas-cli`'s classpath, and *"MongoDB anywhere in `baas-cli`"* is an explicit non-goal. Enforce mechanically, not by convention.
- **The AWS SDK BOM is imported at the root `pom.xml`.** Declare `software.amazon.awssdk:dynamodb` with **no `<version>`**.
- **Sort keys must sort chronologically as strings.** Variable-width or zone-ambiguous timestamps misorder silently, and the failure looks like missing data rather than a formatting bug.
- **No `Scan` capability anywhere.** Every supported query is a `Query` on the project partition or the `requestId` GSI.
- **The table is `DeletionPolicy: Retain` *and* `UpdateReplacePolicy: Retain`**, mirroring `S3MainBucket`.
- **The deployer policy is near IAM's size ceiling.** A `renderedPolicyLeavesRoomInAnInlinePolicyBudget` test holds it under 4096 non-whitespace chars. Wildcard verb classes rather than enumerating actions, but never wildcard `Create`.
- **Do not deploy anything in this phase.** `baas admin setup` is a Phase 2 *closing* step, gated on human approval — it mutates a live stack.
- Run the full reactor before trusting green: `mvn -pl benchmark-runner verify` alone fails by design.
- `ASYNC_PATH` must point at a library that **exists on the build machine** — the `/app/...` path in older notes is the on-instance Linux path and makes the async IT silently skip.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `pom.xml` | Reactor | Add `baas-model` **before** `benchmark-runner` and `baas-cli` |
| `baas-model/pom.xml` | Module descriptor | Create; DynamoDB SDK; enforcer banning Mongo |
| `baas-model/src/main/java/pl/wsztajerowski/baas/model/StoredMeasurement.java` | The stored shape, both kinds | Create |
| `.../model/SecondaryMetric.java`, `.../model/JcstressSummary.java` | Value types | Create |
| `.../model/ResultKeys.java` | `pk`, both `sk` forms, GSI keys, timestamp format | Create |
| `.../model/TagKeys.java` | Known-key vocabulary as constants | Create |
| `.../model/MeasurementItemMapper.java` | `Map<String, AttributeValue>` both ways | Create |
| `baas-cli/pom.xml` | CLI deps | Add `baas-model` |
| `baas-cli/.../commands/RunCommand.java` | Reserved-key check | Replace the local literal list with `TagKeys` |
| `infra/cf-template-core.yaml` | Core stack | Add table, GSI, gateway endpoint, output, IAM |
| `infra/deployer-policy.json` | Deployer template | Add table lifecycle, prefix-scoped |
| `baas-cli/src/test/.../infra/CoreTemplateTest.java` | Template guards | Extend |

---

## Task 1: Create the `baas-model` module, mechanically Mongo-free

Covers 3.1.

**Files:**
- Create: `baas-model/pom.xml`
- Modify: `pom.xml` (`<modules>`, lines 232-237)
- Create: `baas-model/src/main/java/pl/wsztajerowski/baas/model/package-info.java`

**Interfaces:**
- Produces: Maven coordinates `pl.wsztajerowski:baas-model`, package `pl.wsztajerowski.baas.model`, on the reactor ahead of both consumers.

- [ ] **Step 1: Add the module to the reactor, ordered first**

In `pom.xml`, replace the `<modules>` block:

```xml
    <modules>
        <module>baas-model</module>
        <module>fake-jmh-benchmarks</module>
        <module>fake-stress-tests</module>
        <module>benchmark-runner</module>
        <module>baas-cli</module>
    </modules>
```

- [ ] **Step 2: Create `baas-model/pom.xml`**

The enforcer rule is the mechanical guarantee that this module never gains a Mongo dependency — a comment would not survive a careless `mvn dependency` addition.

**Both Morphia groupIds are listed deliberately.** This repo uses `dev.morphia.morphia:morphia-core` (see `benchmark-runner/pom.xml:32`), and `bannedDependencies` excludes do *not* prefix-match across groupId segments — `dev.morphia:*` alone would silently fail to match the coordinate actually in use, giving a rule that looks protective and is not.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>pl.wsztajerowski</groupId>
        <artifactId>benchmark-as-a-service</artifactId>
        <version>0.0.0-semantically-released</version>
    </parent>

    <artifactId>baas-model</artifactId>
    <name>BaaS shared model</name>

    <dependencies>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>dynamodb</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-enforcer-plugin</artifactId>
                <executions>
                    <execution>
                        <id>ban-mongodb</id>
                        <goals><goal>enforce</goal></goals>
                        <configuration>
                            <rules>
                                <bannedDependencies>
                                    <excludes>
                                        <exclude>org.mongodb:*</exclude>
                                        <exclude>dev.morphia:*</exclude>
                                        <exclude>dev.morphia.morphia:*</exclude>
                                    </excludes>
                                    <message>baas-model is on baas-cli's classpath and must stay Mongo-free (design.md non-goal).</message>
                                </bannedDependencies>
                            </rules>
                            <fail>true</fail>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

If `maven-enforcer-plugin` has no version managed in the parent's `<pluginManagement>`, add one there (`3.5.0`) rather than pinning it here.

- [ ] **Step 3: Verify the module builds and the ban is active**

Run: `mvn -pl baas-model verify`
Expected: BUILD SUCCESS.

Then prove the rule actually fires — temporarily add to `baas-model/pom.xml`:

```xml
        <dependency>
            <groupId>org.mongodb</groupId>
            <artifactId>mongodb-driver-sync</artifactId>
            <version>5.2.1</version>
        </dependency>
```

Run: `mvn -pl baas-model verify`
Expected: **FAIL** with the banned-dependency message. **Remove the dependency again** and re-run to confirm green.

Repeat the same proof for the Morphia half, which is a *different* pattern and must be exercised separately:

```xml
        <dependency>
            <groupId>dev.morphia.morphia</groupId>
            <artifactId>morphia-core</artifactId>
        </dependency>
```

A rule that has never been seen to fail is not a rule — and a rule where only one of its patterns has been seen to fail is worse, because the untested pattern is exactly where false confidence lives.

- [ ] **Step 4: Commit**

```bash
git add pom.xml baas-model/
git commit -m "feat(model): add Mongo-free baas-model module to the reactor"
```

---

## Task 2: Define the stored measurement shape

Covers 3.2.

**Files:**
- Create: `baas-model/src/main/java/pl/wsztajerowski/baas/model/StoredMeasurement.java`
- Create: `.../model/SecondaryMetric.java`
- Create: `.../model/JcstressSummary.java`
- Test: `baas-model/src/test/java/pl/wsztajerowski/baas/model/StoredMeasurementTest.java`

**Interfaces:**
- Produces: `StoredMeasurement` (record), `MeasurementKind` (enum `JMH`, `JCSTRESS`), `SecondaryMetric`, `JcstressSummary`. Task 3 keys off `kind`, `benchmarkClass`, `benchmarkMethod`, `createdAt`, `requestId`, `project`. Task 5 maps every component.

**Why these fields and no others.** The item carries what is needed to *list, filter and locate*. `rawData` and `scorePercentiles` are deliberately absent — they stay in the verbatim JMH JSON in S3, and `resultJsonKey` is how you get there. `secondaryMetrics` is reduced to score and unit for the same reason.

- [ ] **Step 1: Write the failing test**

```java
package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredMeasurementTest {

    @Test
    void aJmhMeasurementCarriesItsBenchmarkCoordinates() {
        var m = StoredMeasurementFixtures.jmh();

        assertThat(m.kind()).isEqualTo(MeasurementKind.JMH);
        assertThat(m.benchmarkClass()).isEqualTo("pl.wsztajerowski.fake.Incrementing_Synchronized");
        assertThat(m.benchmarkMethod()).isEqualTo("incrementUsingSynchronized");
        assertThat(m.jcstress()).isNull();
    }

    @Test
    void aJcstressMeasurementHasNoBenchmarkMethodButCarriesCounts() {
        var m = StoredMeasurementFixtures.jcstress();

        assertThat(m.kind()).isEqualTo(MeasurementKind.JCSTRESS);
        assertThat(m.benchmarkMethod()).isNull();
        assertThat(m.jcstress().totalTests()).isEqualTo(12);
    }

    @Test
    void tagsAreDefensivelyCopiedSoAStoredMeasurementCannotBeMutatedAfterConstruction() {
        var mutable = new java.util.HashMap<String, String>();
        mutable.put("project", "lynx-journal");
        var m = StoredMeasurementFixtures.jmh().withTags(mutable);

        mutable.put("project", "tampered");

        assertThat(m.tags()).containsEntry("project", "lynx-journal");
    }

    @Test
    void aJmhMeasurementRequiresItsBenchmarkCoordinates() {
        assertThatThrownBy(() -> StoredMeasurementFixtures.jmh().withBenchmarkClass(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("benchmarkClass");
    }
}
```

- [ ] **Step 2: Run and confirm it fails**

Run: `mvn -pl baas-model test`
Expected: FAIL to compile — none of these types exist.

- [ ] **Step 3: Create the value types**

`MeasurementKind.java`:

```java
package pl.wsztajerowski.baas.model;

public enum MeasurementKind { JMH, JCSTRESS }
```

`SecondaryMetric.java`:

```java
package pl.wsztajerowski.baas.model;

/** JMH secondary metrics reduced to what a table view needs; the full form stays in the S3 result JSON. */
public record SecondaryMetric(double score, String unit) {}
```

`JcstressSummary.java`:

```java
package pl.wsztajerowski.baas.model;

import java.util.Map;

/**
 * JCStress reports counts for everything and names only non-passing tests, so per-test items would
 * cover failures alone — hence one summary on one item, with the full result files in S3.
 */
public record JcstressSummary(
    int totalTests,
    int passedTests,
    int failedTests,
    int errorTests,
    Map<String, String> failed,
    Map<String, String> errors,
    Map<String, String> interesting
) {
    public JcstressSummary {
        // Null-defaulted, not just copied — Map.copyOf(null) throws, and a parsing layer with
        // zero errors will reasonably pass null. Must match StoredMeasurement's handling; two
        // records in one package disagreeing about what null means is how NPEs get shipped.
        failed = failed == null ? Map.of() : Map.copyOf(failed);
        errors = errors == null ? Map.of() : Map.copyOf(errors);
        interesting = interesting == null ? Map.of() : Map.copyOf(interesting);
    }
}
```

- [ ] **Step 4: Create `StoredMeasurement`**

```java
package pl.wsztajerowski.baas.model;

import java.time.Instant;
import java.util.Map;

/**
 * One stored measurement — one DynamoDB item, one Mongo document. The port speaks this shape;
 * each adapter owns its own physical layout.
 *
 * <p>Full-fidelity data (rawData, scorePercentiles, logs, profiling artifacts) is NOT here. It
 * lives in S3 under {@code resultPath}, reachable via {@code resultJsonKey}.
 */
public record StoredMeasurement(
    String project,
    String requestId,
    Instant createdAt,
    MeasurementKind kind,
    String benchmarkClass,
    String benchmarkMethod,
    String mode,
    Double score,
    Double scoreError,
    String scoreUnit,
    Map<String, SecondaryMetric> secondaryMetrics,
    JcstressSummary jcstress,
    Map<String, String> tags,
    String resultPath,
    String resultJsonKey,
    String environmentJsonKey
) {
    public StoredMeasurement {
        require(project, "project");
        require(requestId, "requestId");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (kind == MeasurementKind.JMH) {
            require(benchmarkClass, "benchmarkClass");
            require(benchmarkMethod, "benchmarkMethod");
        }
        secondaryMetrics = secondaryMetrics == null ? Map.of() : Map.copyOf(secondaryMetrics);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public StoredMeasurement withTags(Map<String, String> newTags) {
        return new StoredMeasurement(project, requestId, createdAt, kind, benchmarkClass,
            benchmarkMethod, mode, score, scoreError, scoreUnit, secondaryMetrics, jcstress,
            newTags, resultPath, resultJsonKey, environmentJsonKey);
    }

    public StoredMeasurement withBenchmarkClass(String newBenchmarkClass) {
        return new StoredMeasurement(project, requestId, createdAt, kind, newBenchmarkClass,
            benchmarkMethod, mode, score, scoreError, scoreUnit, secondaryMetrics, jcstress,
            tags, resultPath, resultJsonKey, environmentJsonKey);
    }
}
```

- [ ] **Step 5: Create the shared test fixtures**

Tasks 3 and 5 reuse these, so they live in one place from the start.

`baas-model/src/test/java/pl/wsztajerowski/baas/model/StoredMeasurementFixtures.java`:

```java
package pl.wsztajerowski.baas.model;

import java.time.Instant;
import java.util.Map;

final class StoredMeasurementFixtures {

    private StoredMeasurementFixtures() {}

    static StoredMeasurement jmh() {
        return new StoredMeasurement(
            "lynx-journal",
            "jmh-20260817_220706",
            Instant.parse("2026-08-17T22:07:06.123Z"),
            MeasurementKind.JMH,
            "pl.wsztajerowski.fake.Incrementing_Synchronized",
            "incrementUsingSynchronized",
            "thrpt",
            14075511.867,
            10632927.824,
            "ops/s",
            Map.of("·gc.alloc.rate", new SecondaryMetric(1234.5, "MB/sec")),
            null,
            Map.of("project", "lynx-journal", "type", "jmh", "jdk", "25.0.4"),
            "main/jmh/20260817_220706",
            "main/jmh/20260817_220706/jmh-result.json",
            "main/jmh/20260817_220706/environment.json");
    }

    static StoredMeasurement jcstress() {
        return new StoredMeasurement(
            "lynx-journal",
            "jcstress-20260817_221500",
            Instant.parse("2026-08-17T22:15:00.000Z"),
            MeasurementKind.JCSTRESS,
            null, null, null, null, null, null,
            Map.of(),
            new JcstressSummary(12, 10, 1, 1,
                Map.of("SomeTest", "FORBIDDEN"),
                Map.of("OtherTest", "ERROR"),
                Map.of("ThirdTest", "INTERESTING")),
            Map.of("project", "lynx-journal", "type", "jcstress"),
            "main/jcstress/20260817_221500",
            null,
            "main/jcstress/20260817_221500/environment.json");
    }
}
```

- [ ] **Step 6: Run and confirm green**

Run: `mvn -pl baas-model test`
Expected: PASS, 4 tests.

- [ ] **Step 7: Commit**

```bash
git add baas-model/src
git commit -m "feat(model): define the stored measurement shape for JMH and JCStress"
```

---

## Task 3: Key encoding and fixed-width timestamps

Covers 3.3, 3.4, 3.5.

**Files:**
- Create: `baas-model/src/main/java/pl/wsztajerowski/baas/model/ResultKeys.java`
- Test: `baas-model/src/test/java/pl/wsztajerowski/baas/model/ResultKeysTest.java`

**Interfaces:**
- Consumes: `StoredMeasurement`, `MeasurementKind` from Task 2.
- Produces: `ResultKeys.partitionKey(String project)`, `ResultKeys.sortKey(StoredMeasurement)`, `ResultKeys.requestIndexPartitionKey(String requestId)`, `ResultKeys.requestIndexSortKey(StoredMeasurement)`, `ResultKeys.formatTimestamp(Instant)`, and the constants `ResultKeys.PK_PREFIX`, `ResultKeys.JCSTRESS_SK_PREFIX`, `ResultKeys.SEPARATOR`, `ResultKeys.REQUEST_ID_INDEX_NAME`. Task 5 and all of §6 use these; nothing else may construct a key by string concatenation.

**The one subtlety that matters.** `Instant.toString()` is **not fixed width** — it omits trailing zero fractions, so `2026-01-01T00:00:00Z` and `2026-01-01T00:00:00.500Z` have different lengths and sort wrongly against each other once they are embedded mid-key. Use an explicit formatter with exactly three fractional digits.

- [ ] **Step 1: Write the failing tests**

```java
package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ResultKeysTest {

    @Test
    void partitionKeyIsPrefixedProject() {
        assertThat(ResultKeys.partitionKey("lynx-journal")).isEqualTo("RESULT#lynx-journal");
    }

    @Test
    void jmhSortKeyIsBenchmarkMajorThenChronological() {
        assertThat(ResultKeys.sortKey(StoredMeasurementFixtures.jmh())).isEqualTo(
            "pl.wsztajerowski.fake.Incrementing_Synchronized"
                + "#incrementUsingSynchronized"
                + "#2026-08-17T22:07:06.123Z"
                + "#jmh-20260817_220706");
    }

    @Test
    void jcstressSortKeyUsesAFixedPrefixBecauseThereIsNoBenchmarkMethod() {
        assertThat(ResultKeys.sortKey(StoredMeasurementFixtures.jcstress()))
            .isEqualTo("JCSTRESS#2026-08-17T22:15:00.000Z#jcstress-20260817_221500");
    }

    @Test
    void theRequestIdIndexIsKeyedOnRequestIdThenBenchmark() {
        var m = StoredMeasurementFixtures.jmh();

        assertThat(ResultKeys.requestIndexPartitionKey(m.requestId())).isEqualTo("jmh-20260817_220706");
        assertThat(ResultKeys.requestIndexSortKey(m))
            .isEqualTo("pl.wsztajerowski.fake.Incrementing_Synchronized#incrementUsingSynchronized");
    }

    @Test
    void formattedTimestampsAreAlwaysTheSameWidth() {
        assertThat(ResultKeys.formatTimestamp(Instant.parse("2026-01-01T00:00:00Z")))
            .hasSameSizeAs(ResultKeys.formatTimestamp(Instant.parse("2026-12-31T23:59:59.999Z")));
    }

    /** Instant.toString() drops trailing zero fractions — that is exactly the bug this guards. */
    @Test
    void aWholeSecondStillCarriesThreeFractionalDigits() {
        assertThat(ResultKeys.formatTimestamp(Instant.parse("2026-01-01T00:00:00Z")))
            .isEqualTo("2026-01-01T00:00:00.000Z");
    }

    @Test
    void lexicographicOrderEqualsChronologicalOrderAcrossMonthAndYearBoundaries() {
        List<Instant> chronological = List.of(
            Instant.parse("2025-12-31T23:59:59.998Z"),
            Instant.parse("2025-12-31T23:59:59.999Z"),
            Instant.parse("2026-01-01T00:00:00.000Z"),
            Instant.parse("2026-01-31T23:59:59.999Z"),
            Instant.parse("2026-02-01T00:00:00.000Z"),
            Instant.parse("2026-09-30T12:00:00.500Z"),
            Instant.parse("2026-10-01T12:00:00.500Z"));

        List<String> formatted = chronological.stream().map(ResultKeys::formatTimestamp).toList();

        assertThat(formatted).isSorted();
    }

    @Test
    void lexicographicOrderEqualsChronologicalOrderForRandomInstants() {
        var random = new Random(20260817L);
        var instants = new ArrayList<Instant>();
        for (int i = 0; i < 500; i++) {
            instants.add(Instant.ofEpochMilli(Math.abs(random.nextLong()) % 4_102_444_800_000L));
        }
        instants.sort(Instant::compareTo);

        assertThat(instants.stream().map(ResultKeys::formatTimestamp).toList()).isSorted();
    }

    @Test
    void timestampsAreRenderedInUtcRegardlessOfTheDefaultZone() {
        var original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"));
            assertThat(ResultKeys.formatTimestamp(Instant.parse("2026-01-01T00:00:00Z")))
                .isEqualTo("2026-01-01T00:00:00.000Z");
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -pl baas-model test -Dtest=ResultKeysTest`
Expected: FAIL to compile — `ResultKeys` does not exist.

- [ ] **Step 3: Implement `ResultKeys`**

```java
package pl.wsztajerowski.baas.model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The only place a DynamoDB key is constructed. Encoding a key by hand anywhere else is how a
 * query silently returns zero rows instead of failing to compile.
 *
 * <p>{@code sk} is benchmark-major then chronological, which serves three patterns from one
 * ordering: the latest result for a benchmark, a benchmark's history in order, and grouping.
 */
public final class ResultKeys {

    public static final String PK_PREFIX = "RESULT#";
    public static final String JCSTRESS_SK_PREFIX = "JCSTRESS#";
    public static final String SEPARATOR = "#";
    public static final String REQUEST_ID_INDEX_NAME = "requestId-index";

    /**
     * Fixed width, always three fractional digits, always UTC. Instant.toString() omits trailing
     * zero fractions, which makes keys of differing length that misorder as strings — and the
     * failure surfaces as missing rows, not as a formatting error.
     */
    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private ResultKeys() {}

    public static String partitionKey(String project) {
        return PK_PREFIX + project;
    }

    public static String sortKey(StoredMeasurement measurement) {
        String timestamp = formatTimestamp(measurement.createdAt());
        if (measurement.kind() == MeasurementKind.JCSTRESS) {
            return JCSTRESS_SK_PREFIX + timestamp + SEPARATOR + measurement.requestId();
        }
        return measurement.benchmarkClass()
            + SEPARATOR + measurement.benchmarkMethod()
            + SEPARATOR + timestamp
            + SEPARATOR + measurement.requestId();
    }

    public static String requestIndexPartitionKey(String requestId) {
        return requestId;
    }

    public static String requestIndexSortKey(StoredMeasurement measurement) {
        if (measurement.kind() == MeasurementKind.JCSTRESS) {
            return JCSTRESS_SK_PREFIX + measurement.requestId();
        }
        return measurement.benchmarkClass() + SEPARATOR + measurement.benchmarkMethod();
    }

    public static String formatTimestamp(Instant instant) {
        return TIMESTAMP.format(instant);
    }
}
```

- [ ] **Step 4: Run and confirm green**

Run: `mvn -pl baas-model test -Dtest=ResultKeysTest`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add baas-model/src/main/java/pl/wsztajerowski/baas/model/ResultKeys.java \
        baas-model/src/test/java/pl/wsztajerowski/baas/model/ResultKeysTest.java
git commit -m "feat(model): encode result keys with fixed-width UTC sort timestamps"
```

---

## Task 4: The tag vocabulary, defined once and adopted by the CLI

Covers 3.6, and closes the drift warning recorded in `verify.md` §4.

**Files:**
- Create: `baas-model/src/main/java/pl/wsztajerowski/baas/model/TagKeys.java`
- Test: `baas-model/src/test/java/pl/wsztajerowski/baas/model/TagKeysTest.java`
- Modify: `baas-cli/pom.xml` (add the `baas-model` dependency)
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java` (`RESERVED_TAG_KEYS`, around line 444)

**Interfaces:**
- Produces: `TagKeys.PROJECT`, `TYPE`, `COMMIT`, `JDK`, `CPU_MODEL`, `CPU_ARCH`, `INSTANCE_TYPE`, `IMAGE_VERSION`; `TagKeys.KNOWN` (all eight); `TagKeys.MACHINE_OBSERVED` (the six a caller may not set). §6.10's unknown-key warning uses `KNOWN`.

**This task exists to delete a duplicate, not just to add constants.** `RunCommand.RESERVED_TAG_KEYS` currently hard-codes the same six names, and `UserDataScriptBuilder.SCRIPT_BODY` hard-codes all of them again as shell literals. Adding a third copy would make things worse. The shell literals stay (they are inside a bash heredoc and cannot reference Java constants) but the Java list must become derived.

- [ ] **Step 1: Write the failing tests**

```java
package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TagKeysTest {

    @Test
    void theKnownVocabularyIsExactlyTheEightDocumentedKeys() {
        assertThat(TagKeys.KNOWN).containsExactlyInAnyOrder(
            "project", "type", "commit", "jdk", "cpuModel", "cpuArch", "instanceType", "imageVersion");
    }

    @Test
    void machineObservedKeysAreTheKnownKeysMinusTheCallerOverridableOnes() {
        assertThat(TagKeys.MACHINE_OBSERVED)
            .containsExactlyInAnyOrder("type", "jdk", "cpuModel", "cpuArch", "instanceType", "imageVersion")
            .doesNotContain("project", "commit");
    }

    @Test
    void machineObservedIsASubsetOfKnown() {
        assertThat(TagKeys.KNOWN).containsAll(TagKeys.MACHINE_OBSERVED);
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -pl baas-model test -Dtest=TagKeysTest`
Expected: FAIL to compile — `TagKeys` does not exist.

- [ ] **Step 3: Implement `TagKeys`**

```java
package pl.wsztajerowski.baas.model;

import java.util.List;
import java.util.Set;

/**
 * The known-key tag vocabulary, defined once for both the runner and the CLI. Keys outside this
 * set are permitted — a query naming one warns rather than silently returning nothing.
 */
public final class TagKeys {

    public static final String PROJECT = "project";
    public static final String TYPE = "type";
    public static final String COMMIT = "commit";
    public static final String JDK = "jdk";
    public static final String CPU_MODEL = "cpuModel";
    public static final String CPU_ARCH = "cpuArch";
    public static final String INSTANCE_TYPE = "instanceType";
    public static final String IMAGE_VERSION = "imageVersion";

    public static final Set<String> KNOWN =
        Set.of(PROJECT, TYPE, COMMIT, JDK, CPU_MODEL, CPU_ARCH, INSTANCE_TYPE, IMAGE_VERSION);

    /**
     * Observed on the instance (or derived from the benchmark type), so a caller may not set them:
     * an override would let a result's tags disagree with its own environment.json. `project` and
     * `commit` are deliberately absent — design.md specifies caller-wins for those.
     */
    public static final List<String> MACHINE_OBSERVED =
        List.of(IMAGE_VERSION, INSTANCE_TYPE, JDK, CPU_MODEL, CPU_ARCH, TYPE);

    private TagKeys() {}
}
```

- [ ] **Step 4: Run and confirm green**

Run: `mvn -pl baas-model test -Dtest=TagKeysTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Add the dependency to `baas-cli`**

In `baas-cli/pom.xml`, inside `<dependencies>`:

```xml
        <dependency>
            <groupId>pl.wsztajerowski</groupId>
            <artifactId>baas-model</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 6: Replace the CLI's duplicate list**

In `RunCommand.java`, add `import pl.wsztajerowski.baas.model.TagKeys;` and replace the literal list (around line 444) with:

```java
    /** Defined once in baas-model so the CLI and the runner cannot drift apart. */
    static final List<String> RESERVED_TAG_KEYS = TagKeys.MACHINE_OBSERVED;
```

Do **not** change `buildRunnerTags`'s behaviour or its error message — the existing `RunCommandTest` cases pin both and must stay green.

- [ ] **Step 7: Run the CLI suite**

Run: `mvn -pl baas-model,baas-cli test`
Expected: PASS. `RunCommandTest` must still be green, including `rejectsAReservedTagKey` and `anExplicitTagOverridesTheDerivedValue`.

- [ ] **Step 8: Commit**

```bash
git add baas-model/src baas-cli/pom.xml baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java
git commit -m "feat(model): define the tag vocabulary once and adopt it in baas-cli"
```

---

## Task 5: The `Map<String, AttributeValue>` mapper

Covers 3.7, 3.8, 3.9.

**Files:**
- Create: `baas-model/src/main/java/pl/wsztajerowski/baas/model/MeasurementItemMapper.java`
- Test: `baas-model/src/test/java/pl/wsztajerowski/baas/model/MeasurementItemMapperTest.java`

**Interfaces:**
- Consumes: `StoredMeasurement`, `ResultKeys` from Tasks 2-3.
- Produces: `MeasurementItemMapper.toItem(StoredMeasurement)` returning `Map<String, AttributeValue>`; `MeasurementItemMapper.fromItem(Map<String, AttributeValue>)` returning `StoredMeasurement`; `MeasurementItemMapper.serializedSize(Map<String, AttributeValue>)` returning `int`; and the constant `MeasurementItemMapper.MAX_ITEM_BYTES`. The package-private attribute-name constants (`PK`, `SK`, `GSI1PK`, `GSI1SK`, `TAGS`, …) are what §6's `FilterExpression` builders reference — §6 must not write attribute names by hand.

**Attribute names** (`pk`, `sk`, plus `gsi1pk`/`gsi1sk` for the index) are constants on the mapper. DynamoDB has no `null` attribute type worth using here — absent means absent, so optional fields are simply omitted and `fromItem` tolerates their absence.

- [ ] **Step 1: Write the failing tests**

```java
package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeasurementItemMapperTest {

    @Test
    void aJmhMeasurementRoundTrips() {
        var original = StoredMeasurementFixtures.jmh();

        assertThat(MeasurementItemMapper.fromItem(MeasurementItemMapper.toItem(original)))
            .isEqualTo(original);
    }

    @Test
    void aJcstressMeasurementRoundTrips() {
        var original = StoredMeasurementFixtures.jcstress();

        assertThat(MeasurementItemMapper.fromItem(MeasurementItemMapper.toItem(original)))
            .isEqualTo(original);
    }

    @Test
    void theItemCarriesBothKeyPairs() {
        var item = MeasurementItemMapper.toItem(StoredMeasurementFixtures.jmh());

        assertThat(item.get("pk").s()).isEqualTo("RESULT#lynx-journal");
        assertThat(item.get("sk").s()).startsWith("pl.wsztajerowski.fake.Incrementing_Synchronized#");
        assertThat(item.get("gsi1pk").s()).isEqualTo("jmh-20260817_220706");
        assertThat(item.get("gsi1sk").s())
            .isEqualTo("pl.wsztajerowski.fake.Incrementing_Synchronized#incrementUsingSynchronized");
    }

    @Test
    void absentOptionalAttributesAreOmittedRatherThanStoredAsNull() {
        var item = MeasurementItemMapper.toItem(StoredMeasurementFixtures.jcstress());

        assertThat(item).doesNotContainKey("resultJsonKey");
        assertThat(item).doesNotContainKey("benchmarkMethod");
    }

    @Test
    void aRealisticMeasurementIsFarUnderTheItemLimit() {
        var bytes = MeasurementItemMapper.serializedSize(
            MeasurementItemMapper.toItem(StoredMeasurementFixtures.jmh()));

        assertThat(bytes).isLessThan(4 * 1024);
    }

    @Test
    void anOversizedMeasurementFailsLoudlyRatherThanBeingTruncated() {
        Map<String, String> hugeTags = IntStream.range(0, 20_000)
            .boxed()
            .collect(Collectors.toMap(i -> "key" + i, i -> "value".repeat(10)));

        var oversized = StoredMeasurementFixtures.jmh().withTags(hugeTags);

        assertThatThrownBy(() -> MeasurementItemMapper.toItem(oversized))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("400")
            .hasMessageContaining("jmh-20260817_220706");
    }

    @Test
    void anUnknownAttributeInStoredDataDoesNotBreakReads() {
        var item = new HashMap<>(MeasurementItemMapper.toItem(StoredMeasurementFixtures.jmh()));
        item.put("attributeAddedByALaterVersion", AttributeValue.fromS("whatever"));

        assertThat(MeasurementItemMapper.fromItem(item))
            .isEqualTo(StoredMeasurementFixtures.jmh());
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -pl baas-model test -Dtest=MeasurementItemMapperTest`
Expected: FAIL to compile — `MeasurementItemMapper` does not exist.

- [ ] **Step 3: Implement the mapper**

```java
package pl.wsztajerowski.baas.model;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The schema contract. An explicit mapper rather than the Enhanced Client, because key encoding
 * needs exact control and an incompatible change should break compilation here rather than return
 * zero rows at runtime.
 */
public final class MeasurementItemMapper {

    public static final int MAX_ITEM_BYTES = 400 * 1024;

    static final String PK = "pk";
    static final String SK = "sk";
    static final String GSI1PK = "gsi1pk";
    static final String GSI1SK = "gsi1sk";
    static final String PROJECT = "project";
    static final String REQUEST_ID = "requestId";
    static final String CREATED_AT = "createdAt";
    static final String KIND = "kind";
    static final String BENCHMARK_CLASS = "benchmarkClass";
    static final String BENCHMARK_METHOD = "benchmarkMethod";
    static final String MODE = "mode";
    static final String SCORE = "score";
    static final String SCORE_ERROR = "scoreError";
    static final String SCORE_UNIT = "scoreUnit";
    static final String SECONDARY_METRICS = "secondaryMetrics";
    static final String JCSTRESS = "jcstress";
    static final String TAGS = "tags";
    static final String RESULT_PATH = "resultPath";
    static final String RESULT_JSON_KEY = "resultJsonKey";
    static final String ENVIRONMENT_JSON_KEY = "environmentJsonKey";

    private static final String TOTAL_TESTS = "totalTests";
    private static final String PASSED_TESTS = "passedTests";
    private static final String FAILED_TESTS = "failedTests";
    private static final String ERROR_TESTS = "errorTests";
    private static final String FAILED = "failed";
    private static final String ERRORS = "errors";
    private static final String INTERESTING = "interesting";
    private static final String METRIC_SCORE = "score";
    private static final String METRIC_UNIT = "unit";

    private MeasurementItemMapper() {}

    public static Map<String, AttributeValue> toItem(StoredMeasurement m) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();

        item.put(PK, s(ResultKeys.partitionKey(m.project())));
        item.put(SK, s(ResultKeys.sortKey(m)));
        item.put(GSI1PK, s(ResultKeys.requestIndexPartitionKey(m.requestId())));
        item.put(GSI1SK, s(ResultKeys.requestIndexSortKey(m)));

        item.put(PROJECT, s(m.project()));
        item.put(REQUEST_ID, s(m.requestId()));
        item.put(CREATED_AT, s(ResultKeys.formatTimestamp(m.createdAt())));
        item.put(KIND, s(m.kind().name()));

        putIfPresent(item, BENCHMARK_CLASS, m.benchmarkClass());
        putIfPresent(item, BENCHMARK_METHOD, m.benchmarkMethod());
        putIfPresent(item, MODE, m.mode());
        putIfPresent(item, SCORE_UNIT, m.scoreUnit());
        putIfPresent(item, RESULT_PATH, m.resultPath());
        putIfPresent(item, RESULT_JSON_KEY, m.resultJsonKey());
        putIfPresent(item, ENVIRONMENT_JSON_KEY, m.environmentJsonKey());

        if (m.score() != null) {
            item.put(SCORE, n(m.score()));
        }
        if (m.scoreError() != null) {
            item.put(SCORE_ERROR, n(m.scoreError()));
        }
        if (!m.secondaryMetrics().isEmpty()) {
            item.put(SECONDARY_METRICS, AttributeValue.fromM(
                m.secondaryMetrics().entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> AttributeValue.fromM(Map.of(
                        METRIC_SCORE, n(e.getValue().score()),
                        METRIC_UNIT, s(e.getValue().unit())))))));
        }
        if (!m.tags().isEmpty()) {
            item.put(TAGS, AttributeValue.fromM(
                m.tags().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> s(e.getValue())))));
        }
        if (m.jcstress() != null) {
            item.put(JCSTRESS, AttributeValue.fromM(toJcstressItem(m.jcstress())));
        }

        int size = serializedSize(item);
        if (size > MAX_ITEM_BYTES) {
            throw new IllegalStateException(
                "Measurement for request " + m.requestId() + " serializes to " + size
                    + " bytes, above DynamoDB's 400 KB item limit. Refusing to truncate — "
                    + "reduce the tag set or move the payload to S3.");
        }
        return item;
    }

    public static StoredMeasurement fromItem(Map<String, AttributeValue> item) {
        return new StoredMeasurement(
            str(item, PROJECT),
            str(item, REQUEST_ID),
            Instant.parse(str(item, CREATED_AT)),
            MeasurementKind.valueOf(str(item, KIND)),
            str(item, BENCHMARK_CLASS),
            str(item, BENCHMARK_METHOD),
            str(item, MODE),
            dbl(item, SCORE),
            dbl(item, SCORE_ERROR),
            str(item, SCORE_UNIT),
            secondaryMetricsFrom(item),
            jcstressFrom(item),
            tagsFrom(item),
            str(item, RESULT_PATH),
            str(item, RESULT_JSON_KEY),
            str(item, ENVIRONMENT_JSON_KEY));
    }

    /**
     * Approximates DynamoDB's own accounting: attribute names plus values, recursing into maps.
     * Exactness is not required — the guard exists to fail loudly well before the real limit.
     */
    public static int serializedSize(Map<String, AttributeValue> item) {
        int total = 0;
        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
            total += utf8(entry.getKey()) + valueSize(entry.getValue());
        }
        return total;
    }

    private static Map<String, AttributeValue> toJcstressItem(JcstressSummary j) {
        Map<String, AttributeValue> nested = new LinkedHashMap<>();
        nested.put(TOTAL_TESTS, n(j.totalTests()));
        nested.put(PASSED_TESTS, n(j.passedTests()));
        nested.put(FAILED_TESTS, n(j.failedTests()));
        nested.put(ERROR_TESTS, n(j.errorTests()));
        nested.put(FAILED, stringMap(j.failed()));
        nested.put(ERRORS, stringMap(j.errors()));
        nested.put(INTERESTING, stringMap(j.interesting()));
        return nested;
    }

    private static JcstressSummary jcstressFrom(Map<String, AttributeValue> item) {
        AttributeValue value = item.get(JCSTRESS);
        if (value == null || !value.hasM()) {
            return null;
        }
        Map<String, AttributeValue> nested = value.m();
        return new JcstressSummary(
            intOf(nested, TOTAL_TESTS),
            intOf(nested, PASSED_TESTS),
            intOf(nested, FAILED_TESTS),
            intOf(nested, ERROR_TESTS),
            stringMapFrom(nested, FAILED),
            stringMapFrom(nested, ERRORS),
            stringMapFrom(nested, INTERESTING));
    }

    private static Map<String, SecondaryMetric> secondaryMetricsFrom(Map<String, AttributeValue> item) {
        AttributeValue value = item.get(SECONDARY_METRICS);
        if (value == null || !value.hasM()) {
            return Map.of();
        }
        return value.m().entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> new SecondaryMetric(
                Double.parseDouble(e.getValue().m().get(METRIC_SCORE).n()),
                e.getValue().m().get(METRIC_UNIT).s())));
    }

    private static Map<String, String> tagsFrom(Map<String, AttributeValue> item) {
        AttributeValue value = item.get(TAGS);
        if (value == null || !value.hasM()) {
            return Map.of();
        }
        return value.m().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().s()));
    }

    private static AttributeValue stringMap(Map<String, String> source) {
        return AttributeValue.fromM(source.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> s(e.getValue()))));
    }

    private static Map<String, String> stringMapFrom(Map<String, AttributeValue> nested, String name) {
        AttributeValue value = nested.get(name);
        if (value == null || !value.hasM()) {
            return Map.of();
        }
        return value.m().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().s()));
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, s(value));
        }
    }

    private static String str(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    private static Double dbl(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : Double.valueOf(value.n());
    }

    private static int intOf(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? 0 : Integer.parseInt(value.n());
    }

    private static int valueSize(AttributeValue value) {
        if (value.s() != null) return utf8(value.s());
        if (value.n() != null) return utf8(value.n());
        if (value.hasM()) return serializedSize(value.m());
        return 0;
    }

    private static int utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue n(Number value) {
        return AttributeValue.fromN(String.valueOf(value));
    }
}
```

Numbers round-trip exactly because `String.valueOf(double)` and `Double.parseDouble` are exact inverses for every `double`.

- [ ] **Step 4: Run and confirm green**

Run: `mvn -pl baas-model test -Dtest=MeasurementItemMapperTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Run the whole module**

Run: `mvn -pl baas-model test`
Expected: PASS, 23 tests across the four test classes.

- [ ] **Step 6: Commit**

```bash
git add baas-model/src
git commit -m "feat(model): map stored measurements to and from DynamoDB items"
```

---

## Task 6: The results table, its index, the gateway endpoint and the output

Covers 4.1, 4.2, 4.4. **Does NOT cover 4.3** — see the sequencing hazard above.

**Files:**
- Modify: `infra/cf-template-core.yaml`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java` (create if the existing template test lives elsewhere — locate it with `grep -rl "cf-template-core" baas-cli/src/test`)

**Interfaces:**
- Produces: logical resources `ResultsTable`, `DynamoDbGatewayEndpoint`; stack output `ResultsTableName`. §7.1 reads that output into `BaasConfig`.

**The existing-VPC path is already settled by precedent.** `S3GatewayEndpoint` (line 126) carries `Condition: CreateNetworking`, so when `UseExistingVpc=true` the stack creates no gateway endpoint at all and the operator supplies their own networking. The DynamoDB endpoint mirrors that exactly — same condition, same shape, `RouteTableIds: [!Ref PublicRouteTable]`. Do not invent an `ExistingRouteTableId` parameter; that would diverge from how S3 already works.

Note the consequence and state it in the commit message: under `UseExistingVpc=true` there is no DynamoDB gateway endpoint, so runner traffic to DynamoDB goes out via the existing route. That is the same trade-off already accepted for S3.

- [ ] **Step 1: Write the failing template tests**

Add to the core-template test class. Match the assertion style already used there (it parses the YAML — reuse the existing loader rather than adding a new one):

```java
    @Test
    void theResultsTableIsRetainedOnBothDeleteAndReplace() {
        var table = resource("ResultsTable");

        assertThat(table.get("DeletionPolicy")).isEqualTo("Retain");
        assertThat(table.get("UpdateReplacePolicy")).isEqualTo("Retain");
    }

    @Test
    void theResultsTableIsOnDemandWithStringKeys() {
        var properties = properties("ResultsTable");

        assertThat(properties.get("BillingMode")).isEqualTo("PAY_PER_REQUEST");
        assertThat(keySchema(properties)).containsExactly(entry("pk", "HASH"), entry("sk", "RANGE"));
        assertThat(attributeTypes(properties)).containsEntry("pk", "S").containsEntry("sk", "S");
    }

    @Test
    void theResultsTableHasExactlyOneIndexKeyedOnRequestId() {
        var indexes = globalSecondaryIndexes("ResultsTable");

        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).get("IndexName")).isEqualTo("requestId-index");
    }

    @Test
    void theResultsTableHasNoTimeToLive() {
        assertThat(properties("ResultsTable")).doesNotContainKey("TimeToLiveSpecification");
    }

    /** A gateway endpoint is free; an interface endpoint bills hourly per AZ. */
    @Test
    void dynamoDbIsReachedThroughAGatewayEndpointNotAnInterfaceEndpoint() {
        var properties = properties("DynamoDbGatewayEndpoint");

        assertThat(properties.get("VpcEndpointType")).isEqualTo("Gateway");
        assertThat(String.valueOf(properties.get("ServiceName"))).contains("dynamodb");
    }

    @Test
    void theTableNameIsAStackOutput() {
        assertThat(outputs()).containsKey("ResultsTableName");
    }

    /**
     * The runner still writes to Atlas until the cutover lands. Removing 27017 before then makes
     * every run fail at the database write — deliberately deferred, see plan.md.
     */
    @Test
    void runnerEgressStillPermits27017UntilTheCutover() {
        assertThat(securityGroupEgressPorts("RunnerSecurityGroup")).contains(27017);
    }
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest`
Expected: FAIL — `ResultsTable` and `DynamoDbGatewayEndpoint` are absent. `runnerEgressStillPermits27017UntilTheCutover` should PASS already.

- [ ] **Step 3: Add the table to `cf-template-core.yaml`**

Place it near `S3MainBucket` so the two retained resources sit together:

```yaml
  ResultsTable:
    Type: AWS::DynamoDB::Table
    DeletionPolicy: Retain
    UpdateReplacePolicy: Retain
    Properties:
      TableName: !Sub baas-${ResourceNamePrefix}-results
      BillingMode: PAY_PER_REQUEST
      AttributeDefinitions:
        - AttributeName: pk
          AttributeType: S
        - AttributeName: sk
          AttributeType: S
        - AttributeName: gsi1pk
          AttributeType: S
        - AttributeName: gsi1sk
          AttributeType: S
      KeySchema:
        - AttributeName: pk
          KeyType: HASH
        - AttributeName: sk
          KeyType: RANGE
      GlobalSecondaryIndexes:
        - IndexName: requestId-index
          KeySchema:
            - AttributeName: gsi1pk
              KeyType: HASH
            - AttributeName: gsi1sk
              KeyType: RANGE
          Projection:
            ProjectionType: ALL
      Tags:
        - Key: baas-role
          Value: results
```

`ResourceNamePrefix` is the template's existing parameter and `S3MainBucket` uses `BucketName: !Sub baas-${ResourceNamePrefix}` — the table name above mirrors that convention. Place the resource immediately after `S3MainBucket` (line 166) so the two retained resources are adjacent.

- [ ] **Step 4: Add the gateway endpoint**

```yaml
  DynamoDbGatewayEndpoint:
    Type: AWS::EC2::VPCEndpoint
    Condition: CreateNetworking
    Properties:
      VpcId: !Ref BaasVpc
      ServiceName: !Sub 'com.amazonaws.${AWS::Region}.dynamodb'
      VpcEndpointType: Gateway
      RouteTableIds:
        - !Ref PublicRouteTable
```

Place it immediately after `S3GatewayEndpoint` (line 126-134), which this mirrors line for line.

- [ ] **Step 5: Add the output**

```yaml
  ResultsTableName:
    Value: !Ref ResultsTable
```

The template's outputs carry no `Description` and no `Export` — `BucketName`, `RunnerRoleName` and the rest are bare `Value:` entries. Match that; do not add an export nobody consumes.

- [ ] **Step 6: Run the tests**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add infra/cf-template-core.yaml baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java
git commit -m "feat(infra): add the retained results table, its requestId index and a DynamoDB gateway endpoint"
```

---

## Task 7: Scoped IAM for the runner, the operator and the deployer

Covers 4.5, 4.6, 4.7, 4.9, 4.10. **Does NOT cover 4.8** — see the sequencing hazard above.

**Files:**
- Modify: `infra/cf-template-core.yaml` (`RunnerRole` at line 214, `OperatorRole` at line 417 — note the *logical* id is `OperatorRole`; `BaasCliOperatorRole` appears only in prose)
- Modify: `infra/deployer-policy.json`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/DeployerPolicyTest.java`

**Interfaces:**
- Produces: the runner may `PutItem`/`BatchWriteItem` on the table only; the operator may `Query`/`GetItem` on the table and its index only; the deployer may manage the table's lifecycle, prefix-scoped.

**Why the operator needs the index ARN explicitly.** A `Query` against a GSI authorises on the *index* ARN (`<table-arn>/index/*`), not the table ARN. Granting the table alone makes `--request-id` fail with an opaque AccessDenied at runtime, long after the deploy looks successful.

- [ ] **Step 1: Write the failing tests**

Add to `CoreTemplateTest`:

```java
    @Test
    void theRunnerCanWriteResultsButNeverReadOrDeleteThem() {
        var actions = policyActionsFor("RunnerRole", "dynamodb");

        assertThat(actions).containsExactlyInAnyOrder("dynamodb:PutItem", "dynamodb:BatchWriteItem");
        assertThat(actions).doesNotContain("dynamodb:Scan", "dynamodb:DeleteItem", "dynamodb:Query");
    }

    @Test
    void theOperatorCanReadResultsButNeverWriteThem() {
        var actions = policyActionsFor("OperatorRole", "dynamodb");

        assertThat(actions).containsExactlyInAnyOrder("dynamodb:Query", "dynamodb:GetItem");
        assertThat(actions).doesNotContain("dynamodb:PutItem", "dynamodb:Scan", "dynamodb:DeleteItem");
    }

    @Test
    void theOperatorIsGrantedTheIndexArnBecauseAGsiQueryAuthorisesOnTheIndex() {
        assertThat(policyResourcesFor("OperatorRole", "dynamodb"))
            .anySatisfy(resource -> assertThat(String.valueOf(resource)).contains("index"));
    }

    @Test
    void noDynamoDbGrantUsesAWildcardResource() {
        assertThat(policyResourcesFor("RunnerRole", "dynamodb")).doesNotContain("*");
        assertThat(policyResourcesFor("OperatorRole", "dynamodb")).doesNotContain("*");
    }
```

Add to `DeployerPolicyTest`:

```java
    @Test
    void theDeployerCanManageTheResultsTableLifecycle() {
        var actions = actionsForService("dynamodb");

        assertThat(actions).contains(
            "dynamodb:CreateTable", "dynamodb:DeleteTable", "dynamodb:UpdateTable",
            "dynamodb:DescribeTable", "dynamodb:TagResource", "dynamodb:UntagResource",
            "dynamodb:ListTagsOfResource", "dynamodb:UpdateTimeToLive", "dynamodb:DescribeTimeToLive");
    }

    @Test
    void theDeployerHasNoDataPlaneAccessToResults() {
        assertThat(actionsForService("dynamodb"))
            .doesNotContain("dynamodb:PutItem", "dynamodb:GetItem", "dynamodb:Query", "dynamodb:Scan");
    }

    @Test
    void everyDynamoDbStatementIsPrefixScoped() {
        assertThat(resourcesForService("dynamodb"))
            .isNotEmpty()
            .allSatisfy(resource -> assertThat(resource).doesNotContain("dynamodb:*:*:table/*"));
    }
```

`renderedPolicyLeavesRoomInAnInlinePolicyBudget` already exists — do not modify it. It is the guard that these additions must not break.

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest,DeployerPolicyTest`
Expected: FAIL — no DynamoDB statements exist yet.

- [ ] **Step 3: Grant the runner write-only access**

`RunnerRole` carries a *list* of policies named `${ResourceNamePrefix}-runner-s3-policy` and `${ResourceNamePrefix}-runner-ec2-terminate-policy`. Add a third entry following that convention rather than appending a statement to the S3 policy:

```yaml
        - PolicyName: !Sub ${ResourceNamePrefix}-runner-dynamodb-policy
          PolicyDocument:
            Version: '2012-10-17'
            Statement:
              - Effect: Allow
                Action:
                  - dynamodb:PutItem
                  - dynamodb:BatchWriteItem
                Resource: !GetAtt ResultsTable.Arn
```

`AWS::DynamoDB::Table` exposes a distinct `Arn` attribute, so `!GetAtt ... .Arn` is correct here and `!Ref` (which returns the table *name*) would not be. Copy the `Version` line's exact quoting from the neighbouring policy.

- [ ] **Step 4: Grant the operator read-only access, table and index**

Add a matching policy entry to `OperatorRole`:

```yaml
        - PolicyName: !Sub ${ResourceNamePrefix}-operator-dynamodb-policy
          PolicyDocument:
            Version: '2012-10-17'
            Statement:
              - Effect: Allow
                Action:
                  - dynamodb:Query
                  - dynamodb:GetItem
                Resource:
                  - !GetAtt ResultsTable.Arn
                  - !Sub '${ResultsTable.Arn}/index/*'
```

Also update the comment above `OperatorRole` (line 408-411), which currently describes the role as scoped to "bucket, RunnerRole, mongo SSM path" — add the results table. The mongo SSM path stays in that comment until 4.8 lands in Phase 4.

- [ ] **Step 5: Add the deployer's table lifecycle statement**

In `infra/deployer-policy.json`, add a statement using the existing `${ACCOUNT_ID}` / `${REGION}` / `${PREFIX}` placeholders:

```json
    {
      "Sid": "ResultsTableLifecycle",
      "Effect": "Allow",
      "Action": [
        "dynamodb:CreateTable",
        "dynamodb:DeleteTable",
        "dynamodb:UpdateTable",
        "dynamodb:DescribeTable",
        "dynamodb:DescribeContinuousBackups",
        "dynamodb:DescribeTimeToLive",
        "dynamodb:UpdateTimeToLive",
        "dynamodb:TagResource",
        "dynamodb:UntagResource",
        "dynamodb:ListTagsOfResource"
      ],
      "Resource": "arn:aws:dynamodb:${REGION}:${ACCOUNT_ID}:table/baas-${PREFIX}-results"
    }
```

The placeholders `${ACCOUNT_ID}` / `${REGION}` / `${PREFIX}` are substituted per caller by `DeployerPolicyRenderer` — the file is a template, and attaching it unrendered grants nothing. Copy the partition style from the neighbouring statements in that same JSON file rather than from the YAML template.

CloudFormation calls `DescribeContinuousBackups` and `DescribeTimeToLive` during table create even when neither is configured — omitting them produces a rollback whose message names the missing action, which is recoverable but wastes a full deploy cycle.

- [ ] **Step 6: Run the tests, including the size budget**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest,DeployerPolicyTest`
Expected: PASS, **including** `renderedPolicyLeavesRoomInAnInlinePolicyBudget`. If the budget test now fails, do not raise the limit — collapse the new action list using a `dynamodb:Describe*` wildcard, keeping `Create`/`Delete`/`Update` enumerated.

- [ ] **Step 7: Run the full reactor**

Run: `ASYNC_PATH=<a library that exists on this machine> mvn clean verify`
Expected: BUILD SUCCESS across all 6 modules.

- [ ] **Step 8: Commit**

```bash
git add infra/cf-template-core.yaml infra/deployer-policy.json \
        baas-cli/src/test/java/pl/wsztajerowski/baas/infra/
git commit -m "feat(infra): scope DynamoDB access for the runner, operator and deployer"
```

---

## Task 8: Deploy and verify — HUMAN GATE

**Files:** none. This mutates a live CloudFormation stack.

**Do not run this without explicit human approval.** Everything above is inert until deployed; this step is where it becomes real. It is safe by design — purely additive, Atlas untouched — but it changes a live stack and creates a **retained** resource that will outlive a teardown.

- [ ] **Step 1: Confirm the deployer identity**

```bash
AWS_PROFILE=baas-admin aws sts get-caller-identity
```

- [ ] **Step 2: Deploy**

```bash
baas admin setup
```

Expected: the stack updates and reports the new `ResultsTableName` output.

- [ ] **Step 3: Confirm the table exists, is on-demand, and is empty**

```bash
AWS_PROFILE=baas-admin aws dynamodb describe-table --table-name baas-3q7i7s65-results \
  --query 'Table.{status:TableStatus,billing:BillingModeSummary.BillingMode,items:ItemCount,gsi:GlobalSecondaryIndexes[].IndexName}'
```

Expected: `ACTIVE`, `PAY_PER_REQUEST`, `0` items, one index named `requestId-index`.

- [ ] **Step 4: Confirm the gateway endpoint is associated**

```bash
AWS_PROFILE=baas-admin aws ec2 describe-vpc-endpoints \
  --filters "Name=service-name,Values=com.amazonaws.eu-central-1.dynamodb" \
  --query 'VpcEndpoints[].{id:VpcEndpointId,type:VpcEndpointType,state:State,routes:RouteTableIds}'
```

Expected: one `Gateway` endpoint, `available`, with at least one route table id.

- [ ] **Step 5: Confirm Atlas is genuinely untouched — run a real benchmark**

This is the check that matters. The whole point of deferring 4.3 and 4.8 is that existing runs keep working.

```bash
baas run --skip-build --benchmark-jar fake-jmh-benchmarks/target/fake-jmh-benchmarks.jar \
  --tag phase=2-smoke jmh -- Incrementing_Synchronized -f 1 -wi 1 -i 3
```

Expected: the run completes, results print, and the measurement lands **in Atlas** as before. If it fails at the database write, 4.3 or 4.8 was applied by mistake — revert and redeploy.

- [ ] **Step 6: Tick §3 and the additive §4 items in `tasks.md`**

Tick 3.1-3.9, 4.1, 4.2, 4.4, 4.5, 4.6, 4.7, 4.9, 4.10. **Leave 4.3 and 4.8 unticked** and annotate them with a pointer to the sequencing hazard.

```bash
git add openspec/changes/dynamodb-results-store/tasks.md
git commit -m "docs(openspec): mark dynamodb-results-store §3 and additive §4 complete"
```

---

## Remaining phases — each needs its own plan

Do not attempt these from this document; the detail is not here.

### Phase 3 — §9 Data migration (8 tasks)

Writes history into the table before reads switch to it. **Blocked on two decisions:**

- **§1.6**: what `project` do the 41 untagged rows get? Recommended `unknown`; note it collides with `currentGitCommit()`'s existing `unknown` fallback, so decide whether the two should be distinguishable.
- **§9.4**: map or drop the `source` tag (36 rows, `gha-e2e-test*`)?

Both are recorded in `apply.md`. `design.md`'s Open Question *"How large is the Atlas dataset?"* is now **answered** — 121 documents — and that answer should be folded back into `design.md`.

### Phase 4 — the atomic cutover (§5, §6, §7, §8, plus 4.3 and 4.8; 47 tasks)

**These cannot be split.** §5 makes the runner write to DynamoDB; §6 makes the CLI read it; §7 removes `--mongo-uri`. Landing any subset leaves results written to one store and read from another. 4.3 and 4.8 join here because 27017 egress and the Mongo SSM grant become dead only once §5 and §7.5 land.

Also fold in, from `apply.md`'s open items: `DeployerPreflight.java:68` and `TeardownCommand.java:99-104` hold Mongo references that no `tasks.md` item covers. They belong in §7 — add them as 7.11/7.12 when planning this phase.

Largest single risk: `RunCommand.call()` is executed by no test, so §7.6's "fail before provisioning when the table is unresolvable" has the same untestable shape that §2 hit. Plan a live verification step for it.

### Phase 5 — §10, §11, §12 (24 tasks)

Decommission, documentation, manual verification. §10.2 (decommission the Atlas cluster) is **irreversible** and must be gated on Phase 3's migration being verified — row counts matching and spot-checked scores identical. §11 must fold in the `CLAUDE.md` invariant changes, including retiring *"the mongo URI never goes into user-data"* and *"measurements live only in MongoDB"*.

---

## Out of scope, still open

`BENCHMARK_PARAMETERS` carries the same `eval` injection weakness that §2 fixed for tags, so an operator can still reach `RunnerRole`'s SSM read via a crafted benchmark parameter. It is outside all 108 task items and wants its own change. Recorded in `apply.md` and `finalize.md`.
