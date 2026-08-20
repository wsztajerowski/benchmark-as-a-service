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

### Requirement: The results table is resolved from configuration, not a secret store
`~/.baas/config.yaml` SHALL carry the results table name, populated from the core stack output by
`baas admin setup` and `baas config sync`. `baas run` SHALL pass it into user-data directly, since the
table name is not sensitive.

#### Scenario: Setup records the table name
- **WHEN** `baas admin setup` completes
- **THEN** `config.yaml` holds the results table name from the stack output

#### Scenario: Config sync populates the table name
- **WHEN** `baas config sync --core-stack-name <name>` runs on a machine with no prior config
- **THEN** the results table name is populated from that stack's outputs

#### Scenario: Table name is carried in user-data
- **WHEN** the user-data script is rendered
- **THEN** it contains the table name and performs no parameter-store lookup for database configuration

### Requirement: User tags are passed through to the runner
`baas run` SHALL forward every `--tag key=value` option into the user-data script as a runner argument,
in addition to applying the EC2 instance tags it already applies. A tag applied only to the instance
SHALL NOT be considered forwarded.

#### Scenario: User tags appear in rendered user-data
- **WHEN** `baas run --tag branch=main --tag experiment=gc jmh -- MyBenchmark` renders user-data
- **THEN** the runner invocation carries `--tag branch=main` and `--tag experiment=gc`

#### Scenario: Environment tags are still forwarded
- **WHEN** user-data is rendered
- **THEN** it still forwards `imageVersion` and `instanceType` observed on the instance

### Requirement: The project is derived, with an override
`baas run` SHALL derive the `project` value from the current git repository's name and SHALL accept
`--project <name>` to override it. When neither is available the command SHALL fail before provisioning.

#### Scenario: Derived project is used
- **WHEN** `baas run` is invoked inside a git repository with no `--project`
- **THEN** the repository name is forwarded as the `project` tag

#### Scenario: Unresolvable project fails before provisioning
- **WHEN** `baas run` is invoked outside a git repository with no `--project`
- **THEN** the command exits non-zero and no EC2 instance is launched

### Requirement: Discarding results requires an explicit flag on the run path
`baas run` SHALL expose a `--no-database` pass-through that selects the no-op store on the runner.
Without it, an unresolvable table name SHALL fail before any instance is launched.

#### Scenario: Unresolvable table fails before provisioning
- **WHEN** `baas run jmh -- MyBenchmark` is invoked with no table name in config and no `--no-database`
- **THEN** the command exits non-zero and no EC2 instance is launched

#### Scenario: Explicit opt-in is honoured
- **WHEN** `baas run --no-database jmh -- MyBenchmark` is invoked
- **THEN** the run proceeds and the runner performs no database write

### Requirement: Results filters cover the supported query patterns
`baas results` SHALL accept `--request-id`, `--benchmark-name`, `--tag <key>=<value>` (repeatable),
`--living-branches`, `--project` and `--limit`. `--request-id` SHALL be mutually exclusive with the other
filters, and an invalid combination SHALL fail with a message naming the supported forms.

#### Scenario: Tag filter is accepted
- **WHEN** `baas results --tag jdk=25.0.4` is invoked
- **THEN** matching rows are returned

#### Scenario: Conflicting filters are rejected
- **WHEN** both `--tag branch=main` and `--request-id abc` are given
- **THEN** the command exits non-zero explaining that `--request-id` cannot be combined with other filters

#### Scenario: Limit bounds the output
- **WHEN** `--limit 5` is given and more rows match
- **THEN** at most five rows are returned

### Requirement: A command downloads a run's S3 artifacts
The CLI SHALL provide a command taking a request ID and a destination directory, downloading every S3
object under that run's prefix.

#### Scenario: Artifacts land locally
- **WHEN** the command is invoked with a request ID and a destination
- **THEN** the destination contains the run's result JSON, `environment.json`, process output and logs

#### Scenario: Destination is reported
- **WHEN** the download completes
- **THEN** the command prints the destination path and exits 0

### Requirement: Teardown reports what it retains
`baas admin teardown` SHALL state, before the confirmation prompt, that the results table and the working
bucket are retained by default, so an operator is not left believing that history was deleted.

#### Scenario: Retention is stated before confirmation
- **WHEN** `baas admin teardown` prompts for confirmation
- **THEN** the prompt text names both the retained bucket and the retained results table
