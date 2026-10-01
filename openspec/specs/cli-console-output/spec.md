# cli-console-output Specification

## Purpose
How `baas-cli` writes to the terminal: when colour and in-place redraw are allowed, the guarantee that
no terminal escape sequence ever reaches a file or pipe, and how a live status line coexists with log
output.

## Requirements

### Requirement: Terminal effects are gated on one definition of interactive
The CLI SHALL treat its output as interactive only when the process has a console (standard input and
standard output both attached to a terminal) and the `TERM` environment variable is not `dumb`.
Colour and in-place redraw SHALL be used only when the output is interactive. When it is not, the CLI
SHALL write no terminal escape sequence and no carriage-return redraw to standard output.

#### Scenario: Redirected output carries no escape sequences
- **WHEN** `baas results` is invoked with standard output redirected to a file
- **THEN** the file contains no escape sequence and no carriage-return redraw

#### Scenario: Piped output carries no escape sequences
- **WHEN** `baas results` is piped to another process
- **THEN** that process receives no escape sequence

#### Scenario: A dumb terminal gets plain output
- **WHEN** `baas results` runs on a terminal with `TERM=dumb`
- **THEN** the output contains no escape sequence

#### Scenario: Output is identical to a plain run when not interactive
- **WHEN** any `baas` command runs without a console
- **THEN** its standard output is byte-identical to the output of the same command before terminal
  effects existed

### Requirement: `NO_COLOR` disables colour
The CLI SHALL emit no colour when the `NO_COLOR` environment variable is set, even when the output is
interactive.

#### Scenario: Colour is suppressed on request
- **WHEN** `NO_COLOR=1 baas results` runs on an interactive terminal
- **THEN** the table is printed without colour

### Requirement: Machine-readable output is never coloured
JSON output, CSV output, the `baas run --format json` summary and the rendered deployer policy SHALL
contain no escape sequence, whether or not the output is interactive.

#### Scenario: JSON on a terminal stays plain
- **WHEN** `baas results --format json` runs on an interactive terminal
- **THEN** the output contains no escape sequence

#### Scenario: The deployer policy stays plain
- **WHEN** `baas admin deployer-policy` runs on an interactive terminal
- **THEN** the printed policy contains no escape sequence and can be pasted as IAM JSON

### Requirement: Tables are coloured when interactive, without losing alignment
When the output is interactive and colour is allowed, the `baas results` table and the `baas env diff`
table SHALL emphasise the header row, SHALL de-emphasise values reported as unknown (`n/a`), and the
`baas env diff` table SHALL distinguish the two runs' values. The `baas results` table SHALL also
de-emphasise every row of a measurement tagged `exclude_from_results=true`. Colour SHALL NOT change the
visible column widths: every column SHALL start at the same position as in the uncoloured table.

#### Scenario: Unknown values are de-emphasised
- **WHEN** `baas results` shows a row whose score is unknown, on an interactive terminal
- **THEN** its `n/a` is rendered de-emphasised, distinct from measured values

#### Scenario: Excluded rows are de-emphasised
- **WHEN** `baas results --all-runs` shows a row tagged `exclude_from_results=true`, on an interactive
  terminal
- **THEN** that row is rendered de-emphasised, and the other rows are not

#### Scenario: Columns stay aligned
- **WHEN** a coloured table is compared with the same table printed without colour
- **THEN** after removing escape sequences the two are identical

### Requirement: `baas run` shows a live status line when interactive
While polling for a run's outcome, `baas run` SHALL, when the output is interactive and `--format json`
is not given, maintain a single status line on standard output, redrawn in place, carrying the run's
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
