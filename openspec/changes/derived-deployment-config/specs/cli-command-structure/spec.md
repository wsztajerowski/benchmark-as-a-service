# Spec Delta

## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: A deployment is named by `--deployment`, or by its operator role when none is configured
Every command SHALL accept a global, inherited `--deployment <name>` option, and every explicit pointer
to a concrete deployment SHALL be that option: `teardown --stack-name`, `config sync --name` and
`--config-path` SHALL NOT exist. The option SHALL never be positional. When the option is absent, a
command SHALL use the only configured deployment if exactly one is configured, and SHALL fail listing
the configured names, with the hint `→ choose one: baas config list`, if two or more are. When none is
configured and the option is absent, a command other than `baas admin deployment setup` and
`baas config sync` SHALL take the deployment's name from its credentials when they are an assumed role
named `<name>-role-operator`, and SHALL otherwise fail reporting that no deployment is configured;
`baas admin deployment setup` derives the name from the account, and `baas config sync` requires the
option. A name that is not configured SHALL fail naming the configured deployments when at least one
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

### Requirement: Each deployment has one configuration file, named by the deployment
The CLI SHALL keep one configuration file per deployment, `~/.baas/deployments/<name>.yaml`, and the
deployment's name SHALL be taken from the file's name; no file SHALL record the name inside it. No file
SHALL record a default deployment, and no command SHALL switch one. `baas admin deployment setup` and
`baas config sync` SHALL write the file of the deployment they act on, and `baas admin deployment
teardown` SHALL delete the file of the deployment it tears down once the teardown has completed,
whether or not other deployments remain.

#### Scenario: Setup writes the deployment's own file
- **WHEN** `baas --deployment wiktor-dev admin deployment setup --region us-east-1 --deployer-aws-profile baas-admin` completes
- **THEN** `~/.baas/deployments/wiktor-dev.yaml` holds `aws.deployerProfile: baas-admin` and `aws.region: us-east-1` and no deployment name, and no other file changes

#### Scenario: Teardown removes the file
- **WHEN** `baas --deployment wiktor-dev admin deployment teardown --yes` completes
- **THEN** `~/.baas/deployments/wiktor-dev.yaml` no longer exists

#### Scenario: Tearing down the last deployment
- **WHEN** the only configured deployment is torn down
- **THEN** no deployment is configured, and the next command other than setup reports "No deployment is configured" unless its credentials are a deployment's operator role

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

## REMOVED Requirements

### Requirement: A deployment is named only by `--deployment`
**Reason**: Replaced by *A deployment is named by `--deployment`, or by its operator role when none is
configured*, which keeps every rule here and adds the operator-role case for a machine with no
configuration.
**Migration**: None for laptops. CI stops passing a deployment name; see `ci-benchmark-execution`.

### Requirement: Each deployment has its own configuration file
**Reason**: Replaced by *Each deployment has one configuration file, named by the deployment*. The
name now comes from the file's name alone, so there is no recorded prefix to disagree with it, and the
refusal of a copied file whose prefix names another deployment has nothing left to check.
**Migration**: None. Existing deployments are torn down and recreated with this CLI; delete
`~/.baas/deployments/` before the first setup.

### Requirement: An existing flat configuration is migrated once
**Reason**: No machine holds a flat `~/.baas/config.yaml` any more; the deployments are rebuilt from
scratch with this CLI, so the one-time move and its `aws.profile` rename have nothing left to act on.
**Migration**: None. A stray `~/.baas/config.yaml` is ignored; delete it by hand.
