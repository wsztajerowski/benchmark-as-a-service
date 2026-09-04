## ADDED Requirements

### Requirement: Runner JAR staging is unconditional
`baas run` SHALL always stage the `benchmark-runner` JAR into S3 before launching an instance. Staging SHALL
NOT be conditional on a `--runner-jar` option, and no code path SHALL leave the instance to fetch the JAR
itself.

#### Scenario: Staging happens without an explicit option
- **WHEN** `baas run jmh -- MyBenchmark` is invoked with no JAR option
- **THEN** the runner JAR is uploaded to S3 before the instance is launched

#### Scenario: User-data reads the JAR from S3
- **WHEN** the rendered user-data is inspected
- **THEN** it fetches the runner JAR from an S3 key and contains no conditional GitHub Releases branch

### Requirement: The staged runner JAR is cached and verified
The staged JAR SHALL be stored under a version-scoped key so a subsequent run with the same version reuses
it. The CLI SHALL compute and verify the JAR's checksum before upload and SHALL fail before launching an
instance on mismatch.

#### Scenario: Second run reuses the cached object
- **WHEN** two runs use the same runner version
- **THEN** the second run does not re-upload the JAR

#### Scenario: Corrupted JAR aborts before spending money
- **WHEN** the JAR's checksum does not match the expected value
- **THEN** the command exits non-zero and no EC2 instance is launched

### Requirement: Configuration distinguishes the runner subnet from the build subnet
`~/.baas/config.yaml` SHALL carry the runner subnet identifier, populated from the stack output by
`baas admin setup` and `baas config sync`. `baas run` SHALL use it, and SHALL NOT fall back to the build
subnet.

#### Scenario: Config sync populates the runner subnet
- **WHEN** `baas config sync --core-stack-name <name>` runs
- **THEN** the runner subnet identifier is populated from that stack's outputs

#### Scenario: Missing runner subnet fails clearly
- **WHEN** `baas run` is invoked with no runner subnet in configuration
- **THEN** it exits non-zero naming `baas config sync`, and no instance is launched

### Requirement: Benchmarks requiring outbound network access are documented as unsupported
The CLI documentation SHALL state that a benchmark making outbound network calls will fail, and that only
S3 and DynamoDB are reachable from a runner.

#### Scenario: Constraint is discoverable before a failed run
- **WHEN** a user reads `baas run --help` or the project README
- **THEN** the network-isolation constraint on benchmarks is stated
