# core-stack-provisioning Specification

## Purpose

The `baas-<prefix>` core CloudFormation stack — networking, working bucket, `RunnerRole` and its
instance profile, `OperatorRole` — together with the CLI commands that deploy it (`baas admin
setup`/`teardown`), the credentials each command path resolves, and the runtime behaviour of the
runners it launches.

## Requirements

### Requirement: Core stack contains only CLI-owned infrastructure
The core CloudFormation stack (template `cf-template-core.yaml`) SHALL contain the VPC, public subnet, internet gateway, S3 gateway endpoint, `RunnerSecurityGroup`, `S3MainBucket`, `RunnerRole`, and `RunnerInstanceProfile`. It SHALL NOT contain any GitHub OIDC provider resource or `WorkflowRole`. It MAY take GitHub organisation, repository and identity-provider ARN parameters, used solely to declare a conditional federated principal on `BaasCliOperatorRole`'s trust policy; it SHALL NOT create any other GitHub-related resource.

#### Scenario: Deploying the core stack creates no GitHub-related resources
- **WHEN** `baas admin setup` deploys the core stack
- **THEN** the resulting CloudFormation stack contains no `AWS::IAM::OIDCProvider` resource and no `WorkflowRole`

#### Scenario: Federation parameters produce a trust statement, not a resource
- **WHEN** the core stack is deployed with GitHub organisation, repository and provider ARN supplied
- **THEN** the only difference from a deploy without them is a statement on the operator role's
  trust policy

### Requirement: baas-cli never manages the CI stack
No `baas` command SHALL create, update, delete, or read the CI stack (`cf-template-ci.yaml`, containing the GitHub OIDC identity provider). The CI stack SHALL be deployed and torn down independently, outside of `baas-cli`. Accepting the provider's ARN as an opaque parameter value SHALL NOT be considered managing or reading that stack.

#### Scenario: No command references the CI stack
- **WHEN** any `baas` subcommand executes
- **THEN** it makes no CloudFormation or IAM identity-provider API call scoped to the CI stack

#### Scenario: CI template is not bundled in the CLI
- **WHEN** the `baas-cli` JAR is built
- **THEN** `baas-cli/src/main/resources/templates/` contains only `cf-template-core.yaml`, and `cf-template-ci.yaml` is absent from the JAR's classpath resources

#### Scenario: The provider ARN is passed through unresolved
- **WHEN** `baas admin setup` is given an identity-provider ARN
- **THEN** it forwards the value to CloudFormation without calling IAM to look the provider up

### Requirement: RunnerRole S3 policy matches the bucket name
`RunnerRole`'s S3 access policy SHALL reference the exact same bucket name as the `S3MainBucket` resource (`baas-${ResourceNamePrefix}`).

#### Scenario: Runner can access its own bucket
- **WHEN** an EC2 instance launched with `RunnerRole` attempts `s3:PutObject` against the stack's `S3MainBucket`
- **THEN** the request succeeds (the policy's resource ARN matches the bucket's actual name)

### Requirement: Working bucket survives stack deletion by default
`S3MainBucket` SHALL declare `DeletionPolicy: Retain` and `UpdateReplacePolicy: Retain`, and SHALL declare lifecycle rules expiring noncurrent versions, reaping orphaned delete markers, and aborting incomplete multipart uploads. It SHALL NOT declare any rule that expires current objects under the run prefix, since a run's uploaded input is the only record of what that run measured.

#### Scenario: Default teardown retains the bucket by design
- **WHEN** `baas admin teardown --yes` deletes the core stack without `--delete-bucket`
- **THEN** the stack reaches `DELETE_COMPLETE` and the bucket still exists

#### Scenario: No lifecycle rule expires run artifacts
- **WHEN** the rendered core template's lifecycle rules are inspected
- **THEN** none of them expires current objects under the run prefix

### Requirement: baas admin setup is self-sufficient
`baas admin setup` SHALL accept `--region` and `--aws-profile` directly as command-line options, derive the resource prefix from the caller's AWS account, apply defaults for any omitted option, deploy or update the core stack, and write the result to `~/.baas/config.yaml`. It SHALL NOT require `~/.baas/config.yaml` to pre-exist, and it SHALL NOT expose a `--prefix` option.

#### Scenario: First run with no prior config
- **WHEN** `baas admin setup` runs and `~/.baas/config.yaml` does not exist
- **THEN** the core stack is deployed using the default region and the account-derived prefix, and `~/.baas/config.yaml` is created with the installation's prefix and the credential settings it needs

#### Scenario: No --prefix option
- **WHEN** `baas admin setup --prefix foo` is invoked
- **THEN** picocli reports an unknown option error

### Requirement: Teardown safety gates
`baas admin teardown` SHALL abort if any EC2 instance tagged `baas-role=benchmark-runner` is in the `running` state, SHALL require explicit confirmation (retyping the stack name, or `--yes`), and SHALL retain both the S3 bucket and the DynamoDB results table by default — the bucket is deletable with `--delete-bucket`, the table by no flag at all. It SHALL name both retained resources on exit.

#### Scenario: Abort when a run is in flight
- **WHEN** `baas admin teardown` runs while a `baas-role=benchmark-runner` instance is `running`
- **THEN** the command exits with an error listing the running instance ID(s) and performs no destructive action

#### Scenario: Bucket retained unless explicitly deleted
- **WHEN** `baas admin teardown --yes` runs without `--delete-bucket`
- **THEN** the core stack is deleted but the S3 bucket persists

### Requirement: Bucket emptying handles object versions
`S3UploadService.deleteAllObjects` SHALL delete every object version and delete marker, not only current versions. This SHALL remain true after versioning is suspended, because versions written before suspension persist until a lifecycle rule reaps them.

#### Scenario: Versioned bucket is fully emptied
- **WHEN** `deleteAllObjects` runs against a bucket whose keys have multiple versions
- **THEN** a subsequent `listObjectVersions` returns no versions and no delete markers

#### Scenario: Suspension does not remove the need to walk versions
- **WHEN** `deleteAllObjects` runs against a bucket whose versioning is suspended but which still holds versions written earlier
- **THEN** those earlier versions and any delete markers are deleted

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
`BaasCliDeployerPolicy` SHALL NOT grant any OIDC-provider action, so the identity provider stays outside the deployer's reach and is created by a separate, higher-privileged identity. It SHALL grant `iam:UpdateAssumeRolePolicy`, scoped to the same role resources as its other IAM statements, because deploying the core stack now edits `BaasCliOperatorRole`'s trust policy. That grant confers no escalation the policy did not already allow, since `iam:CreateRole` writes a trust policy too.

#### Scenario: No OIDC actions present
- **WHEN** `infra/deployer-policy.json` is inspected
- **THEN** it contains no action beginning `iam:` and ending `OpenIDConnectProvider`

#### Scenario: The trust-policy edit is permitted and scoped
- **WHEN** an identity holding only `BaasCliDeployerPolicy` deploys a core stack carrying federation parameters
- **THEN** the deploy succeeds, and the policy's `iam:UpdateAssumeRolePolicy` grant names the same role resources as its role-creation statement rather than `Resource: "*"`

### Requirement: Deployer policy is resource-scoped
`BaasCliDeployerPolicy`'s CloudFormation statement SHALL be scoped to `stack/<prefix>/*` and its IAM statement to the installation's own role and instance-profile ARNs — `role/<prefix>-role-*` and `instance-profile/<prefix>-profile-*` — rather than `Resource: "*"`. Because the prefix is account-derived, the rendered policy SHALL reach only installations in the caller's own account.

#### Scenario: Cannot create an arbitrarily-named role
- **WHEN** the deployer policy is evaluated for `iam:CreateRole` on `arn:aws:iam::<acct>:role/admin-backdoor`
- **THEN** the request is not permitted

#### Scenario: An installation deployed by hand needs its own rendered policy
- **WHEN** the deployer policy rendered for `baas-<accountId>` is evaluated against a resource of an installation deployed by hand under another prefix
- **THEN** the request is not permitted, and `baas admin deployer-policy --prefix <prefix>` renders the document that installation needs

### Requirement: Operator identity is an assumable role created by the core stack
The core stack SHALL create `BaasCliOperatorRole` as an `AWS::IAM::Role` resource (not a policy attached to any user), scoped to that stack's own `S3MainBucket`, `RunnerRole`, results table, and the runner AMI pointer path. Its trust policy SHALL allow the AWS account root, so `baas admin setup` requires no additional parameter to identify who the operator is, and SHALL additionally allow the federated workload principal when federation parameters are supplied. The role's ARN SHALL be a stack output (`OperatorRoleArn`) and SHALL be printed by `baas admin setup`. The stack SHALL NOT grant `sts:AssumeRole` on this role to any specific IAM identity — that remains a manual, per-identity step performed outside the stack.

#### Scenario: Operator role is created unattached
- **WHEN** `baas admin setup` deploys the core stack
- **THEN** the stack contains an `AWS::IAM::Role` named `<prefix>-operator-role` with a trust policy allowing `arn:aws:iam::<account-id>:root`, and no `sts:AssumeRole` grant exists for any specific principal until one is added manually

#### Scenario: Operator role ARN is surfaced to the operator
- **WHEN** `baas admin setup` completes
- **THEN** it prints the `OperatorRoleArn` stack output together with instructions for granting `sts:AssumeRole` to a specific IAM identity

#### Scenario: The federated principal is additive
- **WHEN** the core stack is deployed with federation parameters
- **THEN** the account-root principal remains, and the federated statement is present alongside it

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

### Requirement: Failed runs leave diagnosable output
The user-data script SHALL upload `/var/log/cloud-init-output.log` into the run's S3 prefix, alongside the run's other artifacts, before terminating the instance, on both the success and failure paths.

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

### Requirement: The working bucket accumulates no new object versions
`S3MainBucket` SHALL declare `VersioningConfiguration.Status: Suspended`. Overwrite recovery SHALL NOT
be relied upon as a safeguard; run identifiers SHALL be unique enough that an overwrite does not occur.

#### Scenario: Suspended versioning is declared
- **WHEN** the rendered core template is inspected
- **THEN** `S3MainBucket`'s versioning status is `Suspended`

#### Scenario: A new write creates no noncurrent version
- **WHEN** an object is overwritten after suspension
- **THEN** no additional noncurrent version is retained for it

### Requirement: GitHub Actions federates directly into the operator role
The core stack SHALL be able to declare, on `BaasCliOperatorRole`'s trust policy, a federated
principal permitting a named GitHub repository's workload identity to assume that role directly. The
statement SHALL be conditional on the federation parameters being supplied, so an installation that
supplies none deploys exactly as before. The repository parameter SHALL accept more than one
repository, so one installation can serve several without a template migration. The role's
`MaxSessionDuration` SHALL be raised to at least the configured benchmark timeout plus its
termination margin. No intermediate role SHALL stand between the workload identity and the operator
role.

#### Scenario: A federated workload assumes the operator role in one step
- **WHEN** a workflow in the named repository requests credentials with its workload identity token
- **THEN** it assumes `BaasCliOperatorRole` directly, and no second `sts:AssumeRole` call is required

#### Scenario: An installation without federation is unchanged
- **WHEN** the core stack is deployed with no GitHub organisation or repository supplied
- **THEN** `BaasCliOperatorRole`'s trust policy carries the account-root principal alone and no
  federated statement

#### Scenario: Two repositories share one installation
- **WHEN** two repository names are supplied
- **THEN** the trust policy admits both, without any template change

#### Scenario: A session outlasts the run it polls
- **WHEN** a benchmark configured with the default timeout is launched under federated credentials
- **THEN** the session does not expire before the run completes and the instance is terminated

### Requirement: Federation parameters are carried forward, and revoked only on request
`baas admin setup` SHALL accept the GitHub organisation, repository list and OIDC provider ARN as
options. When updating an existing stack it SHALL carry forward the deployed value of every
parameter the caller did not name, rather than resubmitting a default in its place, so that a setup
run for an unrelated reason cannot alter the federated trust. It SHALL provide an explicit option
that revokes federation by submitting those parameters empty, and that option SHALL be the only way
an update removes the federated trust statement. The deployed stack SHALL be the single source of
truth for the federation values; the CLI SHALL NOT keep a second copy of them in its configuration
file.

#### Scenario: An unrelated setup does not revoke CI access
- **WHEN** `baas admin setup` is run again, naming no federation options, on an installation whose
  federation parameters were supplied earlier
- **THEN** the federated trust statement is still present afterwards and continuous integration
  retains access

#### Scenario: Revocation is explicit
- **WHEN** `baas admin setup` is run with the revocation option
- **THEN** the federated trust statement is absent from `BaasCliOperatorRole` afterwards, and the
  account-root principal remains

#### Scenario: Setting and revoking at once is refused
- **WHEN** `baas admin setup` is invoked with both the revocation option and a GitHub organisation
  or repository
- **THEN** the command reports a usage error and deploys nothing

#### Scenario: A first deploy carries nothing forward
- **WHEN** the core stack is created for the first time with no federation options
- **THEN** the create succeeds with the federation parameters explicitly empty, rather than failing
  because a parameter has no previous value

#### Scenario: The values are read back from the stack, not from configuration
- **WHEN** an operator asks which repository the installation trusts
- **THEN** the answer comes from the deployed stack's parameters, and `~/.baas/config.yaml` holds no
  copy of the organisation, repository list or provider ARN

### Requirement: The CI stack contains only the identity provider
`cf-template-ci.yaml` SHALL declare the GitHub OIDC identity provider and nothing else. It SHALL
take no parameter from, and produce no output consumed by, the core stack. Because the identity
provider is account-global and the core stack's trust statement references it, the provider SHALL be
deployed before `baas admin setup` is first run with federation parameters.

#### Scenario: No workload role remains in the CI stack
- **WHEN** `infra/cf-template-ci.yaml` is inspected
- **THEN** it declares no IAM role, and the only resource it declares is the OIDC identity provider

#### Scenario: Deploy order is provider first
- **WHEN** an installation is set up from scratch with federation
- **THEN** the identity provider exists before the core stack is deployed, and the core stack is
  given its ARN

#### Scenario: An existing account-global provider is reused
- **WHEN** the identity provider for the workload-identity issuer already exists in the account
- **THEN** its ARN is supplied to the core stack and no duplicate provider is created

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
