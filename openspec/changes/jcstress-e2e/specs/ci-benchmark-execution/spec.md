# Spec Delta

## MODIFIED Requirements

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
- **THEN** it consists of one job on a hosted runner per benchmark type the self-test covers, each
  job launches exactly one run through the CLI, and no benchmark instance is ever a
  continuous-integration agent

## ADDED Requirements

### Requirement: The self-test also covers JCStress, in sanity mode
Besides the image-dependent type, this project's continuous-integration self-test SHALL run a
JCStress fixture through the CLI, in JCStress sanity mode, with the CLI attached until the run ends.
Its run SHALL be tagged `exclude_from_results=true`. Its assertions SHALL concern the shape of what
the run stored and uploaded, never the fixture's pass or fail counts, because the fixture contains a
test whose outcome depends on whether a data race fires.

#### Scenario: A JCStress regression fails CI
- **WHEN** a change breaks the runner's JCStress execution, report parsing or storage
- **THEN** the self-test's JCStress job fails, rather than the regression reaching a consumer

#### Scenario: The JCStress run is asserted by shape
- **WHEN** the JCStress job's run completes
- **THEN** the job asserts that the run ended completed, that exactly one JCStress measurement is
  retrievable by the run's identifier carrying the job's project, branch, commit and source, and that
  the JCStress process output and the environment manifest reached the run's result path

#### Scenario: A racing fixture does not make CI flaky
- **WHEN** the fixture's forbidden outcome fires in one run and not in another
- **THEN** both runs pass the self-test, since no assertion depends on the pass or fail counts
