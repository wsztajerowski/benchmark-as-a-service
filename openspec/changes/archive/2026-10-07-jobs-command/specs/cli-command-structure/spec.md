# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: `baas admin build-image` builds the runner image`
- TO: `### Requirement: `baas admin image build` builds the runner image`

- FROM: `### Requirement: `baas admin image` reports the current image`
- TO: `### Requirement: `baas admin image show` reports the current image`

- FROM: `### Requirement: `baas env diff` compares two jobs' environments`
- TO: `### Requirement: `baas jobs diff` compares two jobs' environments`

## MODIFIED Requirements

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

#### Scenario: Command is top-level, not under admin
- **WHEN** `baas --help` is rendered
- **THEN** `jobs` appears among the operator commands with `diff` among its verbs, and neither `env` nor an
  `admin` subcommand offers a diff

#### Scenario: Output is pipeable
- **WHEN** `baas jobs diff` output is redirected to a file
- **THEN** the payload contains no logger timestamp prefixes

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

## REMOVED Requirements

### Requirement: Daily-use commands remain top-level
**Reason**: Operator commands are now nouns with verbs; only declared aliases sit at the top level.
**Migration**: `baas run` and `baas query` remain as aliases of `baas jobs run` and `baas results query`; `baas results` alone prints usage — see *Commands are nouns with verbs, and only a one-noun verb has a top-level alias*.

### Requirement: A command downloads a job's S3 artifacts
**Reason**: Downloading is a verb of `jobs`, and it no longer accepts a literal result path.
**Migration**: Use `baas jobs download <job>` (job-tracking, *`baas jobs download` fetches one job's artifacts*).

### Requirement: Teardown reports what it retains
**Reason**: Teardown no longer retains anything: the bucket and the results table are deleted with the deployment.
**Migration**: Export the data first (a later `baas admin` export); see core-stack-provisioning *Teardown removes every deployment resource*.

## ADDED Requirements

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
