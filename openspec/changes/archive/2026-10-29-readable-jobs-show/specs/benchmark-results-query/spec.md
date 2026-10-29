# Spec Delta

## ADDED Requirements

### Requirement: Hidden results are filtered out
`baas results query` SHALL omit measurements tagged `hidden=true` from a project query and from
`--all-projects`, applying the filter server-side via a filter expression. Hiding SHALL apply to those
queries and to the filters layered on them, and SHALL NOT apply to a lookup that names one job by its job
ID: naming a job explicitly is a request for that job, not a query over the project's history. A query
naming a job that does not exist and a query naming a hidden job SHALL therefore be distinguishable.
`--show-hidden` SHALL lift the filter, and the table SHALL render hidden rows de-emphasised when the
output is interactive. The rule is its own, independent of `--exclude-tag`. Only the key `hidden` is
read: a measurement tagged with the key this rule used before, `exclude_from_results`, is not hidden.

#### Scenario: A hidden job is omitted
- **WHEN** a project holds two results for a benchmark, one tagged `hidden=true`
- **THEN** only the other is returned

#### Scenario: Filter is applied server-side
- **WHEN** a project query runs without `--show-hidden`
- **THEN** the request carries a filter expression covering `hidden`

#### Scenario: An explicit job lookup returns a hidden job
- **WHEN** `baas results query --job-id <id>` names a job whose measurements are tagged `hidden=true`
- **THEN** those measurements are returned

#### Scenario: Hidden rows stay out of grouping and tag filters
- **WHEN** `baas results query --project p --tag branch=main --best-per branch` is queried and one
  matching job is hidden
- **THEN** the hidden job's rows are absent from the result and from the grouping that follows it

#### Scenario: `--show-hidden` includes hidden rows
- **WHEN** `baas results query --project p --show-hidden` is queried and one job is hidden
- **THEN** that job's rows are returned, rendered de-emphasised on an interactive terminal

#### Scenario: Every project and every job is the whole table
- **WHEN** `baas results query --all-projects --show-hidden --limit 0` is invoked
- **THEN** every measurement in the table is reported

#### Scenario: A successful hidden job reports its own measurements
- **WHEN** `baas run` completes a job tagged `hidden=true` and prints its result summary
- **THEN** the summary lists that job's measurements rather than appearing empty

#### Scenario: The former key hides nothing
- **WHEN** a project holds a measurement tagged `exclude_from_results=true` and none tagged `hidden`
- **THEN** a project query returns it

## MODIFIED Requirements

### Requirement: A project is chosen when none is named
When `baas results query` is invoked with neither `--project`, `--all-projects` nor `--job-id`:
- if `git.resolveProject` is `true` and the working directory is inside a git repository, the project
  SHALL be that repository's name, resolving the main repository rather than a linked worktree;
- otherwise, when the output is interactive and the format is the table, the command SHALL list the
  projects holding at least one measurement not tagged `hidden=true` as a numbered list,
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

### Requirement: Tags can exclude rows
`--exclude-tag k=v` SHALL be repeatable and SHALL drop a row that matches any of the given pairs — the
mirror of `--tag`, which keeps a row only when it matches all of them. It SHALL be available on
`baas results query` and `baas jobs list` with the same meaning, and SHALL NOT replace or alter the
`hidden` rule.

#### Scenario: One excluded value drops its rows
- **WHEN** `baas results query --project p --exclude-tag source=ci` is run over rows from `source=ci`
  and `source=local`
- **THEN** only the `source=local` rows are returned

#### Scenario: Any match drops the row
- **WHEN** `--exclude-tag branch=a --exclude-tag branch=b` is given
- **THEN** rows on either branch are dropped

## REMOVED Requirements

### Requirement: Excluded results are filtered out
**Reason**: The tag is renamed from `exclude_from_results` to `hidden`, and `--show-excluded` to `--show-hidden`;
the rule itself is unchanged.
**Migration**: Tag such jobs `--tag hidden=true` and query them with `--show-hidden`. Stored measurements tagged
`exclude_from_results=true` are not migrated and become visible; `--exclude-tag exclude_from_results=true` hides
them by hand.
