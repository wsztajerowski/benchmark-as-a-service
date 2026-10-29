# Spec Delta

## ADDED Requirements

### Requirement: Log lines on a terminal show only their level, coloured
When standard output is a terminal by the definition that gates every terminal effect, and standard
error is that same terminal, each log line SHALL be written as `<LEVEL> <message>`, without the
timestamp and the logger name. When colour is also allowed, the level word SHALL be coloured — `ERROR`
bold red, `WARN` bold yellow, `INFO` bold blue, `DEBUG` and `TRACE` bold cyan — and the message SHALL
stay unstyled. In every other case — standard error redirected or piped, standard output not a terminal,
no console, or standard error's target not determinable — log lines SHALL keep the full format, with
timestamp, level and logger name, and SHALL carry no escape sequence. Text written to standard error that
is not a log line SHALL never be coloured. The CLI SHALL write logs nowhere but standard error.

#### Scenario: A warning on a terminal
- **WHEN** an unreleased build runs `baas jobs show <id>` on a terminal with colour allowed
- **THEN** standard error begins with `WARN Unreleased build`, the word `WARN` in bold yellow, with no
  timestamp and no logger name

#### Scenario: `NO_COLOR` keeps the short format without colour
- **WHEN** the same command runs on a terminal with `NO_COLOR` set
- **THEN** the line begins `WARN Unreleased build` and contains no escape sequence

#### Scenario: Standard error redirected to a file
- **WHEN** a command runs on a terminal with standard error redirected to a file
- **THEN** every log line in the file carries its timestamp, level and logger name, and no escape sequence

#### Scenario: Standard output piped
- **WHEN** `baas jobs show <id> --format json | jq .` runs on a terminal
- **THEN** the log lines on standard error keep the full format

#### Scenario: Other text on standard error is untouched
- **WHEN** the `Next` hint block or a picocli usage error is written to standard error on a terminal
- **THEN** none of its lines is coloured

## MODIFIED Requirements

### Requirement: Tables are coloured when interactive, without losing alignment
When the output is interactive and colour is allowed, the `baas results query` table and the `baas jobs diff`
table SHALL emphasise the header row, SHALL de-emphasise values reported as unknown (`n/a`), and the
`baas jobs diff` table SHALL distinguish the two jobs' values. The `baas results query` table SHALL also
de-emphasise every row of a measurement tagged `hidden=true`. Colour SHALL NOT change the
visible column widths: every column SHALL start at the same position as in the uncoloured table.

#### Scenario: Unknown values are de-emphasised
- **WHEN** `baas results query` shows a row whose score is unknown, on an interactive terminal
- **THEN** its `n/a` is rendered de-emphasised, distinct from measured values

#### Scenario: Excluded rows are de-emphasised
- **WHEN** `baas results query --show-hidden` shows a row tagged `hidden=true`, on an interactive
  terminal
- **THEN** that row is rendered de-emphasised, and the other rows are not

#### Scenario: Columns stay aligned
- **WHEN** a coloured table is compared with the same table printed without colour
- **THEN** after removing escape sequences the two are identical
