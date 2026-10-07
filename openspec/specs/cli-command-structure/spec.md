# cli-command-structure Specification

## Purpose

The shape of the `baas` picocli command tree: which commands exist, how they nest, and which
privilege tier each grouping implies.

## Requirements

### Requirement: Deployer-privileged commands are grouped under `admin`
The `baas` command tree SHALL group every command that needs deployer credentials under a nested `admin`
subcommand, as two nouns: `admin deployment` with the verbs `setup` and `teardown`, and `admin image`
with the verbs `build` and `show`. None of these SHALL be reachable as top-level commands, and
`admin deployer-policy`, `admin setup`, `admin teardown`, `admin build-image` and `admin image` without
a verb SHALL NOT exist as commands.

#### Scenario: Admin commands are nested
- **WHEN** a user runs `baas admin deployment setup --help`
- **THEN** picocli shows the setup command's options
- **WHEN** a user runs `baas setup` (without the `admin` prefix) or `baas admin setup`
- **THEN** picocli reports an unknown command error

#### Scenario: The image is a noun with two verbs
- **WHEN** a user runs `baas admin image`
- **THEN** its usage, naming `build` and `show`, is printed and no AWS call is made

### Requirement: `baas admin image build` builds the runner image
`baas admin image build` SHALL render the base from the `infra/runner-image.yaml` bundled with the CLI,
resolve the base's parent release in the stack's region, update the stack when the base or the
extension changed, trigger the image build, poll to completion, write the resulting AMI ID to
`/<prefix>/runner/ami-id`, retire the AMI it replaced, and report the new AMI ID and label. It SHALL
accept `--extension <file>` to replace the deployment's extension, subject to the size limit and the
stale-push guard; without it, the deployed extension SHALL be carried forward unchanged. It SHALL run
under deployer credentials (`aws.profile`), consistent with every other `baas admin` subcommand.

#### Scenario: Successful build reports the AMI
- **WHEN** `baas admin image build` completes
- **THEN** it prints the new AMI ID and the image label, and exits 0

#### Scenario: Build failure is surfaced
- **WHEN** the image build fails
- **THEN** the command exits non-zero, reports the Image Builder failure reason, and leaves the pointer
  and the previous AMI untouched

#### Scenario: Build uses deployer credentials
- **WHEN** `config.yaml` sets both `aws.profile` and `aws.operatorProfile` and `baas admin image build`
  runs
- **THEN** AWS clients are built from `aws.profile`

#### Scenario: Pushing an extension
- **WHEN** `baas admin image build --extension ext.yaml` runs with a file whose marker matches the
  deployed extension
- **THEN** the stack holds the file's content as the extension and the new image's label carries its
  hash

### Requirement: `baas admin image show` reports the current image
`baas admin image show` SHALL report the current runner image's label, AMI ID, build timestamp, and parent
AMI ID, and SHALL report clearly when no image has been built. When an extension is deployed, it SHALL
also report the extension's hash, its size against the 4096-byte limit, and the names of its steps
grouped by phase. When the image's base version differs from the base bundled with the running CLI, it
SHALL warn naming the bundled base version and `baas admin image build`. With `--extension`, it SHALL
instead print the deployed extension preceded by its base marker line, or, when none is deployed, the
starter document marked `none`. Command payload SHALL be written to `System.out` rather than the
logger, so it remains pipeable.

#### Scenario: Current image is reported
- **WHEN** an image has been built and `baas admin image show` runs
- **THEN** the output names the image label, AMI ID, build time, and parent AMI

#### Scenario: No image built yet
- **WHEN** no image has been built and `baas admin image show` runs
- **THEN** the output states that no image exists and names `baas admin image build`

#### Scenario: The extension is summarised
- **WHEN** the deployed extension has build steps `InstallOtelCollector` and `InstallBpftrace`
- **THEN** the output names its hash, its size against 4096 bytes, and both step names under the build
  phase

#### Scenario: Pulling the extension
- **WHEN** `baas admin image show --extension > ext.yaml` runs on a deployment holding extension
  `3f9a1c2e`
- **THEN** `ext.yaml` starts with a marker naming `3f9a1c2e`, followed by the deployed extension

#### Scenario: Pulling when nothing is deployed
- **WHEN** `baas admin image show --extension` runs on a deployment holding no extension
- **THEN** it prints the starter document with a marker naming `none`

#### Scenario: The drift warning names the bundled base
- **WHEN** the deployed image's base is `1.2.0` and the CLI bundles base `1.3.0`
- **THEN** a warning names `1.3.0` as the bundled base and `baas admin image build`

### Requirement: `baas run` requires a built image
`baas run` SHALL resolve the runner AMI from `/<prefix>/runner/ami-id` and SHALL fail before provisioning
any resource when that parameter is absent or names an AMI that no longer exists. The failure message
SHALL name `baas admin image build`.

#### Scenario: No image built yet
- **WHEN** `baas run jmh -- MyBenchmark` is invoked with no AMI pointer present
- **THEN** the command exits non-zero naming `baas admin image build`, and no EC2 instance is launched

#### Scenario: Pointer names a deleted AMI
- **WHEN** the pointer resolves to an AMI that has been deregistered
- **THEN** the command exits non-zero without launching an instance

### Requirement: `baas jobs diff` compares two jobs' environments
`baas jobs diff <jobA> <jobB>` SHALL be a verb of the top-level `jobs` noun, take two job identifiers,
and run under operator credentials. `baas env` SHALL NOT exist.

#### Scenario: Diff is a verb of the jobs noun
- **WHEN** `baas --help` is rendered
- **THEN** `jobs` appears among the operator commands with `diff` among its verbs, and neither `env` nor an
  `admin` subcommand offers a diff

#### Scenario: Output is pipeable
- **WHEN** `baas jobs diff` output is redirected to a file
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
`baas results query` SHALL accept `--job-id`, `--benchmark-name`, `--tag <key>=<value>` (repeatable),
`--exclude-tag <key>=<value>` (repeatable), `--project`, `--all-projects`, `--best-per <tag>`,
`--show-excluded`, `--sort-by`, `--asc`, `--limit`, `--offset`, `--format` and `--watch`. `--job-id`
SHALL NOT combine with `--project` or `--all-projects`, which select a different access path, and
`--project` and `--all-projects` SHALL be mutually exclusive with each other. An invalid combination
SHALL fail with a message naming the conflicting option.

#### Scenario: Tag filter is accepted
- **WHEN** `baas results query --project p --tag jdk=25.0.4` is invoked
- **THEN** matching rows are returned

#### Scenario: Conflicting filters are rejected
- **WHEN** both `--job-id abc` and `--all-projects` are given
- **THEN** the command exits non-zero naming `--all-projects`, and issues no query

#### Scenario: A job lookup does not take a project
- **WHEN** both `--job-id abc` and `--project p` are given
- **THEN** the command exits non-zero naming `--project`, and issues no query

#### Scenario: One project and every project are exclusive
- **WHEN** both `--project p` and `--all-projects` are given
- **THEN** the command exits non-zero naming both options

#### Scenario: Limit bounds the output
- **WHEN** `--limit 5` is given and more rows match
- **THEN** at most five rows are returned

#### Scenario: Removed options are rejected
- **WHEN** `baas results query --all-jobs`, `--group-by branch`, `--living-branches` or `--all` is given
- **THEN** picocli reports an unknown option error

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

#### Scenario: A failed job still reports its identifier
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
`~/.baas/config.yaml` SHALL store only what cannot be obtained from the deployment itself: the
credential settings, the region, the deployment prefix, and the operator's own preferences. Names
the composition rule determines — the working bucket, the results table and the runner instance
profile — SHALL be derived from the prefix at use time. Identifiers AWS assigns, specifically the
runner subnet and security group, SHALL be resolved from the deployment's stack outputs at use
time rather than cached, so that a replaced resource cannot leave a stale identifier behind.

#### Scenario: A replaced security group does not strand the configuration
- **WHEN** the runner security group is replaced by a stack update and its identifier changes, and `baas run` is invoked afterwards with no intervening configuration command
- **THEN** the job uses the current security group identifier

#### Scenario: Config carries no derivable names
- **WHEN** `baas config show` reports the configuration after `baas config sync`
- **THEN** no stored field holds the bucket name, the results table name or the runner instance profile name

### Requirement: Every command accepts an alternative configuration file
Every `baas` command SHALL accept `--config-path <file>`, naming the configuration file the invocation
reads and writes in place of `~/.baas/config.yaml`. The option SHALL be accepted before or after the
subcommand name. When `--config-path` names a file that does not exist, a command that only reads
configuration SHALL fail naming the path, and `baas config sync`, `baas config set` and `baas admin deployment setup`
SHALL create it. Without the option, behaviour on a missing `~/.baas/config.yaml` is unchanged. No command
SHALL accept a per-invocation override of the results table or the working bucket: addressing another
deployment means naming that deployment's configuration file.

#### Scenario: Reading a retired deployment's history
- **WHEN** `baas results query --config-path ~/.baas/retired.yaml --project lynx-journal` runs, and that file
  names a retired deployment's prefix
- **THEN** that deployment's measurements are reported, and `~/.baas/config.yaml` is neither read nor
  changed

#### Scenario: A second configuration is created by sync
- **WHEN** `baas config sync --deployment baas-123456789012-dev --config-path ~/.baas/dev.yaml` runs and the
  file does not exist
- **THEN** `~/.baas/dev.yaml` is written with that prefix, and `~/.baas/config.yaml` is unchanged

#### Scenario: A mistyped path fails rather than reading nothing
- **WHEN** `baas results query --config-path ~/.baas/nope.yaml` runs and the file does not exist
- **THEN** the command exits non-zero naming `~/.baas/nope.yaml`, and issues no AWS call

#### Scenario: The option is inherited on either side of the subcommand
- **WHEN** `baas --config-path f.yaml results` and `baas results query --config-path f.yaml` are each invoked
- **THEN** both read `f.yaml`

#### Scenario: Table and bucket overrides are gone
- **WHEN** `baas results query --results-table t`, `baas jobs download --results-table t` or
  `baas jobs download --bucket b` is invoked
- **THEN** picocli reports an unknown option error

### Requirement: Git is consulted only when the operator enables it
The configuration SHALL carry a `git.resolveProject` preference, `false` by default and set with
`baas config set --git-resolve-project <true|false>`. When it is `false`, no command SHALL invoke git. When it is `true`, git SHALL be
consulted only to derive a project name, as the `baas run` and `baas results query` project requirements
specify; it SHALL NOT be consulted for `branch` or `commit`.

#### Scenario: A fresh configuration does not use git
- **WHEN** `baas run` or `baas results query` is invoked with a configuration that does not set
  `git.resolveProject`
- **THEN** no git process is started

#### Scenario: The preference is set from the CLI
- **WHEN** `baas config set --git-resolve-project true` is invoked
- **THEN** the configuration file records the preference, and `baas config show` reports it

### Requirement: The project is explicit unless derivation is enabled
`baas run` SHALL require `--project <name>` unless `git.resolveProject` is `true`. When it is `true` and
`--project` is absent, the project SHALL be the name of the git repository that contains the
`--benchmark-jar` file, resolving the main repository rather than a linked worktree directory. The
directory the command is invoked from SHALL NOT influence the project. When no project can be
determined the command SHALL fail before provisioning, naming `--project`. The project, explicit or
derived, SHALL consist only of the characters GitHub allows in a repository name — ASCII letters,
digits, `.`, `_` and `-` — and any other name SHALL fail before provisioning, naming the offending
value.

#### Scenario: Project is required by default
- **WHEN** `baas run --benchmark-jar target/b.jar jmh -- MyBenchmark` is invoked with
  `git.resolveProject` unset
- **THEN** the command exits non-zero naming `--project`, and no EC2 instance is launched

#### Scenario: Derived from the JAR's repository, not the working directory
- **WHEN** `git.resolveProject` is `true` and `baas run --benchmark-jar ~/src/lynx-journal/target/b.jar`
  is invoked from inside a different repository
- **THEN** `lynx-journal` is forwarded as the `project` tag

#### Scenario: A linked worktree resolves to its repository
- **WHEN** `git.resolveProject` is `true` and the benchmark JAR lies inside a linked worktree
- **THEN** the repository's name is forwarded, not the worktree directory's name

#### Scenario: A JAR outside any repository fails before provisioning
- **WHEN** `git.resolveProject` is `true`, `--project` is absent and the JAR is not inside a git
  repository
- **THEN** the command exits non-zero naming `--project`, and no EC2 instance is launched

#### Scenario: Any GitHub repository name is a valid project
- **WHEN** `--project node.js` or `--project .github` is given, or derivation yields such a name
- **THEN** it is forwarded unchanged

#### Scenario: A name no GitHub repository could have fails before provisioning
- **WHEN** `--project "it's"` is given, or derivation yields a directory name containing a space
- **THEN** the command exits non-zero quoting the name, and no EC2 instance is launched

#### Scenario: An explicit project wins
- **WHEN** `--project other-name` is given with `git.resolveProject` `true`
- **THEN** `other-name` is forwarded and git is not consulted

### Requirement: `commit` and `branch` are caller-supplied tags, omitted when absent
`baas run` SHALL record `commit` and `branch` only when supplied as `--tag commit=<value>` and
`--tag branch=<value>`. It SHALL NOT derive either from git, SHALL NOT offer dedicated options for
them, and SHALL NOT substitute a placeholder value when one is absent.

#### Scenario: Supplied values are forwarded
- **WHEN** `baas run --tag branch=main --tag commit=3f2a9c1 …` is invoked
- **THEN** the runner invocation carries both tags

#### Scenario: Nothing is derived
- **WHEN** `baas run` is invoked inside a git repository with neither tag given
- **THEN** the stored measurement carries no `commit` and no `branch` tag

#### Scenario: Dedicated options are gone
- **WHEN** `baas run --branch main …` or `baas run --commit abc …` is invoked
- **THEN** picocli reports an unknown option error

### Requirement: The watchdog fires a fixed margin after the benchmark timeout
`baas run` SHALL accept `--watchdog-margin <seconds>`, defaulting to the configuration's
`ec2.watchdogMarginSeconds`, itself defaulting to 300 and set with `baas config set --watchdog-margin`. The instance's self-termination watchdog SHALL
fire `timeout + margin` seconds after launch, and the CLI SHALL stop polling at the same bound. A margin
below 60 SHALL be rejected before provisioning. There SHALL be no option or configuration key setting an
absolute wall-clock bound.

#### Scenario: Default bound is unchanged
- **WHEN** `baas run` is invoked with the default timeout of 7200 and no margin given
- **THEN** the rendered watchdog delay is 7500 seconds

#### Scenario: Margin is added to an explicit timeout
- **WHEN** `baas run --timeout 1000 --watchdog-margin 120 …` is invoked
- **THEN** the rendered watchdog delay is 1120 seconds, and the CLI's poll cap is 1120 seconds

#### Scenario: A too-small margin is refused
- **WHEN** `baas run --watchdog-margin 10 …` is invoked
- **THEN** the command exits non-zero naming the 60-second minimum, and no EC2 instance is launched

#### Scenario: The absolute option is gone
- **WHEN** `baas run --max-wall-clock 9000 …` or `baas config set --max-wall-clock 9000` is invoked
- **THEN** picocli reports an unknown option error

### Requirement: The region resolves from the file, then the environment
Every command SHALL use the configuration file's `aws.region` when it is set, else the `AWS_REGION`
environment variable when it is set, else `eu-central-1`. The resolved value SHALL NOT be written back to
the file by any command except `baas admin deployment setup`, which records the region it deployed to.

#### Scenario: A CI job follows its own region
- **WHEN** a configuration with no `aws.region` is used with `AWS_REGION=eu-west-1`
- **THEN** the command addresses `eu-west-1`, and saving that configuration writes no region

#### Scenario: The file wins
- **WHEN** the file sets `aws.region: eu-central-1` and `AWS_REGION=us-east-1`
- **THEN** the command addresses `eu-central-1`

### Requirement: `baas run` always resolves the results table
`baas run` SHALL resolve the deployment's results table on every invocation, before the runner-image
lookup and before any upload, and SHALL pass it to the runner. An unresolvable table SHALL fail before any
instance is launched. `baas run` SHALL NOT offer an option that discards measurements.

#### Scenario: Unresolvable table fails before provisioning
- **WHEN** `baas run jmh -- MyBenchmark` is invoked with no deployment configured
- **THEN** the command exits non-zero and no EC2 instance is launched

#### Scenario: The discard option is gone
- **WHEN** `baas run --no-database jmh -- MyBenchmark` is invoked
- **THEN** picocli reports an unknown option and nothing is uploaded or launched

### Requirement: Commands are nouns with verbs, and only a one-noun verb has a top-level alias
Every canonical command SHALL have the form `baas [admin] <noun> <verb>`. The operator nouns SHALL be
`jobs` (`run`, `list`, `show`, `diff`, `download`, `terminate`), `results` (`query`) and `config`
(`show`, `set`, `sync`). A top-level alias SHALL exist only for a verb that belongs to exactly one
noun, and the aliases SHALL be `baas run` for `baas jobs run` and `baas query` for `baas results
query`. A noun given without a verb SHALL print its usage and do nothing else. No alias SHALL exist
for a verb shared by several nouns.

#### Scenario: An alias runs its canonical command
- **WHEN** `baas run jmh -- MyBenchmark -f 1` is invoked
- **THEN** it behaves exactly as `baas jobs run jmh -- MyBenchmark -f 1`

#### Scenario: A bare noun prints usage
- **WHEN** `baas jobs` is invoked with no verb
- **THEN** its verbs are listed and nothing is executed

#### Scenario: A shared verb has no alias
- **WHEN** `baas list` or `baas show` is invoked
- **THEN** picocli reports an unknown command error

### Requirement: Top-level help is grouped by workload
`baas --help` SHALL list, in order: the shortcuts, each shown with its canonical command (`run = jobs
run`); the operator commands under a heading naming operator AWS credentials, one line per noun listing
its verbs; and the deployer commands under a heading naming deployer AWS credentials, one line per noun
written as its full path (`admin deployment …`, `admin image …`). The help SHALL NOT name configuration
keys. The first-run guidance SHALL name `admin deployment setup` then `admin image build`.

#### Scenario: The map is visible from the root
- **WHEN** `baas --help` is rendered
- **THEN** the three sections appear in that order, and the deployer lines read `admin deployment` and
  `admin image`

#### Scenario: Aliases show their target
- **WHEN** `baas --help` is rendered
- **THEN** the shortcut lines read `run = jobs run` and `query = results query`

### Requirement: Next-step hints are shown to people, never to scripts
A command MAY end by suggesting at most two follow-up commands, each written to standard error through
the logger as `→ <purpose>: <exact command>` with real identifiers filled in. Hints SHALL appear only
when the console is interactive, by the same definition that gates colour, and SHALL never appear on
standard output. There SHALL be no option to disable them.

#### Scenario: A finished job points to its measurements
- **WHEN** `baas jobs show <id>` completes on an interactive terminal
- **THEN** standard error ends with `→ measurements: baas query --job-id <id>`

#### Scenario: Scripts see no hints
- **WHEN** the same command runs with standard output redirected or without a console
- **THEN** no hint is written to either stream

### Requirement: A deployment is named only by `--deployment`
Every command SHALL accept a global, inherited `--deployment <name>` option, and every pointer to a
concrete deployment SHALL be that option: `teardown --stack-name` and `config sync --name` SHALL NOT
exist. The option SHALL never be positional. While a machine holds a single deployment's
configuration, `--deployment` SHALL be accepted only when it names that deployment, and a different
name SHALL fail naming both.

#### Scenario: Teardown is aimed by the global option
- **WHEN** `baas --deployment baas-123456789012 admin deployment teardown` is invoked on a machine
  configured for that deployment
- **THEN** that deployment is the one torn down

#### Scenario: A different deployment is refused
- **WHEN** `--deployment other` is given on a machine configured for `baas-123456789012`
- **THEN** the command exits non-zero naming both deployments, and calls no AWS API

#### Scenario: Removed pointers are rejected
- **WHEN** `baas admin deployment teardown --stack-name x` or `baas config sync --name x` is invoked
- **THEN** picocli reports an unknown option error

### Requirement: Filter options mean the same on every command
An option offered by more than one command SHALL mean the same thing on each, and a filter meaningful
for several commands SHALL exist on each. `--project p` SHALL mean "only project p" everywhere; what a
command does without it is that command's own default and SHALL be stated in its help.

#### Scenario: A project filter narrows both listings alike
- **WHEN** `baas jobs list --project p` and `baas results query --project p` are run
- **THEN** each reports only project `p`'s rows

#### Scenario: The default without a project is stated
- **WHEN** `baas jobs list --help` and `baas results query --help` are rendered
- **THEN** each states what it reports when `--project` is absent

### Requirement: Listings share one ordering and paging pipeline
`baas jobs list` and `baas results query` SHALL apply, in order: their filters, then `--best-per` where
offered, then sorting, then `--offset <m>`, then `--limit <n>`. The default sort SHALL be newest
first; `--sort-by <field>` SHALL choose another field and `--asc` SHALL reverse the direction.
`--limit` SHALL default to 20, `--limit 0` SHALL mean no limit, and a cut SHALL be announced on
standard error as how many of how many rows were reported.

#### Scenario: The default is the twenty newest
- **WHEN** 30 rows match and no paging option is given
- **THEN** the 20 newest are reported, newest first, and standard error reports 20 of 30

#### Scenario: An offset pages backwards in time
- **WHEN** `--offset 20 --limit 20` is given over 30 matching rows
- **THEN** the 10 oldest rows are reported

#### Scenario: Another order is chosen explicitly
- **WHEN** `baas results query --project p --sort-by benchmark --asc` is run
- **THEN** rows are ordered by benchmark name, ascending
