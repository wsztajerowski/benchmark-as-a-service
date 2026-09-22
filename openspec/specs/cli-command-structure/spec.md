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
`--project <name>` to override it. Derivation SHALL resolve the main repository, so that a run launched
from a linked worktree is attributed to the repository rather than to the worktree directory. When
neither is available the command SHALL fail before provisioning.

#### Scenario: Derived project is used
- **WHEN** `baas run` is invoked inside a git repository with no `--project`
- **THEN** the repository name is forwarded as the `project` tag

#### Scenario: A linked worktree resolves to its repository
- **WHEN** `baas run` is invoked from a linked worktree with no `--project`
- **THEN** the repository's name is forwarded, not the worktree directory's name

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
The CLI SHALL provide a command taking either a run identifier or a literal S3 result path, plus a
destination directory, downloading every S3 object under that run's prefix. A run identifier SHALL be
resolved to the run's stored result path rather than reconstructed from its other attributes.

#### Scenario: Artifacts land locally
- **WHEN** the command is invoked with a run identifier and a destination
- **THEN** the destination contains the run's result JSON, `environment.json`, process output and logs

#### Scenario: A literal result path is accepted
- **WHEN** the command is invoked with a result path rather than an identifier
- **THEN** that prefix is downloaded

#### Scenario: Destination is reported
- **WHEN** the download completes
- **THEN** the command prints the destination path and exits 0

### Requirement: Teardown reports what it retains
`baas admin teardown` SHALL state, before the confirmation prompt, that the results table and the working
bucket are retained by default, so an operator is not left believing that history was deleted. Its
messages SHALL describe the retained names as derived from the installation's AWS account, and SHALL
NOT attribute them to the caller's identity.

#### Scenario: Retention is stated before confirmation
- **WHEN** `baas admin teardown` prompts for confirmation
- **THEN** the prompt text names both the retained bucket and the retained results table

#### Scenario: Retention message explains the name a re-setup will ask for
- **WHEN** `baas admin teardown` completes having retained the bucket
- **THEN** the message states that a later `baas admin setup` in the same account will request that same bucket name, and says how to keep or remove it

### Requirement: `baas run` consumes a pre-built benchmark JAR
`baas run` SHALL NOT build the benchmark project. It SHALL require the benchmark JAR to be named
explicitly on the command line, SHALL NOT fall back to a configured or defaulted path, and SHALL
fail before resolving the runner image and before any upload when no JAR is named or the named JAR
does not exist. The failure message SHALL name the option that supplies it.

#### Scenario: A named JAR is used as-is
- **WHEN** `baas run --benchmark-jar target/benchmarks.jar jmh -- MyBenchmark` is invoked
- **THEN** that JAR is uploaded for the run, and no build is performed in the working directory

#### Scenario: An unnamed JAR fails before provisioning
- **WHEN** `baas run jmh -- MyBenchmark` is invoked with no benchmark JAR named
- **THEN** the command exits non-zero naming the required option, no EC2 instance is launched, and
  nothing is uploaded

#### Scenario: A named JAR that does not exist fails before provisioning
- **WHEN** the named benchmark JAR does not exist
- **THEN** the command exits non-zero naming the path it tried, and no EC2 instance is launched

#### Scenario: No build is attempted in any circumstance
- **WHEN** `baas run` is invoked from a directory containing a buildable project
- **THEN** no build tool is invoked, and the run uses only the named JAR

### Requirement: `commit` and `branch` are derived, with overrides, and omitted when unknown
`baas run` SHALL derive the `commit` and `branch` values from the current git repository and SHALL
accept `--commit <value>` and `--branch <value>` to override them. When a value is neither supplied
nor derivable, the corresponding tag SHALL be omitted from the run entirely; the command SHALL NOT
substitute a placeholder value, and SHALL NOT fail.

#### Scenario: Derived values are forwarded
- **WHEN** `baas run` is invoked inside a git repository with neither option given
- **THEN** the repository's current commit and branch are forwarded as the `commit` and `branch` tags

#### Scenario: Explicit values win over derivation
- **WHEN** `--commit` or `--branch` is given inside a git repository
- **THEN** the supplied value is forwarded and the derived one is not

#### Scenario: An unresolvable value is omitted, not invented
- **WHEN** `baas run` is invoked outside a git repository with neither option given
- **THEN** the run proceeds, and its stored measurement carries no `commit` and no `branch` tag

#### Scenario: No placeholder value reaches the stored result
- **WHEN** a run's commit or branch cannot be resolved
- **THEN** no tag is stored whose value stands in for the unknown value

### Requirement: `baas run` can report its outcome as a machine-readable object
`baas run` SHALL accept a `--format json` option that writes a single summary object to standard
output, carrying at least the run identifier, the project, the run's S3 result path, the outcome, the
process exit code and the launched instance's identifier. The object SHALL be written on the failure
path as well as on success — a failed run is precisely when its identifier is needed — and the command
SHALL still exit non-zero when the run failed. Diagnostics SHALL remain on the logger so that standard
output holds the object alone.

#### Scenario: The summary is machine-readable
- **WHEN** `baas run --format json jmh -- MyBenchmark` completes and its standard output is piped to a
  JSON parser
- **THEN** the parser succeeds, and the parsed object carries the run identifier, project, result path,
  outcome, exit code and instance identifier

#### Scenario: A failed run still reports its identifier
- **WHEN** a run launched with `--format json` fails
- **THEN** the summary object is written with a failed outcome and the run's exit code, and the command
  exits non-zero

#### Scenario: Diagnostics do not corrupt the object
- **WHEN** `baas run -v --format json` is redirected to a file
- **THEN** the file contains exactly one JSON object with no timestamp or log-level prefix, and verbose
  diagnostics appear on standard error

#### Scenario: Default output is unchanged
- **WHEN** `baas run` is invoked without `--format`
- **THEN** its human-readable progress reporting is unchanged and no JSON object is written

### Requirement: Resource names the CLI needs are derived or resolved, not cached
`~/.baas/config.yaml` SHALL store only what cannot be obtained from the installation itself: the
credential settings, the region, the installation prefix, and the operator's own preferences. Names
the composition rule determines — the working bucket, the results table and the runner instance
profile — SHALL be derived from the prefix at use time. Identifiers AWS assigns, specifically the
runner subnet and security group, SHALL be resolved from the installation's stack outputs at use
time rather than cached, so that a replaced resource cannot leave a stale identifier behind.

#### Scenario: A replaced security group does not strand the configuration
- **WHEN** the runner security group is replaced by a stack update and its identifier changes, and `baas run` is invoked afterwards with no intervening configuration command
- **THEN** the run uses the current security group identifier

#### Scenario: Config carries no derivable names
- **WHEN** `baas config show` reports the configuration after `baas config sync`
- **THEN** no stored field holds the bucket name, the results table name or the runner instance profile name

### Requirement: Read-only commands can address another installation's results table
`baas results` and `baas download` SHALL accept `--results-table <name>`, addressing that table for
the invocation only and persisting nothing. No command that writes measurements SHALL accept such an
override, so an operator cannot leave a configuration pointed at an archive and silently record new
runs into it.

#### Scenario: Reading a retired installation's history
- **WHEN** `baas results --results-table baas-3q7i7s65-results` runs on a machine configured for the current installation
- **THEN** the retired table's measurements are reported and `~/.baas/config.yaml` is unchanged

#### Scenario: The override is absent from the write path
- **WHEN** `baas run --results-table other-table` or `baas config set --results-table other-table` is invoked
- **THEN** picocli reports an unknown option error
