# job-artifact-layout Specification

## Purpose
Lays out everything one job produces or consumes under a single S3 prefix, `jobs/<project>/<jobId>/`,
so a job's inputs, results and diagnostics are found in one place and never collide with another job's.

## Requirements

### Requirement: One job occupies one S3 prefix
Every artifact belonging to a benchmark job SHALL live under `jobs/<project>/<jobId>/` in the
working bucket — the uploaded inputs, the environment manifest, the process output, the verbatim
result JSON, the profiling artifacts, the collected logs, the boot log and, for a job whose instance
could not be launched, `launch-error.txt`. No artifact of a job SHALL be written outside that prefix.
The job's status SHALL NOT be written to the prefix; it lives on the job item in the results table.

#### Scenario: A completed job is one prefix
- **WHEN** a job completes
- **THEN** listing `jobs/<project>/<jobId>/` returns every artifact of that job, and no artifact of
  that job exists elsewhere in the bucket

#### Scenario: A failed job is still one prefix
- **WHEN** the benchmark process exits non-zero and the instance self-terminates
- **THEN** `jobs/<project>/<jobId>/` contains the environment manifest and the boot log, and the job
  item records the failure

#### Scenario: A failed launch is still one prefix
- **WHEN** the instance request fails
- **THEN** `jobs/<project>/<jobId>/` contains the uploaded inputs and `launch-error.txt`

#### Scenario: The project segment identifies an unmeasured job
- **WHEN** a job dies before storing any measurement
- **THEN** its project and the instant it started are still readable from its S3 prefix alone

#### Scenario: Two projects share one bucket without collision
- **WHEN** jobs from two different projects are launched by the same AWS identity
- **THEN** each lands under its own `jobs/<project>/` segment

### Requirement: Uploaded inputs are separated from results within the job prefix
The CLI SHALL upload the benchmark JAR to `jobs/<project>/<jobId>/input/`, and SHALL upload an
explicitly overridden runner JAR to the same `input/` sub-prefix. Result artifacts SHALL be written
at the job prefix root, not under `input/`.

#### Scenario: Inputs are skippable as a prefix
- **WHEN** a consumer lists a job prefix and excludes `input/`
- **THEN** the remaining listing contains only result artifacts, with no filename special-casing

#### Scenario: A development runner override is per-job
- **WHEN** `baas run --runner-jar <path>` is used
- **THEN** that JAR is uploaded to the job's `input/` sub-prefix rather than to any shared location

### Requirement: The pinned runner artifact lives outside the job tree
The version-pinned runner JAR SHALL be stored at `releases/<version>/benchmark-runner.jar`, a
top-level prefix distinct from `jobs/`. It SHALL NOT be copied into each job's prefix.

#### Scenario: One copy serves many jobs
- **WHEN** several jobs launch from the same CLI version
- **THEN** the bucket holds one runner JAR object for that version, not one per job

#### Scenario: The artifact prefix is unambiguous in a listing
- **WHEN** the bucket's top-level prefixes are listed
- **THEN** the runner artifact prefix is distinguishable from the job prefix without inspecting
  further path segments

### Requirement: CI jobs use the same layout as any other job
CI-launched jobs SHALL write to `jobs/<project>/<jobId>/` like any other job, and SHALL
pass an explicit project. A dedicated top-level CI prefix SHALL NOT exist. Each CI job SHALL be a
distinct job with its own identifier.

#### Scenario: Two CI jobs do not share a prefix
- **WHEN** a CI workflow runs two benchmark CI jobs
- **THEN** each writes its artifacts under its own job prefix and records its status on its own job
  item, and neither overwrites the other

#### Scenario: CI jobs are attributed
- **WHEN** a CI job stores a measurement
- **THEN** the measurement's project is the one CI passed explicitly, not a fallback value

### Requirement: A job's artifacts are located through its stored path, not a reconstructed one
Consumers SHALL resolve a job's artifacts through the path attributes recorded on its stored
measurements rather than by rebuilding a path from the job's other attributes. Path attributes of
existing measurements SHALL remain authoritative after any relocation.

#### Scenario: An older job resolves after the layout changes
- **WHEN** a job stored before this change is downloaded
- **THEN** its artifacts are found at the path its measurement records, whatever shape that path has

#### Scenario: Reconstruction is not required
- **WHEN** a job's artifacts are fetched
- **THEN** no consumer needs to know which layout the job was written under
