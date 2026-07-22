# CloudFormation stacks for BaaS

The infrastructure is split into two stacks. Run commands from this directory.

## Stack 1: `baas-core` — shared infrastructure

Deploys networking (VPC, subnet, IGW, S3 gateway endpoint, security group), the S3 working
bucket, and the EC2 runner IAM role. This is the same stack that `baas admin setup` deploys on a
user's account.

```bash
aws cloudformation deploy \
  --profile YOUR_AWS_PROFILE \
  --template-file cf-template-core.yaml \
  --stack-name baas-core \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides ResourceNamePrefix=RESOURCE_PREFIX
```

Retrieve the outputs needed by the CI stack:

```bash
aws cloudformation describe-stacks \
  --profile YOUR_AWS_PROFILE \
  --stack-name baas-core \
  --query 'Stacks[0].Outputs'
```

## Stack 2: `baas-ci` — GitHub Actions integration

Deploys the `WorkflowRole` (OIDC) assumed by GitHub Actions. Requires `RunnerRoleArn` and
`BucketName` from the `baas-core` outputs above.

### Without an existing GitHub OIDC provider

```bash
aws cloudformation deploy \
  --profile YOUR_AWS_PROFILE \
  --template-file cf-template-ci.yaml \
  --stack-name baas-ci \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
    ResourceNamePrefix=RESOURCE_PREFIX \
    RunnerRoleArn=RUNNER_ROLE_ARN_FROM_CORE_OUTPUTS \
    BucketName=BUCKET_NAME_FROM_CORE_OUTPUTS \
    GitHubOrg=YOUR_GITHUB_ORG \
    GitHubRepo=YOUR_GITHUB_REPO
```

### With an existing GitHub OIDC provider

```bash
aws cloudformation deploy \
  --profile YOUR_AWS_PROFILE \
  --template-file cf-template-ci.yaml \
  --stack-name baas-ci \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
    ResourceNamePrefix=RESOURCE_PREFIX \
    RunnerRoleArn=RUNNER_ROLE_ARN_FROM_CORE_OUTPUTS \
    BucketName=BUCKET_NAME_FROM_CORE_OUTPUTS \
    GitHubOrg=YOUR_GITHUB_ORG \
    GitHubRepo=YOUR_GITHUB_REPO \
    OIDCProviderArn=arn:aws:iam::YOUR_AWS_ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com
```

The `WorkflowRoleArn` output of the `baas-ci` stack maps to the `WORKFLOW_ROLE_ARN` GitHub
Actions secret.

## IAM policies for `baas-cli` identities

Two distinct IAM policies cover the two privilege levels `baas-cli` operates at. Attach only
the one that matches what the identity actually does — don't grant the deployer policy to
everyday users.

### `BaasCliOperatorPolicy` — standing, narrow

Required by `baas run`, `baas results`, and `baas config`. Covers:

- `ec2:RunInstances` / `ec2:Describe*` to launch and observe benchmark runner instances
- `ec2:TerminateInstances`, scoped to instances tagged `baas-role=benchmark-runner`
- `ssm:GetParameter` / `ssm:PutParameter`, scoped to the Mongo connection-string parameter path
- S3 object access, scoped to the core stack's `S3MainBucket`
- `iam:PassRole`, scoped to `RunnerRole` only

This is the policy day-to-day users of `baas-cli` should hold permanently.

### `BaasCliDeployerPolicy` — elevated, admin-only

Required by `baas admin setup` and `baas admin teardown`. Matches
[`deployer-policy.json`](./deployer-policy.json): `cloudformation:*` on the core stack,
VPC/EC2 networking create/delete, IAM role and instance-profile create, and S3 bucket create.
Attach this only to identities that provision or tear down the core stack — it should not be
held as a standing policy for routine benchmark runs.
