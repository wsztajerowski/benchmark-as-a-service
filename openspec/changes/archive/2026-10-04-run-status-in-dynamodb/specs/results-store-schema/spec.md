## ADDED Requirements

### Requirement: Every run names a results store
The runner SHALL require exactly one of `--results-table` or `--mongo-connection-string`, and SHALL fail
before executing any benchmark when neither or both are given. No option SHALL select a store that
discards measurements. Local runs SHALL name a table on a local endpoint such as LocalStack.

#### Scenario: Missing configuration fails fast
- **WHEN** the runner is invoked with no table name and no connection string
- **THEN** it exits non-zero before running any benchmark, naming the missing configuration

#### Scenario: The discard option is gone
- **WHEN** the runner is invoked with `--no-database`
- **THEN** it rejects the option as unknown and runs no benchmark

#### Scenario: A local run names a local table
- **WHEN** a benchmark is run locally with `--results-table` and a DynamoDB endpoint override
- **THEN** its measurements are written to that local table

## MODIFIED Requirements

### Requirement: S3 is written before the store, and store failure fails the run
The runner SHALL upload result artifacts to S3 before writing to the results store. It SHALL retry the
store write with backoff, and when the write ultimately fails it SHALL exit non-zero so the run item
records a failure and the S3 artifacts remain available for re-import.

#### Scenario: Store failure is not reported as success
- **WHEN** every store write attempt fails
- **THEN** the runner exits non-zero and the run item reads `failed:<exitCode>`

#### Scenario: Artifacts survive a store failure
- **WHEN** the store write fails after the S3 upload succeeded
- **THEN** the result JSON and process output are still present at the run's S3 result path

## REMOVED Requirements

### Requirement: Discarding results requires an explicit opt-in
**Reason**: `--no-database` predates the CLI and has no remaining use. A run without a store also has
nowhere to record its status, which now lives in the results table.
**Migration**: Name a store. For local runs, pass `--results-table` with `--dynamodb-endpoint` pointing at
LocalStack, as `jmh-with-profiler.sh` and `jmh-with-async.sh` already do.
