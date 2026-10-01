# Spec Delta

## ADDED Requirements

### Requirement: The self-test names its project, branch and commit explicitly
The self-test SHALL pass the project with `--project` and the branch and commit with `--tag`, taking
the values from the workflow's own context, because `baas run` derives none of them by default. It
SHALL NOT enable `git.resolveProject`.

#### Scenario: The stored run carries the workflow's values
- **WHEN** the self-test's run completes
- **THEN** its measurements carry the `project`, `branch` and `commit` the job passed, and the job
  asserts them from the JSON `tags` object returned by `baas results --request-id`

#### Scenario: CI does not depend on git derivation
- **WHEN** the self-test runs with a fresh configuration written by `baas config sync`
- **THEN** the run succeeds without `git.resolveProject` being set
