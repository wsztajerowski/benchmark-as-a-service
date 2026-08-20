# core-stack-provisioning Specification

## Purpose

The `baas-<prefix>` core CloudFormation stack — networking, working bucket, `RunnerRole` and its
instance profile, `OperatorRole` — together with the CLI commands that deploy it (`baas admin
setup`/`teardown`), the credentials each command path resolves, and the runtime behaviour of the
runners it launches.

## Requirements

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

### Requirement: RunnerRole S3 policy matches the bucket name
`RunnerRole`'s S3 access policy SHALL reference the exact same bucket name as the `S3MainBucket` resource (`baas-${ResourceNamePrefix}`).

#### Scenario: Runner can access its own bucket
- **WHEN** an EC2 instance launched with `RunnerRole` attempts `s3:PutObject` against the stack's `S3MainBucket`
- **THEN** the request succeeds (the policy's resource ARN matches the bucket's actual name)

### Requirement: Working bucket survives stack deletion by default
`S3MainBucket` SHALL declare `DeletionPolicy: Retain` and `UpdateReplacePolicy: Retain`, and SHALL declare lifecycle rules expiring noncurrent versions and aborting incomplete multipart uploads.

#### Scenario: Default teardown retains the bucket by design
- **WHEN** `baas admin teardown --yes` deletes the core stack without `--delete-bucket`
- **THEN** the stack reaches `DELETE_COMPLETE` and the bucket still exists

### Requirement: baas admin setup is self-sufficient
`baas admin setup` SHALL accept `--region` and `--aws-profile` directly as command-line options, derive the resource prefix from the caller's AWS identity, apply defaults for any omitted option, deploy or update the core stack, and write the result to `~/.baas/config.yaml`. It SHALL NOT require `~/.baas/config.yaml` to pre-exist, and it SHALL NOT expose a `--prefix` option.

#### Scenario: First run with no prior config
- **WHEN** `baas admin setup` runs and `~/.baas/config.yaml` does not exist
- **THEN** the core stack is deployed using the default region and the ARN-derived prefix, and `~/.baas/config.yaml` is created with the stack's outputs, including the results table name

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

### Requirement: config.yaml core stack field is unambiguously scoped
`~/.baas/config.yaml`'s `aws.coreStackName` field SHALL refer only to the core stack; `baas admin setup` sets it to `baas-<prefix>` (the ARN-derived prefix). No config field SHALL exist for the CI stack.

#### Scenario: Field rename reflected in written config
- **WHEN** `baas admin setup` runs
- **THEN** `~/.baas/config.yaml` is written with `aws.coreStackName: baas-<prefix>` (e.g. `baas-a1b2c3d4`) and no CI-stack-related field

### Requirement: Teardown safety gates
`baas admin teardown` SHALL abort if any EC2 instance tagged `baas-role=benchmark-runner` is in the `running` state, SHALL require explicit confirmation (retyping the stack name, or `--yes`), and SHALL retain both the S3 bucket and the DynamoDB results table by default — the bucket is deletable with `--delete-bucket`, the table by no flag at all. It SHALL name both retained resources on exit.

#### Scenario: Abort when a run is in flight
- **WHEN** `baas admin teardown` runs while a `baas-role=benchmark-runner` instance is `running`
- **THEN** the command exits with an error listing the running instance ID(s) and performs no destructive action

#### Scenario: Bucket retained unless explicitly deleted
- **WHEN** `baas admin teardown --yes` runs without `--delete-bucket`
- **THEN** the core stack is deleted but the S3 bucket persists

### Requirement: Bucket emptying handles object versions
`S3UploadService.deleteAllObjects` SHALL delete every object version and delete marker, not only current versions.

#### Scenario: Versioned bucket is fully emptied
- **WHEN** `deleteAllObjects` runs against a versioning-enabled bucket whose keys have multiple versions
- **THEN** a subsequent `listObjectVersions` returns no versions and no delete markers

### Requirement: Deployer policy is created out-of-band, before the core stack exists
`BaasCliDeployerPolicy` (matching `infra/deployer-policy.json`) SHALL cover: CloudFormation stack lifecycle (create, update, delete, describe, change-set operations), VPC/EC2 networking create/delete/describe — including `ec2:DescribeInstances`, needed by `baas admin teardown`'s active-run safety gate — IAM role/instance-profile create (covers both `RunnerRole` and `OperatorRole`, since both are the same resource type), S3 bucket create, and the DynamoDB table lifecycle actions. `baas admin setup`/`baas admin teardown` SHALL require `BaasCliDeployerPolicy`. This policy SHALL be created manually, before the first `baas admin setup` run — the core stack SHALL NOT create it, since CloudFormation cannot grant permission to create CloudFormation stacks.

#### Scenario: Deployer policy permits the full setup/teardown lifecycle
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin setup` followed later by `baas admin teardown`
- **THEN** every AWS API call made by both commands succeeds, including teardown's active-run check

### Requirement: Deployer policy covers the full setup path
`BaasCliDeployerPolicy` SHALL include `ssm:PutParameter` on the runner AMI pointer path, the S3 actions needed to empty and delete the working bucket (`s3:ListBucket`, `s3:ListBucketVersions`, `s3:DeleteObject`, `s3:DeleteObjectVersion`, `s3:DeleteBucket`), and the IAM read-back actions CloudFormation invokes after role creation (`iam:GetRolePolicy`, `iam:ListRolePolicies`, `iam:ListAttachedRolePolicies`).

#### Scenario: Setup succeeds end to end
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin setup`
- **THEN** the stack deploys, including the results table, and the command exits 0

### Requirement: Deployer policy holds no CI-stack permissions
`BaasCliDeployerPolicy` SHALL NOT grant any OIDC-provider action or `iam:UpdateAssumeRolePolicy`.

#### Scenario: No OIDC actions present
- **WHEN** `infra/deployer-policy.json` is inspected
- **THEN** it contains no action beginning `iam:` and ending `OpenIDConnectProvider`, and no `iam:UpdateAssumeRolePolicy`

### Requirement: Deployer policy is resource-scoped
`BaasCliDeployerPolicy`'s CloudFormation statement SHALL be scoped to `stack/baas-*/*` and its IAM statement to `role/*-runner-role`, `role/*-operator-role`, and the corresponding instance-profile ARNs, rather than `Resource: "*"`.

#### Scenario: Cannot create an arbitrarily-named role
- **WHEN** the deployer policy is evaluated for `iam:CreateRole` on `arn:aws:iam::<acct>:role/admin-backdoor`
- **THEN** the request is not permitted

### Requirement: Operator identity is an assumable role created by the core stack
The core stack SHALL create `BaasCliOperatorRole` as an `AWS::IAM::Role` resource (not a policy attached to any user), scoped to that stack's own `S3MainBucket`, `RunnerRole`, results table, and the runner AMI pointer path. Its trust policy SHALL allow the AWS account root, so `baas admin setup` requires no additional parameter to identify who the operator is. The role's ARN SHALL be a stack output (`OperatorRoleArn`) and SHALL be printed by `baas admin setup`. The stack SHALL NOT grant `sts:AssumeRole` on this role to any specific IAM identity — that remains a manual, per-identity step performed outside the stack.

#### Scenario: Operator role is created unattached
- **WHEN** `baas admin setup` deploys the core stack
- **THEN** the stack contains an `AWS::IAM::Role` named `<prefix>-operator-role` with a trust policy allowing `arn:aws:iam::<account-id>:root`, and no `sts:AssumeRole` grant exists for any specific principal until one is added manually

#### Scenario: Operator role ARN is surfaced to the operator
- **WHEN** `baas admin setup` completes
- **THEN** it prints the `OperatorRoleArn` stack output together with instructions for granting `sts:AssumeRole` to a specific IAM identity

### Requirement: Operator role permissions
`BaasCliOperatorRole` SHALL cover: `ec2:RunInstances`/`Describe*` to launch and observe benchmark runner instances, tag-scoped `ec2:TerminateInstances` (condition `aws:ResourceTag/baas-role=benchmark-runner`), `ec2:CreateTags` scoped to the `RunInstances` create action, `ssm:GetParameter` on the runner AMI pointer path (`/<prefix>/runner/ami-id`) and no SSM write of any kind, `dynamodb:Query`/`GetItem` on the results table and its index with no write action, `ec2:DescribeImages` to validate the resolved AMI, S3 object access scoped to the core stack's bucket, and `iam:PassRole` scoped to `RunnerRole` only. It SHALL NOT cover the public AL2023 AMI lookup path (`/aws/service/ami-amazon-linux-latest/*`), which is no longer used now that the runner boots from a purpose-built image. `baas run`/`baas results`/`baas config`/`baas env` SHALL succeed when invoked by an identity that has assumed `BaasCliOperatorRole`.

#### Scenario: Operator role suffices for daily use
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas run jmh -- ...`, `baas results`, or `baas env diff`
- **THEN** every AWS API call made succeeds under that role's permissions, including the SSM read of `/<prefix>/runner/ami-id` needed to resolve the runner's AMI ID

#### Scenario: Public AMI lookup path is no longer granted
- **WHEN** `infra/operator-policy.json` is inspected
- **THEN** it contains no statement granting `ssm:GetParameter` on `/aws/service/ami-amazon-linux-latest/*`

### Requirement: Operator policy reference copy stays in sync
`infra/operator-policy.json` SHALL grant exactly the same set of actions as the `OperatorRole` inline policies in `infra/cf-template-core.yaml`.

#### Scenario: Drift is caught
- **WHEN** an action is added to `OperatorRole` but not to `operator-policy.json`
- **THEN** the drift test fails

### Requirement: Day-to-day commands resolve operator credentials
`~/.baas/config.yaml` SHALL carry `aws.operatorProfile`. `baas run`, `baas results`, and `baas config show` SHALL resolve AWS credentials from it; `baas admin setup` and `baas admin teardown` SHALL continue using `aws.profile`. When `aws.operatorProfile` is unset, day-to-day commands SHALL fall through to the default credential chain and SHALL NOT use `aws.profile`.

#### Scenario: Deployer profile is never silently reused
- **WHEN** `config.yaml` has `aws.profile: baas-deployer` and no `aws.operatorProfile`, and `baas run jmh` is invoked
- **THEN** the AWS client is built without an explicit profile, and a warning naming `baas config set --operator-profile` is printed

#### Scenario: Operator profile is honoured
- **WHEN** `config.yaml` has `aws.operatorProfile: baas-operator` and `baas run jmh` is invoked
- **THEN** the AWS client is built with the `baas-operator` profile

### Requirement: Operators can bootstrap config without the deployer's machine
`BaasCliOperatorRole` SHALL be granted `cloudformation:DescribeStacks` scoped to its own core stack, and `baas config sync --core-stack-name <name>` SHALL populate `bucket`, `subnetId`, `securityGroupId`, `vpcId`, `runnerInstanceProfileName`, `prefix`, and `coreStackName` in `~/.baas/config.yaml` from that stack's outputs.

#### Scenario: Operator on a fresh machine
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas config sync --core-stack-name baas-a1b2c3d4` with no prior `config.yaml`
- **THEN** `config.yaml` is written with the stack's outputs and `baas run` works without hand-copying any file

### Requirement: Failed runs leave diagnosable output
The user-data script SHALL upload `/var/log/cloud-init-output.log` to `s3://<bucket>/<resultPath>/cloud-init-output.log` before terminating the instance, on both the success and failure paths.

#### Scenario: Log survives self-termination
- **WHEN** a benchmark run exits non-zero and the instance self-terminates
- **THEN** the run's S3 prefix contains `cloud-init-output.log` alongside `run-status`

### Requirement: Poll loop detects a dead instance
`baas run` SHALL check the runner instance's state while polling and SHALL abort with a non-zero exit as soon as the instance reaches `terminated` or `shutting-down` without a `run-status` sentinel, rather than waiting for the wall-clock cap.

#### Scenario: Boot failure fails fast
- **WHEN** the runner instance terminates before writing `run-status`
- **THEN** `baas run` reports the instance state and exits non-zero well before `wallClockHardKillSeconds` elapses

### Requirement: Core stack declares the image build pipeline
`cf-template-core.yaml` SHALL declare `AWS::ImageBuilder::Component`,
`AWS::ImageBuilder::ImageRecipe`, `AWS::ImageBuilder::InfrastructureConfiguration`,
`AWS::ImageBuilder::DistributionConfiguration`, and `AWS::ImageBuilder::ImagePipeline`. The recipe's root
volume SHALL be 30 GB gp3, preserving the existing volume requirement. The infrastructure configuration
SHALL place build instances in the public subnet.

#### Scenario: Pipeline resources are present
- **WHEN** the core stack is deployed
- **THEN** it contains an image pipeline, recipe, component, infrastructure configuration, and
  distribution configuration

#### Scenario: Build volume matches the runner volume
- **WHEN** the image recipe is inspected
- **THEN** its root block device is 30 GB and of type gp3

### Requirement: The stack never performs an image build
`cf-template-core.yaml` SHALL NOT declare `AWS::ImageBuilder::Image`, because that resource performs a
build during stack operations and would add a full image build to the duration of every
`baas admin setup`.

#### Scenario: No build resource in the template
- **WHEN** `infra/cf-template-core.yaml` is parsed
- **THEN** no resource has type `AWS::ImageBuilder::Image`

#### Scenario: Setup does not build an image
- **WHEN** `baas admin setup` runs against an account with no runner AMI
- **THEN** it completes without triggering an image build and reports that `baas admin build-image` is
  the next step

### Requirement: Build instances carry only the permissions Image Builder requires
The stack SHALL create a build-instance role and instance profile attaching
`AmazonSSMManagedInstanceCore` and `EC2InstanceProfileForImageBuilder`, plus write access limited to the
build-log prefix of the results bucket.

#### Scenario: Build role is scoped
- **WHEN** the build-instance role is inspected
- **THEN** its only S3 write permission is scoped to the results bucket, and it grants no DynamoDB, no
  `ec2:RunInstances`, and no `iam:*` action

### Requirement: The runner AMI pointer is published as a single SSM parameter
The stack SHALL provide for exactly one String parameter, `/<prefix>/runner/ami-id`, holding the AMI ID of
the current runner image. Its value SHALL be written by `baas admin build-image` rather than by the stack,
since the AMI ID is not known until a build completes. The stack SHALL NOT declare a second AMI pointer.

#### Scenario: Operator can read the pointer
- **WHEN** an identity holding only `BaasCliOperatorRole` reads `/<prefix>/runner/ami-id`
- **THEN** the read is permitted

#### Scenario: Operator cannot write the pointer
- **WHEN** that identity attempts `ssm:PutParameter` on `/<prefix>/runner/ami-id`
- **THEN** the request is denied

#### Scenario: Only one pointer exists
- **WHEN** the stack's SSM parameters are listed
- **THEN** there is exactly one runner AMI pointer

### Requirement: Deployer policy covers the image build path
`BaasCliDeployerPolicy` SHALL grant the `imagebuilder` actions needed to register and execute the pipeline
(including `CreateComponent`, `CreateImageRecipe`, `CreateInfrastructureConfiguration`,
`CreateDistributionConfiguration`, `CreateImagePipeline`, the corresponding `Get`/`List`/`Update`/`Delete`
actions, `StartImagePipelineExecution`, and `TagResource`), `iam:PassRole` limited to the build-instance
profile, the EC2 image actions needed to retire a replaced image (`DeregisterImage`, `DeleteSnapshot`,
`DescribeImages`, `DescribeSnapshots`, `CreateTags`), and `ssm:PutParameter` on `/<prefix>/runner/ami-id`.
Every resource SHALL remain prefix-scoped.

#### Scenario: Build succeeds under the deployer policy alone
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin build-image`
- **THEN** the build completes, the pointer is written, the replaced AMI is retired, and the command
  exits 0

#### Scenario: PassRole cannot be redirected
- **WHEN** the deployer policy is evaluated for `iam:PassRole` on a role other than the build-instance
  profile
- **THEN** the request is not permitted

#### Scenario: Image actions stay prefix-scoped
- **WHEN** `infra/deployer-policy.json` is inspected
- **THEN** no `imagebuilder` statement uses `Resource: "*"`

### Requirement: Operator policy can resolve but not publish the image
`BaasCliOperatorRole` SHALL grant `ssm:GetParameter` on `/<prefix>/runner/ami-id` and `ec2:DescribeImages`
so that `baas run` can resolve and validate the AMI. It SHALL NOT grant any `imagebuilder` action, any
EC2 image-mutating action, or `ssm:PutParameter` on the pointer path.

#### Scenario: Operator resolves the AMI
- **WHEN** `baas run` executes under operator credentials
- **THEN** it can read `/<prefix>/runner/ami-id` and describe the resulting image

#### Scenario: Operator cannot build or retire an image
- **WHEN** `infra/operator-policy.json` is inspected
- **THEN** it contains no `imagebuilder` action and no `ec2:DeregisterImage` or `ec2:CreateImage` action

### Requirement: Database traffic never leaves the VPC
The core stack SHALL create a DynamoDB gateway endpoint associated with the runner's route table, so
runner-to-table traffic is routed privately. A gateway endpoint SHALL be used rather than an interface
endpoint, because it carries no hourly charge.

#### Scenario: Gateway endpoint is present
- **WHEN** the core stack is deployed
- **THEN** an `AWS::EC2::VPCEndpoint` of type Gateway exists for `com.amazonaws.<region>.dynamodb`,
  associated with the runner's route table

#### Scenario: No hourly endpoint charge is introduced
- **WHEN** the template's endpoints are inspected
- **THEN** no interface endpoint is declared for DynamoDB

### Requirement: Runner egress no longer includes the database port
`RunnerSecurityGroup` SHALL NOT permit outbound TCP on port 27017.

#### Scenario: Database port is closed
- **WHEN** the deployed security group's egress rules are inspected
- **THEN** no rule covers port 27017

### Requirement: The runner can write results but not read them
`RunnerRole` SHALL be granted `dynamodb:PutItem` and `dynamodb:BatchWriteItem` on the results table ARN
and nothing else on that table. It SHALL NOT be granted `Query`, `Scan`, `GetItem`, or any delete action.

#### Scenario: Runner can store a result
- **WHEN** an instance using the runner instance profile writes a measurement
- **THEN** the write succeeds

#### Scenario: Runner cannot read the results history
- **WHEN** that instance attempts `dynamodb:Scan` on the results table
- **THEN** the request is denied

#### Scenario: Runner cannot delete results
- **WHEN** that instance attempts `dynamodb:DeleteItem` on the results table
- **THEN** the request is denied

### Requirement: The operator can read results but not write them
`BaasCliOperatorRole` SHALL be granted `dynamodb:Query` and `dynamodb:GetItem` on the results table ARN
and its index ARN, and SHALL NOT be granted write or delete actions on either.

#### Scenario: Operator can query results
- **WHEN** an identity that has assumed the operator role runs `baas results`
- **THEN** the query succeeds

#### Scenario: Operator can query the request-ID index
- **WHEN** that identity runs `baas results --request-id <id>`
- **THEN** the index query succeeds

#### Scenario: Operator cannot mutate results
- **WHEN** that identity attempts `dynamodb:PutItem` on the results table
- **THEN** the request is denied

### Requirement: Deployer policy covers the table lifecycle
`BaasCliDeployerPolicy` SHALL grant `dynamodb:CreateTable`, `DescribeTable`, `UpdateTable`,
`TagResource`, `ListTagsOfResource`, `DescribeTimeToLive`, and `DeleteTable`, scoped to the
prefix-derived table ARN rather than `Resource: "*"`. The rendered policy SHALL remain within the inline
policy budget already asserted by the existing renderer test.

#### Scenario: Setup creates the table under the deployer policy alone
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin setup`
- **THEN** the stack deploys with the results table created and the command exits 0

#### Scenario: Table actions are prefix-scoped
- **WHEN** `infra/deployer-policy.json` is inspected
- **THEN** every `dynamodb` statement names the prefix-derived table ARN and none uses `Resource: "*"`

#### Scenario: Policy still fits the budget
- **WHEN** the deployer policy is rendered
- **THEN** its non-whitespace length remains under the asserted inline-policy limit

### Requirement: Setup detects a retained table from a previous stack
`baas admin setup` SHALL check for an existing results table left behind by a previous stack before
deploying, and SHALL fail with a message naming the table and the remedy, rather than surfacing a
CloudFormation error that does not identify the cause.

#### Scenario: Retained table blocks setup with a clear message
- **WHEN** a results table from a prior stack exists and `baas admin setup` is run
- **THEN** the command exits non-zero naming the table, mirroring the existing pre-check for a retained
  bucket

#### Scenario: Clean account proceeds
- **WHEN** no results table exists
- **THEN** setup proceeds without the pre-check interfering

### Requirement: The table name is a stack output
The core stack SHALL expose the results table name as a stack output, so the CLI can resolve it without
a parameter-store lookup.

#### Scenario: Output is present
- **WHEN** the core stack is deployed
- **THEN** its outputs include the results table name
