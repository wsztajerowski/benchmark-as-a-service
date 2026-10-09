# Spec Delta

## MODIFIED Requirements

### Requirement: Terminal effects are gated on one definition of interactive
The CLI SHALL treat its output as interactive only when the process has a console (standard input and
standard output both attached to a terminal) and the `TERM` environment variable is not `dumb`.
Colour and in-place redraw SHALL be used only when the output is interactive. When it is not, the CLI
SHALL write no terminal escape sequence and no carriage-return redraw to standard output.

#### Scenario: Redirected output carries no escape sequences
- **WHEN** `baas results query` is invoked with standard output redirected to a file
- **THEN** the file contains no escape sequence and no carriage-return redraw

#### Scenario: Piped output carries no escape sequences
- **WHEN** `baas results query` is piped to another process
- **THEN** that process receives no escape sequence

#### Scenario: A dumb terminal gets plain output
- **WHEN** `baas results query` runs on a terminal with `TERM=dumb`
- **THEN** the output contains no escape sequence

#### Scenario: Output is identical to a plain run when not interactive
- **WHEN** any `baas` command runs without a console
- **THEN** its standard output is byte-identical to the output of the same command before terminal
  effects existed

### Requirement: `NO_COLOR` disables colour
The CLI SHALL emit no colour when the `NO_COLOR` environment variable is set, even when the output is
interactive.

#### Scenario: Colour is suppressed on request
- **WHEN** `NO_COLOR=1 baas results query` runs on an interactive terminal
- **THEN** the table is printed without colour

### Requirement: Machine-readable output is never coloured
JSON output, CSV output, the `baas run --format json` summary and the rendered deployer policy SHALL
contain no escape sequence, whether or not the output is interactive.

#### Scenario: JSON on a terminal stays plain
- **WHEN** `baas results query --format json` runs on an interactive terminal
- **THEN** the output contains no escape sequence

#### Scenario: The deployer policy stays plain
- **WHEN** `baas admin deployment setup` prints the deployer policy on an interactive terminal
- **THEN** the printed policy contains no escape sequence and can be pasted as IAM JSON

### Requirement: Tables are coloured when interactive, without losing alignment
When the output is interactive and colour is allowed, the `baas results query` table and the `baas jobs diff`
table SHALL emphasise the header row, SHALL de-emphasise values reported as unknown (`n/a`), and the
`baas jobs diff` table SHALL distinguish the two jobs' values. The `baas results query` table SHALL also
de-emphasise every row of a measurement tagged `exclude_from_results=true`. Colour SHALL NOT change the
visible column widths: every column SHALL start at the same position as in the uncoloured table.

#### Scenario: Unknown values are de-emphasised
- **WHEN** `baas results query` shows a row whose score is unknown, on an interactive terminal
- **THEN** its `n/a` is rendered de-emphasised, distinct from measured values

#### Scenario: Excluded rows are de-emphasised
- **WHEN** `baas results query --show-excluded` shows a row tagged `exclude_from_results=true`, on an interactive
  terminal
- **THEN** that row is rendered de-emphasised, and the other rows are not

#### Scenario: Columns stay aligned
- **WHEN** a coloured table is compared with the same table printed without colour
- **THEN** after removing escape sequences the two are identical
