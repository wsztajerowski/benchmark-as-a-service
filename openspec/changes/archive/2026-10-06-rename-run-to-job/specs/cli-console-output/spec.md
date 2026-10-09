# Spec Delta

## MODIFIED Requirements

### Requirement: Tables are coloured when interactive, without losing alignment
When the output is interactive and colour is allowed, the `baas results` table and the `baas env diff`
table SHALL emphasise the header row, SHALL de-emphasise values reported as unknown (`n/a`), and the
`baas env diff` table SHALL distinguish the two jobs' values. The `baas results` table SHALL also
de-emphasise every row of a measurement tagged `exclude_from_results=true`. Colour SHALL NOT change the
visible column widths: every column SHALL start at the same position as in the uncoloured table.

#### Scenario: Unknown values are de-emphasised
- **WHEN** `baas results` shows a row whose score is unknown, on an interactive terminal
- **THEN** its `n/a` is rendered de-emphasised, distinct from measured values

#### Scenario: Excluded rows are de-emphasised
- **WHEN** `baas results --all-jobs` shows a row tagged `exclude_from_results=true`, on an interactive
  terminal
- **THEN** that row is rendered de-emphasised, and the other rows are not

#### Scenario: Columns stay aligned
- **WHEN** a coloured table is compared with the same table printed without colour
- **THEN** after removing escape sequences the two are identical

### Requirement: `baas run` shows a live status line when interactive
While polling for a job's outcome, `baas run` SHALL, when the output is interactive and `--format json`
is not given, maintain a single status line on standard output, redrawn in place, carrying the job's
state, the elapsed time and the instance identifier, in place of the periodic "still running" log line.
Log output emitted while the line is shown SHALL appear above it without being overwritten by it or
interleaved with it. The line SHALL be cleared before anything else is written to standard output and
before the command exits, including on Ctrl+C before the shutdown hook reports termination. When the
output is not interactive, or `--format json` is given, `baas run` SHALL report progress through the
periodic log line exactly as it did before.

#### Scenario: One line instead of many
- **WHEN** `baas run jmh -- MyBenchmark` polls a running instance on an interactive terminal
- **THEN** progress is shown on one line that updates in place, and no "still running" log line is
  printed

#### Scenario: A warning during polling does not tear the line
- **WHEN** a warning is logged while the status line is shown
- **THEN** the warning appears on its own line above the status line, and the status line is redrawn
  intact below it

#### Scenario: Ctrl+C leaves a clean terminal
- **WHEN** the operator presses Ctrl+C while the status line is shown
- **THEN** the status line is cleared before the termination message is logged

#### Scenario: CI keeps its log lines
- **WHEN** `baas run` runs without a console, as in `e2e-cloud-test.yml`
- **THEN** progress is reported by the periodic log line, unchanged from before

#### Scenario: JSON mode keeps its log lines
- **WHEN** `baas run --format json` runs on an interactive terminal
- **THEN** no status line is drawn, and standard output holds only the summary object
