# Spec Delta

## ADDED Requirements

### Requirement: `baas run` can report its outcome as a machine-readable object
`baas run` SHALL accept a `--format json` option that writes a single summary object to standard
output, carrying at least the run identifier, the project, the run's S3 result path, the outcome, the
process exit code and the launched instance's identifier. The object SHALL be written on the failure
path as well as on success — a failed run is precisely when its identifier is needed — and the command
SHALL still exit non-zero when the run failed. Diagnostics SHALL remain on the logger so that standard
output holds the object alone.

#### Scenario: The summary is machine-readable
- **WHEN** `baas run --format json jmh -- MyBenchmark` completes and its standard output is piped to a
  JSON parser
- **THEN** the parser succeeds, and the parsed object carries the run identifier, project, result path,
  outcome, exit code and instance identifier

#### Scenario: A failed run still reports its identifier
- **WHEN** a run launched with `--format json` fails
- **THEN** the summary object is written with a failed outcome and the run's exit code, and the command
  exits non-zero

#### Scenario: Diagnostics do not corrupt the object
- **WHEN** `baas run -v --format json` is redirected to a file
- **THEN** the file contains exactly one JSON object with no timestamp or log-level prefix, and verbose
  diagnostics appear on standard error

#### Scenario: Default output is unchanged
- **WHEN** `baas run` is invoked without `--format`
- **THEN** its human-readable progress reporting is unchanged and no JSON object is written
