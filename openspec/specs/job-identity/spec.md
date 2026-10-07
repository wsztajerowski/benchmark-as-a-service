# job-identity Specification

## Purpose
Gives every job exactly one identifier, minted once by the launching CLI from a single clock reading,
and builds that identifier and the job's prefix in one place so no reader reconstructs either by hand.

## Requirements

### Requirement: A job has exactly one identifier
A benchmark job SHALL be identified by a single `jobId`, minted by the launching CLI before any
artifact is uploaded. The same value SHALL be used as the job's S3 prefix segment, as the
`jobId` on every measurement the job stores, and as the partition key of the `jobId-index`
lookup. The CLI SHALL NOT derive a second, separate identifier for the job's results.

#### Scenario: One identifier reaches both stores
- **WHEN** a job completes and writes both S3 artifacts and measurements
- **THEN** the S3 prefix segment naming the job and the `jobId` on every stored measurement are
  the same string

#### Scenario: The identifier is reported to the caller
- **WHEN** `baas run` launches a job
- **THEN** it prints the `jobId`, and that value is accepted by the download command

### Requirement: The job identifier is time-ordered and collision-resistant
`jobId` SHALL be `<UTC instant, ISO-8601 basic, milliseconds>-<8 lowercase hex characters>`, for
example `20260820T174432812Z-a3f9c21b`. It SHALL be exactly 28 characters and SHALL contain no
character outside `[0-9A-Za-z-]`; in particular it SHALL contain neither `#` nor `/`. The entropy
SHALL come from a cryptographically strong source.

#### Scenario: Identifiers sort chronologically as strings
- **WHEN** identifiers minted at three increasing instants are sorted lexicographically
- **THEN** the resulting order is identical to their chronological order

#### Scenario: Two jobs in the same millisecond do not collide
- **WHEN** two identifiers are minted from the same instant
- **THEN** they differ in their entropy suffix

#### Scenario: The identifier cannot corrupt a sort key
- **WHEN** an identifier is placed in the last field of a `#`-separated sort key
- **THEN** the sort key still splits into the same number of fields, because the identifier contains
  no separator character

#### Scenario: Width is fixed
- **WHEN** identifiers minted at different instants are compared
- **THEN** every one is 28 characters long

### Requirement: The launching CLI reads the clock once per job
`baas run` SHALL read the current instant exactly once per job, embed it in the `jobId`, and pass
that same instant to the runner. The runner SHALL accept it as `--created-at` and store it as
`createdAt` rather than reading its own clock. When `--created-at` is absent the runner SHALL fall
back to its own clock, so direct invocation keeps working.

#### Scenario: The prefix name and the stored timestamp agree
- **WHEN** a job launched by `baas run` stores a measurement
- **THEN** the instant encoded in the job's `jobId` equals the measurement's `createdAt`

#### Scenario: The instance clock does not reach the record
- **WHEN** the instance's clock differs from the launching machine's
- **THEN** the stored `createdAt` is the launching machine's instant

#### Scenario: Direct runner invocation still works
- **WHEN** `benchmark-runner` is invoked with no `--created-at`
- **THEN** it stores its own current instant and the job succeeds

#### Scenario: One job's measurements share one timestamp
- **WHEN** a single job produces several measurements
- **THEN** every one carries the same `createdAt`

### Requirement: Job identifiers and job prefixes are constructed in one place
Generation of `jobId` and construction of a job's S3 prefix SHALL live in the shared model module
alongside the DynamoDB key encoding, and SHALL be used by both the CLI and the runner. Neither
module SHALL assemble a job prefix or a job identifier by string concatenation elsewhere.

#### Scenario: Both modules use the shared constructors
- **WHEN** the CLI and the runner each resolve a job's S3 prefix for the same project and identifier
- **THEN** both produce the same string, from the same shared code

#### Scenario: The runner's defaults come from the shared code
- **WHEN** `benchmark-runner` is invoked with neither `--job-id` nor `--result-path`
- **THEN** it generates an identifier of the specified shape and derives its result path from it

### Requirement: The full job identifier is rendered, not truncated
`baas results query` SHALL render the job identifier column at full width and SHALL NOT truncate it.

#### Scenario: Two jobs are distinguishable in the output
- **WHEN** `baas results query` renders two rows from different jobs minted in the same second
- **THEN** their identifier columns differ
