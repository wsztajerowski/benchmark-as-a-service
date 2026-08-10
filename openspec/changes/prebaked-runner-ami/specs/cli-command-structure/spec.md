## ADDED Requirements

### Requirement: `baas admin build-image` builds the current image
`baas admin build-image` SHALL render the recipe from `infra/runner-image.yaml`, update the stack when the
recipe version changed, trigger the image build, poll to completion, archive provenance to S3, write the
`current` slot pointer, and report the resulting AMI ID. It SHALL run under deployer credentials
(`aws.profile`), consistent with every other `baas admin` subcommand.

#### Scenario: Successful build reports the AMI
- **WHEN** `baas admin build-image` completes
- **THEN** it prints the new AMI ID and image version, and exits 0

#### Scenario: Build failure is surfaced
- **WHEN** the image build fails
- **THEN** the command exits non-zero, reports the Image Builder failure reason, and leaves the previous
  slot pointer untouched

#### Scenario: Build uses deployer credentials
- **WHEN** `config.yaml` sets both `aws.profile` and `aws.operatorProfile` and `baas admin build-image`
  runs
- **THEN** AWS clients are built from `aws.profile`

### Requirement: `baas admin images` reports both slots
`baas admin images` SHALL list each occupied slot with its slot name, image version, AMI ID, build
timestamp, and drift status. Command payload SHALL be written to `System.out` rather than the logger, so
it remains pipeable.

#### Scenario: Both slots listed
- **WHEN** both slots are occupied and `baas admin images` runs
- **THEN** two rows are printed, one per slot, each naming version, AMI ID, build time, and drift status

#### Scenario: Empty adhoc slot is reported as empty
- **WHEN** only the current slot is occupied
- **THEN** the output shows the current slot and reports the adhoc slot as empty

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

### Requirement: `baas run` can select a specific image
`baas run --image-version <v>` SHALL resolve the AMI whose `baas-image-version` tag matches `<v>` from
either slot, and SHALL fail naming `baas admin build-image --from-version <v>` when no occupied slot
matches. `baas run --ami-id <id>` SHALL use the given AMI directly, failing if it does not exist.

#### Scenario: Version resolves from the adhoc slot
- **WHEN** the adhoc slot holds version `1.2.0` and `baas run --image-version 1.2.0 jmh -- MyBenchmark`
  is invoked
- **THEN** the instance is launched from the adhoc slot's AMI and results are tagged `imageSlot=adhoc`

#### Scenario: Version not present in either slot
- **WHEN** `--image-version 1.1.0` is requested and neither slot holds it
- **THEN** the command exits non-zero naming `baas admin build-image --from-version 1.1.0`

#### Scenario: Explicit AMI override wins
- **WHEN** `--ami-id ami-abc123` is given and that AMI exists
- **THEN** the instance is launched from it regardless of either slot pointer

## REMOVED Requirements

### Requirement: Runner installs its toolchain at boot
**Reason**: `yum update -y` on every run let the OS, JDK patch level, and profiler version drift between
measurements, making results incomparable (finding A8), and it required outbound access to yum
repositories and GitHub, which blocks the private-subnet change. Tooling now comes from a pinned AMI.

**Migration**: Run `baas admin build-image` once per account before the first `baas run`. Existing
`config.yaml` files need no change; the AMI pointer is read from SSM. Runs that previously relied on the
boot-time install now fail fast with a message naming the build command.
