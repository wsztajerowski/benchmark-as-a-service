## ADDED Requirements

### Requirement: Results table configuration
The core stack SHALL create a DynamoDB table named `baas-<prefix>-results` with a partition key `pk` and
a sort key `sk`, both of type String, using on-demand billing. It SHALL declare **no** global secondary
indexes and **no** TTL attribute, and SHALL carry `DeletionPolicy: Retain` and
`UpdateReplacePolicy: Retain`.

#### Scenario: Table is created with the expected key schema
- **WHEN** the core stack is deployed
- **THEN** the table exists with String `pk` as partition key and String `sk` as sort key, on-demand
  billing, and no global secondary indexes

#### Scenario: Benchmark history survives teardown
- **WHEN** `baas admin teardown --yes` deletes the core stack
- **THEN** the stack reaches `DELETE_COMPLETE` and the results table still exists with its items intact

### Requirement: Result item key encoding
A JMH result SHALL be stored at `pk = RUN#<requestId>` and
`sk = BENCH#<fullyQualifiedClassName>#<methodName>#<benchmarkType>`. A JCStress result SHALL be stored at
`pk = RUN#<requestId>` and `sk = JCSTRESS`.

#### Scenario: All results of one run share a partition
- **WHEN** a run producing three JMH benchmarks is stored
- **THEN** all three items have `pk = RUN#<requestId>` and distinct `sk` values

#### Scenario: JCStress keeps one item per request
- **WHEN** a JCStress result is stored for a request that already has one
- **THEN** the existing item is overwritten, preserving the current one-result-per-request behaviour

### Requirement: Result items hold only the queryable summary
A result item SHALL contain the attributes needed to answer queries and render output: benchmark name,
benchmark type, mode, score, score error, score unit, threads, forks, JMH version, JDK version, VM name,
VM version, warmup and measurement counts, `createdAt` as an ISO-8601 string, `tags`,
`profilerOutputPaths`, and `resultJsonKey`. It SHALL NOT contain `rawData` or `scorePercentiles`, and
`secondaryMetrics` SHALL be reduced to a map of metric name to score and unit.

#### Scenario: Heavy fields are absent from the item
- **WHEN** a JMH result with populated `rawData` and `scorePercentiles` is stored
- **THEN** the stored item contains neither attribute, and its serialized size is well under 400 KB

#### Scenario: Secondary metrics are summarised
- **WHEN** a result with three secondary metrics is stored
- **THEN** the item holds three entries, each with only a score and a unit

### Requirement: The verbatim JMH result JSON is preserved in S3
The runner SHALL upload the unmodified JMH result JSON to the run's S3 result path and SHALL record its
key on the result item as `resultJsonKey`.

#### Scenario: Full fidelity is retrievable
- **WHEN** a JMH run completes
- **THEN** the run's S3 result path contains the verbatim JMH JSON, and `resultJsonKey` on every result
  item from that run resolves to it

#### Scenario: Data dropped from the item is present in the JSON
- **WHEN** the object at `resultJsonKey` is parsed
- **THEN** it contains the `rawData` and `scorePercentiles` omitted from the DynamoDB item

### Requirement: Index items are derived from each result
For each result item the store SHALL derive and write: one tag-index item per tag at
`pk = TAG#<key>#<value>`, `sk = <createdAt>#<requestId>#<benchmarkName>`; one benchmark-index item at
`pk = BENCH#<fullyQualifiedClassName>`, `sk = <methodName>#<createdAt>#<requestId>`; and one time-index
item at `pk = ALL#<yyyy-mm>`, `sk = <createdAt>#<requestId>#<benchmarkName>`. Tag-index items SHALL NOT
be written for a reserved set of tag keys.

#### Scenario: Index items accompany a result
- **WHEN** a JMH result carrying four tags is stored
- **THEN** the table also contains four tag-index items, one benchmark-index item, and one time-index item

#### Scenario: Branch is queryable because it is a tag
- **WHEN** a result tagged `branch=main` is stored
- **THEN** a tag-index item exists at `pk = TAG#branch#main`

#### Scenario: Time index is month-partitioned
- **WHEN** a result created in March 2027 is stored
- **THEN** its time-index item has `pk = ALL#2027-03`

### Requirement: Index items carry a display projection
Each index item SHALL project the attributes needed to render a result row without a follow-up fetch:
benchmark name, benchmark type, mode, score, score error, score unit, `createdAt`, `requestId`, `branch`,
`amiId`, and `excludeFromResults`.

#### Scenario: A query needs one round trip
- **WHEN** a branch query returns index items
- **THEN** every column of the rendered output is available from those items alone

### Requirement: Writes are batched and idempotent
The store SHALL write a result item and its derived index items in a single `BatchWriteItem` request per
result. Because every key is derived deterministically from the result, a retried or repeated write SHALL
converge to the same item set rather than creating duplicates.

#### Scenario: One batch per result
- **WHEN** a result with four tags is stored
- **THEN** a single `BatchWriteItem` containing seven items is issued

#### Scenario: Repeated write is idempotent
- **WHEN** the same result is stored twice
- **THEN** the table contains the same item count as after the first write

### Requirement: S3 is written before the store, and store failure fails the run
The runner SHALL upload the result JSON to S3 before writing to DynamoDB. It SHALL retry the DynamoDB
write with backoff, and when the write ultimately fails it SHALL exit non-zero so the `run-status`
sentinel records a failure and the S3 artifacts remain available for re-import.

#### Scenario: Store failure is not reported as success
- **WHEN** every DynamoDB write attempt fails
- **THEN** the runner exits non-zero and `run-status` in S3 reads `failed:<exitCode>`

#### Scenario: Artifacts survive a store failure
- **WHEN** the store write fails after the S3 upload succeeded
- **THEN** the result JSON and process output are still present at the run's S3 result path

### Requirement: The stored shape is defined once and shared
The stored item shapes, key encoding, attribute-name constants, and the attribute-value mapper SHALL live
in a single module depended on by both `benchmark-runner` and `baas-cli`. Neither module SHALL address
stored attributes by locally-declared string literals.

#### Scenario: Key encoding has one definition
- **WHEN** the key-encoding function is changed incompatibly
- **THEN** both dependent modules fail to compile or their tests fail, rather than queries silently
  returning no rows

#### Scenario: Round trip preserves the result
- **WHEN** a result item is mapped to attribute values and back
- **THEN** the reconstructed item equals the original

### Requirement: The store interface exposes writes only
The store interface SHALL expose a single write operation taking a result. It SHALL NOT expose a
field-path-based update or upsert operation.

#### Scenario: No upsert surface exists
- **WHEN** the store interface is inspected
- **THEN** it declares no update, upsert, or field-path mutation method

### Requirement: Discarding results requires an explicit opt-in
A no-op store SHALL be selected only when `--no-database` is passed. A missing or empty table name SHALL
be a hard failure before any benchmark is executed.

#### Scenario: Missing table name fails fast
- **WHEN** the runner is invoked with no table name and no `--no-database`
- **THEN** it exits non-zero before running any benchmark, naming the missing configuration

#### Scenario: Explicit opt-in discards results
- **WHEN** the runner is invoked with `--no-database`
- **THEN** the benchmark runs, no DynamoDB write is attempted, and the run reports success
