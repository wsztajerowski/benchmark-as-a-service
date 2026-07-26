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

## IAM identities for `baas-cli`

Two distinct privilege levels cover what `baas-cli` operates at — a standing, narrow
**role** for day-to-day use, and an elevated, admin-only **policy** for provisioning. Don't
hold the deployer policy permanently; don't skip assuming the operator role.

### `BaasCliDeployerPolicy` — elevated, admin-only, created out-of-band

Required by `baas admin setup` and `baas admin teardown`. Matches
[`deployer-policy.json`](./deployer-policy.json): `cloudformation:*` on the core stack,
VPC/EC2 networking create/delete, IAM role/instance-profile create, and S3 bucket create.
Attach this only to identities that provision or tear down the core stack — it should not
be held as a standing policy for routine benchmark runs.

This one **cannot** be created by the core stack itself — you need deployer permissions
*before* the stack exists in order to create it, so CloudFormation can never be the thing
that grants permission to create CloudFormation stacks. Create this policy manually (IAM
console or `aws iam create-policy`) and attach it to whichever identity will run
`baas admin setup`/`baas admin teardown`, before the first deploy.

It has to be a **customer-managed policy**, not an inline user policy: the document is over
2 KB minified, and IAM caps inline *user* policies at 2 048 characters (managed policies get
6 144). `aws iam put-user-policy` will reject it.

```bash
# First time
aws iam create-policy --policy-name BaasCliDeployerPolicy \
  --policy-document file://deployer-policy.json
aws iam attach-user-policy --user-name YOUR_DEPLOYER_IAM_USER \
  --policy-arn arn:aws:iam::YOUR_ACCOUNT_ID:policy/BaasCliDeployerPolicy

# After any change to deployer-policy.json — the attached policy does not track the file
aws iam create-policy-version --set-as-default \
  --policy-arn arn:aws:iam::YOUR_ACCOUNT_ID:policy/BaasCliDeployerPolicy \
  --policy-document file://deployer-policy.json
```

Re-running the second command matters: nothing keeps the attached policy in sync with this
repo, so a stack change that needs a new permission fails at deploy time with a bare
`AccessDenied` until you publish a new policy version.

### `BaasCliOperatorRole` — standing, narrow, created *by* the core stack, assumed per-session

Required by `baas run`, `baas results`, and `baas config`. Unlike the deployer policy, this
one has no bootstrap problem — by the time the core stack is being deployed, the deployer
already holds deployer privileges, so it's safe (and more precise) for the stack to create
this identity itself, as the `OperatorRole` resource (`AWS::IAM::Role`) in
`cf-template-core.yaml`, scoped exactly to that stack's own `S3MainBucket`, `RunnerRole`,
and mongo SSM parameter path — no wildcards needed, since CloudFormation knows those ARNs.

It's a **role**, not a policy attached directly to a user: whoever runs `baas run` assumes
it via `sts:AssumeRole` for a time-boxed session (1–12h) rather than holding permanent
standing access on their own IAM user. Its trust policy allows the AWS account root, so
`baas admin setup` needs no extra parameter for "who's the operator" — actual gating happens
per-user, by granting `sts:AssumeRole` on this specific role ARN only to the identities that
should be able to assume it:

```bash
# One-time, per operator identity: grant assume-role rights on this role only
aws iam put-user-policy \
  --profile YOUR_ADMIN_PROFILE \
  --user-name YOUR_OPERATOR_IAM_USER \
  --policy-name allow-assume-baas-operator-role \
  --policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole","Resource":"OPERATOR_ROLE_ARN_FROM_SETUP_OUTPUT"}]}'
```

Then the operator adds a profile that assumes it — no `baas-cli` code changes needed, since
the AWS SDK's credential chain resolves `role_arn`/`source_profile` profiles transparently:

```ini
# ~/.aws/config
[profile baas-operator]
role_arn = OPERATOR_ROLE_ARN_FROM_SETUP_OUTPUT
source_profile = YOUR_OPERATOR_IAM_USER_PROFILE
region = eu-central-1
```

Finally, point the CLI at that profile — **this step is required**. Without it,
`baas run`/`baas results`/`baas config` fall through to the default AWS credential chain
rather than assuming the operator role:

```bash
baas config set --operator-profile baas-operator
```

`~/.baas/config.yaml` keeps the two identities separate:

```yaml
aws:
  profile: baas-deployer          # baas admin setup / baas admin teardown
  operatorProfile: baas-operator  # baas run / baas results / baas config
```

`aws.operatorProfile` deliberately does **not** fall back to `aws.profile`. `baas admin
setup` writes the deployer profile into `aws.profile`, and silently reusing it would give
every benchmark run `iam:CreateRole` and `cloudformation:*` — the exact standing privilege
the operator role exists to avoid.

If you are setting up on a machine that never ran `baas admin setup` — the usual case when
the deployer and the operator are different people — pull the stack's values instead of
copying `config.yaml` by hand:

```bash
baas config sync --core-stack-name baas-a1b2c3d4
```

The stack name is printed by `baas admin setup`; the operator cannot derive it, because the
prefix is a hash of the *deployer's* ARN.

Every `baas admin setup` run prints (and the stack outputs as `OperatorRoleArn`) this role's
ARN. [`operator-policy.json`](./operator-policy.json) is a static reference copy of the same
permission statements — useful for review, or as `put-role-policy` content if you need to
build an equivalent role manually before any core stack has been deployed. It carries
`ACCOUNT_ID`, `REGION` and `RESOURCE_PREFIX` placeholder tokens rather than wildcards:
substitute your real values before using it. Its actions, resources *and* conditions are kept
in sync with the template's `OperatorRole` by a test (`OperatorPolicyDriftTest`), which
resolves the template's intrinsics to those same tokens before comparing.

Note the two `ec2:RunInstances` statements. EC2 authorizes `RunInstances` separately against
every resource in the request — instance, image, subnet, security group, network interface,
volume — and `ec2:InstanceType` is only populated for the instance. A single statement
carrying that condition would evaluate false for the other five and deny the whole call, so
the instance-type constraint lives on an instance-scoped statement and the supporting
resources get their own.

## MongoDB Atlas connectivity

The runner reaches Atlas over the public internet on **TCP 27017** (Atlas does not serve
clients on 443). `RunnerSecurityGroup` allows that egress.

Runner instances get an **ephemeral public IP per run** — there is no NAT gateway and no
Elastic IP — so there is no stable address to add to the Atlas IP Access List. In practice
this means the Access List entry has to be `0.0.0.0/0`, with access controlled by the
connection string's credentials rather than by network. If that is unacceptable for your
deployment, the private-networking profile (private subnet + NAT gateway + Atlas
PrivateLink, which needs a paid M10+ tier) is the alternative; it is out of scope for v1
and costs roughly $32/month standing.

## Debugging a failed run

Runner instances self-terminate on both the success and failure paths, so the boot log is
uploaded to S3 before termination:

```
s3://<bucket>/<branch>/<type>/<timestamp>/cloud-init-output.log
```

`baas run` prints that path when a run fails, and when the instance dies before reporting.
