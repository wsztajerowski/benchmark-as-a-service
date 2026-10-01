# Spec Delta

## MODIFIED Requirements

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
