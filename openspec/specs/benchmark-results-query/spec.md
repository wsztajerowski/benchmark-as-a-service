# benchmark-results-query Specification

## Purpose
TBD - created by archiving change dynamodb-results-store. Update Purpose after archive.

## Requirements

### Requirement: Results are loaded with a single query on the project partition
`baas results` SHALL retrieve its working set with one `Query` on `pk = RESULT#<project>`, and SHALL NOT
issue a `Scan`. Filters other than request ID SHALL be applied to the returned rows rather than by
selecting a different index.

#### Scenario: The unfiltered command does not scan
- **WHEN** `baas results` is invoked with no filter
- **THEN** the operation issued is a `Query` on the project partition, not a `Scan`

#### Scenario: Tag filters do not change the access path
- **WHEN** `baas results --tag branch=main` is invoked
- **THEN** the same project-partition `Query` is issued, with the tag applied as a predicate

### Requirement: Results for one run are queryable by request ID
`baas results --request-id <id>` SHALL return every measurement of that run using a single `Query` on the
request-ID index, without a table scan.

#### Scenario: All benchmarks of a run are returned
- **WHEN** a run produced three JMH benchmark methods and `--request-id` names it
- **THEN** three rows are returned

#### Scenario: Unknown request ID returns nothing
- **WHEN** `--request-id` names a run that does not exist
- **THEN** no rows are returned and the command exits 0

### Requirement: Results are filterable by any tag
`baas results --tag <key>=<value>` SHALL return measurements carrying that tag, for any tag key, whether
or not the key is in the known-key vocabulary.

#### Scenario: Known tag filters
- **WHEN** results tagged `jdk=25.0.4` exist and `--tag jdk=25.0.4` is queried
- **THEN** those results are returned

#### Scenario: Custom tag filters
- **WHEN** results tagged `experiment=gc-tuning` exist and `--tag experiment=gc-tuning` is queried
- **THEN** those results are returned

#### Scenario: Repeated tag options combine conjunctively
- **WHEN** `--tag jdk=25.0.4 --tag cpuArch=aarch64` is given
- **THEN** only measurements carrying both tags are returned

### Requirement: Benchmark name matching accepts a regular expression
`baas results --benchmark-name <pattern>` SHALL match the benchmark name as a regular expression, applied
to the rows returned by the partition query.

#### Scenario: Substring pattern matches
- **WHEN** `--benchmark-name Queue` is given and two benchmark classes contain that substring
- **THEN** measurements from both are returned

#### Scenario: Fully qualified name matches
- **WHEN** `--benchmark-name pl.wsztajerowski.MyBenchmark` is given and results exist
- **THEN** those results are returned

### Requirement: Living-branch filtering uses one query
`baas results --living-branches` SHALL filter the returned rows by the branches present in the current
git repository, using the same single partition query rather than one query per branch. When no
measurement carries a `branch` tag, the filter SHALL be a no-op rather than an error.

#### Scenario: Branch filtering issues one query
- **WHEN** `--living-branches` is invoked with eight remote branches present
- **THEN** exactly one `Query` is issued

#### Scenario: Untagged results are unaffected
- **WHEN** `--living-branches` is invoked and no measurement carries a `branch` tag
- **THEN** the command returns rows and exits 0

### Requirement: Excluded results are filtered out
`baas results` SHALL omit measurements tagged `exclude_from_results=true`, applying the filter server-side
via a filter expression.

#### Scenario: Excluded run is omitted
- **WHEN** a project holds two results for a benchmark, one tagged `exclude_from_results=true`
- **THEN** only the other is returned

#### Scenario: Filter is applied server-side
- **WHEN** a query runs
- **THEN** the request carries a filter expression covering `exclude_from_results`

### Requirement: Results are grouped with the best score kept
`baas results` SHALL group returned rows by benchmark and a grouping tag, and keep only the
highest-scoring row per group. The grouping tag SHALL default to `branch`. Rows lacking the grouping tag
SHALL be collected into a single untagged group rather than dropped.

#### Scenario: Best of several runs is kept
- **WHEN** the same benchmark with the same grouping tag value has three results with different scores
- **THEN** one row is returned, carrying the highest score

#### Scenario: Two grouping values stay separate
- **WHEN** a benchmark has results tagged `branch=main` and `branch=feature-x`
- **THEN** two rows are returned, one per branch

#### Scenario: Untagged rows are not lost
- **WHEN** some measurements carry no `branch` tag
- **THEN** they are grouped together and reported, not silently discarded

### Requirement: A run's artifacts can be downloaded from S3
The CLI SHALL provide a command that downloads every S3 artifact for a run — the result JSON,
`environment.json`, process output, `packages.txt`, logs and profiling artifacts — to a local directory.

#### Scenario: Whole run is retrieved
- **WHEN** the download command is invoked for a completed run
- **THEN** the local directory contains the run's result JSON, `environment.json`, process output and any
  profiling artifacts

#### Scenario: Data absent from the item is recoverable
- **WHEN** a measurement's `rawData` is needed
- **THEN** it is available in the downloaded result JSON

#### Scenario: Unknown run reports clearly
- **WHEN** the download command names a run with no S3 prefix
- **THEN** the command exits non-zero naming the run, and creates no partial directory

### Requirement: Command payloads stay on standard output
`baas results` SHALL write its table, JSON and CSV payloads to `System.out` rather than through the
logger, so `--format json | jq` and `--format csv > file` remain usable.

#### Scenario: JSON output is machine-readable
- **WHEN** `baas results --format json` is piped to a JSON parser
- **THEN** the parser succeeds, with no timestamp or log-level prefix on any line

#### Scenario: Diagnostics do not corrupt the payload
- **WHEN** `baas results -v --format csv` is redirected to a file
- **THEN** the file contains only CSV, and verbose diagnostics appear on standard error
