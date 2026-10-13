# Spec Delta

## MODIFIED Requirements

### Requirement: Operator identity is an assumable role created by the core stack
The core stack SHALL create `BaasCliOperatorRole` as an `AWS::IAM::Role` resource (not a policy attached to any user), scoped to that stack's own `S3MainBucket`, `RunnerRole`, results table, and the runner AMI pointer path. Its trust policy SHALL allow the AWS account root, so `baas admin deployment setup` requires no additional parameter to identify who the operator is, and SHALL additionally allow the federated workload principal when federation parameters are supplied. The role's ARN SHALL be a stack output (`OperatorRoleArn`) and SHALL be printed by `baas admin deployment setup`. The stack SHALL NOT grant `sts:AssumeRole` on this role to any specific IAM identity — for anyone other than the deployer, that remains a manual, per-identity step performed outside the stack.

#### Scenario: Operator role is created unattached
- **WHEN** `baas admin deployment setup` deploys the core stack
- **THEN** the stack contains an `AWS::IAM::Role` named `<prefix>-role-operator` with a trust policy allowing `arn:aws:iam::<account-id>:root`, and no `sts:AssumeRole` grant exists for any specific principal in the stack

#### Scenario: Operator role ARN is surfaced to the operator
- **WHEN** `baas admin deployment setup` creates a deployment
- **THEN** it prints the `OperatorRoleArn` stack output, states that the deployer's own credentials already run day-to-day commands as that role, and gives the instructions for granting `sts:AssumeRole` on it to a teammate followed by `baas --deployment <name> config sync --operator-aws-profile <profile>`; it does not ask for a `role_arn` profile

#### Scenario: The federated principal is additive
- **WHEN** the core stack is deployed with federation parameters
- **THEN** the account-root principal remains, and the federated statement is present alongside it

### Requirement: Day-to-day commands resolve operator credentials
Every command outside `baas admin` that makes an AWS call SHALL run as the deployment's operator role,
`<name>-role-operator`. Its source credentials SHALL come from the deployment's `aws.operatorProfile`
when set, else its `aws.deployerProfile` when set, else the default credential chain. The command
SHALL then identify the source with `sts:GetCallerIdentity` and:

- when the source is the assumed role `<name>-role-operator`, use it as is;
- when the source is an assumed role named `<other>-role-operator` for a different `<other>`, fail
  naming both deployments before any further AWS call;
- otherwise assume `arn:aws:iam::<account>:role/<name>-role-operator`, where `<account>` is the
  source's own account, and keep those credentials valid for as long as the command runs.

When the assumption fails, the command SHALL fail naming the role and the source, and SHALL NOT make
any further call with the source credentials. `baas admin` commands SHALL use their source —
`aws.deployerProfile`, else the default credential chain — as is, and SHALL NOT assume the operator
role.

#### Scenario: Deployer profile is never silently reused
- **WHEN** the configuration has `aws.deployerProfile: baas-admin` and no `aws.operatorProfile`, and `baas run jmh` is invoked
- **THEN** every AWS call after identification is made as `<name>-role-operator`, with credentials obtained by assuming it from `baas-admin`

#### Scenario: Operator profile is honoured
- **WHEN** the configuration has `aws.operatorProfile: teammate` and `aws.deployerProfile: baas-admin`, and `baas run jmh` is invoked
- **THEN** the operator role is assumed from `teammate`, and `baas-admin` is not used

#### Scenario: A failed assumption stops the command
- **WHEN** the source credentials may not assume `<name>-role-operator` and `baas jobs list` is invoked
- **THEN** the command exits non-zero naming the role and the source, and makes no EC2, S3, DynamoDB or CloudFormation call

#### Scenario: Another deployment's operator role is refused
- **WHEN** `baas --deployment wiktor-dev results query` runs with credentials for the assumed role `baas-123456789012-role-operator`
- **THEN** the command exits non-zero naming `wiktor-dev` and `baas-123456789012`, and makes no call after identification

#### Scenario: A legacy role_arn profile keeps working
- **WHEN** `aws.operatorProfile` names a `~/.aws` profile whose `role_arn` is `<name>-role-operator`
- **THEN** the command uses that session as is and makes no `sts:AssumeRole` call of its own

#### Scenario: A command outlives one role session
- **WHEN** an attached `baas run` lasts longer than the one-hour limit on a chained role session
- **THEN** the operator credentials are renewed before they expire, and the CLI can still terminate the instance at the end

#### Scenario: Admin commands use the deployer as is
- **WHEN** `baas admin image build` runs with `aws.deployerProfile: baas-admin`
- **THEN** its AWS calls are made with `baas-admin`'s own credentials and no `sts:AssumeRole` call is made

### Requirement: Operators can bootstrap config without the deployer's machine
`BaasCliOperatorRole` SHALL be granted `cloudformation:DescribeStacks` scoped to its own core stack.
`baas config sync` SHALL obtain operator credentials as *Day-to-day commands resolve operator
credentials* states, find the region the deployment's bucket is in, verify that the deployment's stack
exists there, and write that deployment's configuration file with that region. It SHALL accept `--operator-aws-profile <profile>`, used as the source for that
verification and stored as `aws.operatorProfile` once it succeeds. When no deployment is configured on
the machine, `--deployment` SHALL be required, so that a machine with no prior configuration declares
which deployment it adopts rather than inferring one from whichever credentials happen to be active.
The name SHALL never be derived from the account or from the credentials. When exactly one deployment
is configured, a bare `baas config sync` SHALL re-verify that one. When two or more are configured, it
SHALL require `--deployment` as every command does. `baas config sync` SHALL NOT store any value the
CLI derives from the name, and SHALL write nothing when verification fails.

#### Scenario: Operator on a fresh machine
- **WHEN** a teammate whose `my-sso` profile may assume `baas-123456789012-role-operator` runs `baas --deployment baas-123456789012 config sync --operator-aws-profile my-sso` with no configured deployment
- **THEN** `~/.baas/deployments/baas-123456789012.yaml` is written holding `aws.operatorProfile: my-sso` and the deployment's `aws.region`, and nothing else, and `baas run` works without hand-copying any file or writing a `role_arn` profile

#### Scenario: Sync without a name is refused
- **WHEN** `baas config sync` is invoked with no `--deployment` and no deployment is configured
- **THEN** the command exits non-zero asking for `--deployment`, issues no AWS call and writes nothing

#### Scenario: Sync refuses credentials that cannot operate the deployment
- **WHEN** `baas --deployment baas-123456789012 config sync --operator-aws-profile other` runs and `other` may not assume the operator role
- **THEN** the command exits non-zero naming the role and the profile, and writes no file

#### Scenario: Adopting a deployment set up by hand
- **WHEN** an operator runs `baas config sync --deployment baas-123456789012-dev`, naming a deployment whose stack was deployed by hand rather than by `baas admin deployment setup`
- **THEN** that deployment's configuration file is written, and later `baas run`, `baas results query` and `baas admin` invocations naming it address that deployment

#### Scenario: Re-syncing the only deployment
- **WHEN** `baas config sync` is invoked with no `--deployment` and exactly one deployment is configured
- **THEN** that deployment is re-verified and no other file is written

#### Scenario: Adopting a second deployment
- **WHEN** `baas config sync --deployment wiktor-dev` runs on a machine where `baas-123456789012` is configured
- **THEN** a second configuration file is written, `baas-123456789012`'s is unchanged, and later commands must name one of the two

## ADDED Requirements

### Requirement: The deployer may assume its own deployment's operator role
The rendered deployer policy SHALL allow `sts:AssumeRole` on
`arn:aws:iam::<account>:role/<prefix>-role-operator` for its own prefix only, and on no other role. It
SHALL still fit the inline-policy budget test.

#### Scenario: An admin runs a job with no operator setup
- **WHEN** a principal holding only the rendered deployer policy for `baas-123456789012` runs `baas run jmh -- MyBenchmark` with `aws.deployerProfile` set and no `aws.operatorProfile`
- **THEN** the CLI assumes `baas-123456789012-role-operator` and the job launches

#### Scenario: Another deployment's operator role is not granted
- **WHEN** the deployer policy rendered for `baas-123456789012` is inspected
- **THEN** its only `sts:AssumeRole` resource is `arn:aws:iam::<account>:role/baas-123456789012-role-operator`
