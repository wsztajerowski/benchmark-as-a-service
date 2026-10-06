# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: S3 is written before the store, and store failure fails the run`
- TO: `### Requirement: S3 is written before the store, and store failure fails the job`

- FROM: `### Requirement: Every run names a results store`
- TO: `### Requirement: Every job names a results store`

## MODIFIED Requirements

### Requirement: Results table configuration
The core stack SHALL create a DynamoDB table named `baas-<prefix>-results` with a partition key `pk` and
a sort key `sk`, both of type String, using on-demand billing. It SHALL declare exactly one global
secondary index, partitioned on `jobId`, and SHALL declare no TTL attribute. It SHALL carry
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
a JCStress job SHALL be one item. It SHALL NOT write derived, denormalized or index items alongside a
result.

#### Scenario: A JMH run writes one item per benchmark method
- **WHEN** a job producing three JMH benchmark methods is stored
- **THEN** the table contains exactly three new items

#### Scenario: No derived items accompany a result
- **WHEN** a result carrying four tags is stored
- **THEN** the table contains exactly one new item, and no tag-, benchmark- or time-index item

### Requirement: Item key encoding
A measurement SHALL be stored at `pk = RESULT#<project>`. A JMH measurement SHALL use
`sk = <fullyQualifiedClassName>#<methodName>#<mode>#<createdAt>#<jobId>`, followed by
`#<params>` when the benchmark declares `@Param`s, where `<params>` is every resolved parameter as
`name=value`, sorted by name and joined by `,`. A benchmark without params SHALL have no such segment, so
its key is unchanged. A JCStress measurement SHALL use `sk = JCSTRESS#<createdAt>#<jobId>`. The global
secondary index SHALL be partitioned on `jobId` with sort key
`<fullyQualifiedClassName>#<methodName>#<mode>`, which carries no params. A JMH item SHALL carry its
resolved params as a `params` map, omitted when there are none.

#### Scenario: Results of one project share a partition
- **WHEN** results from three separate jobs of the same project are stored
- **THEN** every item has `pk = RESULT#<project>` and a distinct `sk`

#### Scenario: Sort key orders benchmark-major then chronologically
- **WHEN** one benchmark method has results from three different times
- **THEN** those items are adjacent in sort-key order and ordered by `createdAt` within the benchmark

#### Scenario: Every variant of a parameter sweep is stored
- **WHEN** one job of a benchmark declaring `@Param size` with values `10` and `1000` is stored in one write
- **THEN** two items exist, whose sort keys end in `#size=10` and `#size=1000`, and each carries its own
  `params` map

#### Scenario: A benchmark without params keeps its key
- **WHEN** a benchmark that declares no `@Param` is stored
- **THEN** its sort key ends in `#<jobId>` and its item has no `params` attribute

#### Scenario: A run's results are reachable by request ID
- **WHEN** the global secondary index is queried for a job ID
- **THEN** every measurement from that job is returned

### Requirement: Timestamps sort chronologically as strings
`createdAt` SHALL be stored as a fixed-width UTC ISO-8601 instant, so that lexicographic ordering of sort
keys equals chronological ordering. When the job was launched by `baas run`, `createdAt` SHALL be the
instant the CLI minted for the job rather than an instant read on the benchmark instance, so that a job's
identifier and its measurements' timestamps cannot disagree.

#### Scenario: Lexicographic order matches chronological order
- **WHEN** timestamps spanning a month boundary and a year boundary are formatted and sorted as strings
- **THEN** the resulting order is identical to their chronological order

#### Scenario: The stored timestamp is the launching CLI's instant
- **WHEN** `baas run` launches a job and the instance's clock differs from the launching machine's
- **THEN** every stored measurement's `createdAt` is the launching machine's instant

### Requirement: Items hold only the queryable summary
A measurement item SHALL contain the attributes needed to filter results and render output: benchmark
name, benchmark type, mode, score, score error, score unit, `createdAt`, `jobId`, `tags`,
`resultPath`, `resultJsonKey` and `environmentJsonKey`. `secondaryMetrics` SHALL be reduced to a map of
metric name to score and unit. The item SHALL NOT contain `rawData` or `scorePercentiles`.

#### Scenario: Heavy fields are absent from the item
- **WHEN** a JMH result with populated `rawData` and `scorePercentiles` is stored
- **THEN** the stored item contains neither attribute, and its serialized size is well under 400 KB

#### Scenario: Oversized items fail loudly
- **WHEN** a measurement would serialize to more than the DynamoDB item limit
- **THEN** the write fails with an error naming the offending measurement, rather than being truncated

#### Scenario: A JCStress run keeps its summary shape
- **WHEN** a JCStress job is stored
- **THEN** its item carries `totalTests`, `passedTests` and the failed, error and interesting test maps

### Requirement: Tags are the queryable dimensions, with a shared known-key vocabulary
The runner SHALL record `project`, `type`, `jdk`, `jvmVendor`, `cpuModel`, `cpuArch`, `instanceType` and
`imageVersion` as tags on every measurement. It SHALL record `commit`, `branch` and `source` on every
measurement for which they are supplied, and SHALL omit them otherwise rather than storing a
placeholder value standing in for an unknown one. These key names SHALL be defined once as constants
in the shared model module and used by both the runner and the CLI. `branch` and `source` SHALL be
caller-supplied, like `project` and `commit`, rather than machine-observed. `commit` and `branch` SHALL be
supplied only as caller tags; `baas run` SHALL NOT derive them. `source` SHALL identify
how the job was triggered; `baas run` SHALL derive it as `ci` when it detects a continuous-integration
environment and `local` otherwise, and an explicitly supplied value SHALL win over the derived one.
`imageVersion`, `instanceType`, `jdk`, `jvmVendor`, `cpuModel`, `cpuArch` and `type` SHALL NOT be
settable by the caller: `baas run` SHALL reject a `--tag` naming any of them before launching anything.
Tag keys outside the vocabulary SHALL be permitted, and a query naming an unknown key SHALL produce a
warning rather than silently returning nothing.

#### Scenario: Environment tags are observed on the instance
- **WHEN** a benchmark runs on an instance
- **THEN** its stored measurement carries `jdk`, `jvmVendor`, `cpuModel`, `cpuArch` and `instanceType`
  values matching that job's `environment.json`

#### Scenario: Results can be grouped by JVM vendor
- **WHEN** results measured on two vendors' builds of the same Java version exist for one benchmark and
  `jvmVendor` is the grouping tag
- **THEN** they are reported as separate groups rather than merged

#### Scenario: A caller cannot set the JVM vendor
- **WHEN** `baas run --tag jvmVendor=Acme jmh -- MyBenchmark` is invoked
- **THEN** the command exits non-zero naming `jvmVendor` as reserved, and no instance is launched

#### Scenario: Branch is recorded as a tag
- **WHEN** a job is launched with `--tag branch=main`
- **THEN** its stored measurement carries a `branch` tag, and that tag is usable as a filter

#### Scenario: An unsupplied commit or branch is absent, not a placeholder
- **WHEN** a job is launched with no `commit` and no `branch` tag
- **THEN** its stored measurement carries neither key, and no stored value stands in for them

#### Scenario: Unknown tag key warns
- **WHEN** `baas results --project p --tag jvm=21` is queried and no measurement uses the key `jvm`
- **THEN** the command reports that `jvm` is not a known tag key and lists the known keys

#### Scenario: Custom tags are stored and queryable
- **WHEN** a job is invoked with `--tag branch=main --tag experiment=gc-tuning`
- **THEN** both tags are present on the stored measurement and both are usable as filters

#### Scenario: A laptop run is tagged as local
- **WHEN** `baas run` is invoked outside a continuous-integration environment with no `source` tag
- **THEN** the stored measurement carries `source=local`

#### Scenario: A continuous-integration run is tagged as such
- **WHEN** `baas run` is invoked from a continuous-integration environment with no `source` tag
- **THEN** the stored measurement carries `source=ci`

#### Scenario: An explicit source wins over the derived one
- **WHEN** `baas run --tag source=nightly` is invoked
- **THEN** the stored measurement carries `source=nightly`, and the command does not reject the tag

#### Scenario: Source is usable as a grouping dimension
- **WHEN** results carrying `source=ci` and `source=local` exist for one benchmark and `source` is the
  grouping tag
- **THEN** they are reported as separate groups rather than merged

### Requirement: User tags reach the stored result, not only the instance
`baas run` SHALL pass every `--tag` value through user-data to the runner, so that it is recorded on the
stored measurement. Applying a tag only to the EC2 instance SHALL NOT satisfy this requirement.

#### Scenario: A user tag is present on the stored result
- **WHEN** `baas run --tag branch=main jmh -- MyBenchmark` completes
- **THEN** the stored measurement's tags contain `branch=main`

#### Scenario: Rendered user-data carries the tag
- **WHEN** the user-data script is rendered for a job carrying two user tags
- **THEN** the runner invocation in the script includes a `--tag` argument for each of them

### Requirement: The verbatim JMH result JSON is preserved in S3
The runner SHALL upload the unmodified JMH result JSON to the job's S3 result path and SHALL record its
key on the measurement as `resultJsonKey`.

#### Scenario: Full fidelity is retrievable
- **WHEN** a JMH job completes
- **THEN** the job's S3 result path contains the verbatim JMH JSON, and `resultJsonKey` on every
  measurement from that job resolves to it

#### Scenario: Data dropped from the item is present in the JSON
- **WHEN** the object at `resultJsonKey` is parsed
- **THEN** it contains the `rawData` and `scorePercentiles` omitted from the item

### Requirement: S3 is written before the store, and store failure fails the job
The runner SHALL upload result artifacts to S3 before writing to the results store. It SHALL retry the
store write with backoff, and when the write ultimately fails it SHALL exit non-zero so the job item
records a failure and the S3 artifacts remain available for re-import.

#### Scenario: Store failure is not reported as success
- **WHEN** every store write attempt fails
- **THEN** the runner exits non-zero and the job item reads `failed:<exitCode>`

#### Scenario: Artifacts survive a store failure
- **WHEN** the store write fails after the S3 upload succeeded
- **THEN** the result JSON and process output are still present at the job's S3 result path

### Requirement: Every job names a results store
The runner SHALL require exactly one of `--results-table` or `--mongo-connection-string`, and SHALL fail
before executing any benchmark when neither or both are given. No option SHALL select a store that
discards measurements. Local runner invocations SHALL name a table on a local endpoint such as LocalStack.

#### Scenario: Missing configuration fails fast
- **WHEN** the runner is invoked with no table name and no connection string
- **THEN** it exits non-zero before running any benchmark, naming the missing configuration

#### Scenario: The discard option is gone
- **WHEN** the runner is invoked with `--no-database`
- **THEN** it rejects the option as unknown and runs no benchmark

#### Scenario: A local run names a local table
- **WHEN** a benchmark is run locally with `--results-table` and a DynamoDB endpoint override
- **THEN** its measurements are written to that local table
