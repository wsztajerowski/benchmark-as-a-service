# Spec Delta

## ADDED Requirements

### Requirement: Resource names are derived from the caller's AWS account
`baas admin setup` SHALL derive the resource name prefix from the caller's AWS account identifier
as `baas-<accountId>`. The prefix SHALL NOT depend on which principal, role, session or permission
set the caller is using, so that the same account resolves to the same installation for every
identity that can reach it. `baas admin setup` SHALL NOT expose any option that names or selects an
installation — there is exactly one per account and the CLI cannot be told otherwise.

#### Scenario: Two identities on one account resolve the same installation
- **WHEN** `baas admin setup` is run by an IAM user and again by an SSO identity in the same AWS account and region
- **THEN** both runs derive the prefix `baas-<accountId>` and target the same stack, bucket and results table

#### Scenario: Changing permission set does not fork the installation
- **WHEN** the same human runs `baas admin setup` under one permission set and later under a different one in the same account
- **THEN** the second run updates the existing installation rather than creating a second one

#### Scenario: No option selects an installation
- **WHEN** `baas admin setup --mode dev`, `--prefix foo` or `--name foo` is invoked
- **THEN** picocli reports an unknown option error and nothing is deployed

#### Scenario: The prefix is derivable without prior local state
- **WHEN** a machine that has never run `baas admin setup` holds credentials for the account
- **THEN** the installation prefix is computable from the caller's account identifier alone, with no value copied from another machine

### Requirement: Resource names follow one composition rule
Every resource the core stack names SHALL be `<prefix>`, `<prefix>-<name>`, or
`<prefix>-<resource_type>-<name>`, where the `resource_type` and `name` segments are present only
when they are needed to distinguish one resource from another of the same kind. The prefix SHALL
carry the `baas-` namespace itself, so no resource name composes the namespace separately.

#### Scenario: The stack and bucket take the bare prefix
- **WHEN** an installation is deployed for account `123456789012`
- **THEN** the stack is named `baas-123456789012` and the working bucket is named `baas-123456789012`

#### Scenario: Resources of the same kind are distinguished by name
- **WHEN** the stack creates the runner, operator and image-build roles
- **THEN** they are named `<prefix>-role-runner`, `<prefix>-role-operator` and `<prefix>-role-image-build`

#### Scenario: Image Builder resources are distinguishable from one another
- **WHEN** the stack creates the Image Builder component, recipe, infrastructure configuration, distribution configuration and pipeline
- **THEN** no two of them share a name

### Requirement: VPC parameters are immutable once the installation exists
`baas admin setup` SHALL honour `--use-existing-vpc` and its accompanying VPC, subnet and security
group options when it creates an installation. When the installation already exists, it SHALL
compare the submitted networking parameters against the deployed ones and SHALL refuse the update
if they differ, naming both values and submitting nothing. Omitting the options entirely SHALL carry
the deployed values forward.

#### Scenario: Re-setup without networking options preserves existing networking
- **WHEN** `baas admin setup` runs against an installation that was created with `--use-existing-vpc`, and names no networking options
- **THEN** the deployed networking parameters are carried forward and no new VPC is created

#### Scenario: Re-setup with different networking is refused
- **WHEN** `baas admin setup --use-existing-vpc --vpc-id vpc-999 ...` runs against an installation deployed against `vpc-123`
- **THEN** the command exits with an error naming both the deployed and the submitted values, and no stack update is submitted

#### Scenario: Re-setup with identical networking proceeds
- **WHEN** `baas admin setup` repeats exactly the networking options the installation was deployed with
- **THEN** the update proceeds normally

## MODIFIED Requirements

### Requirement: baas admin setup is self-sufficient
`baas admin setup` SHALL accept `--region` and `--aws-profile` directly as command-line options, derive the resource prefix from the caller's AWS account, apply defaults for any omitted option, deploy or update the core stack, and write the result to `~/.baas/config.yaml`. It SHALL NOT require `~/.baas/config.yaml` to pre-exist, and it SHALL NOT expose a `--prefix` option.

#### Scenario: First run with no prior config
- **WHEN** `baas admin setup` runs and `~/.baas/config.yaml` does not exist
- **THEN** the core stack is deployed using the default region and the account-derived prefix, and `~/.baas/config.yaml` is created with the installation's prefix and the credential settings it needs

#### Scenario: No --prefix option
- **WHEN** `baas admin setup --prefix foo` is invoked
- **THEN** picocli reports an unknown option error

### Requirement: Deployer policy is resource-scoped
`BaasCliDeployerPolicy`'s CloudFormation statement SHALL be scoped to `stack/<prefix>/*` and its IAM statement to the installation's own role and instance-profile ARNs — `role/<prefix>-role-*` and `instance-profile/<prefix>-profile-*` — rather than `Resource: "*"`. Because the prefix is account-derived, the rendered policy SHALL reach only installations in the caller's own account.

#### Scenario: Cannot create an arbitrarily-named role
- **WHEN** the deployer policy is evaluated for `iam:CreateRole` on `arn:aws:iam::<acct>:role/admin-backdoor`
- **THEN** the request is not permitted

#### Scenario: An installation deployed by hand needs its own rendered policy
- **WHEN** the deployer policy rendered for `baas-<accountId>` is evaluated against a resource of an installation deployed by hand under another prefix
- **THEN** the request is not permitted, and `baas admin deployer-policy --prefix <prefix>` renders the document that installation needs

### Requirement: Operators can bootstrap config without the deployer's machine
`BaasCliOperatorRole` SHALL be granted `cloudformation:DescribeStacks` scoped to its own core stack. `baas config sync --name <prefix>` SHALL verify that the named installation's stack exists and record its prefix in `~/.baas/config.yaml`. `--name` SHALL be required, so that a machine holding no prior configuration declares which installation it is adopting rather than inferring one from whichever credentials happen to be active. `baas config sync` SHALL NOT store values the CLI derives from the prefix or resolves from the stack at use time.

#### Scenario: Operator on a fresh machine
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas config sync --name baas-123456789012` with no prior `config.yaml`
- **THEN** `config.yaml` is written with that installation's prefix and `baas run` works without hand-copying any file

#### Scenario: Sync without a name is refused
- **WHEN** `baas config sync` is invoked with no `--name`
- **THEN** the command exits with a missing-required-option error and writes nothing

#### Scenario: Adopting an installation deployed by hand
- **WHEN** an operator runs `baas config sync --name baas-123456789012-dev`, naming an installation deployed by hand rather than by `baas admin setup`
- **THEN** subsequent `baas run`, `baas results` and `baas admin` invocations address that installation

## REMOVED Requirements

### Requirement: Resource prefix is derived from the caller's AWS identity
**Reason**: The caller's ARN is not stable for a human. Under IAM Identity Center it changes when the permission set is switched or re-provisioned, and it differs per person on the same account — and a changed prefix does not fail, it silently deploys a second complete installation beside the first. Deriving from the account instead makes the name stable, derivable without local state, and shared by everyone who can reach the account. Replaced by *Resource names are derived from the caller's AWS account*.

**Migration**: An existing installation cannot be renamed, because CloudFormation cannot rename a stack. Deploy the new installation with `baas admin setup`, run `baas admin build-image`, repoint any recorded stack name (including CI's `CORE_STACK_NAME`) at it, then tear down the old stack. Its bucket and results table are `DeletionPolicy: Retain` and no lifecycle rule expires them, so they survive as a read-only archive and can be copied forward later. Deregistering the retired AMI and its snapshot is a manual step.

### Requirement: config.yaml core stack field is unambiguously scoped
**Reason**: The core stack's name and the installation prefix are now the same string, so storing both invites them to disagree. The `aws.coreStackName` field is removed and `prefix` is the single stored identifier.

**Migration**: `baas config sync --name <prefix>` rewrites the file in the new shape. An existing `config.yaml` carrying `aws.coreStackName` is not read for that value; nothing is silently migrated from it.
