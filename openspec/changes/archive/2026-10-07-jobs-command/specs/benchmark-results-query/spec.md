# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: `baas results --watch` follows results as they land`
- TO: `### Requirement: `baas results query --watch` follows results as they land`

## MODIFIED Requirements

### Requirement: Results for one job are queryable by job ID
`baas results query --job-id <id>` SHALL return every measurement of that job using a single `Query` on
the job-ID index, without a table scan. `--job-id` SHALL be an ordinary filter: it SHALL combine with
`--tag`, `--exclude-tag`, `--benchmark-name`, `--best-per` and the ordering and paging options, and
SHALL NOT combine with `--project` or `--all-projects`, which name a different access path.

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

### Requirement: Results are filterable by any tag
`baas results query --tag <key>=<value>` SHALL return measurements carrying that tag, for any tag key, whether
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
`baas results query --benchmark-name <pattern>` SHALL match the benchmark name as a regular expression, applied
to the rows returned by the partition query.

#### Scenario: Substring pattern matches
- **WHEN** `--benchmark-name Queue` is given and two benchmark classes contain that substring
- **THEN** measurements from both are returned

#### Scenario: Fully qualified name matches
- **WHEN** `--benchmark-name pl.wsztajerowski.MyBenchmark` is given and results exist
- **THEN** those results are returned

### Requirement: Excluded results are filtered out
`baas results query` SHALL omit measurements tagged `exclude_from_results=true` from a project query and
from `--all-projects`, applying the filter server-side via a filter expression. Exclusion SHALL apply to
those queries and to the filters layered on them, and SHALL NOT apply to a lookup that names one job by
its job ID: naming a job explicitly is a request for that job, not a query over the project's history. A
query naming a job that does not exist and a query naming an excluded job SHALL therefore be
distinguishable. `--show-excluded` SHALL lift the exclusion, and the table SHALL render excluded rows
de-emphasised when the output is interactive. The exclusion is its own rule, independent of
`--exclude-tag`.

#### Scenario: Excluded job is omitted
- **WHEN** a project holds two results for a benchmark, one tagged `exclude_from_results=true`
- **THEN** only the other is returned

#### Scenario: Filter is applied server-side
- **WHEN** a project query runs without `--show-excluded`
- **THEN** the request carries a filter expression covering `exclude_from_results`

#### Scenario: An explicit job lookup returns an excluded job
- **WHEN** `baas results query --job-id <id>` names a job whose measurements are tagged
  `exclude_from_results=true`
- **THEN** those measurements are returned

#### Scenario: Excluded rows stay out of grouping and tag filters
- **WHEN** `baas results query --project p --tag branch=main --best-per branch` is queried and one
  matching job is excluded
- **THEN** the excluded job's rows are absent from the result and from the grouping that follows it

#### Scenario: `--all-jobs` includes excluded rows
- **WHEN** `baas results query --project p --show-excluded` is queried and one job is excluded
- **THEN** that job's rows are returned, rendered de-emphasised on an interactive terminal

#### Scenario: Every project and every job is the whole table
- **WHEN** `baas results query --all-projects --show-excluded --limit 0` is invoked
- **THEN** every measurement in the table is reported

#### Scenario: A successful excluded job reports its own measurements
- **WHEN** `baas run` completes a job tagged `exclude_from_results=true` and prints its result summary
- **THEN** the summary lists that job's measurements rather than appearing empty

### Requirement: Command payloads stay on standard output
`baas results query` SHALL write its table, JSON and CSV payloads to `System.out` rather than through the
logger, so `--format json | jq` and `--format csv > file` remain usable.

#### Scenario: JSON output is machine-readable
- **WHEN** `baas results query --format json` is piped to a JSON parser
- **THEN** the parser succeeds, with no timestamp or log-level prefix on any line

#### Scenario: Diagnostics do not corrupt the payload
- **WHEN** `baas results query -v --format csv` is redirected to a file
- **THEN** the file contains only CSV, and verbose diagnostics appear on standard error

### Requirement: `baas results query --watch` follows results as they land
`baas results query` SHALL accept `--watch`, which repeats the command's query at a fixed interval and redraws
the table in place until the operator interrupts it. Each refresh SHALL issue the same queries as a
single `baas results query` invocation with the same filters. Warnings derived from the returned rows SHALL
be shown as part of the redrawn frame, below the table, rather than logged on every refresh.
`--watch` SHALL be refused, before any query is issued and with a non-zero exit, when the output is not
interactive or when `--format json` or `--format csv` is given.

#### Scenario: The table refreshes in place
- **WHEN** `baas results query --watch` runs on an interactive terminal and a new measurement is stored
- **THEN** a later refresh shows the new row in the same screen area, without scrolling earlier frames

#### Scenario: Watching a redirect is refused
- **WHEN** `baas results query --watch > out.txt` is invoked
- **THEN** the command exits non-zero with a message that `--watch` needs an interactive terminal, and
  issues no query

#### Scenario: Watching a machine format is refused
- **WHEN** `baas results query --watch --format json` is invoked
- **THEN** the command exits non-zero with a message that `--watch` applies to the table only

#### Scenario: A row-derived warning is shown once per frame
- **WHEN** `--tag experiment=x` is watched and no returned row carries the `experiment` tag
- **THEN** the unknown-tag warning appears below the table in each frame, and is not repeated as a log
  line on every refresh

#### Scenario: Interrupting leaves a clean terminal
- **WHEN** the operator presses Ctrl+C during `baas results query --watch`
- **THEN** the command exits with the last frame left on screen and the cursor on a fresh line

### Requirement: Results are read from one partition unless every project is requested
When a project is known — named by `--project` or derived — `baas results query` SHALL retrieve its working set
with one `Query` on `pk = RESULT#<project>` and SHALL NOT issue a `Scan`. A `Scan` of the table SHALL be
issued only to list projects for the project picker and to serve `--all-projects`. Filters other than
job ID SHALL be applied to the returned rows rather than by selecting a different index.

#### Scenario: A named project does not scan
- **WHEN** `baas results query --project lynx-journal` is invoked
- **THEN** the operation issued is a `Query` on the project partition, not a `Scan`

#### Scenario: Tag filters do not change the access path
- **WHEN** `baas results query --project lynx-journal --tag branch=main` is invoked
- **THEN** the same project-partition `Query` is issued, with the tag applied as a predicate

#### Scenario: Every project is one scan
- **WHEN** `baas results query --all-projects` is invoked
- **THEN** one paginated `Scan` is issued, and no per-project `Query`

### Requirement: A project is chosen when none is named
When `baas results query` is invoked with neither `--project`, `--all-projects` nor `--job-id`:
- if `git.resolveProject` is `true` and the working directory is inside a git repository, the project
  SHALL be that repository's name, resolving the main repository rather than a linked worktree;
- otherwise, when the output is interactive and the format is the table, the command SHALL list the
  projects holding at least one measurement not tagged `exclude_from_results=true` as a numbered list,
  together with a tip naming `baas config set --git-resolve-project true`, and SHALL read the operator's
  choice as a number followed by Enter;
- otherwise the command SHALL exit non-zero, listing the same projects and naming `--project` and
  `--all-projects`.

Under `--watch` the choice SHALL be made once, before the first frame.

#### Scenario: The picker offers projects with visible results
- **WHEN** the table holds projects `a` (non-excluded rows), `b` (only excluded rows) and `c`
  (non-excluded rows), and `baas results query` runs on an interactive terminal
- **THEN** `a` and `c` are offered as numbered entries, `b` is not, and a tip names
  `baas config set --git-resolve-project true`

#### Scenario: Choosing a number reports that project
- **WHEN** the operator enters the number shown beside `c`
- **THEN** the command reports `c`'s results exactly as `baas results query --project c` would

#### Scenario: An invalid choice is refused
- **WHEN** the operator enters a value that is not one of the listed numbers
- **THEN** the command exits non-zero without querying any project

#### Scenario: Git derivation skips the picker when enabled
- **WHEN** `git.resolveProject` is `true` and `baas results query` is invoked inside the `lynx-journal`
  repository
- **THEN** `lynx-journal`'s results are reported and no list is shown

#### Scenario: A redirect gets a list, not a prompt
- **WHEN** `baas results query > out.txt` or `baas results query --format json` is invoked with no project resolvable
- **THEN** the command exits non-zero without waiting for input, and its message lists the available
  projects and names `--project` and `--all-projects`

### Requirement: Every project can be reported at once
`baas results query --all-projects` SHALL report measurements from every project, and its table SHALL carry a
PROJECT column. The PROJECT column SHALL appear only under `--all-projects`.

#### Scenario: Every project is reported with its name
- **WHEN** `baas results query --all-projects` is invoked and two projects hold results
- **THEN** rows from both are reported, each naming its project

#### Scenario: A single project carries no project column
- **WHEN** `baas results query --project lynx-journal` is invoked
- **THEN** the table has no PROJECT column

### Requirement: Output carries a measurement's tags, and the table omits the score error
The `baas results query` table SHALL NOT include a score-error column, and its benchmark column SHALL NOT carry
params. JSON output SHALL carry `scoreError`, a `tags` object holding every tag of the measurement and a
`params` object holding its params (empty when there are none); `benchmarkName` SHALL stay the plain name.
CSV output SHALL carry a `scoreError` column, a `tags` column holding every tag as `key=value` pairs
separated by `;`, and, as its last column, a `params` column in the same form. With `-v`, each table row
SHALL be followed by an indented line labelled `params` listing its params, when it has any, and then an
indented line labelled `tags` listing every tag; without `-v`, no such line SHALL be printed.

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

## REMOVED Requirements

### Requirement: Results are grouped with the best score kept
**Reason**: Grouping is no longer the default view, and it compared rows across JMH modes and always kept the highest score — the slowest result for time-per-op modes.
**Migration**: Pass `--best-per <tag>` to `baas results query` (requirement *The best score per group is reported only on request*); `--group-by <tag>` becomes `--best-per <tag>`, `--all-jobs`' every-row view is the default.

### Requirement: A job's artifacts can be downloaded from S3
**Reason**: Downloading belongs to the execution domain, and the literal-result-path form served only jobs predating job items or the unified layout, of which none exist after the rebuild.
**Migration**: Use `baas jobs download <job>` with a job id (job-tracking, *`baas jobs download` fetches one job's artifacts*).

## ADDED Requirements

### Requirement: `baas results query` lists every measurement by default
`baas results` SHALL be a noun whose only verb is `query`; `baas query` SHALL be its top-level alias, and
`baas results` alone SHALL print its usage. Without `--best-per`, `baas results query` SHALL report every
measurement that its filters select — one row per stored measurement — subject to the exclusion rule
and the shared ordering and paging pipeline.

#### Scenario: Every measurement is a row
- **WHEN** a benchmark on one branch has three results and `baas results query --project p` is run
- **THEN** three rows are returned

#### Scenario: The alias runs the query
- **WHEN** `baas query --project p` is run
- **THEN** it reports exactly what `baas results query --project p` reports

#### Scenario: The bare noun prints usage
- **WHEN** `baas results` is run with no verb
- **THEN** its usage, naming `query`, is printed and nothing is queried

### Requirement: The best score per group is reported only on request
`--best-per <tag>` SHALL group the selected rows by project, benchmark, params, JMH mode and the value
of `<tag>`, and keep one row per group: the highest score for a throughput mode and the lowest for a
time-per-operation mode (average time, sample time, single shot). A non-finite score SHALL never win.
Rows lacking `<tag>` SHALL be collected into a single untagged group rather than dropped. `--best-per`
SHALL require a value, and there SHALL be no separate grouping option.

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

### Requirement: Tags can exclude rows
`--exclude-tag k=v` SHALL be repeatable and SHALL drop a row that matches any of the given pairs — the
mirror of `--tag`, which keeps a row only when it matches all of them. It SHALL be available on
`baas results query` and `baas jobs list` with the same meaning, and SHALL NOT replace or alter the
`exclude_from_results` rule.

#### Scenario: One excluded value drops its rows
- **WHEN** `baas results query --project p --exclude-tag source=ci` is run over rows from `source=ci`
  and `source=local`
- **THEN** only the `source=local` rows are returned

#### Scenario: Any match drops the row
- **WHEN** `--exclude-tag branch=a --exclude-tag branch=b` is given
- **THEN** rows on either branch are dropped
