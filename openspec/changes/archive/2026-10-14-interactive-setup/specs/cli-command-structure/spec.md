# Spec Delta

## ADDED Requirements

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

## MODIFIED Requirements

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

### Requirement: Next-step hints are shown to people, never to scripts
A command MAY end by suggesting at most two follow-up commands, each written to standard error through
the logger as `→ <purpose>: <exact command>` with real identifiers filled in. A suggested command that
acts on a deployment SHALL always carry `--deployment <name>`, however many deployments are configured,
so that it stays valid when another is added. Hints SHALL appear only when the console is interactive,
by the same definition that gates colour, and SHALL never appear on standard output. There SHALL be no
option to disable them. Setup's closing report of next steps is its result, not a hint: it is printed
in both modes, and every command in it carries `--deployment <name>` too.

#### Scenario: A finished job points to its measurements
- **WHEN** `baas jobs show <id>` completes on an interactive terminal for deployment `baas-123456789012`
- **THEN** standard error ends with `→ measurements: baas --deployment baas-123456789012 query --job-id <id>`

#### Scenario: Scripts see no hints
- **WHEN** the same command runs with standard output redirected or without a console
- **THEN** no hint is written to either stream

#### Scenario: A hint printed with one deployment works with two
- **WHEN** a hint is printed while one deployment is configured, a second is then configured, and the hint's command is run as printed
- **THEN** it acts on the deployment it named, and is not refused as ambiguous

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
