# cli-command-structure Specification

## Purpose

The shape of the `baas` picocli command tree: which commands exist, how they nest, and which
privilege tier each grouping implies.

## Requirements

### Requirement: Deployer-privileged commands are grouped under `admin`
The `baas` command tree SHALL group every command that needs deployer credentials under a nested `admin`
subcommand, as two nouns: `admin deployment` with the verbs `setup`, `teardown`, `export` and
`import`, and `admin image` with the verbs `build` and `show`. None of these SHALL be reachable as top-level commands, and
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

#### Scenario: The deployment noun lists its four verbs
- **WHEN** a user runs `baas admin deployment`
- **THEN** its usage, naming `setup`, `teardown`, `export` and `import`, is printed and no AWS call is made

#### Scenario: Export and import have no other form
- **WHEN** a user runs `baas export`, `baas import` or `baas jobs export`
- **THEN** picocli reports an unknown command error

### Requirement: `baas admin image build` builds the runner image
`baas admin image build` SHALL render the base from the `infra/runner-image.yaml` bundled with the CLI,
resolve the base's parent release in the stack's region, update the stack when the base or the
extension changed, trigger the image build, poll to completion, write the resulting AMI ID to
`/<prefix>/runner/ami-id`, retire the AMI it replaced, and report the new AMI ID and label. It SHALL
accept `--extension <file>` to replace the deployment's extension, subject to the size limit and the
stale-push guard; without it, the deployed extension SHALL be carried forward unchanged. It SHALL run
under deployer credentials (`aws.deployerProfile`), consistent with every other `baas admin` subcommand.

#### Scenario: Successful build reports the AMI
- **WHEN** `baas admin image build` completes
- **THEN** it prints the new AMI ID and the image label, and exits 0

#### Scenario: Build failure is surfaced
- **WHEN** the image build fails
- **THEN** the command exits non-zero, reports the Image Builder failure reason, and leaves the pointer
  and the previous AMI untouched

#### Scenario: Build uses deployer credentials
- **WHEN** the configuration sets both `aws.deployerProfile` and `aws.operatorProfile` and `baas admin image build`
  runs
- **THEN** AWS clients are built from `aws.deployerProfile`

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
SHALL say the deployment has no runner image, naming the deployment, and SHALL name
`baas --deployment <name> admin image build`.

#### Scenario: No image built yet
- **WHEN** `baas run jmh -- MyBenchmark` is invoked for deployment `wiktor-dev` with no AMI pointer present
- **THEN** the command exits non-zero naming `baas --deployment wiktor-dev admin image build`, and no EC2 instance is launched

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
`baas-role=benchmark-runner`, `baas-job-id=<jobId>` and `baas-deployment=<prefix>`. A caller tag on the
instance could collide with a fixed key, which EC2 rejects for the whole launch, and would be subject to
EC2's tag limits.

#### Scenario: User tags appear in rendered user-data
- **WHEN** `baas run --tag branch=main --tag experiment=gc jmh -- MyBenchmark` renders user-data
- **THEN** the runner invocation carries `--tag branch=main` and `--tag experiment=gc`

#### Scenario: No caller tag reaches the instance
- **WHEN** `baas run --tag project=foo --tag branch=main jmh -- MyBenchmark` launches its instance
- **THEN** the instance's tags are exactly `project=baas`, `baas-role`, `baas-job-id` and
  `baas-deployment`, and the launch does not fail on a duplicate key

#### Scenario: The instance names its deployment
- **WHEN** `baas --deployment wiktor-dev run jmh -- MyBenchmark` launches its instance
- **THEN** the instance carries `baas-deployment=wiktor-dev`

#### Scenario: Environment tags are still forwarded
- **WHEN** user-data is rendered
- **THEN** it still forwards `imageVersion` and `instanceType` observed on the instance

### Requirement: Results filters cover the supported query patterns
`baas results query` SHALL accept `--job-id`, `--benchmark-name`, `--tag <key>=<value>` (repeatable),
`--exclude-tag <key>=<value>` (repeatable), `--project`, `--all-projects`, `--best-per <tag>`,
`--show-hidden`, `--sort-by`, `--asc`, `--limit`, `--offset`, `--format` and `--watch`. `--job-id`
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
output holds the object alone. For a `--detach` launch the object SHALL be written once the launch is
confirmed, with the outcome `launched` and the job item's status at that moment — `launched`, or
`launching` when the launch could not be recorded — and exit code zero; it SHALL NOT report
`completed` for a job that has not run.

#### Scenario: The summary is machine-readable
- **WHEN** `baas run --format json jmh -- MyBenchmark` completes and its standard output is piped to a
  JSON parser
- **THEN** the parser succeeds, and the parsed object carries the job identifier, project, result path,
  outcome, exit code and instance identifier

#### Scenario: A failed job still reports its identifier
- **WHEN** a job launched with `--format json` fails
- **THEN** the summary object is written with a failed outcome and the job's exit code, and the command
  exits non-zero

#### Scenario: A detached launch reports launched
- **WHEN** `baas run --detach --format json jmh -- MyBenchmark` returns after a confirmed launch
- **THEN** the object carries the outcome `launched`, the job status `launched` (or `launching`), the
  job identifier and the instance identifier, and the exit code is zero

#### Scenario: A detached launch that fails reports failed
- **WHEN** `baas run --detach --format json` cannot launch the instance, or finds the job cancelled
  while launching
- **THEN** the object carries a failed outcome and the command exits non-zero

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
A deployment's configuration file SHALL store only what the user chose — credential profile names and
the operator's own preferences — and the deployment's region, which cannot be derived without an AWS
call and does not change while the deployment exists. The deployment's name SHALL be the file's name
and SHALL NOT be stored inside it. Names the composition rule determines — the core stack, the working
bucket, the results table, the runner instance profile and the operator role — SHALL be derived from
the name at use time. Identifiers AWS assigns, specifically the runner subnet and security group,
SHALL be resolved from the deployment's stack outputs at use time rather than cached, so that a
replaced resource cannot leave a stale identifier behind.

#### Scenario: A replaced security group does not strand the configuration
- **WHEN** the runner security group is replaced by a stack update and its identifier changes, and `baas run` is invoked afterwards with no intervening configuration command
- **THEN** the job uses the current security group identifier

#### Scenario: Config carries no derivable names
- **WHEN** a deployment's configuration file is read after `baas config sync`
- **THEN** no stored field holds the deployment name, the bucket name, the results table name, the runner instance profile name or the runner source repository

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
Every command SHALL use the configuration file's `aws.region` when it is set. When no file holds a
region for the deployment — a machine with no configuration, such as a CI job on the operator role —
the command SHALL use the region the deployment's working bucket is in, found from the bucket once per
invocation, and SHALL fail naming the deployment when no such bucket exists. `AWS_REGION`, else
`eu-central-1`, SHALL only choose where that lookup is sent from and where `baas admin deployment
setup` creates a new deployment when `--region` is absent. Only `baas admin deployment setup`, which
records the region it deployed to, and `baas config sync`, which records the region it found, SHALL
write a region to a file.

#### Scenario: A CI job follows its own region
- **WHEN** a command runs with no configuration file against a deployment whose bucket is in `eu-central-1`, with `AWS_REGION=us-east-1`
- **THEN** every deployment-scoped call addresses `eu-central-1` — the deployment's own region, not the job's `AWS_REGION` — and no file is written

#### Scenario: The file wins
- **WHEN** the file sets `aws.region: eu-central-1` and `AWS_REGION=us-east-1`
- **THEN** the command addresses `eu-central-1` and makes no bucket lookup

#### Scenario: A missing deployment fails first
- **WHEN** a command runs with no configuration file under the assumed role `ghost-role-operator`, and no bucket named `ghost` exists
- **THEN** the command exits non-zero naming `ghost` and stating that no such deployment exists

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
`jobs` (`run`, `list`, `show`, `diff`, `download`, `terminate`, `connect`), `results` (`query`, `diff`)
and `config` (`show`, `set`, `sync`). A top-level alias SHALL exist only for a verb that belongs to
exactly one noun, and the aliases SHALL be `baas run` for `baas jobs run` and `baas query` for `baas
results query`. A noun given without a verb SHALL print its usage and do nothing else. No alias SHALL
exist for a verb shared by several nouns.

#### Scenario: An alias runs its canonical command
- **WHEN** `baas run jmh -- MyBenchmark -f 1` is invoked
- **THEN** it behaves exactly as `baas jobs run jmh -- MyBenchmark -f 1`

#### Scenario: A bare noun prints usage
- **WHEN** `baas jobs` is invoked with no verb
- **THEN** its verbs are listed and nothing is executed

#### Scenario: A shared verb has no alias
- **WHEN** `baas list`, `baas show` or `baas diff` is invoked
- **THEN** picocli reports an unknown command error

#### Scenario: Connect has no top-level alias
- **WHEN** `baas connect` is invoked
- **THEN** picocli reports an unknown command error, and `baas jobs connect` is the only form

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
A command MAY end by suggesting at most two follow-up commands, written to standard error as one block:
a line `Next`, then one line per hint, `  <purpose>  <exact command>`, with the commands aligned and real
identifiers filled in. The block SHALL carry no timestamp, log level or logger name. A suggested command
that acts on a deployment SHALL always carry `--deployment <name>`, however many deployments are
configured, so that it stays valid when another is added. Hints SHALL appear only when the console is
interactive, by the same definition that gates colour, and SHALL never appear on standard output. There
SHALL be no option to disable them. Setup's closing report of next steps is its result, not a hint: it
is printed in both modes, and every command in it carries `--deployment <name>` too.

#### Scenario: A finished job points to its measurements
- **WHEN** `baas jobs show <id>` completes on an interactive terminal for deployment `baas-123456789012`
- **THEN** standard error ends with a `Next` line followed by
  `  measurements  baas --deployment baas-123456789012 query --job-id <id>` and
  `  files         baas --deployment baas-123456789012 jobs download <id>`, with no timestamp, level or
  logger name on any of them

#### Scenario: Scripts see no hints
- **WHEN** the same command runs with standard output redirected or without a console
- **THEN** no hint is written to either stream

#### Scenario: A hint printed with one deployment works with two
- **WHEN** a hint is printed while one deployment is configured, a second is then configured, and the hint's command is run as printed
- **THEN** it acts on the deployment it named, and is not refused as ambiguous

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
`--limit` SHALL default to 20, except for `baas results query --job-id`, which SHALL default to no limit;
`--limit 0` SHALL mean no limit, and a cut SHALL be announced on standard error as how many of how many
rows were reported. Sorting by score SHALL place rows whose score is unknown last, in either direction.

#### Scenario: The default is the twenty newest
- **WHEN** 30 rows match and no paging option is given
- **THEN** the 20 newest are reported, newest first, and standard error reports 20 of 30

#### Scenario: An offset pages backwards in time
- **WHEN** `--offset 20 --limit 20` is given over 30 matching rows
- **THEN** the 10 oldest rows are reported

#### Scenario: Another order is chosen explicitly
- **WHEN** `baas results query --project p --sort-by benchmark --asc` is run
- **THEN** rows are ordered by benchmark name, ascending

#### Scenario: A job lookup is not cut by default
- **WHEN** `baas results query --job-id <id>` matches 30 rows and no paging option is given
- **THEN** all 30 rows are reported, and nothing is announced on standard error

#### Scenario: Unknown scores sort last
- **WHEN** `baas results query --project p --sort-by score` is run, with and without `--asc`, over rows of which one has no stored score
- **THEN** that row is reported last both times

### Requirement: `baas config list` lists the configured deployments
`baas config list` SHALL print one row per file in `~/.baas/deployments/`, with the columns
DEPLOYMENT, REGION, OPERATOR PROFILE and DEPLOYER PROFILE, read from the files alone. It SHALL make no
AWS call and SHALL NOT require any deployment to be selected, so it works with any number of
configured deployments. `--format json` SHALL print the same rows as a JSON array. With no deployment
configured it SHALL print that none is configured and exit 0. `list` SHALL NOT be aliased at the top
level.

#### Scenario: Two deployments listed without credentials
- **WHEN** `baas config list` runs with `baas-123456789012` and `wiktor-dev` configured and no AWS credentials available
- **THEN** it prints both rows with their region and profiles, and exits 0

#### Scenario: Machine-readable listing
- **WHEN** `baas config list --format json` runs
- **THEN** stdout is a JSON array with one object per deployment and nothing else

#### Scenario: No deployment configured
- **WHEN** `baas config list` runs with no deployment configured
- **THEN** it reports that no deployment is configured and exits 0

### Requirement: The deployer profile option is named for the deployer
`baas config set` SHALL accept `--deployer-aws-profile <name>`, stored as `aws.deployerProfile`, beside
`--operator-aws-profile`. It SHALL NOT accept `--aws-profile`. `baas config show` SHALL label the two
profiles as the deployer profile and the operator profile.

#### Scenario: Setting the deployer profile
- **WHEN** `baas config set --deployer-aws-profile baas-admin` runs
- **THEN** the selected deployment's file holds `aws.deployerProfile: baas-admin`

#### Scenario: The old option is gone
- **WHEN** `baas config set --aws-profile baas-admin` runs
- **THEN** picocli reports an unknown option error and nothing is written

### Requirement: A deployment is named by `--deployment`, or by its operator role when none is configured
Every command SHALL accept a global, inherited `--deployment <name>` option, and every explicit pointer
to a concrete deployment SHALL be that option: `teardown --stack-name`, `config sync --name` and
`--config-path` SHALL NOT exist. The option SHALL never be positional. Its help SHALL say that it is
optional when exactly one deployment is configured on the machine, required with two or more (pointing
to `baas config list`), and that printed hints always include it. When the option is absent, a
command SHALL use the only configured deployment if exactly one is configured, and SHALL fail listing
the configured names, with the hint `→ choose one: baas config list`, if two or more are — except an
interactive `baas admin deployment setup`, which asks which one. When none is
configured and the option is absent, a day-to-day command that reaches AWS (`run` and every `jobs`
and `results` command) SHALL take the deployment's name from its credentials when they are an assumed
role named `<name>-role-operator`, and SHALL otherwise fail reporting that no deployment is
configured; `baas admin deployment setup` derives or suggests the name from the account, `baas config sync`
requires the option, and every other command — the local `config show`/`config set` and the
deployer's `baas admin` commands — fails reporting that no deployment is configured. A name that is not configured SHALL fail naming the configured deployments when at least one
is configured, except in `baas admin deployment setup` and `baas config sync`, which create its
configuration. No environment variable and no stored setting SHALL select a deployment. No command
SHALL accept a per-invocation override of the results table or the working bucket.

#### Scenario: Teardown is aimed by the global option
- **WHEN** `baas --deployment baas-123456789012 admin deployment teardown` is invoked on a machine
  configured for that deployment
- **THEN** that deployment is the one torn down

#### Scenario: A different deployment is refused
- **WHEN** `baas --deployment other results query` is given on a machine configured only for `baas-123456789012`
- **THEN** the command exits non-zero naming `other` and the configured `baas-123456789012`, and calls no AWS API

#### Scenario: Removed pointers are rejected
- **WHEN** `baas admin deployment teardown --stack-name x`, `baas config sync --name x` or `baas --config-path f.yaml results query` is invoked
- **THEN** picocli reports an unknown option error

#### Scenario: One deployment needs no flag
- **WHEN** `baas results query` runs with only `baas-123456789012` configured
- **THEN** it reads `baas-123456789012`'s results

#### Scenario: Two deployments require the flag
- **WHEN** `baas run jmh -- MyBenchmark` runs with `baas-123456789012` and `wiktor-dev` configured and no `--deployment`
- **THEN** the command exits non-zero listing both names and the hint `→ choose one: baas config list`, issues no AWS call and launches nothing

#### Scenario: Teardown is never ambiguous
- **WHEN** `baas admin deployment teardown --yes` runs with two deployments configured and no `--deployment`
- **THEN** the command exits non-zero listing both names and deletes nothing

#### Scenario: An environment variable selects nothing
- **WHEN** `BAAS_DEPLOYMENT=wiktor-dev baas results query` runs with two deployments configured
- **THEN** the command fails listing both names, as if the variable were unset

#### Scenario: Table and bucket overrides are gone
- **WHEN** `baas results query --results-table t`, `baas jobs download --results-table t` or `baas jobs download --bucket b` is invoked
- **THEN** picocli reports an unknown option error

#### Scenario: An operator-role session names its deployment
- **WHEN** `baas run jmh -- MyBenchmark` runs with no deployment configured, no `--deployment`, and credentials for the assumed role `baas-123456789012-role-operator`
- **THEN** the job runs on deployment `baas-123456789012`, and no configuration file is written

#### Scenario: Other credentials name nothing
- **WHEN** `baas results query` runs with no deployment configured, no `--deployment`, and credentials that are not an assumed `*-role-operator` role
- **THEN** the command exits non-zero reporting that no deployment is configured and naming `--deployment`

#### Scenario: The help states the rule
- **WHEN** `baas results query --help` is shown
- **THEN** the `--deployment` description says it is optional with exactly one deployment configured, required with two or more, and always included in printed hints

### Requirement: Each deployment has one configuration file, named by the deployment
The CLI SHALL keep one configuration file per deployment, `~/.baas/deployments/<name>.yaml`, and the
deployment's name SHALL be taken from the file's name; no file SHALL record the name inside it. No file
SHALL record a default deployment, and no command SHALL switch one. `baas admin deployment setup` and
`baas config sync` SHALL write the file of the deployment they act on, and `baas admin deployment
teardown` SHALL delete the file of the deployment it tears down once the teardown has completed,
whether or not other deployments remain. When setup creates a deployment it SHALL write the file as
soon as CloudFormation accepts the create request, before the stack is complete, so that every stack
setup has created is named by a file on the machine that created it; a create request that is refused
SHALL leave no file. The file then holds the region, and the deployer profile when the
deployer-permission check had found nothing missing.

#### Scenario: Setup writes the deployment's own file
- **WHEN** `baas --deployment wiktor-dev admin deployment setup --region us-east-1 --deployer-aws-profile baas-admin` completes
- **THEN** `~/.baas/deployments/wiktor-dev.yaml` holds `aws.deployerProfile: baas-admin` and `aws.region: us-east-1` and no deployment name, and no other file changes

#### Scenario: Teardown removes the file
- **WHEN** `baas --deployment wiktor-dev admin deployment teardown --yes` completes
- **THEN** `~/.baas/deployments/wiktor-dev.yaml` no longer exists

#### Scenario: Tearing down the last deployment
- **WHEN** the only configured deployment is torn down
- **THEN** no deployment is configured, and the next command other than setup reports "No deployment is configured" unless its credentials are a deployment's operator role

#### Scenario: An interrupted create leaves a file
- **WHEN** setup is interrupted after CloudFormation accepted the create of `team-bench` and before the stack completed
- **THEN** `~/.baas/deployments/team-bench.yaml` exists with the region, `baas config list` shows `team-bench`, and `baas --deployment team-bench admin deployment teardown` can reach it

#### Scenario: A refused create leaves no file
- **WHEN** CloudFormation refuses the create request of a new deployment
- **THEN** no configuration file is written for it

### Requirement: A configuration file never records the CLI's own defaults
A save SHALL write a preference only when the user has set it; a value the CLI defaults SHALL be
supplied at read time and SHALL NOT be written. `aws.region` is written by setup and sync as *The
region resolves from the file, then the environment* states.

#### Scenario: A changed default reaches an existing machine
- **WHEN** a file written by `baas config sync` without any `config set` is read by a CLI whose default benchmark timeout differs from the one that wrote it
- **THEN** the reading CLI's default applies

#### Scenario: A set value is kept
- **WHEN** `baas config set --timeout 10800` runs
- **THEN** the file holds `ec2.benchmarkTimeoutSeconds: 10800`, and no other `ec2` key unless one was set before

#### Scenario: A fresh file holds only what was chosen
- **WHEN** `baas --deployment wiktor-dev config sync --operator-aws-profile my-sso` writes a new file
- **THEN** the file holds `aws.operatorProfile: my-sso` and `aws.region`, and no `ec2`, `git` or `runner` key

### Requirement: Setup stores the deployer profile only on evidence that it can deploy
`baas admin deployment setup` SHALL write the deployer profile it used — from
`--deployer-aws-profile` or its credentials prompt — to the deployment's configuration file only when
there is evidence that identity can deploy: the deployer-permission check ran and found nothing
missing, or a stack create or update changed something. Otherwise the previously stored profile SHALL
stay, and setup SHALL say that the profile was not stored because it could not be verified.

#### Scenario: An unverifiable identity does not replace a working one
- **WHEN** the file stores `aws.deployerProfile: baas-admin`, and setup runs with `--deployer-aws-profile lynx`, an identity that may not simulate policies, on a deployment with nothing to update
- **THEN** the file still stores `baas-admin`, and setup says `lynx` was not stored

#### Scenario: A verified identity is stored
- **WHEN** setup runs with `--deployer-aws-profile baas-admin` and the permission check finds nothing missing
- **THEN** the file stores `aws.deployerProfile: baas-admin`, even when the stack had nothing to update

#### Scenario: A create is its own evidence
- **WHEN** an identity that may not simulate policies creates a deployment successfully
- **THEN** the file stores that identity's profile

### Requirement: A CLI older than the deployment's recorded version is refused
Every command that makes an AWS call, under `baas admin` or not, SHALL first read the deployment's
recorded CLI version (the core stack's `BaasCliVersion`) and compare it numerically, field by field,
with its own. When its own version is lower — patch level included — the command SHALL make no
further AWS call, write to standard error the recorded version, its own version and the command
`curl -fsSL https://github.com/<repository>/releases/latest/download/install.sh | sh`, where
`<repository>` is the one the CLI was released from, and exit 1. When the stack does not exist or
records no version, the command SHALL proceed. `baas jobs terminate` SHALL NOT perform this check.
Commands that make no AWS call — `config list`, `config show`, `config set`, `--help`, `--version`
— SHALL NOT perform it. `baas admin deployment setup` performs it as its own step (see
*guided-setup*).

#### Scenario: An older CLI is refused before it acts
- **WHEN** the deployment records `8.1.0` and `baas results query` from `8.0.3` is run
- **THEN** it writes the two versions and the installer command to standard error, queries nothing,
  and exits 1

#### Scenario: A patch-older CLI is refused
- **WHEN** the deployment records `8.1.1` and a command from `8.1.0` is run
- **THEN** it is refused as above

#### Scenario: Versions compare numerically
- **WHEN** the deployment records `8.9.0` and a command from `8.10.0` is run
- **THEN** it is not refused

#### Scenario: Terminating is never refused
- **WHEN** the deployment records `8.1.0` and `baas jobs terminate <jobId>` from `8.0.3` is run
- **THEN** it terminates the job as it would for a current CLI

#### Scenario: No recorded version means no refusal
- **WHEN** the deployment's stack predates the recorded version, or records none
- **THEN** every command proceeds without a version message

#### Scenario: An admin command is refused too
- **WHEN** the deployment records `8.1.0` and `baas admin deployment teardown` from `8.0.3` is run
- **THEN** it is refused before deleting or exporting anything

### Requirement: A CLI newer than the recorded version says what to run
When a checked command's own version is higher than the recorded one, it SHALL proceed and SHALL
write one line to standard error naming both versions. Under `baas admin` the line SHALL name the
command that records the newer version (`baas --deployment <name> admin deployment setup`);
elsewhere it SHALL tell the user to ask the deployment's admin to run that setup with the newer
release. The line SHALL NOT appear on standard output.

#### Scenario: An operator ahead of the deployment is told whom to ask
- **WHEN** the deployment records `8.1.0` and `baas jobs list --format json` from `8.2.0` is run
- **THEN** the list is printed on standard output unchanged, and standard error carries one line
  naming `8.1.0`, `8.2.0` and asking the deployment's admin to run setup

#### Scenario: An admin ahead of the deployment is told to run setup
- **WHEN** the deployment records `8.1.0` and `baas admin image show` from `8.2.0` is run
- **THEN** standard error carries one line naming both versions and
  `baas --deployment <name> admin deployment setup`

### Requirement: An unreleased build is not checked, and says so
A CLI whose version is the unreleased placeholder `0.0.0-semantically-released`, or is not a numeric
`x.y.z`, SHALL NOT compare versions and SHALL NOT read the recorded version for that purpose. Each
command that would otherwise check SHALL write one line to standard error saying the build is
unreleased and was not checked against the deployment, and SHALL proceed.

#### Scenario: CI's reactor build runs unchecked
- **WHEN** a reactor-built CLI runs `baas run` against a deployment recording `8.1.0`
- **THEN** the job launches as it would from a current release, and standard error carries the
  unreleased-build line
