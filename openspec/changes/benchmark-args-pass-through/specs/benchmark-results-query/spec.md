# Spec Delta

## MODIFIED Requirements

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
In the table, a measurement carrying a `profilers` tag SHALL have its score followed by `*`, with the
scores of unmarked rows aligned as if the mark were there. When at least one printed row is marked, the
table SHALL be followed by one line saying that `*` marks a measurement taken under a profiler, which
`-v` names; when none is marked, that line SHALL NOT be printed. JSON and CSV SHALL carry no mark beyond
the tag itself.

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

#### Scenario: A profiled measurement is marked
- **WHEN** the table prints one measurement carrying `profilers=async` and one carrying no `profilers` tag
- **THEN** the first score is followed by `*`, both scores' decimal points are in the same column, and
  the table is followed by the footnote line

#### Scenario: No footnote without a profiled row
- **WHEN** no printed measurement carries a `profilers` tag
- **THEN** no score carries `*`, and no footnote line is printed

#### Scenario: Machine formats carry no mark
- **WHEN** a measurement carrying `profilers=async` is printed as JSON
- **THEN** its `score` is a plain number, and its `tags` object carries `profilers`
