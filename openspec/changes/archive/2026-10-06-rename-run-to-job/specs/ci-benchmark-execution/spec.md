# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: A continuous-integration job can correlate and diagnose the run it launched`
- TO: `### Requirement: A continuous-integration job can correlate and diagnose the job it launched`

- FROM: `### Requirement: Self-test assertions are satisfiable by the run that produces them`
- TO: `### Requirement: Self-test assertions are satisfiable by the job that produces them`

## MODIFIED Requirements

### Requirement: Continuous integration drives the CLI, not its own orchestrator
A continuous-integration benchmark job SHALL obtain a benchmark job by invoking the CLI's run
command. It SHALL NOT provision or terminate the benchmark instance, mint a job identifier,
construct an S3 key, install a JDK or profiler onto the measuring instance, or read database
configuration from a parameter store. A workflow SHALL NOT re-implement any responsibility the CLI
already owns, so that a behaviour change in the CLI cannot leave CI measuring something different
from a developer's laptop.

#### Scenario: The workflow contains no provisioning
- **WHEN** the continuous-integration benchmark workflow is inspected
- **THEN** it issues no instance launch, termination or tagging call, and no identifier or S3 key is
  assembled in shell

#### Scenario: The measuring instance installs nothing
- **WHEN** a CI-launched job executes
- **THEN** the benchmark instance boots the runner image and installs no toolchain, exactly as a
  job launched from a developer's machine does

#### Scenario: One job, one run
- **WHEN** the continuous-integration benchmark workflow runs
- **THEN** it consists of one CI job on a hosted runner per benchmark type the self-test covers, each
  CI job launches exactly one job through the CLI, and no benchmark instance is ever a
  continuous-integration agent

### Requirement: Continuous integration authenticates as the operator with no intermediate role
A continuous-integration job SHALL obtain AWS credentials by federating its workload identity
directly into the operator role, with no intermediate role and no long-lived credential. The
operator role's maximum session duration SHALL be at least the configured benchmark timeout plus
its termination margin, so that credentials cannot expire while a job is still being polled. A
separate continuous-integration role SHALL NOT exist.

#### Scenario: Credentials outlive the run they poll
- **WHEN** a benchmark configured with the default timeout is launched from continuous integration
- **THEN** the CI job's credentials remain valid until the job completes, and the CLI terminates the
  instance itself rather than leaving the shell watchdog to do it

#### Scenario: No second assumable role exists
- **WHEN** the account's roles trusting this repository's workload identity are enumerated
- **THEN** the operator role is the only one

#### Scenario: Federation is declared on the role it grants
- **WHEN** the question "which workload may act as the operator" is asked
- **THEN** it is answered by reading the operator role's trust policy, without scanning identity
  policies elsewhere in the account

### Requirement: The mechanism is not narrowed to the type the self-test exercises
The benchmark type SHALL remain a caller-supplied parameter of a CI-launched job. The
fact that this project's own self-test exercises one type SHALL NOT restrict which types a
consuming repository may run through the same mechanism, and no workflow, option default or
validation SHALL hard-code a single type as the only permitted value.

#### Scenario: A consumer selects a different type
- **WHEN** a consuming repository's workflow invokes the job command with `jmh` or `jcstress`
- **THEN** the job proceeds exactly as `jmh-with-async` does, with no type-specific carve-out

#### Scenario: No type is privileged
- **WHEN** the job command's type parameter is inspected
- **THEN** every supported benchmark type remains selectable, and none is hard-coded as the only
  option

### Requirement: The self-test covers the image-dependent benchmark type and discards its numbers
This project's own continuous-integration benchmark self-test SHALL exercise the benchmark type
whose failure modes depend on the baked runner image — kernel tunables, the pinned profiler and
`perf` versions, and profiling-artifact upload. Its measurements SHALL be tagged
`exclude_from_results=true`, because they measure fixture code and carry no analytical meaning.

#### Scenario: Self-test measurements never reach a comparison
- **WHEN** the self-test completes and `baas results` is queried for the project
- **THEN** the self-test's measurements are absent from the returned rows

#### Scenario: Image-dependent behaviour is covered
- **WHEN** the self-test passes
- **THEN** the baked image's profiler, `perf` and kernel tunables were exercised end to end, and
  profiling artifacts were uploaded to the job's result path

### Requirement: A continuous-integration job can correlate and diagnose the job it launched
A continuous-integration job SHALL obtain the identifier of the job it launched from the job
command's own machine-readable output, without parsing diagnostic logs. It SHALL be able to retrieve
that job's stored measurements and artifacts by that identifier, including when the job was tagged
for exclusion and including when the job failed.

#### Scenario: The identifier is read from structured output
- **WHEN** a continuous-integration job launches a job
- **THEN** it reads the job identifier from a machine-readable summary rather than from a log line

#### Scenario: An excluded run is still assertable
- **WHEN** the self-test asserts on the job it just launched by that job's identifier
- **THEN** the job's measurements are returned, despite carrying `exclude_from_results=true`

#### Scenario: A failed run is diagnosable
- **WHEN** a CI-launched job fails
- **THEN** the CI job still has the job identifier, and the job's boot log and any produced output are
  retrievable from its result path

### Requirement: Self-test assertions are satisfiable by the job that produces them
An assertion made by the self-test SHALL be expressed against values the launching CI job itself
supplied or received. A hard-coded expected value that no CI job writes SHALL NOT stand as an
assertion.

#### Scenario: Assertion and run agree by construction
- **WHEN** the self-test asserts on a tag value
- **THEN** the asserted value is the one that CI job passed to the job command, not a literal
  maintained separately from it

#### Scenario: A never-passing assertion is not reintroduced
- **WHEN** the self-test's assertions are reviewed
- **THEN** each one has been observed to pass against a real job

### Requirement: The benchmark self-test is triggered deliberately, not on every push
Because a continuous-integration benchmark job provisions a paid instance, the self-test SHALL be
triggered by a path-filtered pull-request event plus an explicit manual dispatch. A change touching
no code path the self-test covers SHALL NOT trigger it.

#### Scenario: A documentation-only change pays nothing
- **WHEN** a pull request changes only documentation
- **THEN** the benchmark self-test does not run and no instance is launched

#### Scenario: The self-test can be demanded on request
- **WHEN** a maintainer dispatches the workflow manually
- **THEN** it runs without requiring a pull request to be opened

### Requirement: The self-test names its project, branch and commit explicitly
The self-test SHALL pass the project with `--project` and the branch and commit with `--tag`, taking
the values from the workflow's own context, because `baas run` derives none of them by default. It
SHALL NOT enable `git.resolveProject`.

#### Scenario: The stored run carries the workflow's values
- **WHEN** the self-test's job completes
- **THEN** its measurements carry the `project`, `branch` and `commit` the CI job passed, and the CI job
  asserts them from the JSON `tags` object returned by `baas results --job-id`

#### Scenario: CI does not depend on git derivation
- **WHEN** the self-test runs with a fresh configuration written by `baas config sync`
- **THEN** the job succeeds without `git.resolveProject` being set

### Requirement: The self-test also covers JCStress, in sanity mode
Besides the image-dependent type, this project's continuous-integration self-test SHALL run a
JCStress fixture through the CLI, in JCStress sanity mode, with the CLI attached until the job ends.
Its job SHALL be tagged `exclude_from_results=true`. Its assertions SHALL concern the shape of what
the job stored and uploaded, never the fixture's pass or fail counts, because the fixture contains a
test whose outcome depends on whether a data race fires.

#### Scenario: A JCStress regression fails CI
- **WHEN** a change breaks the runner's JCStress execution, report parsing or storage
- **THEN** the self-test's JCStress CI job fails, rather than the regression reaching a consumer

#### Scenario: The JCStress run is asserted by shape
- **WHEN** the JCStress CI job's job completes
- **THEN** the CI job asserts that the job ended completed, that exactly one JCStress measurement is
  retrievable by the job's identifier carrying the CI job's project, branch, commit and source, and that
  the JCStress process output and the environment manifest reached the job's result path

#### Scenario: A racing fixture does not make CI flaky
- **WHEN** the fixture's forbidden outcome fires in one job and not in another
- **THEN** both jobs pass the self-test, since no assertion depends on the pass or fail counts
