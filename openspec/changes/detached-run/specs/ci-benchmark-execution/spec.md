# Spec Delta

## MODIFIED Requirements

### Requirement: The self-test covers the image-dependent benchmark type and discards its numbers
This project's own continuous-integration benchmark self-test SHALL exercise the benchmark type
whose failure modes depend on the baked runner image — kernel tunables, the pinned profiler and
`perf` versions, and profiling-artifact upload. Its measurements SHALL be tagged
`exclude_from_results=true`, because they measure fixture code and carry no analytical meaning. It
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
