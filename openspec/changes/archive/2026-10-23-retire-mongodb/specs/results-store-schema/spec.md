## MODIFIED Requirements

### Requirement: The store interface exposes writes only
The store interface SHALL expose a single write operation taking a measurement. It SHALL NOT expose a
field-path-based update or upsert operation, and SHALL NOT expose storage-specific concepts such as
index items or key encoding.

#### Scenario: No upsert surface exists
- **WHEN** the store interface is inspected
- **THEN** it declares no update, upsert, or field-path mutation method

#### Scenario: The interface is storage-neutral
- **WHEN** the store interface is inspected
- **THEN** its signatures name domain types only, with no DynamoDB type in any position

### Requirement: Every job names a results store
The runner SHALL require `--results-table`, and SHALL fail before executing any benchmark when it is
not given. It SHALL offer no other results store, and no option SHALL select a store that discards
measurements. Local runner invocations SHALL name a table on a local endpoint such as LocalStack.

#### Scenario: Missing configuration fails fast
- **WHEN** the runner is invoked with no table name
- **THEN** it exits non-zero before running any benchmark, naming the missing option

#### Scenario: The discard option is gone
- **WHEN** the runner is invoked with `--no-database`
- **THEN** it rejects the option as unknown and runs no benchmark

#### Scenario: The MongoDB option is gone
- **WHEN** the runner is invoked with `--mongo-connection-string` or `-m`
- **THEN** it rejects the option as unknown and runs no benchmark, and a `MONGO_CONNECTION_STRING`
  environment variable has no effect on any run

#### Scenario: A local job names a local table
- **WHEN** a benchmark is run locally with `--results-table` and a DynamoDB endpoint override
- **THEN** its measurements are written to that local table

## ADDED Requirements

### Requirement: Every job names an artifact bucket
The runner SHALL require `--s3-bucket`, and SHALL fail before executing any benchmark when it is not
given. It SHALL NOT write a job's artifacts to the local file system in place of the bucket. Local
runner invocations SHALL name a bucket on a local endpoint such as LocalStack.

#### Scenario: Missing bucket fails fast
- **WHEN** the runner is invoked with no `--s3-bucket`
- **THEN** it exits non-zero before running any benchmark, naming the missing option

#### Scenario: A local job names a local bucket
- **WHEN** a benchmark is run locally with `--s3-bucket` and an S3 endpoint override
- **THEN** its process output, result JSON and profiler artifacts are written to that local bucket

#### Scenario: Binary artifacts upload like any other
- **WHEN** a job produces a binary artifact such as a JFR recording or a compressed JCStress result
- **THEN** it is uploaded to the job's prefix and the job's outcome does not depend on the
  artifact being readable as text

## REMOVED Requirements

### Requirement: MongoDB remains available as a runner-local adapter
**Reason**: No live user exists: the one standalone consumer it was kept for sits on a branch frozen
in 2024 that cannot run the current runner. Keeping it meant every service integration test stored
through MongoDB, leaving the DynamoDB path BaaS actually uses without an end-to-end runner test.
**Migration**: Run `benchmark-runner.jar` with `--results-table` (and `--dynamodb-endpoint` for a
local DynamoDB such as LocalStack). Measurements already in a MongoDB are not migrated.
