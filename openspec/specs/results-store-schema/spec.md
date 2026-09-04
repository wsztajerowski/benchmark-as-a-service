# results-store-schema Specification

## Purpose
TBD - created by archiving change dynamodb-results-store. Update Purpose after archive.

## Requirements

### Requirement: Results table configuration
The core stack SHALL create a DynamoDB table named `baas-<prefix>-results` with a partition key `pk` and
a sort key `sk`, both of type String, using on-demand billing. It SHALL declare exactly one global
secondary index, partitioned on `requestId`, and SHALL declare no TTL attribute. It SHALL carry
`DeletionPolicy: Retain` and `UpdateReplacePolicy: Retain`.

#### Scenario: Table is created with the expected key schema
- **WHEN** the core stack is deployed
- **THEN** the table exists with String `pk` as partition key, String `sk` as sort key, on-demand
  billing, and exactly one global secondary index

#### Scenario: Benchmark history survives teardown
- **WHEN** `baas admin teardown --yes` deletes the core stack
- **THEN** the stack reaches `DELETE_COMPLETE` and the results table still exists with its items intact

### Requirement: One item per measurement
The store SHALL write exactly one item per measurement. A JMH benchmark method result SHALL be one item;
a JCStress run SHALL be one item. It SHALL NOT write derived, denormalized or index items alongside a
result.

#### Scenario: A JMH run writes one item per benchmark method
- **WHEN** a run producing three JMH benchmark methods is stored
- **THEN** the table contains exactly three new items

#### Scenario: No derived items accompany a result
- **WHEN** a result carrying four tags is stored
- **THEN** the table contains exactly one new item, and no tag-, benchmark- or time-index item

### Requirement: Item key encoding
A measurement SHALL be stored at `pk = RESULT#<project>`. A JMH measurement SHALL use
`sk = <fullyQualifiedClassName>#<methodName>#<createdAt>#<requestId>`; a JCStress measurement SHALL use
`sk = JCSTRESS#<createdAt>#<requestId>`. The global secondary index SHALL be partitioned on `requestId`
with sort key `<fullyQualifiedClassName>#<methodName>`.

#### Scenario: Results of one project share a partition
- **WHEN** results from three separate runs of the same project are stored
- **THEN** every item has `pk = RESULT#<project>` and a distinct `sk`

#### Scenario: Sort key orders benchmark-major then chronologically
- **WHEN** one benchmark method has results from three different times
- **THEN** those items are adjacent in sort-key order and ordered by `createdAt` within the benchmark

#### Scenario: A run's results are reachable by request ID
- **WHEN** the global secondary index is queried for a request ID
- **THEN** every measurement from that run is returned

### Requirement: Timestamps sort chronologically as strings
`createdAt` SHALL be stored as a fixed-width UTC ISO-8601 instant, so that lexicographic ordering of sort
keys equals chronological ordering.

#### Scenario: Lexicographic order matches chronological order
- **WHEN** timestamps spanning a month boundary and a year boundary are formatted and sorted as strings
- **THEN** the resulting order is identical to their chronological order

### Requirement: `project` is derived from the git repository name
`baas run` SHALL derive `project` from the name of the git repository it is invoked in, and SHALL accept
a `--project` option overriding it. A measurement SHALL NOT be written when `project` cannot be resolved.

#### Scenario: Project defaults to the repository name
- **WHEN** `baas run` is invoked inside a repository named `lynx-journal` with no `--project`
- **THEN** the stored measurement has `pk = RESULT#lynx-journal`

#### Scenario: Explicit override wins
- **WHEN** `--project other-name` is given
- **THEN** the stored measurement has `pk = RESULT#other-name`

### Requirement: Items hold only the queryable summary
A measurement item SHALL contain the attributes needed to filter results and render output: benchmark
name, benchmark type, mode, score, score error, score unit, `createdAt`, `requestId`, `tags`,
`resultPath`, `resultJsonKey` and `environmentJsonKey`. `secondaryMetrics` SHALL be reduced to a map of
metric name to score and unit. The item SHALL NOT contain `rawData` or `scorePercentiles`.

#### Scenario: Heavy fields are absent from the item
- **WHEN** a JMH result with populated `rawData` and `scorePercentiles` is stored
- **THEN** the stored item contains neither attribute, and its serialized size is well under 400 KB

#### Scenario: Oversized items fail loudly
- **WHEN** a measurement would serialize to more than the DynamoDB item limit
- **THEN** the write fails with an error naming the offending measurement, rather than being truncated

#### Scenario: A JCStress run keeps its summary shape
- **WHEN** a JCStress run is stored
- **THEN** its item carries `totalTests`, `passedTests` and the failed, error and interesting test maps

### Requirement: Tags are the queryable dimensions, with a shared known-key vocabulary
The runner SHALL record `project`, `type`, `commit`, `jdk`, `cpuModel`, `cpuArch`, `instanceType` and
`imageVersion` as tags on every measurement. These key names SHALL be defined once as constants in the
shared model module and used by both the runner and the CLI. Tag keys outside the vocabulary SHALL be
permitted, and a query naming an unknown key SHALL produce a warning rather than silently returning
nothing.

#### Scenario: Environment tags are observed on the instance
- **WHEN** a benchmark runs on an instance
- **THEN** its stored measurement carries `jdk`, `cpuModel`, `cpuArch` and `instanceType` values matching
  that run's `environment.json`

#### Scenario: Unknown tag key warns
- **WHEN** `baas results --tag jvm=21` is queried and no measurement uses the key `jvm`
- **THEN** the command reports that `jvm` is not a known tag key and lists the known keys

#### Scenario: Custom tags are stored and queryable
- **WHEN** a run is invoked with `--tag branch=main --tag experiment=gc-tuning`
- **THEN** both tags are present on the stored measurement and both are usable as filters

### Requirement: User tags reach the stored result, not only the instance
`baas run` SHALL pass every `--tag` value through user-data to the runner, so that it is recorded on the
stored measurement. Applying a tag only to the EC2 instance SHALL NOT satisfy this requirement.

#### Scenario: A user tag is present on the stored result
- **WHEN** `baas run --tag branch=main jmh -- MyBenchmark` completes
- **THEN** the stored measurement's tags contain `branch=main`

#### Scenario: Rendered user-data carries the tag
- **WHEN** the user-data script is rendered for a run carrying two user tags
- **THEN** the runner invocation in the script includes a `--tag` argument for each of them

### Requirement: The verbatim JMH result JSON is preserved in S3
The runner SHALL upload the unmodified JMH result JSON to the run's S3 result path and SHALL record its
key on the measurement as `resultJsonKey`.

#### Scenario: Full fidelity is retrievable
- **WHEN** a JMH run completes
- **THEN** the run's S3 result path contains the verbatim JMH JSON, and `resultJsonKey` on every
  measurement from that run resolves to it

#### Scenario: Data dropped from the item is present in the JSON
- **WHEN** the object at `resultJsonKey` is parsed
- **THEN** it contains the `rawData` and `scorePercentiles` omitted from the item

### Requirement: Writes are idempotent
Because every key is derived deterministically from the measurement, a retried or repeated write SHALL
converge to the same item set rather than creating duplicates.

#### Scenario: Repeated write is idempotent
- **WHEN** the same result is stored twice
- **THEN** the table contains the same item count as after the first write

### Requirement: S3 is written before the store, and store failure fails the run
The runner SHALL upload result artifacts to S3 before writing to the results store. It SHALL retry the
store write with backoff, and when the write ultimately fails it SHALL exit non-zero so the `run-status`
sentinel records a failure and the S3 artifacts remain available for re-import.

#### Scenario: Store failure is not reported as success
- **WHEN** every store write attempt fails
- **THEN** the runner exits non-zero and `run-status` in S3 reads `failed:<exitCode>`

#### Scenario: Artifacts survive a store failure
- **WHEN** the store write fails after the S3 upload succeeded
- **THEN** the result JSON and process output are still present at the run's S3 result path

### Requirement: The stored shape is defined once and shared
The item shape, key encoding, tag-key vocabulary and the attribute-value mapper SHALL live in a single
module depended on by both `benchmark-runner` and `baas-cli`. Neither module SHALL address stored
attributes by locally-declared string literals. That module SHALL NOT depend on any MongoDB library.

#### Scenario: Key encoding has one definition
- **WHEN** the key-encoding function is changed incompatibly
- **THEN** both dependent modules fail to compile or their tests fail, rather than queries silently
  returning no rows

#### Scenario: Round trip preserves the measurement
- **WHEN** a measurement is mapped to attribute values and back
- **THEN** the reconstructed measurement equals the original

#### Scenario: The shared module stays storage-neutral
- **WHEN** the shared module's dependencies are inspected
- **THEN** no MongoDB driver or object-mapping library is present

### Requirement: The store interface exposes writes only
The store interface SHALL expose a single write operation taking a measurement. It SHALL NOT expose a
field-path-based update or upsert operation, and SHALL NOT expose storage-specific concepts such as
index items or key encoding.

#### Scenario: No upsert surface exists
- **WHEN** the store interface is inspected
- **THEN** it declares no update, upsert, or field-path mutation method

#### Scenario: The interface is storage-neutral
- **WHEN** the store interface is inspected
- **THEN** its signatures name domain types only, with no DynamoDB or MongoDB type in any position

### Requirement: MongoDB remains available as a runner-local adapter
`benchmark-runner` SHALL retain a MongoDB-backed implementation of the store port, selectable when the
JAR is used standalone. `baas-cli` SHALL NOT offer, reference or depend on MongoDB, and `baas run` SHALL
NOT be able to select it.

#### Scenario: Standalone runner writes to MongoDB
- **WHEN** `benchmark-runner.jar` is invoked directly with a MongoDB connection string
- **THEN** the benchmark runs and its measurements are written to that MongoDB

#### Scenario: The CLI has no MongoDB surface
- **WHEN** `baas` and its subcommands are inspected for options and dependencies
- **THEN** no MongoDB option, connection string or driver dependency is present

#### Scenario: Both adapters satisfy the same contract
- **WHEN** the store contract test suite is run against the DynamoDB adapter and the MongoDB adapter
- **THEN** both pass it

### Requirement: Discarding results requires an explicit opt-in
A no-op store SHALL be selected only when `--no-database` is passed. Missing or empty store configuration
SHALL be a hard failure before any benchmark is executed.

#### Scenario: Missing configuration fails fast
- **WHEN** the runner is invoked with no table name, no connection string and no `--no-database`
- **THEN** it exits non-zero before running any benchmark, naming the missing configuration

#### Scenario: Explicit opt-in discards results
- **WHEN** the runner is invoked with `--no-database`
- **THEN** the benchmark runs, no store write is attempted, and the run reports success
