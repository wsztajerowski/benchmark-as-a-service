# Spec Delta

## ADDED Requirements

### Requirement: Each deployment has its own configuration file
The CLI SHALL keep one configuration file per deployment, `~/.baas/deployments/<name>.yaml`, where
`<name>` is the deployment's prefix. No file SHALL record a default deployment, and no command SHALL
switch one. `baas admin deployment setup` and `baas config sync` SHALL write the file of the
deployment they act on, and `baas admin deployment teardown` SHALL delete the file of the deployment
it tears down once the teardown has completed, whether or not other deployments remain.

#### Scenario: Setup writes the deployment's own file
- **WHEN** `baas --deployment wiktor-dev admin deployment setup` completes
- **THEN** `~/.baas/deployments/wiktor-dev.yaml` holds that deployment's prefix and region, and no other file changes

#### Scenario: Teardown removes the file
- **WHEN** `baas --deployment wiktor-dev admin deployment teardown --yes` completes
- **THEN** `~/.baas/deployments/wiktor-dev.yaml` no longer exists

#### Scenario: Tearing down the last deployment
- **WHEN** the only configured deployment is torn down
- **THEN** no deployment is configured, and the next command other than setup reports "No deployment is configured"

### Requirement: An existing flat configuration is migrated once
When `~/.baas/config.yaml` holds a `prefix`, the CLI SHALL move its content to
`~/.baas/deployments/<prefix>.yaml` and remove `~/.baas/config.yaml` before resolving the
deployment. When the target file already exists, the flat file's content SHALL replace it, since the
flat file can only have been written later by an older CLI. A `~/.baas/config.yaml` with no `prefix`
SHALL be left untouched and ignored.

#### Scenario: First run after upgrading
- **WHEN** a command runs and `~/.baas/config.yaml` holds `prefix: baas-123456789012` and no deployment file exists
- **THEN** `~/.baas/deployments/baas-123456789012.yaml` holds the same settings, `~/.baas/config.yaml` is gone, and the command addresses that deployment

#### Scenario: An older CLI afterwards fails loudly
- **WHEN** a CLI from before this change runs after the migration
- **THEN** it finds no `~/.baas/config.yaml` and reports that no deployment is configured, rather than addressing any deployment

### Requirement: A deployment is selected by name, or implied when it is the only one
Every command that addresses a deployment SHALL use the one named by the global `--deployment <name>`
option. When the option is absent, it SHALL use the only configured deployment if exactly one is
configured, and SHALL fail listing the configured names if two or more are. When none is configured,
every command SHALL fail reporting that no deployment is configured, except
`baas admin deployment setup`, which derives the name. No environment variable and no stored setting
SHALL select a deployment.

#### Scenario: One deployment needs no flag
- **WHEN** `baas results query` runs with only `baas-123456789012` configured
- **THEN** it reads `baas-123456789012`'s results

#### Scenario: Two deployments require the flag
- **WHEN** `baas run jmh -- MyBenchmark` runs with `baas-123456789012` and `wiktor-dev` configured and no `--deployment`
- **THEN** the command exits non-zero listing both names, issues no AWS call and launches nothing

#### Scenario: Teardown is never ambiguous
- **WHEN** `baas admin deployment teardown --yes` runs with two deployments configured and no `--deployment`
- **THEN** the command exits non-zero listing both names and deletes nothing

#### Scenario: An environment variable selects nothing
- **WHEN** `BAAS_DEPLOYMENT=wiktor-dev baas results query` runs with two deployments configured
- **THEN** the command fails listing both names, as if the variable were unset

## MODIFIED Requirements

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

### Requirement: Resource names the CLI needs are derived or resolved, not cached
A deployment's configuration file SHALL store only what cannot be obtained from the deployment
itself: the credential settings, the region, the deployment prefix, and the operator's own
preferences. Names the composition rule determines — the working bucket, the results table and the
runner instance profile — SHALL be derived from the prefix at use time. Identifiers AWS assigns,
specifically the runner subnet and security group, SHALL be resolved from the deployment's stack
outputs at use time rather than cached, so that a replaced resource cannot leave a stale identifier
behind.

#### Scenario: A replaced security group does not strand the configuration
- **WHEN** the runner security group is replaced by a stack update and its identifier changes, and `baas run` is invoked afterwards with no intervening configuration command
- **THEN** the job uses the current security group identifier

#### Scenario: Config carries no derivable names
- **WHEN** `baas config show` reports the configuration after `baas config sync`
- **THEN** no stored field holds the bucket name, the results table name or the runner instance profile name

## REMOVED Requirements

### Requirement: Every command accepts an alternative configuration file
**Reason**: A deployment is addressed by name only. Each deployment has its own file, and
`--deployment` selects it. The retired-deployment case it served is gone, because nothing survives a
teardown (`jobs-command`).
**Migration**: `--config-path ~/.baas/dev.yaml` becomes `--deployment <its prefix>`. The flat
`~/.baas/config.yaml` is migrated automatically. Tests receive the configuration root through code.
The table and bucket overrides stay gone: no command accepts `--results-table` or `--bucket`.
