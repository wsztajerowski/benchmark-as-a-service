# Spec Delta

## MODIFIED Requirements

### Requirement: The self-test covers the image-dependent benchmark type and discards its numbers
This project's own continuous-integration benchmark self-test SHALL exercise the benchmark type
whose failure modes depend on the baked runner image — kernel tunables, the pinned profiler and
`perf` versions, and profiling-artifact upload. Its measurements SHALL be tagged
`exclude_from_results=true`, because they measure fixture code and carry no analytical meaning.

#### Scenario: Self-test measurements never reach a comparison
- **WHEN** the self-test completes and `baas results query` is queried for the project
- **THEN** the self-test's measurements are absent from the returned rows

#### Scenario: Image-dependent behaviour is covered
- **WHEN** the self-test passes
- **THEN** the baked image's profiler, `perf` and kernel tunables were exercised end to end, and
  profiling artifacts were uploaded to the job's result path

### Requirement: The self-test names its project, branch and commit explicitly
The self-test SHALL pass the project with `--project` and the branch and commit with `--tag`, taking
the values from the workflow's own context, because `baas run` derives none of them by default. It
SHALL NOT enable `git.resolveProject`.

#### Scenario: The stored job carries the workflow's values
- **WHEN** the self-test's job completes
- **THEN** its measurements carry the `project`, `branch` and `commit` the CI job passed, and the CI job
  asserts them from the JSON `tags` object returned by `baas results query --job-id`

#### Scenario: CI does not depend on git derivation
- **WHEN** the self-test runs with a fresh configuration written by `baas config sync`
- **THEN** the job succeeds without `git.resolveProject` being set
