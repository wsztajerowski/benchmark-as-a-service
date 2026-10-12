# Spec Delta

## MODIFIED Requirements

### Requirement: The self-test names its project, branch and commit explicitly
The self-test SHALL pass the project with `--project` and the branch and commit with `--tag`, taking
the values from the workflow's own context, because `baas run` derives none of them by default. It
SHALL NOT enable `git.resolveProject`.

#### Scenario: The stored job carries the workflow's values
- **WHEN** the self-test's job completes
- **THEN** its measurements carry the `project`, `branch` and `commit` the CI job passed, and the CI job
  asserts them from the JSON `tags` object returned by `baas results query --job-id`

#### Scenario: CI does not depend on git derivation
- **WHEN** the self-test runs with no configuration file on the CI machine
- **THEN** the job succeeds without `git.resolveProject` being set

## ADDED Requirements

### Requirement: Continuous integration names its deployment only through the operator role
A continuous-integration job SHALL configure only the operator role to federate into and a region for
the credential exchange. It SHALL NOT run `baas config sync`, pass `--deployment`, or hold a
repository variable naming the deployment: the CLI takes the deployment from the operator role the job
federated into, and the deployment's region from its bucket.

#### Scenario: The workflow carries no deployment name
- **WHEN** `e2e-cloud-test.yml` is inspected
- **THEN** it contains no `config sync` step, no `--deployment` and no reference to `CORE_STACK_NAME`

#### Scenario: A region mismatch does not misdirect CI
- **WHEN** the CI job's `AWS_REGION` differs from the region the deployment was created in
- **THEN** the job runs on the deployment in its own region
