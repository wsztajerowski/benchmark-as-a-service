## ADDED Requirements

### Requirement: One run occupies one S3 prefix
Every artifact belonging to a benchmark run SHALL live under `runs/<project>/<runId>/` in the
working bucket — the uploaded inputs, the environment manifest, the process output, the verbatim
result JSON, the profiling artifacts, the collected logs, the boot log and the `run-status`
sentinel. No artifact of a run SHALL be written outside that prefix.

#### Scenario: A completed run is one prefix
- **WHEN** a run completes
- **THEN** listing `runs/<project>/<runId>/` returns every artifact of that run, and no artifact of
  that run exists elsewhere in the bucket

#### Scenario: A failed run is still one prefix
- **WHEN** the benchmark process exits non-zero and the instance self-terminates
- **THEN** `runs/<project>/<runId>/` contains the environment manifest, the boot log and a
  `run-status` sentinel recording the failure

#### Scenario: The project segment identifies an unmeasured run
- **WHEN** a run dies before storing any measurement, so no tags exist for it anywhere
- **THEN** its project and the instant it started are still readable from its S3 prefix alone

#### Scenario: Two projects share one bucket without collision
- **WHEN** runs from two different projects are launched by the same AWS identity
- **THEN** each lands under its own `runs/<project>/` segment

### Requirement: Uploaded inputs are separated from results within the run prefix
The CLI SHALL upload the benchmark JAR to `runs/<project>/<runId>/input/`, and SHALL upload an
explicitly overridden runner JAR to the same `input/` sub-prefix. Result artifacts SHALL be written
at the run prefix root, not under `input/`.

#### Scenario: Inputs are skippable as a prefix
- **WHEN** a consumer lists a run prefix and excludes `input/`
- **THEN** the remaining listing contains only result artifacts, with no filename special-casing

#### Scenario: A development runner override is per-run
- **WHEN** `baas run --runner-jar <path>` is used
- **THEN** that JAR is uploaded to the run's `input/` sub-prefix rather than to any shared location

### Requirement: The pinned runner artifact lives outside the run tree
The version-pinned runner JAR SHALL be stored at `releases/<version>/benchmark-runner.jar`, a
top-level prefix distinct from `runs/`. It SHALL NOT be copied into each run's prefix.

#### Scenario: One copy serves many runs
- **WHEN** several runs launch from the same CLI version
- **THEN** the bucket holds one runner JAR object for that version, not one per run

#### Scenario: The artifact prefix is unambiguous in a listing
- **WHEN** the bucket's top-level prefixes are listed
- **THEN** the runner artifact prefix is distinguishable from the run prefix without inspecting
  further path segments

### Requirement: CI runs use the same layout as any other run
Continuous-integration runs SHALL write to `runs/<project>/<runId>/` like any other run, and SHALL
pass an explicit project. A dedicated top-level CI prefix SHALL NOT exist. Each CI job SHALL be a
distinct run with its own identifier.

#### Scenario: Two CI jobs do not share a prefix
- **WHEN** a CI workflow runs two benchmark jobs
- **THEN** each writes its own `run-status` under its own run prefix, and neither overwrites the
  other

#### Scenario: CI runs are attributed
- **WHEN** a CI run stores a measurement
- **THEN** the measurement's project is the one CI passed explicitly, not a fallback value

### Requirement: A run's artifacts are located through its stored path, not a reconstructed one
Consumers SHALL resolve a run's artifacts through the path attributes recorded on its stored
measurements rather than by rebuilding a path from the run's other attributes. Path attributes of
existing measurements SHALL remain authoritative after any relocation.

#### Scenario: An older run resolves after the layout changes
- **WHEN** a run stored before this change is downloaded
- **THEN** its artifacts are found at the path its measurement records, whatever shape that path has

#### Scenario: Reconstruction is not required
- **WHEN** a run's artifacts are fetched
- **THEN** no consumer needs to know which layout the run was written under

### Requirement: Existing runs are relocated without altering their stored keys
Runs written before this change SHALL be relocated into `runs/<project>/<existing-requestId>/`.
The relocation SHALL copy objects, then update the stored `resultPath`, `resultJsonKey`,
`environmentJsonKey` and profiler-output prefix attributes. It SHALL NOT modify any partition key or
sort key, and SHALL NOT mint new identifiers for existing runs. It SHALL be idempotent and SHALL
offer a dry run that reports every copy and every attribute update without performing either.

#### Scenario: Keys are untouched
- **WHEN** the relocation completes
- **THEN** every measurement has the same partition key and sort key it had before

#### Scenario: Relocation is re-runnable
- **WHEN** the relocation is run a second time
- **THEN** it completes without error and changes nothing

#### Scenario: Dry run performs no writes
- **WHEN** the relocation is invoked in dry-run mode
- **THEN** it reports the intended copies and attribute updates, and neither copies an object nor
  updates an item

#### Scenario: Measurements stored under an unresolved project are relocated too
- **WHEN** measurements exist whose project was never recorded and defaulted to a placeholder
- **THEN** they are relocated under that placeholder project segment, with their keys unchanged

#### Scenario: Missing inputs do not fail the relocation
- **WHEN** an older run's uploaded input JAR has already been expired by a lifecycle rule
- **THEN** the run's remaining artifacts are relocated and the relocation reports the absence rather
  than failing
