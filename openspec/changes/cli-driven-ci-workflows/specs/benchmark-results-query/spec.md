# Spec Delta

## MODIFIED Requirements

### Requirement: Excluded results are filtered out
`baas results` SHALL omit measurements tagged `exclude_from_results=true` from a project sweep, applying
the filter server-side via a filter expression. Exclusion SHALL apply to sweeps and to the filters
layered on them, and SHALL NOT apply to a lookup that names one run by its request ID: naming a run
explicitly is a request for that run, not a query over the project's history. A query naming a run that
does not exist and a query naming an excluded run SHALL therefore be distinguishable.

#### Scenario: Excluded run is omitted
- **WHEN** a project holds two results for a benchmark, one tagged `exclude_from_results=true`
- **THEN** only the other is returned

#### Scenario: Filter is applied server-side
- **WHEN** a project-partition query runs
- **THEN** the request carries a filter expression covering `exclude_from_results`

#### Scenario: An explicit run lookup returns an excluded run
- **WHEN** `baas results --request-id <id>` names a run whose measurements are tagged
  `exclude_from_results=true`
- **THEN** those measurements are returned

#### Scenario: Excluded rows stay out of grouping and tag filters
- **WHEN** `baas results --tag branch=main` is queried and one matching run is excluded
- **THEN** the excluded run's rows are absent from the result and from the grouping that follows it

#### Scenario: A successful excluded run reports its own measurements
- **WHEN** `baas run` completes a run tagged `exclude_from_results=true` and prints its result summary
- **THEN** the summary lists that run's measurements rather than appearing empty
