# Spec Delta

## MODIFIED Requirements

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
- **WHEN** `baas admin deployment teardown` runs with two deployments configured and no `--deployment`
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
- **WHEN** `baas --deployment wiktor-dev admin deployment teardown` completes
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
