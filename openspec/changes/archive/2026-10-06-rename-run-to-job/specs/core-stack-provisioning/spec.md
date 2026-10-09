# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: Failed runs leave diagnosable output`
- TO: `### Requirement: Failed jobs leave diagnosable output`

## MODIFIED Requirements

### Requirement: Working bucket survives stack deletion by default
`S3MainBucket` SHALL declare `DeletionPolicy: Retain` and `UpdateReplacePolicy: Retain`, and SHALL declare lifecycle rules expiring noncurrent versions, reaping orphaned delete markers, and aborting incomplete multipart uploads. It SHALL NOT declare any rule that expires current objects under the job prefix, since a job's uploaded input is the only record of what that job measured.

#### Scenario: Default teardown retains the bucket by design
- **WHEN** `baas admin teardown --yes` deletes the core stack without `--delete-bucket`
- **THEN** the stack reaches `DELETE_COMPLETE` and the bucket still exists

#### Scenario: No lifecycle rule expires run artifacts
- **WHEN** the rendered core template's lifecycle rules are inspected
- **THEN** none of them expires current objects under the job prefix

### Requirement: Teardown safety gates
`baas admin teardown` SHALL abort if any EC2 instance tagged `baas-role=benchmark-runner` is in the
`pending` or `running` state, SHALL require explicit confirmation (retyping the stack name, or
`--yes`), and SHALL retain both the S3 bucket and the DynamoDB results table by default. The bucket
is deletable with `--delete-bucket`, the table by no flag at all. It SHALL name both retained
resources on exit. Both gates SHALL pass before anything is deleted, the runner image included.
When it aborts over in-flight jobs, it SHALL name each job by the identifier in its instance's
`baas-job-id` tag, alongside the instance identifier and state, and SHALL name
`baas jobs terminate <jobId>` as the way to stop one. It SHALL read those identifiers from the
instances, not from the results table.

#### Scenario: Abort when a run is in flight
- **WHEN** `baas admin teardown` runs while a `baas-role=benchmark-runner` instance is `running`
- **THEN** the command exits with an error listing each in-flight job's identifier, instance ID and
  state, names `baas jobs terminate <jobId>`, and performs no destructive action

#### Scenario: Abort when a run is still booting
- **WHEN** `baas admin teardown` runs while a `baas-role=benchmark-runner` instance is `pending`
- **THEN** the command exits with an error listing that job's identifier and instance ID and performs
  no destructive action, so a job launched moments earlier does not lose its role, subnet or image
  mid-boot

#### Scenario: The gate needs no table access
- **WHEN** `baas admin teardown` runs under the deployer policy, which grants no read of the results table
- **THEN** the in-flight check and its message succeed

#### Scenario: Bucket retained unless explicitly deleted
- **WHEN** `baas admin teardown --yes` runs without `--delete-bucket`
- **THEN** the core stack is deleted but the S3 bucket persists

### Requirement: Deployer policy is created out-of-band, before the core stack exists
`BaasCliDeployerPolicy` (matching `infra/deployer-policy.json`) SHALL cover: CloudFormation stack lifecycle (create, update, delete, describe, change-set operations), VPC/EC2 networking create/delete/describe — including `ec2:DescribeInstances`, needed by `baas admin teardown`'s active-job safety gate — IAM role/instance-profile create (covers both `RunnerRole` and `OperatorRole`, since both are the same resource type), S3 bucket create, and the DynamoDB table lifecycle actions. `baas admin setup`/`baas admin teardown` SHALL require `BaasCliDeployerPolicy`. This policy SHALL be created manually, before the first `baas admin setup` run — the core stack SHALL NOT create it, since CloudFormation cannot grant permission to create CloudFormation stacks.

#### Scenario: Deployer policy permits the full setup/teardown lifecycle
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin setup` followed later by `baas admin teardown`
- **THEN** every AWS API call made by both commands succeeds, including teardown's active-job check

### Requirement: Operator role permissions
`BaasCliOperatorRole` SHALL cover: `ec2:RunInstances`/`Describe*` to launch and observe benchmark runner instances, tag-scoped `ec2:TerminateInstances` (condition `aws:ResourceTag/baas-role=benchmark-runner`), `ec2:CreateTags` scoped to the `RunInstances` create action, `ssm:GetParameter` on the runner AMI pointer path (`/<prefix>/runner/ami-id`) and no SSM write of any kind, `dynamodb:Query`/`Scan`/`GetItem` on the results table and its index, `dynamodb:UpdateItem` on the results table restricted to the `JOB` partition and no other write action, `ec2:DescribeImages` to validate the resolved AMI, S3 object access scoped to the core stack's bucket, and `iam:PassRole` scoped to `RunnerRole` only. It SHALL NOT cover the public AL2023 AMI lookup path (`/aws/service/ami-amazon-linux-latest/*`), which is no longer used now that the runner boots from a purpose-built image. `baas run`/`baas jobs`/`baas results`/`baas config`/`baas env` SHALL succeed when invoked by an identity that has assumed `BaasCliOperatorRole`.

#### Scenario: Operator role suffices for daily use
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas run jmh -- ...`, `baas jobs list`, `baas jobs terminate`, `baas results`, or `baas env diff`
- **THEN** every AWS API call made succeeds under that role's permissions, including the SSM read of `/<prefix>/runner/ami-id` needed to resolve the runner's AMI ID and the run-item writes

#### Scenario: Public AMI lookup path is no longer granted
- **WHEN** `infra/operator-policy.json` is inspected
- **THEN** it contains no statement granting `ssm:GetParameter` on `/aws/service/ami-amazon-linux-latest/*`

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

### Requirement: The runner can write results but not read them
`RunnerRole` SHALL be granted `dynamodb:PutItem` and `dynamodb:BatchWriteItem` on the results table ARN
restricted to `RESULT#` partitions, and `dynamodb:UpdateItem` restricted to the `JOB` partition, and
nothing else on that table. It SHALL NOT be granted `Query`, `Scan`, `GetItem`, or any delete action.

#### Scenario: Runner can store a result
- **WHEN** an instance using the runner instance profile writes a measurement
- **THEN** the write succeeds

#### Scenario: Runner can record its run's status
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
- **WHEN** an identity that has assumed the operator role runs `baas results`
- **THEN** the query succeeds

#### Scenario: Operator can query the request-ID index
- **WHEN** that identity runs `baas results --job-id <id>`
- **THEN** the index query succeeds

#### Scenario: Operator can record a run's status
- **WHEN** that identity runs `baas run`, which reserves and updates the job item
- **THEN** the writes succeed

#### Scenario: Operator cannot mutate results
- **WHEN** that identity attempts `dynamodb:PutItem` on the results table, or `dynamodb:UpdateItem` on a key in a `RESULT#` partition
- **THEN** the request is denied

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
- **THEN** the session does not expire before the job completes and the instance is terminated

### Requirement: The CI stack contains only the identity provider
`cf-template-ci.yaml` SHALL declare the GitHub OIDC identity provider and nothing else. It SHALL
take no parameter from, and produce no output consumed by, the core stack. Because the identity
provider is account-global and the core stack's trust statement references it, the provider SHALL be
deployed before `baas admin setup` is first job with federation parameters.

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
- **THEN** both invocations derive the prefix `baas-<accountId>` and target the same stack, bucket and results table

#### Scenario: Changing permission set does not fork the installation
- **WHEN** the same human runs `baas admin setup` under one permission set and later under a different one in the same account
- **THEN** the second job updates the existing installation rather than creating a second one

#### Scenario: No option selects an installation
- **WHEN** `baas admin setup --mode dev`, `--prefix foo` or `--name foo` is invoked
- **THEN** picocli reports an unknown option error and nothing is deployed

#### Scenario: The prefix is derivable without prior local state
- **WHEN** a machine that has never run `baas admin setup` holds credentials for the account
- **THEN** the installation prefix is computable from the caller's account identifier alone, with no value copied from another machine
