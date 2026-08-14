# cli-command-structure Specification

## Purpose

The shape of the `baas` picocli command tree: which commands exist, how they nest, and which
privilege tier each grouping implies.

## Requirements

### Requirement: Deployer-privileged commands are grouped under `admin`
The `baas` command tree SHALL group `setup` and `teardown` under a nested `admin` subcommand (`baas admin setup`, `baas admin teardown`). These SHALL NOT be reachable as top-level commands (e.g. `baas setup` directly SHALL NOT exist).

#### Scenario: Admin commands are nested
- **WHEN** a user runs `baas admin setup --help`
- **THEN** picocli shows the setup command's options
- **WHEN** a user runs `baas setup` (without the `admin` prefix)
- **THEN** picocli reports an unknown command error

### Requirement: Daily-use commands remain top-level
`run`, `results`, and `config` (with its `set`/`show` subcommands) SHALL remain directly reachable from the `baas` root command, unaffected by the `admin` grouping.

#### Scenario: Top-level commands unchanged
- **WHEN** a user runs `baas run jmh -- MyBenchmark -f 1`, `baas results`, or `baas config show`
- **THEN** each resolves to the same command implementation as before this change, with no `admin` prefix required

### Requirement: `baas admin build-image` builds the runner image
`baas admin build-image` SHALL render the recipe from `infra/runner-image.yaml`, update the stack when the
recipe version changed, trigger the image build, poll to completion, write the resulting AMI ID to
`/<prefix>/runner/ami-id`, retire the AMI it replaced, and report the new AMI ID. It SHALL run under
deployer credentials (`aws.profile`), consistent with every other `baas admin` subcommand.

#### Scenario: Successful build reports the AMI
- **WHEN** `baas admin build-image` completes
- **THEN** it prints the new AMI ID and image version, and exits 0

#### Scenario: Build failure is surfaced
- **WHEN** the image build fails
- **THEN** the command exits non-zero, reports the Image Builder failure reason, and leaves the pointer
  and the previous AMI untouched

#### Scenario: Build uses deployer credentials
- **WHEN** `config.yaml` sets both `aws.profile` and `aws.operatorProfile` and `baas admin build-image`
  runs
- **THEN** AWS clients are built from `aws.profile`

### Requirement: `baas admin image` reports the current image
`baas admin image` SHALL report the current runner image's version, AMI ID, build timestamp, and parent
AMI ID, and SHALL report clearly when no image has been built. Command payload SHALL be written to
`System.out` rather than the logger, so it remains pipeable.

#### Scenario: Current image is reported
- **WHEN** an image has been built and `baas admin image` runs
- **THEN** the output names the image version, AMI ID, build time, and parent AMI

#### Scenario: No image built yet
- **WHEN** no image has been built and `baas admin image` runs
- **THEN** the output states that no image exists and names `baas admin build-image`

### Requirement: `baas run` requires a built image
`baas run` SHALL resolve the runner AMI from `/<prefix>/runner/ami-id` and SHALL fail before provisioning
any resource when that parameter is absent or names an AMI that no longer exists. The failure message
SHALL name `baas admin build-image`.

#### Scenario: No image built yet
- **WHEN** `baas run jmh -- MyBenchmark` is invoked with no AMI pointer present
- **THEN** the command exits non-zero naming `baas admin build-image`, and no EC2 instance is launched

#### Scenario: Pointer names a deleted AMI
- **WHEN** the pointer resolves to an AMI that has been deregistered
- **THEN** the command exits non-zero without launching an instance

### Requirement: `baas run` accepts an explicit AMI override
`baas run --ami-id <id>` SHALL launch from the given AMI instead of the pointer, failing when that AMI
does not exist. There SHALL be no version-selection option, because exactly one image is maintained.

#### Scenario: Explicit AMI override wins
- **WHEN** `--ami-id ami-abc123` is given and that AMI exists
- **THEN** the instance is launched from it regardless of the pointer value

#### Scenario: Override naming a missing AMI fails before provisioning
- **WHEN** `--ami-id ami-missing` is given and no such AMI exists
- **THEN** the command exits non-zero and no instance is launched

### Requirement: `baas env diff` compares two runs' environments
`baas env diff <resultPathA> <resultPathB>` SHALL be available as a top-level command, alongside the other
day-to-day commands, and SHALL run under operator credentials.

#### Scenario: Command is top-level, not under admin
- **WHEN** `baas --help` is rendered
- **THEN** `env` appears as a top-level command and not as an `admin` subcommand

#### Scenario: Output is pipeable
- **WHEN** `baas env diff` output is redirected to a file
- **THEN** the payload contains no logger timestamp prefixes
