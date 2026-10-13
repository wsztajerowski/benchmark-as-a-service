# Spec Delta

## MODIFIED Requirements

### Requirement: RunnerRole S3 policy matches the bucket name
`RunnerRole`'s S3 access policy SHALL reference the exact same bucket name as the `S3MainBucket`
resource (`<prefix>`), and SHALL grant only what a job does on the instance: writing and reading
objects under `jobs/`, and reading objects under `releases/`. It SHALL NOT grant deleting any object,
listing the bucket, or writing anywhere outside `jobs/` — in particular not under `releases/`, which
holds the runner every later job of that version executes.

#### Scenario: Runner can access its own bucket
- **WHEN** an EC2 instance launched with `RunnerRole` attempts `s3:PutObject` against a key under
  `jobs/` in the stack's `S3MainBucket`
- **THEN** the request succeeds (the policy's resource ARN matches the bucket's actual name)

#### Scenario: Runner reads the pinned runner JAR
- **WHEN** an instance launched with `RunnerRole` downloads `releases/<version>/benchmark-runner.jar`
- **THEN** the request succeeds

#### Scenario: Runner cannot replace the pinned runner JAR
- **WHEN** code on an instance launched with `RunnerRole` attempts to write any key under `releases/`
- **THEN** the request is denied

#### Scenario: Runner cannot delete objects
- **WHEN** code on an instance launched with `RunnerRole` attempts to delete any object in the bucket
- **THEN** the request is denied

#### Scenario: Runner cannot list the bucket
- **WHEN** `infra/cf-template-core.yaml` is inspected
- **THEN** `RunnerRole` holds no `s3:ListBucket` and no S3 action on the bucket resource itself

### Requirement: Operator role permissions
`BaasCliOperatorRole` SHALL cover: `ec2:RunInstances`/`Describe*` to launch and observe benchmark runner instances, tag-scoped `ec2:TerminateInstances` (condition `aws:ResourceTag/baas-role=benchmark-runner`), `ec2:CreateTags` scoped to the `RunInstances` create action, `ssm:GetParameter` on the runner AMI pointer path (`/<prefix>/runner/ami-id`) and no SSM write of any kind, `dynamodb:Query`/`Scan`/`GetItem` on the results table and its index, `dynamodb:UpdateItem` on the results table restricted to the `JOB` partition and no other write action, `ec2:DescribeImages` to validate the resolved AMI, S3 object read and write and bucket listing scoped to the core stack's bucket and no S3 deletion of any kind, and `iam:PassRole` scoped to `RunnerRole` only. It SHALL NOT cover the public AL2023 AMI lookup path (`/aws/service/ami-amazon-linux-latest/*`), which is no longer used now that the runner boots from a purpose-built image. `baas jobs`/`baas results query`/`baas config` (and the aliases `baas run`, `baas query`) SHALL succeed when invoked by an identity that has assumed `BaasCliOperatorRole`.

#### Scenario: Operator role suffices for daily use
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas run jmh -- ...`, `baas jobs list`, `baas jobs terminate`, `baas results query`, or `baas jobs diff`
- **THEN** every AWS API call made succeeds under that role's permissions, including the SSM read of `/<prefix>/runner/ami-id` needed to resolve the runner's AMI ID and the run-item writes

#### Scenario: Public AMI lookup path is no longer granted
- **WHEN** `infra/operator-policy.json` is inspected
- **THEN** it contains no statement granting `ssm:GetParameter` on `/aws/service/ami-amazon-linux-latest/*`

#### Scenario: An operator session cannot delete job artifacts
- **WHEN** an identity that has assumed `BaasCliOperatorRole` — a CI workflow included — attempts to
  delete any object in the working bucket
- **THEN** the request is denied, and emptying the bucket remains possible only under the deployer's
  rights during teardown
