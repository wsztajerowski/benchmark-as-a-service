# Spec Delta

## MODIFIED Requirements

### Requirement: The self-test covers the image-dependent benchmark type and discards its numbers
This project's own continuous-integration benchmark self-test SHALL exercise the benchmark type
whose failure modes depend on the baked runner image — kernel tunables, the pinned profiler and
`perf` versions, and profiling-artifact upload. Its measurements SHALL be tagged
`hidden=true`, because they measure fixture code and carry no analytical meaning. It
SHALL launch its job detached and follow it to its end by the job identifier, with a wait bounded by
the job's watchdog bound, so that a regression in the detached path fails CI.

#### Scenario: Self-test measurements never reach a comparison
- **WHEN** the self-test completes and `baas results query` is queried for the project
- **THEN** the self-test's measurements are absent from the returned rows

#### Scenario: Image-dependent behaviour is covered
- **WHEN** the self-test passes
- **THEN** the baked image's profiler, `perf` and kernel tunables were exercised end to end, and
  profiling artifacts were uploaded to the job's result path

#### Scenario: The detached path is covered
- **WHEN** the self-test launches its job with `--detach`
- **THEN** the launch reports `launched`, and the CI job polls the job by its identifier until a
  terminal status and fails unless that status is `completed`

#### Scenario: A detached self-test cannot hang CI
- **WHEN** the detached job never reaches a terminal status
- **THEN** the CI job's wait ends once the job's watchdog bound has passed, and the CI job fails

### Requirement: A continuous-integration job can correlate and diagnose the job it launched
A continuous-integration job SHALL obtain the identifier of the job it launched from the job
command's own machine-readable output, without parsing diagnostic logs. It SHALL be able to retrieve
that job's stored measurements and artifacts by that identifier, including when the job was tagged
for exclusion and including when the job failed.

#### Scenario: The identifier is read from structured output
- **WHEN** a continuous-integration job launches a job
- **THEN** it reads the job identifier from a machine-readable summary rather than from a log line

#### Scenario: An excluded job is still assertable
- **WHEN** the self-test asserts on the job it just launched by that job's identifier
- **THEN** the job's measurements are returned, despite carrying `hidden=true`

#### Scenario: A failed job is diagnosable
- **WHEN** a CI-launched job fails
- **THEN** the CI job still has the job identifier, and the job's boot log and any produced output are
  retrievable from its result path

### Requirement: The self-test also covers JCStress, in sanity mode
Besides the image-dependent type, this project's continuous-integration self-test SHALL run a
JCStress fixture through the CLI, in JCStress sanity mode, with the CLI attached until the job ends.
Its job SHALL be tagged `hidden=true`. Its assertions SHALL concern the shape of what
the job stored and uploaded, never the fixture's pass or fail counts, because the fixture contains a
test whose outcome depends on whether a data race fires.

#### Scenario: A JCStress regression fails CI
- **WHEN** a change breaks the runner's JCStress execution, report parsing or storage
- **THEN** the self-test's JCStress CI job fails, rather than the regression reaching a consumer

#### Scenario: The JCStress job is asserted by shape
- **WHEN** the JCStress CI job's job completes
- **THEN** the CI job asserts that the job ended completed, that exactly one JCStress measurement is
  retrievable by the job's identifier carrying the CI job's project, branch, commit and source, and that
  the JCStress process output and the environment manifest reached the job's result path

#### Scenario: A racing fixture does not make CI flaky
- **WHEN** the fixture's forbidden outcome fires in one job and not in another
- **THEN** both jobs pass the self-test, since no assertion depends on the pass or fail counts
