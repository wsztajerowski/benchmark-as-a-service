# Spec Delta

## MODIFIED Requirements

### Requirement: The full job identifier is rendered, not truncated
`baas results query` SHALL render the job identifier column at full width and SHALL NOT truncate it.

#### Scenario: Two jobs are distinguishable in the output
- **WHEN** `baas results query` renders two rows from different jobs minted in the same second
- **THEN** their identifier columns differ
