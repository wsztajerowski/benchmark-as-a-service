# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: Results for one run are queryable by request ID`
- TO: `### Requirement: Results for one job are queryable by job ID`

- FROM: `### Requirement: A run's artifacts can be downloaded from S3`
- TO: `### Requirement: A job's artifacts can be downloaded from S3`

## MODIFIED Requirements

### Requirement: Results for one job are queryable by job ID
`baas results --job-id <id>` SHALL return every measurement of that job using a single `Query` on the
job-ID index, without a table scan.

#### Scenario: All benchmarks of a run are returned
- **WHEN** a job produced three JMH benchmark methods and `--job-id` names it
- **THEN** three rows are returned

#### Scenario: Unknown request ID returns nothing
- **WHEN** `--job-id` names a job that does not exist
- **THEN** no rows are returned and the command exits 0

### Requirement: Excluded results are filtered out
`baas results` SHALL omit measurements tagged `exclude_from_results=true` from a project sweep and from
`--all-projects`, applying the filter server-side via a filter expression. Exclusion SHALL apply to sweeps
and to the filters layered on them, and SHALL NOT apply to a lookup that names one job by its job ID:
naming a job explicitly is a request for that job, not a query over the project's history. A query naming
a job that does not exist and a query naming an excluded job SHALL therefore be distinguishable.
`--all-jobs` SHALL lift the exclusion, and the table SHALL render excluded rows de-emphasised when the
output is interactive.

#### Scenario: Excluded run is omitted
- **WHEN** a project holds two results for a benchmark, one tagged `exclude_from_results=true`
- **THEN** only the other is returned

#### Scenario: Filter is applied server-side
- **WHEN** a project-partition query runs without `--all-jobs`
- **THEN** the request carries a filter expression covering `exclude_from_results`

#### Scenario: An explicit run lookup returns an excluded run
- **WHEN** `baas results --job-id <id>` names a job whose measurements are tagged
  `exclude_from_results=true`
- **THEN** those measurements are returned

#### Scenario: Excluded rows stay out of grouping and tag filters
- **WHEN** `baas results --project p --tag branch=main` is queried and one matching job is excluded
- **THEN** the excluded job's rows are absent from the result and from the grouping that follows it

#### Scenario: `--all-runs` includes excluded rows
- **WHEN** `baas results --project p --all-jobs` is queried and one job is excluded
- **THEN** that job's rows are returned, rendered de-emphasised on an interactive terminal

#### Scenario: Every project and every run is the whole table
- **WHEN** `baas results --all-projects --all-jobs` is invoked
- **THEN** every measurement in the table is reported

#### Scenario: A successful excluded run reports its own measurements
- **WHEN** `baas run` completes a job tagged `exclude_from_results=true` and prints its result summary
- **THEN** the summary lists that job's measurements rather than appearing empty

### Requirement: Results are grouped with the best score kept
`baas results` SHALL group returned rows by project, benchmark, params and a grouping tag, and keep only
the highest-scoring row per group; each variant of a `@Param` sweep is therefore its own group. Rows
SHALL be displayed ordered by project, benchmark and params, so one variant's groups are adjacent. The grouping tag SHALL default to `branch`. Rows lacking the grouping tag
SHALL be collected into a single untagged group rather than dropped. `--all-jobs` SHALL disable grouping
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
- **WHEN** `--all-jobs` is given and a benchmark has three results on one branch
- **THEN** three rows are returned

### Requirement: A job's artifacts can be downloaded from S3
The CLI SHALL provide a command that downloads every S3 artifact for a job — the result JSON,
`environment.json`, process output, `packages.txt`, logs and profiling artifacts — to a local directory.
The command SHALL accept either a job identifier, which it resolves to the job's stored path through the
job-ID index, or a literal S3 result path, so that jobs stored under any past layout remain
retrievable. A job identifier SHALL resolve through the job's job item when one exists, so a job that
stored no measurement — a failed or vanished job, or a failed launch — is retrievable by identifier.
For a job recorded before job items existed, it SHALL resolve through the job's measurements.

#### Scenario: Whole run is retrieved
- **WHEN** the download command is invoked for a completed job
- **THEN** the local directory contains the job's result JSON, `environment.json`, process output and any
  profiling artifacts

#### Scenario: A run identifier is resolved through the index
- **WHEN** the download command is given the job identifier that `baas run` printed
- **THEN** it resolves the stored result path through the job-ID index and downloads that prefix

#### Scenario: A failed run is retrievable by identifier
- **WHEN** the download command is given the identifier of a job that failed before storing any
  measurement
- **THEN** it resolves the path from the job item and downloads the boot log and environment manifest

#### Scenario: A run from before run items resolves
- **WHEN** the download command is given the identifier of a job recorded before this change
- **THEN** it resolves the path from that job's measurements

#### Scenario: A path from an older layout still resolves
- **WHEN** the download command is given the literal result path of a job stored before the current
  layout
- **THEN** that prefix is downloaded

#### Scenario: Data absent from the item is recoverable
- **WHEN** a measurement's `rawData` is needed
- **THEN** it is available in the downloaded result JSON

#### Scenario: Unknown run reports clearly
- **WHEN** the download command names a job with no S3 prefix
- **THEN** the command exits non-zero naming the job, and creates no partial directory

### Requirement: Results are read from one partition unless every project is requested
When a project is known — named by `--project` or derived — `baas results` SHALL retrieve its working set
with one `Query` on `pk = RESULT#<project>` and SHALL NOT issue a `Scan`. A `Scan` of the table SHALL be
issued only to list projects for the project picker and to serve `--all-projects`. Filters other than
job ID SHALL be applied to the returned rows rather than by selecting a different index.

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
When `baas results` is invoked with neither `--project`, `--all-projects` nor `--job-id`:
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
