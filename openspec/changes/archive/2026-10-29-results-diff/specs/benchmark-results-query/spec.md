# Spec Delta

## MODIFIED Requirements

### Requirement: `baas results query` lists every measurement by default
`baas results` SHALL be a noun whose verbs are `query` and `diff`; `baas query` SHALL be the top-level
alias of `baas results query`, and `baas results` alone SHALL print its usage. Without `--best-per`,
`baas results query` SHALL report every measurement that its filters select — one row per stored
measurement — subject to the exclusion rule and the shared ordering and paging pipeline.

#### Scenario: Every measurement is a row
- **WHEN** a benchmark on one branch has three results and `baas results query --project p` is run
- **THEN** three rows are returned

#### Scenario: The alias runs the query
- **WHEN** `baas query --project p` is run
- **THEN** it reports exactly what `baas results query --project p` reports

#### Scenario: The bare noun prints usage
- **WHEN** `baas results` is run with no verb
- **THEN** its usage, naming `query` and `diff`, is printed and nothing is queried
