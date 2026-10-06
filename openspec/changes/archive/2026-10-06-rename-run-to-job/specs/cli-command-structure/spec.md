# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: `baas env diff` compares two runs' environments`
- TO: `### Requirement: `baas env diff` compares two jobs' environments`

- FROM: `### Requirement: A command downloads a run's S3 artifacts`
- TO: `### Requirement: A command downloads a job's S3 artifacts`

## MODIFIED Requirements

### Requirement: Daily-use commands remain top-level
`run`, `jobs` (with its `list`/`terminate` subcommands), `results`, and `config` (with its `set`/`show` subcommands) SHALL remain directly reachable from the `baas` root command, unaffected by the `admin` grouping.

#### Scenario: Top-level commands unchanged
- **WHEN** a user runs `baas run jmh -- MyBenchmark -f 1`, `baas jobs list`, `baas results`, or `baas config show`
- **THEN** each resolves to its command implementation with no `admin` prefix required

### Requirement: `baas env diff` compares two jobs' environments
`baas env diff <resultPathA> <resultPathB>` SHALL be available as a top-level command, alongside the other
day-to-day commands, and SHALL run under operator credentials.

#### Scenario: Command is top-level, not under admin
- **WHEN** `baas --help` is rendered
- **THEN** `env` appears as a top-level command and not as an `admin` subcommand

#### Scenario: Output is pipeable
- **WHEN** `baas env diff` output is redirected to a file
- **THEN** the payload contains no logger timestamp prefixes

### Requirement: User tags are passed through to the runner
`baas run` SHALL forward every `--tag key=value` option into the user-data script as a runner argument.
It SHALL NOT apply any caller tag to the EC2 instance: the instance carries only `project=baas`,
`baas-role=benchmark-runner` and `baas-job-id=<jobId>`. A caller tag on the instance could collide with
a fixed key, which EC2 rejects for the whole launch, and would be subject to EC2's tag limits.

#### Scenario: User tags appear in rendered user-data
- **WHEN** `baas run --tag branch=main --tag experiment=gc jmh -- MyBenchmark` renders user-data
- **THEN** the runner invocation carries `--tag branch=main` and `--tag experiment=gc`

#### Scenario: No caller tag reaches the instance
- **WHEN** `baas run --tag project=foo --tag branch=main jmh -- MyBenchmark` launches its instance
- **THEN** the instance's tags are exactly `project=baas`, `baas-role` and `baas-job-id`, and the launch
  does not fail on a duplicate key

#### Scenario: Environment tags are still forwarded
- **WHEN** user-data is rendered
- **THEN** it still forwards `imageVersion` and `instanceType` observed on the instance

### Requirement: Results filters cover the supported query patterns
`baas results` SHALL accept `--job-id`, `--benchmark-name`, `--tag <key>=<value>` (repeatable),
`--project`, `--all-projects`, `--all-jobs`, `--group-by` and `--limit`. `--job-id` SHALL be mutually
exclusive with every option that selects rows — `--project`, `--all-projects`, `--benchmark-name` and
`--tag` — because one job is already narrower than any of them and a disagreeing selector would be
ignored silently. `--project` and `--all-projects` SHALL be mutually exclusive with each other. An
invalid combination SHALL fail with a message naming the conflicting option.

#### Scenario: Tag filter is accepted
- **WHEN** `baas results --project p --tag jdk=25.0.4` is invoked
- **THEN** matching rows are returned

#### Scenario: Conflicting filters are rejected
- **WHEN** both `--tag branch=main` and `--job-id abc` are given
- **THEN** the command exits non-zero explaining that `--job-id` cannot be combined with other filters

#### Scenario: A run lookup does not take a project
- **WHEN** both `--job-id abc` and `--project p` are given
- **THEN** the command exits non-zero naming `--project`, and issues no query

#### Scenario: One project and every project are exclusive
- **WHEN** both `--project p` and `--all-projects` are given
- **THEN** the command exits non-zero naming both options

#### Scenario: Limit bounds the output
- **WHEN** `--limit 5` is given and more rows match
- **THEN** at most five rows are returned

#### Scenario: Removed options are rejected
- **WHEN** `baas results --living-branches` or `baas results --all` is invoked
- **THEN** picocli reports an unknown option error

### Requirement: A command downloads a job's S3 artifacts
The CLI SHALL provide a command taking either a job identifier or a literal S3 result path, plus a
destination directory, downloading every S3 object under that job's prefix. A job identifier SHALL be
resolved to the job's stored result path rather than reconstructed from its other attributes.

#### Scenario: Artifacts land locally
- **WHEN** the command is invoked with a job identifier and a destination
- **THEN** the destination contains the job's result JSON, `environment.json`, process output and logs

#### Scenario: A literal result path is accepted
- **WHEN** the command is invoked with a result path rather than an identifier
- **THEN** that prefix is downloaded

#### Scenario: Destination is reported
- **WHEN** the download completes
- **THEN** the command prints the destination path and exits 0

### Requirement: `baas run` consumes a pre-built benchmark JAR
`baas run` SHALL NOT build the benchmark project. It SHALL require the benchmark JAR to be named
explicitly on the command line, SHALL NOT fall back to a configured or defaulted path, and SHALL
fail before resolving the runner image and before any upload when no JAR is named or the named JAR
does not exist. The failure message SHALL name the option that supplies it.

#### Scenario: A named JAR is used as-is
- **WHEN** `baas run --benchmark-jar target/benchmarks.jar jmh -- MyBenchmark` is invoked
- **THEN** that JAR is uploaded for the job, and no build is performed in the working directory

#### Scenario: An unnamed JAR fails before provisioning
- **WHEN** `baas run jmh -- MyBenchmark` is invoked with no benchmark JAR named
- **THEN** the command exits non-zero naming the required option, no EC2 instance is launched, and
  nothing is uploaded

#### Scenario: A named JAR that does not exist fails before provisioning
- **WHEN** the named benchmark JAR does not exist
- **THEN** the command exits non-zero naming the path it tried, and no EC2 instance is launched

#### Scenario: No build is attempted in any circumstance
- **WHEN** `baas run` is invoked from a directory containing a buildable project
- **THEN** no build tool is invoked, and the job uses only the named JAR

### Requirement: `baas run` can report its outcome as a machine-readable object
`baas run` SHALL accept a `--format json` option that writes a single summary object to standard
output, carrying at least the job identifier, the project, the job's S3 result path, the outcome, the
process exit code and the launched instance's identifier. The object SHALL be written on the failure
path as well as on success — a failed job is precisely when its identifier is needed — and the command
SHALL still exit non-zero when the job failed. Diagnostics SHALL remain on the logger so that standard
output holds the object alone.

#### Scenario: The summary is machine-readable
- **WHEN** `baas run --format json jmh -- MyBenchmark` completes and its standard output is piped to a
  JSON parser
- **THEN** the parser succeeds, and the parsed object carries the job identifier, project, result path,
  outcome, exit code and instance identifier

#### Scenario: A failed run still reports its identifier
- **WHEN** a job launched with `--format json` fails
- **THEN** the summary object is written with a failed outcome and the job's exit code, and the command
  exits non-zero

#### Scenario: Diagnostics do not corrupt the object
- **WHEN** `baas run -v --format json` is redirected to a file
- **THEN** the file contains exactly one JSON object with no timestamp or log-level prefix, and verbose
  diagnostics appear on standard error

#### Scenario: Default output is unchanged
- **WHEN** `baas run` is invoked without `--format` and without a console
- **THEN** its human-readable progress reporting is unchanged and no JSON object is written

#### Scenario: Default output shows a live status line when interactive
- **WHEN** `baas run` is invoked without `--format` on an interactive terminal
- **THEN** polling progress is shown as a live status line, as the `cli-console-output` capability
  specifies, and no JSON object is written

### Requirement: Resource names the CLI needs are derived or resolved, not cached
`~/.baas/config.yaml` SHALL store only what cannot be obtained from the installation itself: the
credential settings, the region, the installation prefix, and the operator's own preferences. Names
the composition rule determines — the working bucket, the results table and the runner instance
profile — SHALL be derived from the prefix at use time. Identifiers AWS assigns, specifically the
runner subnet and security group, SHALL be resolved from the installation's stack outputs at use
time rather than cached, so that a replaced resource cannot leave a stale identifier behind.

#### Scenario: A replaced security group does not strand the configuration
- **WHEN** the runner security group is replaced by a stack update and its identifier changes, and `baas run` is invoked afterwards with no intervening configuration command
- **THEN** the job uses the current security group identifier

#### Scenario: Config carries no derivable names
- **WHEN** `baas config show` reports the configuration after `baas config sync`
- **THEN** no stored field holds the bucket name, the results table name or the runner instance profile name
