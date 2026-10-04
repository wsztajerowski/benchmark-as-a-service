## MODIFIED Requirements

### Requirement: One run occupies one S3 prefix
Every artifact belonging to a benchmark run SHALL live under `runs/<project>/<runId>/` in the
working bucket — the uploaded inputs, the environment manifest, the process output, the verbatim
result JSON, the profiling artifacts, the collected logs, the boot log and, for a run whose instance
could not be launched, `launch-error.txt`. No artifact of a run SHALL be written outside that prefix.
The run's status SHALL NOT be written to the prefix; it lives on the run item in the results table.

#### Scenario: A completed run is one prefix
- **WHEN** a run completes
- **THEN** listing `runs/<project>/<runId>/` returns every artifact of that run, and no artifact of
  that run exists elsewhere in the bucket

#### Scenario: A failed run is still one prefix
- **WHEN** the benchmark process exits non-zero and the instance self-terminates
- **THEN** `runs/<project>/<runId>/` contains the environment manifest and the boot log, and the run
  item records the failure

#### Scenario: A failed launch is still one prefix
- **WHEN** the instance request fails
- **THEN** `runs/<project>/<runId>/` contains the uploaded inputs and `launch-error.txt`

#### Scenario: The project segment identifies an unmeasured run
- **WHEN** a run dies before storing any measurement
- **THEN** its project and the instant it started are still readable from its S3 prefix alone

#### Scenario: Two projects share one bucket without collision
- **WHEN** runs from two different projects are launched by the same AWS identity
- **THEN** each lands under its own `runs/<project>/` segment

### Requirement: CI runs use the same layout as any other run
Continuous-integration runs SHALL write to `runs/<project>/<runId>/` like any other run, and SHALL
pass an explicit project. A dedicated top-level CI prefix SHALL NOT exist. Each CI job SHALL be a
distinct run with its own identifier.

#### Scenario: Two CI jobs do not share a prefix
- **WHEN** a CI workflow runs two benchmark jobs
- **THEN** each writes its artifacts under its own run prefix and records its status on its own run
  item, and neither overwrites the other

#### Scenario: CI runs are attributed
- **WHEN** a CI run stores a measurement
- **THEN** the measurement's project is the one CI passed explicitly, not a fallback value
