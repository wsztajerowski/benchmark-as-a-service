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
- **WHEN** `baas admin deployment setup` deploys the core stack
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
- **WHEN** `baas admin deployment setup` is given an identity-provider ARN
- **THEN** it forwards the value to CloudFormation without calling IAM to look the provider up

### Requirement: RunnerRole S3 policy matches the bucket name
`RunnerRole`'s S3 access policy SHALL reference the exact same bucket name as the `S3MainBucket` resource (`baas-${ResourceNamePrefix}`).

#### Scenario: Runner can access its own bucket
- **WHEN** an EC2 instance launched with `RunnerRole` attempts `s3:PutObject` against the stack's `S3MainBucket`
- **THEN** the request succeeds (the policy's resource ARN matches the bucket's actual name)

### Requirement: baas admin deployment setup is self-sufficient
`baas admin deployment setup` SHALL accept `--region` and `--deployer-profile` directly as command-line options, resolve the deployment name as *A deployment is named by its prefix, derived from the account by default* states, apply defaults for any omitted option, render the deployer policy for that deployment and check the caller's rights against it, deploy or update the core stack, and write the result to that deployment's configuration file. It SHALL NOT require any configuration file to pre-exist. It SHALL NOT expose a `--prefix` option: the name is given only by the global `--deployment`. It SHALL NOT accept `--aws-profile`.

#### Scenario: First run with no prior config
- **WHEN** `baas admin deployment setup` runs and no deployment is configured
- **THEN** the core stack is deployed using the default region and the account-derived prefix, and `~/.baas/deployments/<prefix>.yaml` is created with the deployment's prefix and the credential settings it needs

#### Scenario: No --prefix option
- **WHEN** `baas admin deployment setup --prefix foo` is invoked
- **THEN** picocli reports an unknown option error

#### Scenario: The deployer profile option is renamed
- **WHEN** `baas admin deployment setup --aws-profile baas-admin` is invoked
- **THEN** picocli reports an unknown option error, and `--deployer-profile baas-admin` is accepted and stored as `aws.deployerProfile`

### Requirement: Teardown safety gates
`baas admin deployment teardown` SHALL abort if any EC2 instance tagged `baas-role=benchmark-runner` and
`baas-deployment=<the deployment being torn down>` is in the `pending` or `running` state; runners of
another deployment SHALL NOT block it. It SHALL require explicit confirmation — retyping the
deployment's name, or `--yes`. Both gates SHALL pass before anything is deleted, the runner image
included. When it aborts over in-flight jobs, it SHALL name each job by the identifier in its
instance's `baas-job-id` tag, alongside the instance identifier and state, and SHALL name
`baas jobs terminate <jobId>` as the way to stop one. It SHALL read those identifiers from the
instances, not from the results table. Before the prompt it SHALL state that the deployment's bucket
and results table, with every job and measurement in them, will be deleted and cannot be recovered.

#### Scenario: Abort when a job is in flight
- **WHEN** `baas admin deployment teardown` runs while a runner instance of the same deployment is `running`
- **THEN** the command exits with an error listing each in-flight job's identifier, instance ID and
  state, names `baas jobs terminate <jobId>`, and performs no destructive action

#### Scenario: Abort when a job is still booting
- **WHEN** `baas admin deployment teardown` runs while a runner instance of the same deployment is `pending`
- **THEN** the command exits with an error listing that job's identifier and instance ID and performs
  no destructive action, so a job launched moments earlier does not lose its role, subnet or image
  mid-boot

#### Scenario: Another deployment's runner does not block
- **WHEN** `baas --deployment wiktor-dev admin deployment teardown` runs while a runner of deployment `baas-123456789012` in the same region is `running`
- **THEN** the in-flight gate passes for `wiktor-dev`

#### Scenario: The gate needs no table access
- **WHEN** `baas admin deployment teardown` runs under the deployer policy, which grants no read of the results table
- **THEN** the in-flight check and its message succeed

#### Scenario: The prompt says everything will be deleted
- **WHEN** `baas admin deployment teardown` prompts for confirmation
- **THEN** the prompt states that the bucket and the results table will be deleted, and `--delete-bucket`
  is not an option

### Requirement: Bucket emptying handles object versions
`S3UploadService.deleteAllObjects` SHALL delete every object version and delete marker, not only current versions. This SHALL remain true after versioning is suspended, because versions written before suspension persist until a lifecycle rule reaps them.

#### Scenario: Versioned bucket is fully emptied
- **WHEN** `deleteAllObjects` runs against a bucket whose keys have multiple versions
- **THEN** a subsequent `listObjectVersions` returns no versions and no delete markers

#### Scenario: Suspension does not remove the need to walk versions
- **WHEN** `deleteAllObjects` runs against a bucket whose versioning is suspended but which still holds versions written earlier
- **THEN** those earlier versions and any delete markers are deleted

### Requirement: Deployer policy is created out-of-band, before the core stack exists
`BaasCliDeployerPolicy` (matching `infra/deployer-policy.json`) SHALL cover: CloudFormation stack lifecycle (create, update, delete, describe, change-set operations), VPC/EC2 networking create/delete/describe — including `ec2:DescribeInstances`, needed by `baas admin deployment teardown`'s active-job safety gate — IAM role/instance-profile create (covers both `RunnerRole` and `OperatorRole`, since both are the same resource type), S3 bucket create, and the DynamoDB table lifecycle actions. `baas admin deployment setup`/`baas admin deployment teardown` SHALL require `BaasCliDeployerPolicy`. This policy SHALL be attached by hand, before the first successful `baas admin deployment setup` — whose first run prints it when it is missing — and the core stack SHALL NOT create it, since CloudFormation cannot grant permission to create CloudFormation stacks.

#### Scenario: Deployer policy permits the full setup/teardown lifecycle
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin deployment setup` followed later by `baas admin deployment teardown`
- **THEN** every AWS API call made by both commands succeeds, including teardown's active-job check

### Requirement: Deployer policy covers the full setup path
`BaasCliDeployerPolicy` SHALL include `ssm:PutParameter` on the runner AMI pointer path, the S3 actions needed to empty and delete the working bucket (`s3:ListBucket`, `s3:ListBucketVersions`, `s3:DeleteObject`, `s3:DeleteObjectVersion`, `s3:DeleteBucket`), and the IAM read-back actions CloudFormation invokes after role creation (`iam:GetRolePolicy`, `iam:ListRolePolicies`, `iam:ListAttachedRolePolicies`).

#### Scenario: Setup succeeds end to end
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin deployment setup`
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
`BaasCliDeployerPolicy`'s CloudFormation statement SHALL be scoped to `stack/<prefix>/*` and its IAM statement to the deployment's own role and instance-profile ARNs — `role/<prefix>-role-*` and `instance-profile/<prefix>-profile-*` — rather than `Resource: "*"`. Because the prefix is account-derived, the rendered policy SHALL reach only deployments in the caller's own account.

#### Scenario: Cannot create an arbitrarily-named role
- **WHEN** the deployer policy is evaluated for `iam:CreateRole` on `arn:aws:iam::<acct>:role/admin-backdoor`
- **THEN** the request is not permitted

#### Scenario: A deployment set up by hand needs its own rendered policy
- **WHEN** the deployer policy rendered for `baas-<accountId>` is evaluated against a resource of a deployment set up by hand under another prefix
- **THEN** the request is not permitted, and `baas admin deployment setup` for that deployment prints the document it needs

### Requirement: Operator identity is an assumable role created by the core stack
The core stack SHALL create `BaasCliOperatorRole` as an `AWS::IAM::Role` resource (not a policy attached to any user), scoped to that stack's own `S3MainBucket`, `RunnerRole`, results table, and the runner AMI pointer path. Its trust policy SHALL allow the AWS account root, so `baas admin deployment setup` requires no additional parameter to identify who the operator is, and SHALL additionally allow the federated workload principal when federation parameters are supplied. The role's ARN SHALL be a stack output (`OperatorRoleArn`) and SHALL be printed by `baas admin deployment setup`. The stack SHALL NOT grant `sts:AssumeRole` on this role to any specific IAM identity — that remains a manual, per-identity step performed outside the stack.

#### Scenario: Operator role is created unattached
- **WHEN** `baas admin deployment setup` deploys the core stack
- **THEN** the stack contains an `AWS::IAM::Role` named `<prefix>-operator-role` with a trust policy allowing `arn:aws:iam::<account-id>:root`, and no `sts:AssumeRole` grant exists for any specific principal until one is added manually

#### Scenario: Operator role ARN is surfaced to the operator
- **WHEN** `baas admin deployment setup` completes
- **THEN** it prints the `OperatorRoleArn` stack output together with instructions for granting `sts:AssumeRole` to a specific IAM identity

#### Scenario: The federated principal is additive
- **WHEN** the core stack is deployed with federation parameters
- **THEN** the account-root principal remains, and the federated statement is present alongside it

### Requirement: Operator role permissions
`BaasCliOperatorRole` SHALL cover: `ec2:RunInstances`/`Describe*` to launch and observe benchmark runner instances, tag-scoped `ec2:TerminateInstances` (condition `aws:ResourceTag/baas-role=benchmark-runner`), `ec2:CreateTags` scoped to the `RunInstances` create action, `ssm:GetParameter` on the runner AMI pointer path (`/<prefix>/runner/ami-id`) and no SSM write of any kind, `dynamodb:Query`/`Scan`/`GetItem` on the results table and its index, `dynamodb:UpdateItem` on the results table restricted to the `JOB` partition and no other write action, `ec2:DescribeImages` to validate the resolved AMI, S3 object access scoped to the core stack's bucket, and `iam:PassRole` scoped to `RunnerRole` only. It SHALL NOT cover the public AL2023 AMI lookup path (`/aws/service/ami-amazon-linux-latest/*`), which is no longer used now that the runner boots from a purpose-built image. `baas jobs`/`baas results query`/`baas config` (and the aliases `baas run`, `baas query`) SHALL succeed when invoked by an identity that has assumed `BaasCliOperatorRole`.

#### Scenario: Operator role suffices for daily use
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas run jmh -- ...`, `baas jobs list`, `baas jobs terminate`, `baas results query`, or `baas jobs diff`
- **THEN** every AWS API call made succeeds under that role's permissions, including the SSM read of `/<prefix>/runner/ami-id` needed to resolve the runner's AMI ID and the run-item writes

#### Scenario: Public AMI lookup path is no longer granted
- **WHEN** `infra/operator-policy.json` is inspected
- **THEN** it contains no statement granting `ssm:GetParameter` on `/aws/service/ami-amazon-linux-latest/*`

### Requirement: Operator policy reference copy stays in sync
`infra/operator-policy.json` SHALL grant exactly the same set of actions as the `OperatorRole` inline policies in `infra/cf-template-core.yaml`.

#### Scenario: Drift is caught
- **WHEN** an action is added to `OperatorRole` but not to `operator-policy.json`
- **THEN** the drift test fails

### Requirement: Day-to-day commands resolve operator credentials
A deployment's configuration file SHALL carry `aws.operatorProfile`. `baas run`, `baas results query`, and `baas config show` SHALL resolve AWS credentials from it; `baas admin deployment setup` and `baas admin deployment teardown` SHALL continue using `aws.deployerProfile`. When `aws.operatorProfile` is unset, day-to-day commands SHALL fall through to the default credential chain and SHALL NOT use `aws.deployerProfile`.

#### Scenario: Deployer profile is never silently reused
- **WHEN** the configuration has `aws.deployerProfile: baas-deployer` and no `aws.operatorProfile`, and `baas run jmh` is invoked
- **THEN** the AWS client is built without an explicit profile, and a warning naming `baas config set --operator-profile` is printed

#### Scenario: Operator profile is honoured
- **WHEN** the configuration has `aws.operatorProfile: baas-operator` and `baas run jmh` is invoked
- **THEN** the AWS client is built with the `baas-operator` profile

### Requirement: Operators can bootstrap config without the deployer's machine
`BaasCliOperatorRole` SHALL be granted `cloudformation:DescribeStacks` scoped to its own core stack.
`baas config sync` SHALL verify that the deployment's stack exists and write that deployment's
configuration file. When no deployment is configured on the machine, `--deployment` SHALL be
required, so that a machine with no prior configuration declares which deployment it adopts rather
than inferring one from whichever credentials happen to be active. The name SHALL never be derived
from the account. When exactly one deployment is configured, a bare `baas config sync` SHALL re-sync
that one. When two or more are configured, it SHALL require `--deployment` as every command does.
`baas config sync` SHALL NOT store values the CLI derives from the prefix or resolves from the stack
at use time.

#### Scenario: Operator on a fresh machine
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas config sync --deployment baas-123456789012` with no configured deployment
- **THEN** that deployment's configuration file is written and `baas run` works without hand-copying any file

#### Scenario: Sync without a name is refused
- **WHEN** `baas config sync` is invoked with no `--deployment` and no deployment is configured
- **THEN** the command exits non-zero asking for `--deployment`, issues no AWS call and writes nothing

#### Scenario: Adopting a deployment set up by hand
- **WHEN** an operator runs `baas config sync --deployment baas-123456789012-dev`, naming a deployment whose stack was deployed by hand rather than by `baas admin deployment setup`
- **THEN** that deployment's configuration file is written, and later `baas run`, `baas results query` and `baas admin` invocations naming it address that deployment

#### Scenario: Re-syncing the only deployment
- **WHEN** `baas config sync` is invoked with no `--deployment` and exactly one deployment is configured
- **THEN** that deployment is re-synced and no other file is written

#### Scenario: Adopting a second deployment
- **WHEN** `baas config sync --deployment wiktor-dev` runs on a machine where `baas-123456789012` is configured
- **THEN** a second configuration file is written, `baas-123456789012`'s is unchanged, and later commands must name one of the two

### Requirement: Failed jobs leave diagnosable output
The user-data script SHALL upload `/var/log/cloud-init-output.log` into the job's S3 prefix, alongside the job's other artifacts, before terminating the instance, on the success path, the failure path and the watchdog path.

#### Scenario: Log survives self-termination
- **WHEN** a benchmark job exits non-zero and the instance self-terminates
- **THEN** the job's S3 prefix contains `cloud-init-output.log`, and the job item reads `failed:<exitCode>`

#### Scenario: Log survives the watchdog
- **WHEN** the watchdog terminates the instance
- **THEN** the job's S3 prefix contains `cloud-init-output.log`, and the job item reads `timed-out`

### Requirement: Poll loop detects a dead instance
`baas run` SHALL check the runner instance's state while polling and SHALL stop polling with a non-zero exit as soon as the instance reaches `terminated` or `shutting-down` while the job item holds no terminal status, rather than waiting for the poll cap of `timeout + watchdog margin`. Before reporting, it SHALL re-read the job item once, so a final status written moments before termination is not reported as a failure.

#### Scenario: Boot failure fails fast
- **WHEN** the runner instance terminates before recording a terminal status
- **THEN** `baas run` reports the instance state and exits non-zero well before `timeout + watchdog margin` elapses

#### Scenario: A status written just before termination is honoured
- **WHEN** the instance records `completed` and terminates between two polls
- **THEN** `baas run` reports the job as completed

### Requirement: Core stack declares the image build pipeline
`cf-template-core.yaml` SHALL declare `AWS::ImageBuilder::ImageRecipe`,
`AWS::ImageBuilder::InfrastructureConfiguration`, `AWS::ImageBuilder::DistributionConfiguration`, and
`AWS::ImageBuilder::ImagePipeline`, and an `AWS::ImageBuilder::Component` for each of the base, the
extension and the contract. The extension component SHALL exist only while an extension is deployed,
and its document SHALL be carried by a stack parameter. The recipe SHALL list the base, then the
extension when present, then the contract. The recipe's root volume SHALL be 30 GB gp3, preserving the
existing volume requirement. The infrastructure configuration SHALL place build instances in the public
subnet.

#### Scenario: Pipeline resources are present
- **WHEN** the core stack is deployed
- **THEN** it contains an image pipeline, recipe, base and contract components, infrastructure
  configuration, and distribution configuration

#### Scenario: The extension component follows the parameter
- **WHEN** the core stack is deployed with an empty extension parameter
- **THEN** it contains no extension component, and the recipe lists the base and the contract only

#### Scenario: The contract runs last
- **WHEN** the recipe of a stack holding an extension is inspected
- **THEN** its components are the base, the extension and the contract, in that order

#### Scenario: Build volume matches the runner volume
- **WHEN** the image recipe is inspected
- **THEN** its root block device is 30 GB and of type gp3

### Requirement: The stack never performs an image build
`cf-template-core.yaml` SHALL NOT declare `AWS::ImageBuilder::Image`, because that resource performs a
build during stack operations and would add a full image build to the duration of every
`baas admin deployment setup`.

#### Scenario: No build resource in the template
- **WHEN** `infra/cf-template-core.yaml` is parsed
- **THEN** no resource has type `AWS::ImageBuilder::Image`

#### Scenario: Setup does not build an image
- **WHEN** `baas admin deployment setup` runs against an account with no runner AMI
- **THEN** it completes without triggering an image build and reports that `baas admin image build` is
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
the current runner image. Its value SHALL be written by `baas admin image build` rather than by the stack,
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
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin image build`
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

### Requirement: The image build and the runner do not share a security group
When the stack creates its own networking, `RunnerImageInfrastructure` SHALL use a dedicated
`ImageBuildSecurityGroup` permitting outbound TCP 443 and 80, and `RunnerSecurityGroup` SHALL permit
outbound TCP 443 only. Under `UseExistingVpc` both SHALL use `ExistingSecurityGroupId`, and the stack
SHALL NOT create a security group in the existing VPC. `RunnerSecurityGroup`'s `GroupDescription` SHALL
NOT change, since editing it replaces the group.

#### Scenario: Port 80 is the build's alone
- **WHEN** the two groups' egress rules are inspected on a stack that created its networking
- **THEN** the build group covers 443 and 80, and the runner group covers 443 and nothing else

#### Scenario: An existing VPC keeps the operator's group
- **WHEN** the stack is deployed with `UseExistingVpc=true`
- **THEN** no security group resource is created and the build instance uses `ExistingSecurityGroupId`

### Requirement: The runner can write results but not read them
`RunnerRole` SHALL be granted `dynamodb:PutItem` and `dynamodb:BatchWriteItem` on the results table ARN
restricted to `RESULT#` partitions, and `dynamodb:UpdateItem` restricted to the `JOB` partition, and
nothing else on that table. It SHALL NOT be granted `Query`, `Scan`, `GetItem`, or any delete action.

#### Scenario: Runner can store a result
- **WHEN** an instance using the runner instance profile writes a measurement
- **THEN** the write succeeds

#### Scenario: Runner can record its job's status
- **WHEN** that instance updates its job item in the `JOB` partition
- **THEN** the write succeeds

#### Scenario: Runner cannot put items outside the measurement partitions
- **WHEN** that instance attempts `dynamodb:PutItem` on a key in the `JOB` partition
- **THEN** the request is denied

#### Scenario: Runner cannot read the results history
- **WHEN** that instance attempts `dynamodb:Scan` on the results table
- **THEN** the request is denied

#### Scenario: Runner cannot delete results
- **WHEN** that instance attempts `dynamodb:DeleteItem` on the results table
- **THEN** the request is denied

### Requirement: The operator can read results but not write them
`BaasCliOperatorRole` SHALL be granted `dynamodb:Query`, `dynamodb:Scan` and `dynamodb:GetItem` on the
results table ARN and its index ARN, and `dynamodb:UpdateItem` on the table restricted to the `JOB`
partition. It SHALL NOT be granted any other write action, any write in a `RESULT#` partition, or any
delete action.

#### Scenario: Operator can query results
- **WHEN** an identity that has assumed the operator role runs `baas results query`
- **THEN** the query succeeds

#### Scenario: Operator can query the job-ID index
- **WHEN** that identity runs `baas results query --job-id <id>`
- **THEN** the index query succeeds

#### Scenario: Operator can record a job's status
- **WHEN** that identity runs `baas run`, which reserves and updates the job item
- **THEN** the writes succeed

#### Scenario: Operator cannot mutate results
- **WHEN** that identity attempts `dynamodb:PutItem` on the results table, or `dynamodb:UpdateItem` on a key in a `RESULT#` partition
- **THEN** the request is denied

### Requirement: Deployer policy covers the table lifecycle
`BaasCliDeployerPolicy` SHALL grant `dynamodb:CreateTable`, `DescribeTable`, `UpdateTable`,
`TagResource`, `ListTagsOfResource`, `DescribeTimeToLive`, and `DeleteTable`, scoped to the
prefix-derived table ARN rather than `Resource: "*"`. The rendered policy SHALL remain within the inline
policy budget already asserted by the existing renderer test.

#### Scenario: Setup creates the table under the deployer policy alone
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin deployment setup`
- **THEN** the stack deploys with the results table created and the command exits 0

#### Scenario: Table actions are prefix-scoped
- **WHEN** `infra/deployer-policy.json` is inspected
- **THEN** every `dynamodb` statement names the prefix-derived table ARN and none uses `Resource: "*"`

#### Scenario: Policy still fits the budget
- **WHEN** the deployer policy is rendered
- **THEN** its non-whitespace length remains under the asserted inline-policy limit

### Requirement: The table name is a stack output
The core stack SHALL expose the results table name as a stack output, so the CLI can resolve it without
a parameter-store lookup.

#### Scenario: Output is present
- **WHEN** the core stack is deployed
- **THEN** its outputs include the results table name

### Requirement: The working bucket accumulates no new object versions
`S3MainBucket` SHALL declare `VersioningConfiguration.Status: Suspended`. Overwrite recovery SHALL NOT
be relied upon as a safeguard; job identifiers SHALL be unique enough that an overwrite does not occur.

#### Scenario: Suspended versioning is declared
- **WHEN** the rendered core template is inspected
- **THEN** `S3MainBucket`'s versioning status is `Suspended`

#### Scenario: A new write creates no noncurrent version
- **WHEN** an object is overwritten after suspension
- **THEN** no additional noncurrent version is retained for it

### Requirement: GitHub Actions federates directly into the operator role
The core stack SHALL be able to declare, on `BaasCliOperatorRole`'s trust policy, a federated
principal permitting a named GitHub repository's workload identity to assume that role directly. The
statement SHALL be conditional on the federation parameters being supplied, so a deployment that
supplies none deploys exactly as before. The repository parameter SHALL accept more than one
repository, so one deployment can serve several without a template migration. The role's
`MaxSessionDuration` SHALL be raised to at least the configured benchmark timeout plus its
termination margin. No intermediate role SHALL stand between the workload identity and the operator
role.

#### Scenario: A federated workload assumes the operator role in one step
- **WHEN** a workflow in the named repository requests credentials with its workload identity token
- **THEN** it assumes `BaasCliOperatorRole` directly, and no second `sts:AssumeRole` call is required

#### Scenario: A deployment without federation is unchanged
- **WHEN** the core stack is deployed with no GitHub organisation or repository supplied
- **THEN** `BaasCliOperatorRole`'s trust policy carries the account-root principal alone and no
  federated statement

#### Scenario: Two repositories share one deployment
- **WHEN** two repository names are supplied
- **THEN** the trust policy admits both, without any template change

#### Scenario: A session outlasts the job it polls
- **WHEN** a benchmark configured with the default timeout is launched under federated credentials
- **THEN** the session does not expire before the job completes and the instance is terminated

### Requirement: Federation parameters are carried forward, and revoked only on request
`baas admin deployment setup` SHALL accept the GitHub organisation, repository list and OIDC provider ARN as
options. When updating an existing stack it SHALL carry forward the deployed value of every
parameter the caller did not name, rather than resubmitting a default in its place, so that a setup
run for an unrelated reason cannot alter the federated trust. It SHALL provide an explicit option
that revokes federation by submitting those parameters empty, and that option SHALL be the only way
an update removes the federated trust statement. The deployed stack SHALL be the single source of
truth for the federation values; the CLI SHALL NOT keep a second copy of them in its configuration
file.

#### Scenario: An unrelated setup does not revoke CI access
- **WHEN** `baas admin deployment setup` is run again, naming no federation options, on a deployment whose
  federation parameters were supplied earlier
- **THEN** the federated trust statement is still present afterwards and continuous integration
  retains access

#### Scenario: Revocation is explicit
- **WHEN** `baas admin deployment setup` is run with the revocation option
- **THEN** the federated trust statement is absent from `BaasCliOperatorRole` afterwards, and the
  account-root principal remains

#### Scenario: Setting and revoking at once is refused
- **WHEN** `baas admin deployment setup` is invoked with both the revocation option and a GitHub organisation
  or repository
- **THEN** the command reports a usage error and deploys nothing

#### Scenario: A first deploy carries nothing forward
- **WHEN** the core stack is created for the first time with no federation options
- **THEN** the create succeeds with the federation parameters explicitly empty, rather than failing
  because a parameter has no previous value

#### Scenario: The values are read back from the stack, not from configuration
- **WHEN** an operator asks which repository the deployment trusts
- **THEN** the answer comes from the deployed stack's parameters, and the deployment's configuration file holds no
  copy of the organisation, repository list or provider ARN

### Requirement: The CI stack contains only the identity provider
`cf-template-ci.yaml` SHALL declare the GitHub OIDC identity provider and nothing else. It SHALL
take no parameter from, and produce no output consumed by, the core stack. Because the identity
provider is account-global and the core stack's trust statement references it, the provider SHALL be
deployed before `baas admin deployment setup` is first job with federation parameters.

#### Scenario: No workload role remains in the CI stack
- **WHEN** `infra/cf-template-ci.yaml` is inspected
- **THEN** it declares no IAM role, and the only resource it declares is the OIDC identity provider

#### Scenario: Deploy order is provider first
- **WHEN** a deployment is set up from scratch with federation
- **THEN** the identity provider exists before the core stack is deployed, and the core stack is
  given its ARN

#### Scenario: An existing account-global provider is reused
- **WHEN** the identity provider for the workload-identity issuer already exists in the account
- **THEN** its ARN is supplied to the core stack and no duplicate provider is created

### Requirement: Resource names follow one composition rule
Every resource the core stack names SHALL be `<prefix>`, `<prefix>-<name>`, or
`<prefix>-<resource_type>-<name>`, where the `resource_type` and `name` segments are present only
when they are needed to distinguish one resource from another of the same kind. The prefix SHALL be
the deployment name verbatim. The derived default carries the `baas-` namespace itself, so no
resource name composes the namespace separately, and a named deployment's prefix carries whatever
its name carries.

#### Scenario: The stack and bucket take the bare prefix
- **WHEN** a deployment is set up for account `123456789012` with no name given
- **THEN** the stack is named `baas-123456789012` and the working bucket is named `baas-123456789012`

#### Scenario: A named deployment's prefix is used verbatim
- **WHEN** a deployment named `wiktor-dev` is set up
- **THEN** its stack and bucket are named `wiktor-dev` and its runner role `wiktor-dev-role-runner`, with no `baas-` prepended

#### Scenario: Resources of the same kind are distinguished by name
- **WHEN** the stack creates the runner, operator and image-build roles
- **THEN** they are named `<prefix>-role-runner`, `<prefix>-role-operator` and `<prefix>-role-image-build`

#### Scenario: Image Builder resources are distinguishable from one another
- **WHEN** the stack creates the Image Builder component, recipe, infrastructure configuration, distribution configuration and pipeline
- **THEN** no two of them share a name

### Requirement: VPC parameters are immutable once the deployment exists
`baas admin deployment setup` SHALL honour `--use-existing-vpc` and its accompanying VPC, subnet and security
group options when it creates a deployment. When the deployment already exists, it SHALL
compare the submitted networking parameters against the deployed ones and SHALL refuse the update
if they differ, naming both values and submitting nothing. Omitting the options entirely SHALL carry
the deployed values forward.

#### Scenario: Re-setup without networking options preserves existing networking
- **WHEN** `baas admin deployment setup` runs against a deployment that was created with `--use-existing-vpc`, and names no networking options
- **THEN** the deployed networking parameters are carried forward and no new VPC is created

#### Scenario: Re-setup with different networking is refused
- **WHEN** `baas admin deployment setup --use-existing-vpc --vpc-id vpc-999 ...` runs against a deployment set up against `vpc-123`
- **THEN** the command exits with an error naming both the deployed and the submitted values, and no stack update is submitted

#### Scenario: Re-setup with identical networking proceeds
- **WHEN** `baas admin deployment setup` repeats exactly the networking options the deployment was set up with
- **THEN** the update proceeds normally

### Requirement: Teardown retires the runner image
After the core stack is deleted, `baas admin deployment teardown` SHALL retire the deployment's runner image,
unconditionally and with no option to keep it:
- the AMI named by `/<prefix>/runner/ami-id` SHALL be deregistered and its snapshots deleted;
- the `/<prefix>/runner/ami-id` parameter SHALL be deleted;
- every Image Builder image record of the deployment's recipe SHALL be deleted.

`<prefix>` SHALL be the deployment being torn down, so `--deployment` retires that deployment's
image and not the one this machine is configured for. Retirement SHALL NOT fail the teardown: a
pointer that is absent, a pointer naming an AMI that no longer exists, or a deletion that fails
SHALL be reported as a warning that names what remains. The command SHALL still exit 0 once the
stack is deleted.

#### Scenario: A torn-down deployment leaves no image behind
- **WHEN** `baas admin deployment teardown --yes` completes for a deployment with a published image
- **THEN** the pointer parameter no longer exists, the AMI it named is deregistered, that AMI's
  snapshots are deleted, and no Image Builder image record of the deployment's recipe remains

#### Scenario: A later setup cannot inherit the image
- **WHEN** the same deployment is set up again after a teardown
- **THEN** `baas run` refuses to launch for want of a runner image until `baas admin image build`
  has run

#### Scenario: Nothing to retire
- **WHEN** teardown runs for a deployment whose pointer does not exist
- **THEN** the stack is deleted, no image-related error is raised, and the command exits 0

#### Scenario: The pointer names an AMI that is already gone
- **WHEN** the pointer names an AMI that has already been deregistered
- **THEN** the pointer is still deleted and the command exits 0

#### Scenario: A failed retirement is reported, not fatal
- **WHEN** deregistering the AMI or deleting a snapshot fails after the stack is deleted
- **THEN** the command warns naming the AMI or snapshot left behind and exits 0

#### Scenario: Another deployment's image
- **WHEN** `baas admin deployment teardown --deployment baas-123456789012-dev` runs on a machine configured for
  `baas-123456789012`
- **THEN** it retires the image published at `/baas-123456789012-dev/runner/ami-id`, and the
  configured deployment's image is untouched

### Requirement: Setup carries the image parameters forward on an existing deployment
When `baas admin deployment setup` creates a stack, it SHALL submit a rendered value for every runner image
parameter, so that none of the template's placeholder defaults is registered. When it updates an
existing stack, it SHALL leave every image parameter the deployed stack has at its deployed value, and
SHALL submit the rendered value only for an image parameter the deployed stack lacks. The image
parameters are the base version, the parent AMI, the base component, the extension and its version,
the contract, the recipe version and the label.

#### Scenario: A plain setup does not revert an extended image
- **WHEN** a deployment holds an extension and a newer base, and `baas admin deployment setup` runs again
  without options
- **THEN** the stack's image parameters are unchanged after the update

#### Scenario: Setup upgrades a deployment from before the extension
- **WHEN** the deployed stack has only the base version, parent AMI and base component parameters, and
  `baas admin deployment setup` runs with a CLI that declares the rest
- **THEN** those three keep their deployed values, and the extension, its version, the contract, the
  recipe version and the label are submitted with rendered values

#### Scenario: Setup on a new deployment registers the bundled base
- **WHEN** `baas admin deployment setup` creates a stack
- **THEN** the registered base component is the rendering of the bundled `runner-image.yaml`, not the
  template's placeholder

### Requirement: Setup writes a starter extension file
`baas admin deployment setup` SHALL write `~/.baas/runner-image-extension.yaml` containing the starter extension —
comments only, with a base marker naming `none` — when that file does not exist, and SHALL NOT modify it
when it does. The starter SHALL be the same document `baas admin image show --extension` prints for an
deployment holding no extension. Setup SHALL name the file and the command that pushes it.

#### Scenario: First setup writes the starter
- **WHEN** `baas admin deployment setup` completes and `~/.baas/runner-image-extension.yaml` does not exist
- **THEN** the file exists, carries a marker naming `none`, and deploying it would install nothing

#### Scenario: An edited extension file survives setup
- **WHEN** `~/.baas/runner-image-extension.yaml` holds the operator's edits and `baas admin deployment setup`
  runs again
- **THEN** the file is byte-identical afterwards

### Requirement: Teardown removes every deployment resource
The working bucket and the results table SHALL carry `DeletionPolicy: Delete` and `UpdateReplacePolicy:
Delete`. `baas admin deployment teardown` SHALL empty the bucket — every object version and delete
marker — then delete the core stack, so that the bucket, the results table and every other stack
resource are removed, and then retire the runner image. A completed teardown SHALL leave no BaaS resource
of the deployment in the account, and SHALL report no retained resource.

#### Scenario: Nothing is left after teardown
- **WHEN** `baas admin deployment teardown --yes` completes
- **THEN** the stack, the bucket, the results table, the runner image and its pointer no longer exist

#### Scenario: A later setup starts clean
- **WHEN** the same deployment is set up again after a teardown
- **THEN** setup creates the bucket and the table anew, and no pre-check for a leftover bucket or table
  exists

### Requirement: The working bucket never expires job artifacts
`S3MainBucket` SHALL declare lifecycle rules expiring noncurrent versions, reaping orphaned delete
markers and aborting incomplete multipart uploads, and SHALL NOT declare any rule that expires current
objects under the job prefix, since a job's uploaded input is the only record of what that job measured.

#### Scenario: No lifecycle rule expires job artifacts
- **WHEN** the rendered core template's lifecycle rules are inspected
- **THEN** none of them expires current objects under the job prefix

### Requirement: Setup renders the deployer policy and stops when the caller lacks it
Before calling any CloudFormation, DynamoDB or EC2 API — after only the name validation and the
bucket-region lookup, which must come first so that a deployment living in another region is named
rather than met with a policy for a second one — `baas admin deployment setup` SHALL render
the deployer policy for the deployment it is about to create or update — the account from
`sts:GetCallerIdentity`, the region from `--region` or its default, the name resolved as
*A deployment is named by its prefix, derived from the account by default* states — and, where the
caller may call `iam:SimulatePrincipalPolicy`, check the
caller's rights against it. When rights are missing it SHALL print the rendered policy to standard
output, state on standard error which actions are missing and that the policy must be attached before
re-running, create nothing, and exit non-zero. When rights are present, or cannot be checked, it SHALL
proceed; an `AccessDenied` during the deployment SHALL still be reported together with the rendered
policy. There SHALL be no separate command that prints the policy, and no option to render it for
another account.

#### Scenario: Missing rights stop setup before anything is created
- **WHEN** an identity without the deployer policy runs `baas admin deployment setup`
- **THEN** standard output holds the rendered policy JSON, standard error names the missing actions, no
  stack is created, and the exit code is non-zero

#### Scenario: The policy can be handed over
- **WHEN** `baas admin deployment setup > policy.json` runs for an identity lacking rights
- **THEN** `policy.json` is a valid IAM policy document for that deployment, and nothing else

#### Scenario: Sufficient rights deploy as before
- **WHEN** an identity holding the rendered policy runs `baas admin deployment setup`
- **THEN** no policy is printed and the deployment is created or updated

#### Scenario: The command is gone
- **WHEN** `baas admin deployer-policy` is invoked
- **THEN** picocli reports an unknown command error

#### Scenario: A named deployment's policy names that deployment
- **WHEN** `baas --deployment wiktor-dev admin deployment setup --region us-east-1` runs for an identity holding only the default deployment's policy
- **THEN** the printed policy's resources are scoped to `wiktor-dev` in `us-east-1`, and nothing is created

### Requirement: A deployment is named by its prefix, derived from the account by default
`baas admin deployment setup` SHALL create or update the deployment named by `--deployment`, using
that name verbatim as the resource name prefix. When `--deployment` is absent and no deployment is
configured on the machine, it SHALL derive the name from the caller's AWS account identifier as
`baas-<accountId>`. When `--deployment` is absent and exactly one deployment is configured, it SHALL
update that one. When two or more are configured, it SHALL require `--deployment`. The derived name
SHALL NOT depend on which principal, role, session or permission set the caller is using.

#### Scenario: Two identities on one account resolve the same default deployment
- **WHEN** `baas admin deployment setup` is run with no `--deployment` on a machine with no configured deployment, by an IAM user and again by an SSO identity in the same AWS account and region
- **THEN** both invocations derive the prefix `baas-<accountId>` and target the same stack, bucket and results table

#### Scenario: Changing permission set does not fork the deployment
- **WHEN** the same human runs `baas admin deployment setup` under one permission set and later under a different one in the same account
- **THEN** the second run updates the existing deployment rather than creating a second one

#### Scenario: A named deployment beside the default one
- **WHEN** `baas --deployment wiktor-dev admin deployment setup --region us-east-1` runs in an account whose default deployment `baas-<accountId>` exists in `eu-central-1`
- **THEN** a second deployment is created whose stack and bucket are named `wiktor-dev` and whose results table is `wiktor-dev-results`, and the default deployment is unchanged

#### Scenario: The default name is derivable without prior local state
- **WHEN** a machine that has never configured a deployment holds credentials for the account
- **THEN** the default deployment's prefix is computable from the caller's account identifier alone, with no value copied from another machine

### Requirement: A deployment name satisfies every service that carries it
`baas admin deployment setup` SHALL refuse, before any AWS call, a deployment name that some resource
composed from it could not carry. A name SHALL be accepted only if it:
- is 3 to N characters long, where N is 64 minus the length of the longest role-name suffix the
  composition rule appends, so that every composed IAM role name fits IAM's 64 characters;
- matches `^[a-z][a-z0-9-]*[a-z0-9]$`;
- contains no `--`;
- does not start with `aws`, `ssm`, `sthree-` or `amzn-s3-demo-`;
- does not end with `-s3alias`.

The refusal SHALL name the rule that was broken. No other check SHALL apply. In particular, a name of
the form `baas-<12 digits>` naming a different account is accepted.

#### Scenario: An SSM-reserved name is refused before deploying
- **WHEN** `baas --deployment aws-dev admin deployment setup` runs
- **THEN** the command exits non-zero naming the reserved `aws` prefix, issues no AWS call, and no policy is printed

#### Scenario: A name too long for the composed role names is refused
- **WHEN** the deployment name is one character longer than N
- **THEN** the command exits non-zero naming the length limit and the composed name that would exceed it

#### Scenario: Another account's default-looking name is accepted
- **WHEN** `baas --deployment baas-999999999999 admin deployment setup` runs in account `123456789012`
- **THEN** the name passes validation

### Requirement: Setup is the only command that validates a name
Commands other than `baas admin deployment setup` SHALL NOT validate a deployment name. They SHALL
resolve it against the deployments configured on the machine, and a name that is not configured
SHALL fail as an unknown deployment.

#### Scenario: An unknown name is reported as unknown, not invalid
- **WHEN** `baas --deployment Foo results query` runs and no deployment `Foo` is configured
- **THEN** the command exits non-zero naming the deployments that are configured, and issues no AWS call
