# CloudFormation stacks for BaaS

The infrastructure is split into two stacks. Run commands from this directory.

## Stack 1: `baas-core` — shared infrastructure

Deploys networking (VPC, subnet, IGW, S3 and DynamoDB gateway endpoints, security group), the S3
working bucket, the DynamoDB results table, the EC2 runner IAM role, and the EC2 Image Builder
resources that bake the runner AMI. This is the same stack that `baas admin deployment setup` deploys on a
user's account.

**The results table** (`baas-<prefix>-results`, output as `ResultsTableName`) is on-demand billed,
keyed `pk`/`sk`, with one GSI `jobId-index` over `gsi1pk`/`gsi1sk` and no TTL. Like the bucket,
it is `DeletionPolicy: Delete` / `UpdateReplacePolicy: Delete`: **nothing survives a teardown**, which
empties the bucket and then deletes the stack. Export a deployment's data before tearing it down.

Runners reach it through a **gateway** endpoint, associated with the runner subnet's route table.
Gateway, not interface: gateway endpoints are free, interface endpoints carry an hourly charge, and
this project's standing cost is a thing to defend. A template test asserts no interface endpoint for
DynamoDB is ever added.

**The Image Builder half** — `Component`, `ImageRecipe`, `InfrastructureConfiguration`,
`DistributionConfiguration`, `ImagePipeline`, plus an `ImageBuildRole` + instance profile — holds
only durable configuration. There is deliberately **no `AWS::ImageBuilder::Image`**: that resource
performs a build during stack operations, so every `baas admin deployment setup` would take ~15 minutes even
when nothing about the image changed. Builds are triggered out of band by `baas admin image build`
via `StartImagePipelineExecution`.

`ImageBuildRole` carries `AmazonSSMManagedInstanceCore` and `EC2InstanceProfileForImageBuilder`
(both required by Image Builder itself) plus a single inline grant: `s3:PutObject` on
`<bucket>/image-builds/*`. The build host installs packages and writes its own logs; it never
reads results, launches instances, or touches IAM.

The recipe runs three components in order: the **base**, rendered from the bundled
`infra/runner-image.yaml`; the deployment's optional **extension** (below); and the BaaS
**contract**, which writes the image label and, in its `test` phase on an instance booted from the
new image, fails the build when Java ≥ the runner's version, the AWS CLI, async-profiler, a `perf`
matching the kernel, `perf_event_paranoid ≤ 1` or `kptr_restrict = 0` is missing. Image tests are
enabled on the pipeline for that reason; a failed contract leaves the pointer on the previous image.

Eight stack parameters describe the image (`RunnerImageParameters.ALL`). `baas admin image build`
plans and submits all of them. `baas admin deployment setup` submits all of them on create — letting the
template's placeholder defaults stand would register a no-op component at a version, and Image
Builder would then reject the real one at that same version — and on update only those the stack
lacks, carrying the rest forward, so a plain setup never reverts an image or drops an extension.

Deploying this template **by hand** with `aws cloudformation deploy` leaves those parameters at
their defaults, which registers the placeholder components. Use `baas admin deployment setup` unless you are
deliberately deploying without an image and intend to pass the parameters yourself; the next
`baas admin image build` replaces the placeholders.

If this is the first Image Builder pipeline in the account, the deploying identity needs
`iam:CreateServiceLinkedRole` for `imagebuilder.amazonaws.com` — the service provisions
`AWSServiceRoleForImageBuilder` on first use, and the stack fails with `AccessDenied` on
`RunnerImagePipeline` without it.

```bash
aws cloudformation deploy \
  --profile YOUR_AWS_PROFILE \
  --template-file cf-template-core.yaml \
  --deployment baas-core \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides ResourceNamePrefix=RESOURCE_PREFIX
```

Retrieve the outputs needed by the CI stack:

```bash
aws cloudformation describe-stacks \
  --profile YOUR_AWS_PROFILE \
  --deployment baas-core \
  --query 'Stacks[0].Outputs'
```

### Extending the runner image

A deployment can add anything to its runner image — an observability agent, another profiler, a
native library — without a checkout. The extension is a raw
[AWSTOE component document](https://docs.aws.amazon.com/imagebuilder/latest/userguide/toe-use-documents.html),
run after the base and before the contract, stored in the stack exactly as written.

```bash
baas admin image show --extension > ext.yaml       # pull: the deployed extension, or a commented starter
$EDITOR ext.yaml
baas admin image build --extension ext.yaml   # push, then bake (~15 minutes)
baas admin image show                              # label, extension hash, size, step names
```

`baas admin deployment setup` also writes the starter to `~/.baas/runner-image-extension.yaml` the first time,
never overwriting it.

- **Keep the first line.** `# baas-extension-base: <hash|none>` names the extension the file was
  pulled from. A push from a copy someone else has since replaced is refused — the stack keeps no
  earlier version, so the push would discard theirs. Pull again and re-apply the edit. With no
  extension deployed, any file is accepted.
- **4096 bytes, comments included.** It travels as a stack parameter. Longer logic fits as a step
  that downloads and runs a script.
- **A file of comments only removes the extension.**
- **Never put a credential in it.** Anyone who can describe the stack can read it.
- **Results stay distinguishable.** The image label becomes `<base>+ext.<hash>`, so results from an
  extended image never share an `imageVersion` tag with a stock image's, and every job records the
  JVM vendor, kernel tunables and packages it measured on in `environment.json` / `packages.txt`.
- **What the contract does not constrain** is yours to change: another JDK vendor or a newer Java,
  transparent hugepages, swap. async-profiler is checked for presence only; on OpenJ9 it works
  only partly, so `jmh-with-async` profiles there are weaker.
- **The bake may reach the internet** (the build security group allows 443 and 80), which is what
  lets an extension download things.

## Stack 2: `baas-ci` — the GitHub Actions identity provider

Deploys the OIDC identity provider and nothing else. It takes no parameter from the core stack,
and produces none the core stack consumes — so **deploy order is provider first**, then
`baas admin deployment setup` with the provider's ARN. `WorkflowRole` used to live here and is gone: GitHub
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
  --deployment baas-ci \
  --parameter-overrides ResourceNamePrefix=RESOURCE_PREFIX
```

No `--capabilities` is needed — the stack declares no IAM role.

### Federating a core stack into the provider

```bash
baas admin deployment setup \
  --oidc-provider-arn arn:aws:iam::YOUR_AWS_ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com \
  --github-org YOUR_GITHUB_ORG \
  --github-repo YOUR_GITHUB_REPO
```

All three are required together: a partial set deploys a trust condition that is always false, so
the stack would report success while CI has no access — `baas admin deployment setup` rejects that outright.
`--github-repo` is repeatable (and accepts a comma-separated list), so one deployment can serve
several repositories.

A later `baas admin deployment setup` that names none of them **carries the deployed values forward** rather
than resubmitting the template defaults, so a setup run for an unrelated reason cannot silently
revoke CI's access. Removing the trust is therefore its own explicit gesture:

```bash
baas admin deployment setup --revoke-github-oidc
```

The account-root principal on the trust policy survives either way, so neither federating nor
revoking can lock a local operator out. The deployed stack's parameters are the single source of
truth for what the trust policy says — no deployment file under `~/.baas/deployments/` holds a copy:

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

Required by `baas admin deployment setup`, `baas admin image build` and `baas admin deployment teardown`. Attach this
only to identities that provision, image or tear down the core stack — it should not be held as a
standing policy for routine benchmark jobs.

**It is rendered per deployment, not shared.** Every resource it names derives from the
account, the region and the deployment prefix — `<prefix>` for the stack and bucket,
`<prefix>-role-runner` for the role, `<prefix>-results` for the table.
[`deployer-policy.json`](./deployer-policy.json) is therefore a *template* carrying
`${ACCOUNT_ID}` / `${REGION}` / `${PREFIX}` placeholders, and must never be attached in that form.

Note what changed: the prefix is `baas-<accountId>[-dev]`, so everyone on one account renders the
*same* policy for the *same* deployment. It is no longer per-caller, and it was never a
multi-tenancy boundary — the deployer policy is effectively account admin (see CLAUDE.md's
accepted risks). What it still does is keep an account's `shared` and `dev` deployments apart,
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
# Setup renders the policy for the deployment it is about to create and, when the identity lacks
# it, prints it on standard output and stops having created nothing. The region is baked into it,
# so pass the one the deployment will live in.
baas admin deployment setup --region eu-central-1 > policy.json

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

If a permission is missing, `baas admin deployment setup` fails and prints the rendered policy rather than
surfacing a bare `AccessDenied`. Treat that as a convenience, not a control: it is bypassable by
calling IAM directly, and nothing stops a deployer from granting itself more. That is deliberate —
this is an internal tool for development environments where the deployer is a trusted developer.
The per-caller scoping is about keeping two developers out of each other's stacks, not about
containing either of them.

Re-running the second command matters: nothing keeps the attached policy in sync with this
repo, so a stack change that needs a new permission fails at deploy time with a bare
`AccessDenied` until you publish a new policy version.

### `BaasCliOperatorRole` — standing, narrow, created *by* the core stack, assumed per-session

Required by `baas run`, `baas results query`, and `baas config`. Unlike the deployer policy, this
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
`baas admin deployment setup` needs no extra parameter for "who's the operator" — actual gating happens
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
`baas run`/`baas results query`/`baas config` fall through to the default AWS credential chain
rather than assuming the operator role:

```bash
baas config set --operator-profile baas-operator
```

Each deployment's file, `~/.baas/deployments/<name>.yaml`, keeps the two identities separate:

```yaml
aws:
  deployerProfile: baas-deployer  # baas admin …
  operatorProfile: baas-operator  # baas run / baas results query / baas jobs / baas config
```

`aws.operatorProfile` deliberately does **not** fall back to `aws.deployerProfile`. `baas admin
deployment setup --deployer-profile` writes the deployer profile there, and silently reusing it
would give every benchmark job `iam:CreateRole` and `cloudformation:*` — the exact standing
privilege the operator role exists to avoid. (The key was `aws.profile`, and the option
`--aws-profile`, before `multiple-deployments`; an old file is read and rewritten with the new key.)

If you are setting up on a machine that never ran `baas admin deployment setup` — the usual case when
the deployer and the operator are different people — adopt the deployment instead of copying its
file by hand:

```bash
baas --deployment baas-123456789012 config sync
```

With nothing configured, the name is required even though the prefix *is* derivable from the
account, because a bare sync on a machine with no local state would adopt whichever deployment the
active credentials imply. In CI — a wrong federated role, or a leftover `AWS_PROFILE` — that binds
the machine to another account's deployment and fails much later, after something has been
provisioned. `baas admin deployment setup` prints the name; `config sync` adopts it. With exactly
one deployment configured, a bare `baas config sync` re-syncs it.

`baas config list` shows what this machine is configured for — each deployment with its region and
both profiles — from the files alone, with no AWS call.

Sync also finds the deployment's region — from its bucket, whose name is global — and stores it,
so a machine never needs a region typed. The region is chosen once, by `baas admin deployment setup --region`;
there is no `config set --region`.

Every `baas admin deployment setup` run prints (and the stack outputs as `OperatorRoleArn`) this role's
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

## A second deployment, for developing BaaS itself

An account can hold more than one deployment, each named explicitly ([ADR 0006](../docs/adr/0006-more-than-one-deployment-per-account.md)).
Developing BaaS itself is the case that wants one — somewhere to exercise a template change, an IAM
edit or an image bake without disturbing the account's real deployment. **A change touching
`infra/`, IAM or the runner image is verified this way before it merges**: CI's e2e runs the PR's
CLI against the default deployment's stack, IAM and AMI, so it never exercises such a change.

```bash
DEV=wiktor-dev   # any name: 3-47 chars, lowercase letters, digits and hyphens, starting with a letter

# 1. Ask setup for the policy. Under the account's deployer it prints the policy rendered for $DEV
#    on stdout, names what is missing on stderr, and creates nothing.
baas --deployment "$DEV" admin deployment setup --region us-east-1 > /tmp/deployer-dev.json
#    ...attach /tmp/deployer-dev.json as a CUSTOMER-MANAGED policy, with an identity above the
#    deployer. It will not fit inline alongside the account's own: two rendered documents are
#    ~8.5k characters against IAM's 5120-character inline budget, shared across every inline policy
#    on the principal.

# 2. Run setup again: it now deploys, and writes ~/.baas/deployments/$DEV.yaml.
baas --deployment "$DEV" admin deployment setup --region us-east-1
baas --deployment "$DEV" admin image build

# 3. Work against it through the worktree's own build. With two deployments configured, every
#    command needs --deployment — the alias carries it, and the installed `baas` stays on yours.
alias baas-dev="java -jar $PWD/baas-cli/target/baas-cli.jar --deployment $DEV"
baas-dev run --benchmark-jar fake-jmh-benchmarks/target/fake-jmh-benchmarks.jar --project dev \
  --runner-jar benchmark-runner/target/benchmark-runner.jar jmh -- -f 1 -wi 1 -i 1

# 4. Tear it down. Its file goes with it, and with one deployment left the flag is optional again.
baas-dev admin deployment teardown
```

Three things worth knowing:

- **The name is the prefix, verbatim.** `wiktor-dev` makes the stack and bucket `wiktor-dev`, the table
  `wiktor-dev-results` and the runner role `wiktor-dev-role-runner`. Bucket and IAM names are
  global, so a name exists in one region of one account only; the region is a free choice.
- **Runners carry `baas-deployment=<name>`**, so two deployments in one region never count each
  other's runners — neither in `jobs list` nor in teardown's in-flight gate.
- **Teardown deletes everything**, bucket, results table and runner image included — nothing survives
  it, for a dev deployment as for the account's own. While it exists it costs one AMI snapshot
  (~$0.20/month for 30 GB).

## Client configuration beyond credentials — `runner.sourceRepo`

Each deployment's file carries one setting that is neither a credential nor a stack output:

```yaml
runner:
  sourceRepo: wsztajerowski/benchmark-as-a-service   # default
```

It names the GitHub repository the version-pinned runner JAR is downloaded from, and it is read
**only** when `releases/<version>/benchmark-runner.jar` is not yet seeded in the bucket. After the
first job of a given CLI version the object exists, is never overwritten, and the setting goes
unread until the next version bump — so changing it does not repoint jobs already pinned.

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
**ephemeral public IP per job** — no NAT gateway, no Elastic IP — so there was no stable address to
add to the IP Access List. The entry was `0.0.0.0/0`, with access controlled by the connection
string's credentials rather than by network. That was the standing argument for the
private-networking profile (private subnet + NAT gateway + PrivateLink, needing a paid M10+ tier,
roughly $32/month). With DynamoDB reached over a gateway endpoint, a private subnet no longer needs
egress for the store at all — which is what the `private-runner-network` change builds on.

`benchmark-runner` still carries a MongoDB adapter for standalone use against a user's own cluster,
selected by `--mongo-connection-string` on that JAR. No BaaS infrastructure supports it.

## Debugging a failed job

Runner instances self-terminate on both the success and failure paths, so the boot log is
uploaded to S3 before termination:

```
s3://<bucket>/jobs/<project>/<jobId>/cloud-init-output.log
```

`baas run` prints that path when a job fails, and when the instance dies before reporting.
