## ADDED Requirements

### Requirement: The stack owns the runner session document
The core stack SHALL declare a Session Manager session document named `<prefix>-session-runner` that
starts an interactive bash shell, in `/app` when that directory exists, as the agent's default
non-root user, with an idle timeout, and with no transcript logging and no encryption setting that
would require the runner to reach another resource. Changing the document's content SHALL update it in
place under the same name. The CLI SHALL NOT read, create or modify the account's default session
preferences document (`SSM-SessionManagerRunShell`).

#### Scenario: The document is part of the deployment
- **WHEN** `baas admin deployment setup` deploys the core stack
- **THEN** a session document named `<prefix>-session-runner` exists, and teardown removes it with the
  stack

#### Scenario: The account default is never touched
- **WHEN** setup, a job, `baas jobs connect` or teardown runs
- **THEN** no call reads or writes `SSM-SessionManagerRunShell`

#### Scenario: A content change updates the document in place
- **WHEN** the document's content changes between two setups of the same deployment
- **THEN** the stack update succeeds and the document keeps its name

### Requirement: Every runner registers with Session Manager
`RunnerRole` SHALL grant `ssm:UpdateInstanceInformation` and the Session Manager channel actions
`ssmmessages:CreateControlChannel`, `ssmmessages:CreateDataChannel`, `ssmmessages:OpenControlChannel`
and `ssmmessages:OpenDataChannel`, and nothing else of `ssm` or `ssmmessages`: no Run Command, no
parameter or document access. `RunnerSecurityGroup` SHALL gain no inbound rule, and its egress and
`GroupDescription` SHALL be unchanged.

#### Scenario: A runner is connectable without any per-job choice
- **WHEN** any job's instance has booted
- **THEN** its agent is registered and online in Session Manager

#### Scenario: The runner gains no inbound path
- **WHEN** the template's `RunnerSecurityGroup` is inspected
- **THEN** it declares no ingress rule, and its egress and description are as before

## MODIFIED Requirements

### Requirement: Operator role permissions
`BaasCliOperatorRole` SHALL cover: `ec2:RunInstances`/`Describe*` to launch and observe benchmark runner instances, tag-scoped `ec2:TerminateInstances` (condition `aws:ResourceTag/baas-role=benchmark-runner`), `ec2:CreateTags` scoped to the `RunInstances` create action, `ssm:GetParameter` on the runner AMI pointer path (`/<prefix>/runner/ami-id`) and no SSM write of any kind, `dynamodb:Query`/`Scan`/`GetItem` on the results table and its index, `dynamodb:UpdateItem` on the results table restricted to the `JOB` partition and no other write action, `ec2:DescribeImages` to validate the resolved AMI, S3 object read and write and bucket listing scoped to the core stack's bucket and no S3 deletion of any kind, and `iam:PassRole` scoped to `RunnerRole` only. For `baas jobs connect` it SHALL additionally cover: `ssm:StartSession` on instances tagged `baas-deployment=<prefix>` and on the document `<prefix>-session-runner` only, with the session document access check required so that no other document can be used; `ssm:TerminateSession` and `ssm:ResumeSession` only on sessions the calling session started; `ssmmessages:OpenDataChannel` on those sessions; and `ssm:DescribeInstanceInformation`. It SHALL NOT cover the public AL2023 AMI lookup path (`/aws/service/ami-amazon-linux-latest/*`), which is no longer used now that the runner boots from a purpose-built image. `baas jobs`/`baas results query`/`baas config` (and the aliases `baas run`, `baas query`) SHALL succeed when invoked by an identity that has assumed `BaasCliOperatorRole`.

#### Scenario: Operator role suffices for daily use
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas run jmh -- ...`, `baas jobs list`, `baas jobs terminate`, `baas jobs connect`, `baas results query`, or `baas jobs diff`
- **THEN** every AWS API call made succeeds under that role's permissions, including the SSM read of `/<prefix>/runner/ami-id` needed to resolve the runner's AMI ID and the run-item writes

#### Scenario: Public AMI lookup path is no longer granted
- **WHEN** `infra/operator-policy.json` is inspected
- **THEN** it contains no statement granting `ssm:GetParameter` on `/aws/service/ami-amazon-linux-latest/*`

#### Scenario: An operator session cannot delete job artifacts
- **WHEN** an identity that has assumed `BaasCliOperatorRole` — a CI workflow included — attempts to
  delete any object in the working bucket
- **THEN** the request is denied, and emptying the bucket remains possible only under the deployer's
  rights during teardown

#### Scenario: Sessions are limited to the deployment's runners and document
- **WHEN** an identity that has assumed one deployment's operator role starts a session on an instance
  not tagged with that deployment, or with any document other than `<prefix>-session-runner`
- **THEN** the request is denied

#### Scenario: Sessions cannot be used for Run Command
- **WHEN** an identity that has assumed `BaasCliOperatorRole` calls `ssm:SendCommand` on a runner
- **THEN** the request is denied

### Requirement: Deployer policy covers the full setup path
`BaasCliDeployerPolicy` SHALL include `ssm:PutParameter` on the runner AMI pointer path, the S3 actions needed to empty and delete the working bucket (`s3:ListBucket`, `s3:ListBucketVersions`, `s3:DeleteObject`, `s3:DeleteObjectVersion`, `s3:DeleteBucket`), the IAM read-back actions CloudFormation invokes after role creation (`iam:GetRolePolicy`, `iam:ListRolePolicies`, `iam:ListAttachedRolePolicies`), and the SSM document actions CloudFormation invokes to create, update and delete the runner session document, scoped to `document/<prefix>-*`.

#### Scenario: Setup succeeds end to end
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin deployment setup`
- **THEN** the stack deploys, including the results table and the runner session document, and the command exits 0

#### Scenario: The deployer cannot touch another document
- **WHEN** the deployer policy is evaluated for `ssm:UpdateDocument` on `document/SSM-SessionManagerRunShell`
- **THEN** the request is not permitted
