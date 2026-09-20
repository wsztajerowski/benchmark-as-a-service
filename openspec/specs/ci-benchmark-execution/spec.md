# ci-benchmark-execution Specification

## Purpose

Defines how continuous integration executes benchmarks: by driving the published CLI rather than
re-implementing provisioning, identity and artifact handling in workflow shell, and what the
project's own self-test is permitted to assert about measurements it deliberately discards.

## Requirements

### Requirement: Continuous integration drives the CLI, not its own orchestrator
A continuous-integration benchmark job SHALL obtain a benchmark run by invoking the CLI's run
command. It SHALL NOT provision or terminate the benchmark instance, mint a run identifier,
construct an S3 key, install a JDK or profiler onto the measuring instance, or read database
configuration from a parameter store. A workflow SHALL NOT re-implement any responsibility the CLI
already owns, so that a behaviour change in the CLI cannot leave CI measuring something different
from a developer's laptop.

#### Scenario: The workflow contains no provisioning
- **WHEN** the continuous-integration benchmark workflow is inspected
- **THEN** it issues no instance launch, termination or tagging call, and no identifier or S3 key is
  assembled in shell

#### Scenario: The measuring instance installs nothing
- **WHEN** a continuous-integration run executes
- **THEN** the benchmark instance boots the runner image and installs no toolchain, exactly as a
  run launched from a developer's machine does

#### Scenario: One job, one run
- **WHEN** the continuous-integration benchmark workflow runs
- **THEN** it consists of a single job on a hosted runner, and the benchmark instance is never a
  continuous-integration agent

### Requirement: Continuous integration authenticates as the operator with no intermediate role
A continuous-integration job SHALL obtain AWS credentials by federating its workload identity
directly into the operator role, with no intermediate role and no long-lived credential. The
operator role's maximum session duration SHALL be at least the configured benchmark timeout plus
its termination margin, so that credentials cannot expire while a run is still being polled. A
separate continuous-integration role SHALL NOT exist.

#### Scenario: Credentials outlive the run they poll
- **WHEN** a benchmark configured with the default timeout is launched from continuous integration
- **THEN** the job's credentials remain valid until the run completes, and the CLI terminates the
  instance itself rather than leaving the shell watchdog to do it

#### Scenario: No second assumable role exists
- **WHEN** the account's roles trusting this repository's workload identity are enumerated
- **THEN** the operator role is the only one

#### Scenario: Federation is declared on the role it grants
- **WHEN** the question "which workload may act as the operator" is asked
- **THEN** it is answered by reading the operator role's trust policy, without scanning identity
  policies elsewhere in the account

### Requirement: The mechanism is not narrowed to the type the self-test exercises
The benchmark type SHALL remain a caller-supplied parameter of a continuous-integration run. The
fact that this project's own self-test exercises one type SHALL NOT restrict which types a
consuming repository may run through the same mechanism, and no workflow, option default or
validation SHALL hard-code a single type as the only permitted value.

#### Scenario: A consumer selects a different type
- **WHEN** a consuming repository's workflow invokes the run command with `jmh` or `jcstress`
- **THEN** the run proceeds exactly as `jmh-with-async` does, with no type-specific carve-out

#### Scenario: No type is privileged
- **WHEN** the run command's type parameter is inspected
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
  profiling artifacts were uploaded to the run's result path

### Requirement: A continuous-integration job can correlate and diagnose the run it launched
A continuous-integration job SHALL obtain the identifier of the run it launched from the run
command's own machine-readable output, without parsing diagnostic logs. It SHALL be able to retrieve
that run's stored measurements and artifacts by that identifier, including when the run was tagged
for exclusion and including when the run failed.

#### Scenario: The identifier is read from structured output
- **WHEN** a continuous-integration job launches a run
- **THEN** it reads the run identifier from a machine-readable summary rather than from a log line

#### Scenario: An excluded run is still assertable
- **WHEN** the self-test asserts on the run it just launched by that run's identifier
- **THEN** the run's measurements are returned, despite carrying `exclude_from_results=true`

#### Scenario: A failed run is diagnosable
- **WHEN** a continuous-integration run fails
- **THEN** the job still has the run identifier, and the run's boot log and any produced output are
  retrievable from its result path

### Requirement: Self-test assertions are satisfiable by the run that produces them
An assertion made by the self-test SHALL be expressed against values the launching job itself
supplied or received. A hard-coded expected value that no job writes SHALL NOT stand as an
assertion.

#### Scenario: Assertion and run agree by construction
- **WHEN** the self-test asserts on a tag value
- **THEN** the asserted value is the one that job passed to the run command, not a literal
  maintained separately from it

#### Scenario: A never-passing assertion is not reintroduced
- **WHEN** the self-test's assertions are reviewed
- **THEN** each one has been observed to pass against a real run

### Requirement: The benchmark self-test is triggered deliberately, not on every push
Because a continuous-integration benchmark run provisions a paid instance, the self-test SHALL be
triggered by a path-filtered pull-request event plus an explicit manual dispatch. A change touching
no code path the self-test covers SHALL NOT trigger it.

#### Scenario: A documentation-only change pays nothing
- **WHEN** a pull request changes only documentation
- **THEN** the benchmark self-test does not run and no instance is launched

#### Scenario: The self-test can be demanded on request
- **WHEN** a maintainer dispatches the workflow manually
- **THEN** it runs without requiring a pull request to be opened
