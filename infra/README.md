# CloudFormation stacks for BaaS

The infrastructure is split into two stacks. Run commands from this directory.

## Stack 1: `baas-core` — shared infrastructure

Deploys networking (VPC, subnet, IGW, S3 and DynamoDB gateway endpoints, security group), the S3
working bucket, the DynamoDB results table, the EC2 runner IAM role, and the EC2 Image Builder
resources that bake the runner AMI. This is the same stack that `baas admin setup` deploys on a
user's account.

**The results table** (`baas-<prefix>-results`, output as `ResultsTableName`) is on-demand billed,
keyed `pk`/`sk`, with one GSI `requestId-index` over `gsi1pk`/`gsi1sk` and no TTL. Like the bucket,
it is `DeletionPolicy: Retain` / `UpdateReplacePolicy: Retain` — benchmark history outlives any
single stack — which means a teardown leaves it behind and the next `setup` for the same caller
fails on the name. `baas admin setup` pre-checks for exactly that and says how to recover.

Runners reach it through a **gateway** endpoint, associated with the runner subnet's route table.
Gateway, not interface: gateway endpoints are free, interface endpoints carry an hourly charge, and
this project's standing cost is a thing to defend. A template test asserts no interface endpoint for
DynamoDB is ever added.

**The Image Builder half** — `Component`, `ImageRecipe`, `InfrastructureConfiguration`,
`DistributionConfiguration`, `ImagePipeline`, plus an `ImageBuildRole` + instance profile — holds
only durable configuration. There is deliberately **no `AWS::ImageBuilder::Image`**: that resource
performs a build during stack operations, so every `baas admin setup` would take ~15 minutes even
when nothing about the image changed. Builds are triggered out of band by `baas admin build-image`
via `StartImagePipelineExecution`.

`ImageBuildRole` carries `AmazonSSMManagedInstanceCore` and `EC2InstanceProfileForImageBuilder`
(both required by Image Builder itself) plus a single inline grant: `s3:PutObject` on
`<bucket>/image-builds/*`. The build host installs packages and writes its own logs; it never
reads results, launches instances, or touches IAM.

Three stack parameters carry the image definition in from `infra/runner-image.yaml`:
`RunnerImageVersion`, `RunnerParentAmiId`, and `RunnerImageComponentData` (the rendered AWSTOE
document). `baas admin setup` and `baas admin build-image` both render them from the same
classpath resource, so the two commands always submit identical values — if setup let the
template's placeholder default stand, it would register a no-op component at the declared version
and Image Builder would then reject the real one at that same version.

Deploying this template **by hand** with `aws cloudformation deploy` leaves those three parameters
at their defaults, which registers the placeholder component. Use `baas admin setup` unless you
are deliberately deploying without an image and intend to pass the parameters yourself.

If this is the first Image Builder pipeline in the account, the deploying identity needs
`iam:CreateServiceLinkedRole` for `imagebuilder.amazonaws.com` — the service provisions
`AWSServiceRoleForImageBuilder` on first use, and the stack fails with `AccessDenied` on
`RunnerImagePipeline` without it.

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

### `BaasCliDeployerPolicy` — elevated, admin-only, per caller

Required by `baas admin setup`, `baas admin build-image` and `baas admin teardown`. Attach this
only to identities that provision, image or tear down the core stack — it should not be held as a
standing policy for routine benchmark runs.

**It is rendered per identity, not shared.** Every resource it names derives from the caller's
account, region and ARN-hash prefix — `baas-<prefix>` for the bucket, `<prefix>-runner-role` for
the role, `baas-<prefix>-results` for the table. [`deployer-policy.json`](./deployer-policy.json)
is therefore a *template* carrying `${ACCOUNT_ID}` / `${REGION}` / `${PREFIX}` placeholders, and
must never be attached in that form. Two callers get two different policies, and neither can reach
the other's stack, bucket, table or parameter.

It covers the table's **lifecycle only** — `CreateTable`, `DeleteTable`, `UpdateTable`,
`Describe*`, tagging — and deliberately not its data. A deployer cannot read or write measurements
with it; that is the operator role's job, and migrating data needs a principal holding
`BatchWriteItem`, which neither identity has by default.

This one **cannot** be created by the core stack itself — you need deployer permissions
*before* the stack exists in order to create it, so CloudFormation can never be the thing
that grants permission to create CloudFormation stacks.

It has to be a **customer-managed policy**. The rendered document is ~3.9 KB with whitespace
stripped, which is what IAM counts, and the limits are:

| Attachment | Cap | Fits? |
|---|---|---|
| Customer-managed policy | 6144, to itself | yes — use this |
| Inline policy on a user or group | 5120, **shared across every inline policy on that principal** | only if little else is inline there |
| Inline policy on a user (`put-user-policy`) | 2048 | no |

The inline case is the trap: the cap is a budget shared with whatever else is attached, so the
policy can fit today and be rejected after an unrelated addition. A `DeployerPolicyTest` case holds
the rendered size under 4096 to keep headroom, which is why several statements wildcard a whole
verb class (`ec2:Describe*`, `s3:Get*`, `imagebuilder:Get*`) instead of enumerating actions.
`Create` is deliberately not wildcarded — `imagebuilder:CreateImage` must stay excluded, and
`s3:Put*` would grant `PutObject`.

Two grants look wrong and are not:

- **`ImageBuilderRead` uses `Resource: "*"`.** Image Builder authorises read operations against the
  collection (`component/*`) even when the call names one specific ARN, so a prefix-scoped resource
  can never satisfy them. Everything that *acts* — create, delete, update, tag, start a build —
  stays pinned to `<prefix>-runner*`.
- **`CloudFormationValidateTemplate` uses `Resource: "*"`.** The action parses a template body and
  reads no account state; AWS offers no resource-level permission for it. It is a separate
  statement so the stack-scoped `CloudFormation` grant stays scoped to one stack.

```bash
# The user renders their own policy...
baas admin deployer-policy > policy.json

# ...or an administrator renders it for them, without the user running anything
baas admin deployer-policy --for-arn arn:aws:iam::123456789012:user/alice > policy.json

# First time
aws iam create-policy --policy-name BaasCliDeployerPolicy-alice \
  --policy-document file://policy.json
aws iam attach-user-policy --user-name alice \
  --policy-arn arn:aws:iam::YOUR_ACCOUNT_ID:policy/BaasCliDeployerPolicy-alice

# After any change to the template — the attached policy does not track the file
aws iam create-policy-version --set-as-default \
  --policy-arn arn:aws:iam::YOUR_ACCOUNT_ID:policy/BaasCliDeployerPolicy-alice \
  --policy-document file://policy.json
```

Give each identity its own policy name; a single shared `BaasCliDeployerPolicy` would have to be
re-rendered every time a different person ran setup.

If a permission is missing, `baas admin setup` fails and prints the rendered policy rather than
surfacing a bare `AccessDenied`. Treat that as a convenience, not a control: it is bypassable by
calling IAM directly, and nothing stops a deployer from granting itself more. That is deliberate —
this is an internal tool for development environments where the deployer is a trusted developer.
The per-caller scoping is about keeping two developers out of each other's stacks, not about
containing either of them.

Re-running the second command matters: nothing keeps the attached policy in sync with this
repo, so a stack change that needs a new permission fails at deploy time with a bare
`AccessDenied` until you publish a new policy version.

### `BaasCliOperatorRole` — standing, narrow, created *by* the core stack, assumed per-session

Required by `baas run`, `baas results`, and `baas config`. Unlike the deployer policy, this
one has no bootstrap problem — by the time the core stack is being deployed, the deployer
already holds deployer privileges, so it's safe (and more precise) for the stack to create
this identity itself, as the `OperatorRole` resource (`AWS::IAM::Role`) in
`cf-template-core.yaml`, scoped exactly to that stack's own `S3MainBucket`, `RunnerRole`,
results table and SSM parameter paths — no wildcards needed, since CloudFormation knows those ARNs.
On the table it holds `Query` and `GetItem`, on the table **and** its index, and no write action at
all: a command that reads results has no business putting them.

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

## MongoDB Atlas connectivity — removed

**Nothing connects to Atlas.** Measurements go to the DynamoDB results table over the gateway
endpoint. The runner's TCP 27017 egress, the `/<prefix>/mongo/connection-string` parameter, and
every IAM grant that named it — on the runner, the operator, the deployer and the GHA
`WorkflowRole` — are all gone.

For the record, while it was live: Atlas does not serve clients on 443, and runner instances get an
**ephemeral public IP per run** — no NAT gateway, no Elastic IP — so there was no stable address to
add to the IP Access List. The entry was `0.0.0.0/0`, with access controlled by the connection
string's credentials rather than by network. That was the standing argument for the
private-networking profile (private subnet + NAT gateway + PrivateLink, needing a paid M10+ tier,
roughly $32/month). With DynamoDB reached over a gateway endpoint, a private subnet no longer needs
egress for the store at all — which is what the `private-runner-network` change builds on.

`benchmark-runner` still carries a MongoDB adapter for standalone use against a user's own cluster,
selected by `--mongo-connection-string` on that JAR. No BaaS infrastructure supports it.

## Debugging a failed run

Runner instances self-terminate on both the success and failure paths, so the boot log is
uploaded to S3 before termination:

```
s3://<bucket>/<branch>/<type>/<timestamp>/cloud-init-output.log
```

`baas run` prints that path when a run fails, and when the instance dies before reporting.
