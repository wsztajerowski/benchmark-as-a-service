# Spec Delta

## ADDED Requirements

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
- **WHEN** the watched rows span two runner image versions
- **THEN** the environment warning appears below the table in each frame, and is not repeated as a log
  line on every refresh

#### Scenario: Interrupting leaves a clean terminal
- **WHEN** the operator presses Ctrl+C during `baas results --watch`
- **THEN** the command exits with the last frame left on screen and the cursor on a fresh line
