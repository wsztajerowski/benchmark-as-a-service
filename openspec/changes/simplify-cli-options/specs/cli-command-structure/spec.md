# Spec Delta

## ADDED Requirements

### Requirement: Every command accepts an alternative configuration file
Every `baas` command SHALL accept `--config-path <file>`, naming the configuration file the invocation
reads and writes in place of `~/.baas/config.yaml`. The option SHALL be accepted before or after the
subcommand name. When `--config-path` names a file that does not exist, a command that only reads
configuration SHALL fail naming the path, and `baas config sync`, `baas config set` and `baas admin setup`
SHALL create it. Without the option, behaviour on a missing `~/.baas/config.yaml` is unchanged. No command
SHALL accept a per-invocation override of the results table or the working bucket: addressing another
installation means naming that installation's configuration file.

#### Scenario: Reading a retired installation's history
- **WHEN** `baas results --config-path ~/.baas/retired.yaml --project lynx-journal` runs, and that file
  names a retired installation's prefix
- **THEN** that installation's measurements are reported, and `~/.baas/config.yaml` is neither read nor
  changed

#### Scenario: A second configuration is created by sync
- **WHEN** `baas config sync --name baas-123456789012-dev --config-path ~/.baas/dev.yaml` runs and the
  file does not exist
- **THEN** `~/.baas/dev.yaml` is written with that prefix, and `~/.baas/config.yaml` is unchanged

#### Scenario: A mistyped path fails rather than reading nothing
- **WHEN** `baas results --config-path ~/.baas/nope.yaml` runs and the file does not exist
- **THEN** the command exits non-zero naming `~/.baas/nope.yaml`, and issues no AWS call

#### Scenario: The option is inherited on either side of the subcommand
- **WHEN** `baas --config-path f.yaml results` and `baas results --config-path f.yaml` are each invoked
- **THEN** both read `f.yaml`

#### Scenario: Table and bucket overrides are gone
- **WHEN** `baas results --results-table t`, `baas download --results-table t` or
  `baas download --bucket b` is invoked
- **THEN** picocli reports an unknown option error

### Requirement: Git is consulted only when the operator enables it
The configuration SHALL carry a `git.resolveProject` preference, `false` by default and set with
`baas config set --git-resolve-project <true|false>`. When it is `false`, no command SHALL invoke git. When it is `true`, git SHALL be
consulted only to derive a project name, as the `baas run` and `baas results` project requirements
specify; it SHALL NOT be consulted for `branch` or `commit`.

#### Scenario: A fresh configuration does not use git
- **WHEN** `baas run` or `baas results` is invoked with a configuration that does not set
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
determined the command SHALL fail before provisioning, naming `--project`.

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

## MODIFIED Requirements

### Requirement: Results filters cover the supported query patterns
`baas results` SHALL accept `--request-id`, `--benchmark-name`, `--tag <key>=<value>` (repeatable),
`--project`, `--all-projects`, `--all-runs`, `--group-by` and `--limit`. `--request-id` SHALL be mutually
exclusive with the other filters, `--project` and `--all-projects` SHALL be mutually exclusive with each
other, and an invalid combination SHALL fail with a message naming the supported forms.

#### Scenario: Tag filter is accepted
- **WHEN** `baas results --project p --tag jdk=25.0.4` is invoked
- **THEN** matching rows are returned

#### Scenario: Conflicting filters are rejected
- **WHEN** both `--tag branch=main` and `--request-id abc` are given
- **THEN** the command exits non-zero explaining that `--request-id` cannot be combined with other filters

#### Scenario: One project and every project are exclusive
- **WHEN** both `--project p` and `--all-projects` are given
- **THEN** the command exits non-zero naming both options

#### Scenario: Limit bounds the output
- **WHEN** `--limit 5` is given and more rows match
- **THEN** at most five rows are returned

#### Scenario: Removed options are rejected
- **WHEN** `baas results --living-branches` or `baas results --all` is invoked
- **THEN** picocli reports an unknown option error

## REMOVED Requirements

### Requirement: The project is derived, with an override
**Reason**: The project no longer comes from the invocation directory. It is explicit, or — opt-in —
derived from the benchmark JAR's repository.
**Migration**: Pass `--project <name>`, or run `baas config set --git-resolve-project true`. See
*The project is explicit unless derivation is enabled*.

### Requirement: `commit` and `branch` are derived, with overrides, and omitted when unknown
**Reason**: Git is no longer consulted for either value, and `--commit`/`--branch` duplicated `--tag`.
**Migration**: Pass `--tag commit=<sha> --tag branch=<name>`. See *`commit` and `branch` are
caller-supplied tags, omitted when absent*.

### Requirement: `baas run` accepts an explicit AMI override
**Reason**: Exactly one runner image exists and its predecessor is deregistered on rebuild, so the
override could only name the current image or an image built outside BaaS, whose results are not
comparable. It was an escape hatch from the no-fallback invariant.
**Migration**: None. Build a new image with `baas admin build-image` and run normally.

### Requirement: Read-only commands can address another installation's results table
**Reason**: Replaced by the global `--config-path`, which addresses a whole installation rather than one
of its resources, and applies uniformly to every command.
**Migration**: Point `--config-path` at a configuration file that names the other installation's prefix.
For a live installation, create one with `baas config sync --name <prefix> --config-path <file>`. For a
torn-down one, keep a copy of the configuration that addressed it, or write the prefix, region and
credential settings by hand: `config sync` verifies the stack exists, so it cannot adopt a torn-down
installation.
