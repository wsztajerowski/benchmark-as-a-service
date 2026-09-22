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

## Stack 2: `baas-ci` — the GitHub Actions identity provider

Deploys the OIDC identity provider and nothing else. It takes no parameter from the core stack,
and produces none the core stack consumes — so **deploy order is provider first**, then
`baas admin setup` with the provider's ARN. `WorkflowRole` used to live here and is gone: GitHub
Actions federates directly into `BaasCliOperatorRole`, which the core stack owns.

The provider is **account-global** — one per issuer URL per account. Check before deploying:

```bash
aws iam list-open-id-connect-providers --profile YOUR_AWS_PROFILE
```

If one already exists for `token.actions.githubusercontent.com`, **do not deploy this stack** —
it would fail with `EntityAlreadyExists`. Use the existing ARN instead.

This needs an identity above the deployer: `deployer-policy.json` scopes `iam:Get*`/`iam:List*` to
roles and never to `oidc-provider/*`, so the deployer gets `AccessDenied` even on the list above.

```bash
aws cloudformation deploy \
  --profile YOUR_ADMIN_AWS_PROFILE \
  --template-file cf-template-ci.yaml \
  --stack-name baas-ci \
  --parameter-overrides ResourceNamePrefix=RESOURCE_PREFIX
```

No `--capabilities` is needed — the stack declares no IAM role.

### Federating a core stack into the provider

```bash
baas admin setup \
  --oidc-provider-arn arn:aws:iam::YOUR_AWS_ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com \
  --github-org YOUR_GITHUB_ORG \
  --github-repo YOUR_GITHUB_REPO
```

All three are required together: a partial set deploys a trust condition that is always false, so
the stack would report success while CI has no access — `baas admin setup` rejects that outright.
`--github-repo` is repeatable (and accepts a comma-separated list), so one installation can serve
several repositories.

A later `baas admin setup` that names none of them **carries the deployed values forward** rather
than resubmitting the template defaults, so a setup run for an unrelated reason cannot silently
revoke CI's access. Removing the trust is therefore its own explicit gesture:

```bash
baas admin setup --revoke-github-oidc
```

The account-root principal on the trust policy survives either way, so neither federating nor
revoking can lock a local operator out. The deployed stack's parameters are the single source of
truth for what the trust policy says — `~/.baas/config.yaml` holds no copy:

```bash
aws cloudformation describe-stacks --stack-name PREFIX \
  --query 'Stacks[0].Parameters[?starts_with(ParameterKey, `GitHub`)]'
```

The core stack's `OperatorRoleArn` output is what GitHub Actions assumes. Set it as the plain
`OPERATOR_ROLE_ARN` repository **variable** — a role ARN is not sensitive, so it needs no secret.

## IAM identities for `baas-cli`

Two distinct privilege levels cover what `baas-cli` operates at — a standing, narrow
**role** for day-to-day use, and an elevated, admin-only **policy** for provisioning. Don't
hold the deployer policy permanently; don't skip assuming the operator role.

### `BaasCliDeployerPolicy` — elevated, admin-only, per caller

Required by `baas admin setup`, `baas admin build-image` and `baas admin teardown`. Attach this
only to identities that provision, image or tear down the core stack — it should not be held as a
standing policy for routine benchmark runs.

**It is rendered per installation, not shared.** Every resource it names derives from the
account, the region and the installation prefix — `<prefix>` for the stack and bucket,
`<prefix>-role-runner` for the role, `<prefix>-results` for the table.
[`deployer-policy.json`](./deployer-policy.json) is therefore a *template* carrying
`${ACCOUNT_ID}` / `${REGION}` / `${PREFIX}` placeholders, and must never be attached in that form.

Note what changed: the prefix is `baas-<accountId>[-dev]`, so everyone on one account renders the
*same* policy for the *same* installation. It is no longer per-caller, and it was never a
multi-tenancy boundary — the deployer policy is effectively account admin (see CLAUDE.md's
accepted risks). What it still does is keep an account's `shared` and `dev` installations apart,
and keep one account out of another's.

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
  stays pinned to `<prefix>-*`.
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
baas config sync --name baas-123456789012
```

`--name` is required even though the prefix *is* derivable from the account, because a bare sync
on a machine with no local state would adopt whichever installation the active credentials imply.
In CI — a wrong federated role, or a leftover `AWS_PROFILE` — that binds the machine to another
account's installation and fails much later, after something has been provisioned. `baas admin
setup` prints the name; `baas config sync --name` adopts it. Use `--name baas-<accountId>-dev` to
point a machine at the development installation.

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

## A second installation, for developing BaaS itself

`baas admin setup` derives its name from the caller's AWS account and takes **no option to name a
different one**: there is exactly one installation per account, and the CLI cannot be told
otherwise. That is deliberate. A user of BaaS should never have to ask which installation they are
on, and a development convenience has no business in the released command surface.

Developing BaaS itself is the case that wants a second, throwaway installation — somewhere to
exercise a template change or an image bake without disturbing the account's real one. It is a
procedure, not a feature:

```bash
ACCT=$(aws sts get-caller-identity --query Account --output text --profile baas-admin)
DEV="baas-${ACCT}-dev"

# 1. Render and attach the deployer policy for that prefix. The policy is prefix-exact, so the
#    account's own one grants nothing here. Needs an identity above the deployer.
baas admin deployer-policy --prefix "$DEV" > /tmp/deployer-dev.json
#    ...attach /tmp/deployer-dev.json as a CUSTOMER-MANAGED policy. It will not fit inline
#    alongside the account's own: two rendered documents are ~8.5k characters against IAM's
#    5120-character inline budget, which is shared across every inline policy on the principal.

# 2. Deploy the core template directly. ResourceNamePrefix is an ordinary parameter.
aws cloudformation deploy \
  --template-file infra/cf-template-core.yaml \
  --stack-name "$DEV" \
  --parameter-overrides "ResourceNamePrefix=$DEV" \
  --capabilities CAPABILITY_NAMED_IAM \
  --profile baas-admin

# 3. Point this machine at it. Everything downstream resolves from the configured prefix.
baas config sync --name "$DEV"
baas admin build-image        # renders the real component and updates the stack

#    ...work...

# 4. Tear it down, and go back to the account's own installation.
baas admin teardown --stack-name "$DEV" --delete-bucket
baas config sync --name "baas-${ACCT}"
```

Three things worth knowing before you use it:

- **The template's `RunnerImageComponentData` default is a placeholder**, registered at the default
  `RunnerImageVersion` of `1.0.0`. Step 3's `baas admin build-image` replaces it with the real
  component at whatever `infra/runner-image.yaml` declares. This works only while those two
  versions differ — if `runner-image.yaml` is ever set to `1.0.0`, the placeholder occupies that
  version and Image Builder will refuse the real one, because components are immutable at a
  version. Bump `imageVersion` before doing dev work at `1.0.0`.
- **Teardown retains the bucket and the results table** unless you pass `--delete-bucket`, and
  there is no flag for the table. A dev installation left half-removed will block the next deploy
  of the same prefix with a CloudFormation error that never mentions which resource; delete
  `$DEV` and `$DEV-results` by hand.
- **It costs a second AMI snapshot** (~$0.20/month for 30 GB) for as long as it exists, and the
  one-image rule only retires images the *same* installation replaced — so deregister the dev AMI
  and delete its snapshot when you tear the installation down.

If you would rather not manage the policy juggling, a **separate AWS account** gives the same
isolation for free: account-derived naming distinguishes the two installations with no prefix
games, `baas admin setup` works unmodified in both, and each account's deployer policy names only
its own account.

## Client configuration beyond credentials — `runner.sourceRepo`

`~/.baas/config.yaml` carries one setting that is neither a credential nor a stack output:

```yaml
runner:
  sourceRepo: wsztajerowski/benchmark-as-a-service   # default
```

It names the GitHub repository the version-pinned runner JAR is downloaded from, and it is read
**only** when `releases/<version>/benchmark-runner.jar` is not yet seeded in the bucket. After the
first run of a given CLI version the object exists, is never overwritten, and the setting goes
unread until the next version bump — so changing it does not repoint runs already pinned.

It is configuration rather than a constant in the user-data script so that a fork can point at its
own releases. Note that the *instance* never contacts GitHub: the CLI does the download on the
laptop, verifies the asset against the `.sha256` published by the same release build, and uploads
it. A mismatch uploads nothing and launches nothing.

## MongoDB Atlas connectivity — removed

**Nothing connects to Atlas.** Measurements go to the DynamoDB results table over the gateway
endpoint. The runner's TCP 27017 egress, the `/<prefix>/mongo/connection-string` parameter, and
every IAM grant that named it — on the runner, the operator, the deployer and the GHA
`WorkflowRole`, itself since deleted — are all gone.

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
s3://<bucket>/runs/<project>/<runId>/cloud-init-output.log
```

`baas run` prints that path when a run fails, and when the instance dies before reporting.
