# Spec Delta

## MODIFIED Requirements

### Requirement: Tables are coloured when interactive, without losing alignment
When the output is interactive and colour is allowed, the `baas results query` table, the `baas jobs diff`
table and the `baas results diff` table SHALL emphasise the header row, SHALL de-emphasise values reported
as unknown (`n/a`), and the `baas jobs diff` table SHALL distinguish the two jobs' values. The `baas
results query` table SHALL also de-emphasise every row of a measurement tagged `hidden=true`.
The `baas results diff` table SHALL render a `regression` verdict in red and an `improvement` in green, and
SHALL leave `~`, `?`, `units differ` and the one-sided verdicts uncoloured. Colour SHALL NOT change the
visible column widths: every column SHALL start at the same position as in the uncoloured table.

#### Scenario: Unknown values are de-emphasised
- **WHEN** `baas results query` shows a row whose score is unknown, on an interactive terminal
- **THEN** its `n/a` is rendered de-emphasised, distinct from measured values

#### Scenario: Excluded rows are de-emphasised
- **WHEN** `baas results query --show-hidden` shows a row tagged `hidden=true`, on an interactive
  terminal
- **THEN** that row is rendered de-emphasised, and the other rows are not

#### Scenario: Verdicts are coloured by direction
- **WHEN** `baas results diff` shows one `regression`, one `improvement` and one `~` row, on an interactive
  terminal
- **THEN** the regression is red, the improvement green, and the `~` row uncoloured

#### Scenario: Columns stay aligned
- **WHEN** a coloured table is compared with the same table printed without colour
- **THEN** after removing escape sequences the two are identical
