## MODIFIED Requirements

### Requirement: A run's artifacts can be downloaded from S3
The CLI SHALL provide a command that downloads every S3 artifact for a run — the result JSON,
`environment.json`, process output, `packages.txt`, logs and profiling artifacts — to a local directory.
The command SHALL accept either a run identifier, which it resolves to the run's stored path through the
request-ID index, or a literal S3 result path, so that runs stored under any past layout remain
retrievable.

#### Scenario: Whole run is retrieved
- **WHEN** the download command is invoked for a completed run
- **THEN** the local directory contains the run's result JSON, `environment.json`, process output and any
  profiling artifacts

#### Scenario: A run identifier is resolved through the index
- **WHEN** the download command is given the run identifier that `baas run` printed
- **THEN** it resolves the stored result path through the request-ID index and downloads that prefix

#### Scenario: A path from an older layout still resolves
- **WHEN** the download command is given the literal result path of a run stored before the current
  layout
- **THEN** that prefix is downloaded

#### Scenario: Data absent from the item is recoverable
- **WHEN** a measurement's `rawData` is needed
- **THEN** it is available in the downloaded result JSON

#### Scenario: Unknown run reports clearly
- **WHEN** the download command names a run with no S3 prefix
- **THEN** the command exits non-zero naming the run, and creates no partial directory
