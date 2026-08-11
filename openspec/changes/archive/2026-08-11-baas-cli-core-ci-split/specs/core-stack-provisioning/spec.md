## ADDED Requirements

### Requirement: Core stack contains only CLI-owned infrastructure
The core CloudFormation stack (template `cf-template-core.yaml`) SHALL contain the VPC, public subnet, internet gateway, S3 gateway endpoint, `RunnerSecurityGroup`, `S3MainBucket`, `RunnerRole`, and `RunnerInstanceProfile`. It SHALL NOT contain any GitHub OIDC provider, `WorkflowRole`, or GitHub org/repo/workflow parameters.

#### Scenario: Deploying the core stack creates no GitHub-related resources
- **WHEN** `baas admin setup` deploys the core stack
- **THEN** the resulting CloudFormation stack contains no `AWS::IAM::OIDCProvider` resource and no `WorkflowRole`

### Requirement: baas-cli never manages the CI stack
No `baas` command SHALL create, update, delete, or read the CI stack (`cf-template-ci.yaml`, containing the GitHub OIDC provider and `WorkflowRole`). The CI stack SHALL be deployed and torn down independently, outside of `baas-cli`.

#### Scenario: No command references the CI stack
- **WHEN** any `baas` subcommand executes
- **THEN** it makes no CloudFormation, IAM OIDC, or `WorkflowRole`-related API call scoped to the CI stack

#### Scenario: CI template is not bundled in the CLI
- **WHEN** the `baas-cli` JAR is built
- **THEN** `baas-cli/src/main/resources/templates/` contains only `cf-template-core.yaml`, and `cf-template-ci.yaml` is absent from the JAR's classpath resources

### Requirement: baas admin setup is self-sufficient
`baas admin setup` SHALL accept `--region`, `--aws-profile`, and `--mongo-uri` directly as command-line options, derive the resource prefix from the caller's AWS identity, apply defaults for any omitted option, deploy or update the core stack, and write the result to `~/.baas/config.yaml`. It SHALL NOT require `~/.baas/config.yaml` to pre-exist, and it SHALL NOT expose a `--prefix` option.

#### Scenario: First run with no prior config
- **WHEN** `baas admin setup --mongo-uri "mongodb+srv://..."` runs and `~/.baas/config.yaml` does not exist
- **THEN** the core stack is deployed using the default region and the ARN-derived prefix, the Mongo URI is stored in SSM SecureString, and `~/.baas/config.yaml` is created with the stack's outputs

### Requirement: Resource prefix is derived from the caller's AWS identity
`baas admin setup` SHALL derive the resource name prefix deterministically from the caller's AWS identity ARN (`lowercase(base32(sha256(arn)))[0:8]`) and name the core stack `baas-<prefix>`. It SHALL NOT accept a user-supplied prefix.

#### Scenario: Prefix is stable per identity
- **WHEN** `baas admin setup` is run twice by the same AWS principal
- **THEN** both runs derive the same prefix and target the same `baas-<prefix>` core stack

#### Scenario: No --prefix option
- **WHEN** `baas admin setup --prefix foo` is invoked
- **THEN** picocli reports an unknown option error

### Requirement: baas admin setup accepts no GitHub/OIDC options
`baas admin setup` SHALL NOT expose `--github-org`, `--github-repo`, `--workflow-id`, `--workflow-branch`, or `--oidc-provider-arn` options.

#### Scenario: Removed options are rejected
- **WHEN** `baas admin setup --github-org foo` is invoked
- **THEN** picocli reports an unknown option error, since no such option is registered

### Requirement: Operator identity is an assumable role created by the core stack
The core stack SHALL create `BaasCliOperatorRole` as an `AWS::IAM::Role` resource (not a policy attached to any user), scoped to that stack's own `S3MainBucket`, `RunnerRole`, and mongo SSM parameter path. Its trust policy SHALL allow the AWS account root, so `baas admin setup` requires no additional parameter to identify who the operator is. The role's ARN SHALL be a stack output (`OperatorRoleArn`) and SHALL be printed by `baas admin setup`. The stack SHALL NOT grant `sts:AssumeRole` on this role to any specific IAM identity — that remains a manual, per-identity step performed outside the stack.

#### Scenario: Operator role is created unattached
- **WHEN** `baas admin setup` deploys the core stack
- **THEN** the stack contains an `AWS::IAM::Role` named `<prefix>-operator-role` with a trust policy allowing `arn:aws:iam::<account-id>:root`, and no `sts:AssumeRole` grant exists for any specific principal until one is added manually

#### Scenario: Operator role ARN is surfaced to the operator
- **WHEN** `baas admin setup` completes
- **THEN** it prints the `OperatorRoleArn` stack output together with instructions for granting `sts:AssumeRole` to a specific IAM identity

### Requirement: Operator role permissions
`BaasCliOperatorRole` SHALL cover: `ec2:RunInstances`/`Describe*` to launch and observe benchmark runner instances, tag-scoped `ec2:TerminateInstances` (condition `aws:ResourceTag/baas-role=benchmark-runner`), `ec2:CreateTags` scoped to the `RunInstances` create action, `ssm:GetParameter`/`PutParameter` on the mongo connection-string path, `ssm:GetParameter` on the public AMI lookup path (`/aws/service/ami-amazon-linux-latest/*`), S3 object access scoped to the core stack's bucket, and `iam:PassRole` scoped to `RunnerRole` only. `baas run`/`baas results`/`baas config` SHALL succeed when invoked by an identity that has assumed `BaasCliOperatorRole`.

#### Scenario: Operator role suffices for daily use
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas run jmh -- ...` or `baas results`
- **THEN** every AWS API call made succeeds under that role's permissions, including the AMI SSM lookup needed to resolve the runner's AMI ID

### Requirement: Deployer policy is created out-of-band, before the core stack exists
`BaasCliDeployerPolicy` (matching `infra/deployer-policy.json`) SHALL cover: CloudFormation stack lifecycle (create, update, delete, describe, change-set operations), VPC/EC2 networking create/delete/describe — including `ec2:DescribeInstances`, needed by `baas admin teardown`'s active-run safety gate — IAM role/instance-profile create (covers both `RunnerRole` and `OperatorRole`, since both are the same resource type), S3 bucket create, and `ssm:DeleteParameter` scoped to the mongo connection-string path, needed by `baas admin teardown`'s cleanup step. `baas admin setup`/`baas admin teardown` SHALL require `BaasCliDeployerPolicy`. This policy SHALL be created manually, before the first `baas admin setup` run — the core stack SHALL NOT create it, since CloudFormation cannot grant permission to create CloudFormation stacks.

#### Scenario: Deployer policy permits the full setup/teardown lifecycle
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin setup` followed later by `baas admin teardown`
- **THEN** every AWS API call made by both commands succeeds, including teardown's active-run check and SSM parameter cleanup

### Requirement: RunnerRole S3 policy matches the bucket name
`RunnerRole`'s S3 access policy SHALL reference the exact same bucket name as the `S3MainBucket` resource (`baas-${ResourceNamePrefix}`).

#### Scenario: Runner can access its own bucket
- **WHEN** an EC2 instance launched with `RunnerRole` attempts `s3:PutObject` against the stack's `S3MainBucket`
- **THEN** the request succeeds (the policy's resource ARN matches the bucket's actual name)

### Requirement: config.yaml core stack field is unambiguously scoped
`~/.baas/config.yaml`'s `aws.coreStackName` field SHALL refer only to the core stack; `baas admin setup` sets it to `baas-<prefix>` (the ARN-derived prefix). No config field SHALL exist for the CI stack.

#### Scenario: Field rename reflected in written config
- **WHEN** `baas admin setup` runs
- **THEN** `~/.baas/config.yaml` is written with `aws.coreStackName: baas-<prefix>` (e.g. `baas-a1b2c3d4`) and no CI-stack-related field

### Requirement: Teardown safety gates
`baas admin teardown` SHALL abort if any EC2 instance tagged `baas-role=benchmark-runner` is in the `running` state, SHALL require explicit confirmation (retyping the stack name, or `--yes`), SHALL retain the S3 bucket by default (deleting it only with `--delete-bucket`), and SHALL NOT delete or modify anything in MongoDB/Atlas.

#### Scenario: Abort when a run is in flight
- **WHEN** `baas admin teardown` runs while a `baas-role=benchmark-runner` instance is `running`
- **THEN** the command exits with an error listing the running instance ID(s) and performs no destructive action

#### Scenario: Bucket retained unless explicitly deleted
- **WHEN** `baas admin teardown --yes` runs without `--delete-bucket`
- **THEN** the core stack is deleted but the S3 bucket persists
