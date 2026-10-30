# Spec Delta

## MODIFIED Requirements

### Requirement: The mechanism is not narrowed to the type the self-test exercises
The benchmark type and the benchmark's arguments SHALL remain caller-supplied parameters of a
CI-launched job. The fact that this project's own self-test exercises one type with one profiler SHALL
NOT restrict which types, profilers or arguments a consuming repository may run through the same
mechanism, and no workflow, option default or validation SHALL hard-code a single type or profiler as the
only permitted value.

#### Scenario: A consumer selects a different type
- **WHEN** a consuming repository's workflow invokes the job command with `jcstress`, or with `jmh` and
  no profiler
- **THEN** the job proceeds exactly as the self-test's profiled `jmh` job does, with no type-specific
  carve-out

#### Scenario: No type is privileged
- **WHEN** the job command's type parameter is inspected
- **THEN** every supported benchmark type remains selectable, and none is hard-coded as the only
  option

### Requirement: The self-test covers the image-dependent benchmark type and discards its numbers
This project's own continuous-integration benchmark self-test SHALL exercise a `jmh` job profiled with
async-profiler, because its failure modes depend on the baked runner image: kernel tunables, the pinned
profiler and `perf` versions, and profiling-artifact upload. It SHALL request one profiler output
format, and SHALL fail unless a flame graph the job produced names the fixture's benchmark method,
rather than accepting any flame-graph file. Its measurements SHALL be tagged `hidden=true`, because
they measure fixture code and carry no analytical meaning. It SHALL launch its job detached and follow
it to its end by the job identifier, with a wait bounded by the job's watchdog bound, so that a
regression in the detached path fails CI.

#### Scenario: Self-test measurements never reach a comparison
- **WHEN** the self-test completes and `baas results query` is queried for the project
- **THEN** the self-test's measurements are absent from the returned rows

#### Scenario: Image-dependent behaviour is covered
- **WHEN** the self-test passes
- **THEN** the baked image's profiler, `perf` and kernel tunables were exercised end to end, and
  profiling artifacts were uploaded to the job's result path

#### Scenario: An empty flame graph fails CI
- **WHEN** the job's flame graph holds no frame naming the fixture's benchmark method
- **THEN** the CI job fails, although a flame-graph file exists

#### Scenario: The profiled job is tagged by its profiler
- **WHEN** the self-test's job completes
- **THEN** its measurements carry `type=jmh` and `profilers=async`

#### Scenario: The detached path is covered
- **WHEN** the self-test launches its job with `--detach`
- **THEN** the launch reports `launched`, and the CI job polls the job by its identifier until a
  terminal status and fails unless that status is `completed`

#### Scenario: A detached self-test cannot hang CI
- **WHEN** the detached job never reaches a terminal status
- **THEN** the CI job's wait ends once the job's watchdog bound has passed, and the CI job fails
