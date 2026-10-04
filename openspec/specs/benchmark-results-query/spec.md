# benchmark-results-query Specification

## Purpose
TBD - created by archiving change dynamodb-results-store. Update Purpose after archive.

## Requirements

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

### Requirement: Excluded results are filtered out
`baas results` SHALL omit measurements tagged `exclude_from_results=true` from a project sweep and from
`--all-projects`, applying the filter server-side via a filter expression. Exclusion SHALL apply to sweeps
and to the filters layered on them, and SHALL NOT apply to a lookup that names one run by its request ID:
naming a run explicitly is a request for that run, not a query over the project's history. A query naming
a run that does not exist and a query naming an excluded run SHALL therefore be distinguishable.
`--all-runs` SHALL lift the exclusion, and the table SHALL render excluded rows de-emphasised when the
output is interactive.

#### Scenario: Excluded run is omitted
- **WHEN** a project holds two results for a benchmark, one tagged `exclude_from_results=true`
- **THEN** only the other is returned

#### Scenario: Filter is applied server-side
- **WHEN** a project-partition query runs without `--all-runs`
- **THEN** the request carries a filter expression covering `exclude_from_results`

#### Scenario: An explicit run lookup returns an excluded run
- **WHEN** `baas results --request-id <id>` names a run whose measurements are tagged
  `exclude_from_results=true`
- **THEN** those measurements are returned

#### Scenario: Excluded rows stay out of grouping and tag filters
- **WHEN** `baas results --project p --tag branch=main` is queried and one matching run is excluded
- **THEN** the excluded run's rows are absent from the result and from the grouping that follows it

#### Scenario: `--all-runs` includes excluded rows
- **WHEN** `baas results --project p --all-runs` is queried and one run is excluded
- **THEN** that run's rows are returned, rendered de-emphasised on an interactive terminal

#### Scenario: Every project and every run is the whole table
- **WHEN** `baas results --all-projects --all-runs` is invoked
- **THEN** every measurement in the table is reported

#### Scenario: A successful excluded run reports its own measurements
- **WHEN** `baas run` completes a run tagged `exclude_from_results=true` and prints its result summary
- **THEN** the summary lists that run's measurements rather than appearing empty

### Requirement: Results are grouped with the best score kept
`baas results` SHALL group returned rows by project, benchmark, params and a grouping tag, and keep only
the highest-scoring row per group; each variant of a `@Param` sweep is therefore its own group. Rows
SHALL be displayed ordered by project, benchmark and params, so one variant's groups are adjacent. The grouping tag SHALL default to `branch`. Rows lacking the grouping tag
SHALL be collected into a single untagged group rather than dropped. `--all-runs` SHALL disable grouping
and report every row.

#### Scenario: Best of several runs is kept
- **WHEN** the same benchmark with the same grouping tag value has three results with different scores
- **THEN** one row is returned, carrying the highest score

#### Scenario: Two grouping values stay separate
- **WHEN** a benchmark has results tagged `branch=main` and `branch=feature-x`
- **THEN** two rows are returned, one per branch

#### Scenario: A sweep's variants are grouped separately
- **WHEN** a benchmark has results for `size=10` and `size=100000` on `branch=main`
- **THEN** two rows are returned, one per variant, each carrying that variant's highest score

#### Scenario: Untagged rows are not lost
- **WHEN** some measurements carry no `branch` tag
- **THEN** they are grouped together and reported, not silently discarded

#### Scenario: Two projects never share a group
- **WHEN** `--all-projects` reports the same benchmark name and grouping value from two projects
- **THEN** two rows are returned, one per project

#### Scenario: `--all-runs` reports every row
- **WHEN** `--all-runs` is given and a benchmark has three results on one branch
- **THEN** three rows are returned

### Requirement: A run's artifacts can be downloaded from S3
The CLI SHALL provide a command that downloads every S3 artifact for a run — the result JSON,
`environment.json`, process output, `packages.txt`, logs and profiling artifacts — to a local directory.
The command SHALL accept either a run identifier, which it resolves to the run's stored path through the
request-ID index, or a literal S3 result path, so that runs stored under any past layout remain
retrievable. A run identifier SHALL resolve through the run's run item when one exists, so a run that
stored no measurement — a failed or vanished run, or a failed launch — is retrievable by identifier.
For a run recorded before run items existed, it SHALL resolve through the run's measurements.

#### Scenario: Whole run is retrieved
- **WHEN** the download command is invoked for a completed run
- **THEN** the local directory contains the run's result JSON, `environment.json`, process output and any
  profiling artifacts

#### Scenario: A run identifier is resolved through the index
- **WHEN** the download command is given the run identifier that `baas run` printed
- **THEN** it resolves the stored result path through the request-ID index and downloads that prefix

#### Scenario: A failed run is retrievable by identifier
- **WHEN** the download command is given the identifier of a run that failed before storing any
  measurement
- **THEN** it resolves the path from the run item and downloads the boot log and environment manifest

#### Scenario: A run from before run items resolves
- **WHEN** the download command is given the identifier of a run recorded before this change
- **THEN** it resolves the path from that run's measurements

#### Scenario: A path from an older layout still resolves
- **WHEN** the download command is given the literal result path of a run stored before the current
  layout
- **THEN** that prefix is downloaded

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

### Requirement: `baas results --watch` follows results as they land
`baas results` SHALL accept `--watch`, which repeats the command's query at a fixed interval and redraws
the table in place until the operator interrupts it. Each refresh SHALL issue the same queries as a
single `baas results` invocation with the same filters. Warnings derived from the returned rows SHALL
be shown as part of the redrawn frame, below the table, rather than logged on every refresh.
`--watch` SHALL be refused, before any query is issued and with a non-zero exit, when the output is not
interactive or when `--format json` or `--format csv` is given.

#### Scenario: The table refreshes in place
- **WHEN** `baas results --watch` runs on an interactive terminal and a new measurement is stored
- **THEN** a later refresh shows the new row in the same screen area, without scrolling earlier frames

#### Scenario: Watching a redirect is refused
- **WHEN** `baas results --watch > out.txt` is invoked
- **THEN** the command exits non-zero with a message that `--watch` needs an interactive terminal, and
  issues no query

#### Scenario: Watching a machine format is refused
- **WHEN** `baas results --watch --format json` is invoked
- **THEN** the command exits non-zero with a message that `--watch` applies to the table only

#### Scenario: A row-derived warning is shown once per frame
- **WHEN** `--tag experiment=x` is watched and no returned row carries the `experiment` tag
- **THEN** the unknown-tag warning appears below the table in each frame, and is not repeated as a log
  line on every refresh

#### Scenario: Interrupting leaves a clean terminal
- **WHEN** the operator presses Ctrl+C during `baas results --watch`
- **THEN** the command exits with the last frame left on screen and the cursor on a fresh line

### Requirement: Results are read from one partition unless every project is requested
When a project is known — named by `--project` or derived — `baas results` SHALL retrieve its working set
with one `Query` on `pk = RESULT#<project>` and SHALL NOT issue a `Scan`. A `Scan` of the table SHALL be
issued only to list projects for the project picker and to serve `--all-projects`. Filters other than
request ID SHALL be applied to the returned rows rather than by selecting a different index.

#### Scenario: A named project does not scan
- **WHEN** `baas results --project lynx-journal` is invoked
- **THEN** the operation issued is a `Query` on the project partition, not a `Scan`

#### Scenario: Tag filters do not change the access path
- **WHEN** `baas results --project lynx-journal --tag branch=main` is invoked
- **THEN** the same project-partition `Query` is issued, with the tag applied as a predicate

#### Scenario: Every project is one scan
- **WHEN** `baas results --all-projects` is invoked
- **THEN** one paginated `Scan` is issued, and no per-project `Query`

### Requirement: A project is chosen when none is named
When `baas results` is invoked with neither `--project`, `--all-projects` nor `--request-id`:
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
  (non-excluded rows), and `baas results` runs on an interactive terminal
- **THEN** `a` and `c` are offered as numbered entries, `b` is not, and a tip names
  `baas config set --git-resolve-project true`

#### Scenario: Choosing a number reports that project
- **WHEN** the operator enters the number shown beside `c`
- **THEN** the command reports `c`'s results exactly as `baas results --project c` would

#### Scenario: An invalid choice is refused
- **WHEN** the operator enters a value that is not one of the listed numbers
- **THEN** the command exits non-zero without querying any project

#### Scenario: Git derivation skips the picker when enabled
- **WHEN** `git.resolveProject` is `true` and `baas results` is invoked inside the `lynx-journal`
  repository
- **THEN** `lynx-journal`'s results are reported and no list is shown

#### Scenario: A redirect gets a list, not a prompt
- **WHEN** `baas results > out.txt` or `baas results --format json` is invoked with no project resolvable
- **THEN** the command exits non-zero without waiting for input, and its message lists the available
  projects and names `--project` and `--all-projects`

### Requirement: Every project can be reported at once
`baas results --all-projects` SHALL report measurements from every project, and its table SHALL carry a
PROJECT column. The PROJECT column SHALL appear only under `--all-projects`.

#### Scenario: Every project is reported with its name
- **WHEN** `baas results --all-projects` is invoked and two projects hold results
- **THEN** rows from both are reported, each naming its project

#### Scenario: A single project carries no project column
- **WHEN** `baas results --project lynx-journal` is invoked
- **THEN** the table has no PROJECT column

### Requirement: Output carries a measurement's tags, and the table omits the score error
The `baas results` table SHALL NOT include a score-error column, and its benchmark column SHALL NOT carry
params. JSON output SHALL carry `scoreError`, a `tags` object holding every tag of the measurement and a
`params` object holding its params (empty when there are none); `benchmarkName` SHALL stay the plain name.
CSV output SHALL carry a `scoreError` column, a `tags` column holding every tag as `key=value` pairs
separated by `;`, and, as its last column, a `params` column in the same form. With `-v`, each table row
SHALL be followed by an indented line labelled `params` listing its params, when it has any, and then an
indented line labelled `tags` listing every tag; without `-v`, no such line SHALL be printed.

#### Scenario: Table has no error column
- **WHEN** `baas results --project p` prints a table
- **THEN** no column is headed with the score error

#### Scenario: Machine formats keep the error and carry tags
- **WHEN** `baas results --project p --format json` is invoked
- **THEN** each object carries `scoreError` and a `tags` object equal to the stored tag map

#### Scenario: Verbose table shows tags under each row
- **WHEN** `baas results --project p -v` prints a table
- **THEN** each row is followed by an indented line labelled `tags` listing its tags as `key=value`

#### Scenario: Verbose table shows a sweep variant's params above its tags
- **WHEN** `baas results --project p -v` prints a row whose params are `impl=hash` and `size=10`
- **THEN** the row is followed by `params  impl=hash  size=10`, then by its `tags` line

#### Scenario: Default table shows no tag lines
- **WHEN** `baas results --project p` prints a table without `-v`
- **THEN** no tag line is printed
