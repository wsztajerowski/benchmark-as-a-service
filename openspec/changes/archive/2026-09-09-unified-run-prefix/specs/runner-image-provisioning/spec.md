## MODIFIED Requirements

### Requirement: Every run records the environment it ran on
Before starting the benchmark process, user-data SHALL write `<result-path>/environment.json` recording at
least the image version and AMI ID, the instance type and region, the CPU model and topology, total
memory, the OS version and kernel release, the JVM version, the baked tool versions, and the kernel
tunables in effect. It SHALL additionally record the run's identity — its project, branch, run identifier
and creation instant — so that a run which stores no measurement remains identifiable from S3 alone. It
SHALL also write `<result-path>/packages.txt` containing `rpm -qa`. Both SHALL be uploaded before the
benchmark process starts. `environment.json` SHALL carry a `schemaVersion` field.

#### Scenario: Manifest accompanies a successful run
- **WHEN** a benchmark completes
- **THEN** `<result-path>/environment.json` and `<result-path>/packages.txt` exist alongside the run output

#### Scenario: Manifest survives a failed run
- **WHEN** the benchmark process exits non-zero
- **THEN** `<result-path>/environment.json` and `<result-path>/packages.txt` are still present

#### Scenario: Manifest identifies a run that stored nothing
- **WHEN** a run fails before writing any measurement
- **THEN** its `environment.json` still records the project, branch, run identifier and creation instant

#### Scenario: Manifest records what the image does not control
- **WHEN** `environment.json` is read
- **THEN** it records the instance type and CPU model, which are properties of the run rather than of the
  image

#### Scenario: Manifest is versioned
- **WHEN** `environment.json` is read
- **THEN** it carries a `schemaVersion` field identifying its structure
