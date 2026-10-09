## MODIFIED Requirements

### Requirement: Results for one job are queryable by job ID
`baas results query --job-id <id>` SHALL return every measurement of that job using a single `Query` on
the job-ID index, without a table scan. `--job-id` SHALL be an ordinary filter: it SHALL combine with
`--tag`, `--exclude-tag`, `--benchmark-name`, `--best-per` and the ordering and paging options, and
SHALL NOT combine with `--project` or `--all-projects`, which name a different access path. A job lookup
SHALL apply no row limit unless `--limit` is given.

#### Scenario: All benchmarks of a job are returned
- **WHEN** a job produced three JMH benchmark methods and `--job-id` names it
- **THEN** three rows are returned

#### Scenario: Unknown job ID returns nothing
- **WHEN** `--job-id` names a job that does not exist
- **THEN** no rows are returned and the command exits 0

#### Scenario: A job lookup takes further filters
- **WHEN** `baas results query --job-id <id> --benchmark-name Incrementing` names a job with three methods,
  one of them matching
- **THEN** one row is returned

#### Scenario: A large job is returned whole
- **WHEN** a job stored 48 measurements and `baas results query --job-id <id> --format json` is run without `--limit`
- **THEN** 48 objects are written to standard output, and nothing reports a cut

#### Scenario: An explicit limit still bounds a job lookup
- **WHEN** `baas results query --job-id <id> --limit 5` names a job with 48 measurements
- **THEN** five rows are returned, and standard error reports 5 of 48

### Requirement: Output carries a measurement's tags, and the table omits the score error
The `baas results query` table SHALL NOT include a score-error column, and its benchmark column SHALL NOT carry
params. JSON output SHALL carry `scoreError`, a `tags` object holding every tag of the measurement and a
`params` object holding its params (empty when there are none); `benchmarkName` SHALL stay the plain name.
CSV output SHALL carry a `scoreError` column, a `tags` column holding every tag as `key=value` pairs
separated by `;`, and, as its last column, a `params` column in the same form. With `-v`, each table row
SHALL be followed by an indented line labelled `params` listing its params, when it has any, and then an
indented line labelled `tags` listing every tag; without `-v`, no such line SHALL be printed. A score or
score error that the stored item does not carry, or that is not finite, SHALL be reported as unknown and
never as a number: JSON `null`, an empty CSV cell, and a de-emphasised table cell.

#### Scenario: Table has no error column
- **WHEN** `baas results query --project p` prints a table
- **THEN** no column is headed with the score error

#### Scenario: Machine formats keep the error and carry tags
- **WHEN** `baas results query --project p --format json` is invoked
- **THEN** each object carries `scoreError` and a `tags` object equal to the stored tag map

#### Scenario: Verbose table shows tags under each row
- **WHEN** `baas results query --project p -v` prints a table
- **THEN** each row is followed by an indented line labelled `tags` listing its tags as `key=value`

#### Scenario: Verbose table shows a sweep variant's params above its tags
- **WHEN** `baas results query --project p -v` prints a row whose params are `impl=hash` and `size=10`
- **THEN** the row is followed by `params  impl=hash  size=10`, then by its `tags` line

#### Scenario: Default table shows no tag lines
- **WHEN** `baas results query --project p` prints a table without `-v`
- **THEN** no tag line is printed

#### Scenario: A missing score is not reported as zero
- **WHEN** a stored measurement carries no score, and it is printed as JSON, as CSV and as a table
- **THEN** JSON carries `"score":null`, the CSV score cell is empty, and the table cell is de-emphasised rather than `0.000`

### Requirement: The best score per group is reported only on request
`--best-per <tag>` SHALL group the selected rows by project, benchmark, params, JMH mode and the value
of `<tag>`, and keep one row per group: the highest score for a throughput mode and the lowest for a
time-per-operation mode (average time, sample time, single shot). A non-finite score, and a score the
stored item does not carry, SHALL never win. Rows lacking `<tag>` SHALL be collected into a single
untagged group rather than dropped. `--best-per` SHALL require a value, and there SHALL be no separate
grouping option.

#### Scenario: Best of several jobs is kept
- **WHEN** the same throughput benchmark on `branch=main` has three results and `--best-per branch` is
  given
- **THEN** one row is returned, carrying the highest score

#### Scenario: Lower is better for time per operation
- **WHEN** an average-time benchmark on `branch=main` has results of 12 ns/op and 9 ns/op and
  `--best-per branch` is given
- **THEN** the 9 ns/op row is returned

#### Scenario: Modes are never compared
- **WHEN** one benchmark has a throughput result and an average-time result on one branch and
  `--best-per branch` is given
- **THEN** two rows are returned, one per mode

#### Scenario: Two grouping values stay separate
- **WHEN** a benchmark has results tagged `branch=main` and `branch=feature-x` and `--best-per branch`
  is given
- **THEN** two rows are returned, one per branch

#### Scenario: A sweep's variants are grouped separately
- **WHEN** a benchmark has results for `size=10` and `size=100000` on `branch=main` and
  `--best-per branch` is given
- **THEN** two rows are returned, one per variant

#### Scenario: Untagged rows are not lost
- **WHEN** some measurements carry no `branch` tag and `--best-per branch` is given
- **THEN** they are grouped together and reported, not silently discarded

#### Scenario: Two projects never share a group
- **WHEN** `--all-projects --best-per branch` reports the same benchmark and branch from two projects
- **THEN** two rows are returned, one per project

#### Scenario: A missing score never wins a lower-is-better group
- **WHEN** an average-time benchmark on `branch=main` has a result of 9 ns/op and a result whose score was not stored, and `--best-per branch` is given
- **THEN** the 9 ns/op row is returned
