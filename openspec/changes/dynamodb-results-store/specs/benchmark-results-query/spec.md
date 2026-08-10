## ADDED Requirements

### Requirement: Results for one run are queryable by request ID
`baas results --request-id <id>` SHALL return every result of that run using a single `Query` on
`pk = RUN#<id>`, without a table scan.

#### Scenario: All benchmarks of a run are returned
- **WHEN** a run produced three JMH benchmarks and `--request-id` names it
- **THEN** three rows are returned

#### Scenario: Unknown request ID returns nothing
- **WHEN** `--request-id` names a run that does not exist
- **THEN** no rows are returned and the command exits 0

### Requirement: Results are queryable by branch, newest first
`baas results --branch <name>` SHALL query `pk = TAG#branch#<name>` in descending sort-key order, so the
most recent results are returned first, without a table scan.

#### Scenario: Newest results come first
- **WHEN** three runs on `main` were stored at different times and `--branch main` is queried
- **THEN** rows are returned in descending `createdAt` order

#### Scenario: Branch query does not scan
- **WHEN** `--branch main` is queried
- **THEN** the operation issued is a `Query`, not a `Scan`

### Requirement: Results are queryable by benchmark over time
`baas results --benchmark <fullyQualifiedClassName>` SHALL query `pk = BENCH#<fqcn>`, returning every
method of that class. When a method is also given, the query SHALL additionally constrain the sort key
with `begins_with(<methodName>#)`.

#### Scenario: All methods of a class
- **WHEN** a class has results for two benchmark methods and only the class is given
- **THEN** results for both methods are returned

#### Scenario: One method's history
- **WHEN** a class and one method are given
- **THEN** only that method's results are returned, ordered by `createdAt`

### Requirement: Results are queryable by arbitrary tag
`baas results --tag <key>=<value>` SHALL query `pk = TAG#<key>#<value>` in descending order, for any tag
key that was recorded, without a table scan.

#### Scenario: Project tag is queryable
- **WHEN** results tagged `project=lynx-journal` exist and `--tag project=lynx-journal` is queried
- **THEN** those results are returned

#### Scenario: Arbitrary custom tag is queryable
- **WHEN** results tagged `experiment=gc-tuning` exist and `--tag experiment=gc-tuning` is queried
- **THEN** those results are returned without a table scan

### Requirement: Recent results are available without a filter
`baas results` with no filter SHALL query the month-partitioned time index in descending order, walking
back through earlier months until the requested limit is satisfied or the archive is exhausted.

#### Scenario: Recent results without a scan
- **WHEN** `baas results` is invoked with no filter
- **THEN** rows are returned newest-first and no `Scan` is issued

#### Scenario: Limit spans a month boundary
- **WHEN** the current month holds fewer results than the requested limit
- **THEN** the previous month is queried to make up the difference

### Requirement: Excluded results are filtered out
`baas results` SHALL omit results tagged `exclude_from_results=true`, applying the filter server-side via
a filter expression on the projected `excludeFromResults` attribute.

#### Scenario: Excluded run is omitted
- **WHEN** a branch holds two results, one tagged `exclude_from_results=true`
- **THEN** only the other is returned

#### Scenario: Filter is applied server-side
- **WHEN** a query runs
- **THEN** the request carries a filter expression on `excludeFromResults`

### Requirement: Results are grouped with the best score kept
`baas results` SHALL group returned rows by `(benchmark, branch)` and keep only the highest-scoring row
per group. Grouping and selection SHALL be applied client-side over the returned rows, since DynamoDB
performs no aggregation.

#### Scenario: Best of several runs is kept
- **WHEN** the same benchmark on the same branch has three results with different scores
- **THEN** one row is returned, carrying the highest score

#### Scenario: Same benchmark on two branches stays separate
- **WHEN** a benchmark has results on `main` and on a feature branch
- **THEN** two rows are returned, one per branch

### Requirement: Benchmark matching is exact or prefix, not regular expression
`--benchmark` SHALL match a fully-qualified class name exactly, optionally narrowed by a method prefix. It
SHALL NOT accept a regular expression, and an input that was previously valid as a regular expression
SHALL fail with a message naming the supported forms.

#### Scenario: Regular expression is rejected clearly
- **WHEN** `--benchmark ".*Queue.*"` is given
- **THEN** the command exits non-zero explaining that exact class names and method prefixes are supported

#### Scenario: Exact class name matches
- **WHEN** `--benchmark pl.wsztajerowski.MyBenchmark` is given and results exist
- **THEN** those results are returned

### Requirement: Command payloads stay on standard output
`baas results` SHALL continue to write its table, JSON, and CSV payloads to `System.out` rather than
through the logger, so `--format json | jq` and `--format csv > file` remain usable.

#### Scenario: JSON output is machine-readable
- **WHEN** `baas results --format json` is piped to a JSON parser
- **THEN** the parser succeeds, with no timestamp or log-level prefix on any line

#### Scenario: Diagnostics do not corrupt the payload
- **WHEN** `baas results -v --format csv` is redirected to a file
- **THEN** the file contains only CSV, and verbose diagnostics appear on standard error
