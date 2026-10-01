# Spec Delta

## ADDED Requirements

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
The `baas results` table SHALL NOT include a score-error column. JSON output SHALL carry `scoreError` and
a `tags` object holding every tag of the measurement; CSV output SHALL carry a `scoreError` column and a
`tags` column holding every tag as `key=value` pairs separated by `;`. With `-v`, each table row SHALL be
followed by an indented line listing every tag of that row; without `-v`, no such line SHALL be printed.

#### Scenario: Table has no error column
- **WHEN** `baas results --project p` prints a table
- **THEN** no column is headed with the score error

#### Scenario: Machine formats keep the error and carry tags
- **WHEN** `baas results --project p --format json` is invoked
- **THEN** each object carries `scoreError` and a `tags` object equal to the stored tag map

#### Scenario: Verbose table shows tags under each row
- **WHEN** `baas results --project p -v` prints a table
- **THEN** each row is followed by an indented line listing its tags as `key=value`

#### Scenario: Default table shows no tag lines
- **WHEN** `baas results --project p` prints a table without `-v`
- **THEN** no tag line is printed

## MODIFIED Requirements

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
`baas results` SHALL group returned rows by project, benchmark and a grouping tag, and keep only the
highest-scoring row per group. The grouping tag SHALL default to `branch`. Rows lacking the grouping tag
SHALL be collected into a single untagged group rather than dropped. `--all-runs` SHALL disable grouping
and report every row.

#### Scenario: Best of several runs is kept
- **WHEN** the same benchmark with the same grouping tag value has three results with different scores
- **THEN** one row is returned, carrying the highest score

#### Scenario: Two grouping values stay separate
- **WHEN** a benchmark has results tagged `branch=main` and `branch=feature-x`
- **THEN** two rows are returned, one per branch

#### Scenario: Untagged rows are not lost
- **WHEN** some measurements carry no `branch` tag
- **THEN** they are grouped together and reported, not silently discarded

#### Scenario: Two projects never share a group
- **WHEN** `--all-projects` reports the same benchmark name and grouping value from two projects
- **THEN** two rows are returned, one per project

#### Scenario: `--all-runs` reports every row
- **WHEN** `--all-runs` is given and a benchmark has three results on one branch
- **THEN** three rows are returned

## REMOVED Requirements

### Requirement: Results are loaded with a single query on the project partition
**Reason**: `--all-projects` and the project picker need every partition, which has no index; one
partition is still read with one `Query`.
**Migration**: None for callers. See *Results are read from one partition unless every project is
requested*.

### Requirement: Living-branch filtering uses one query
**Reason**: `--living-branches` is removed; it was the only `baas results` behaviour reading the local git
repository without being asked to.
**Migration**: Filter on `--tag branch=<name>`.
