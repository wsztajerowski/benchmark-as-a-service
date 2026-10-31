---
paths:
  - "infra/**"
  - "baas-cli/src/*/java/pl/wsztajerowski/baas/deployment/**"
  - "baas-cli/src/*/java/pl/wsztajerowski/baas/session/DeploymentNames*.java"
---

# Infrastructure and IAM

The two stacks, resource naming, the network, the bucket and SSM grants, and the deployer policy.

## Stacks

Two independently-deployed CloudFormation stacks. `infra/README.md` is current — follow it. There
is no `cf-template-main.yaml` and no bootstrap stack.

- **`cf-template-core.yaml`** — networking, the `<prefix>` bucket, `RunnerRole` +
  instance profile, `OperatorRole`, and the EC2 Image Builder resources (`Component`,
  `ImageRecipe`, `InfrastructureConfiguration`, `DistributionConfiguration`, `ImagePipeline`) plus
  the build-instance role. Deployed by `baas admin deployment setup`, bundled into the CLI as the
  classpath resource `/templates/cf-template-core.yaml`. It always creates its own networking.
  Eight parameters
  describe the runner image (`RunnerImageParameters.ALL`): the base, parent, extension and contract
  documents and their versions, and the label. Each component travels as a parameter value, so it
  must stay under CloudFormation's 4096-byte cap — unit-tested for base and contract, refused before
  submission for an extension. Two more — `GitHubOrg`, `GitHubRepo` — declare the conditional
  federated principal on `OperatorRole`'s trust policy and create no resource; the provider's ARN
  is composed in the template from the account, since its issuer URL fixes it. `GitHubRepo` is a
  `CommaDelimitedList` of **already-composed** `repo:<org>/<name>:*` subject patterns: setup builds them,
  because CloudFormation cannot iterate a list and every in-template trick for it leans on
  `Fn::Sub` re-scanning text substituted into it, which it does not do.
- **The GitHub OIDC identity provider is created by setup, never by a stack, and never modified or
  deleted by `baas`.** It is account-global (one per issuer URL) and may serve other projects, so
  it lives outside every deployment: setup creates it through the IAM API, with the
  `sts.amazonaws.com` audience and no thumbprint, when federation is asked for and it is absent;
  an existing one is reused, and one lacking that audience is left to an IAM administrator, with
  the command printed. Teardown says it stays. The deployer policy's `GitHubOidcProvider`
  statement grants only `Get` and `Create` on that one ARN. This reverses `baas-cli-core-ci-split`
  (ADR 0008); `cf-template-ci.yaml` is deleted.
  `WorkflowRole` is deleted too — GitHub Actions federates straight into `OperatorRole`, because a
  role-chained session is capped at 60 minutes by STS whatever `MaxSessionDuration` says, against
  a 7200 s default benchmark timeout. That failure was not an error but a red CI job with a good
  measurement, an un-terminated instance and a full EC2 bill.

IAM is split deliberately: `deployer-policy.json` → `BaasCliDeployerPolicy`, elevated, only for
`baas admin deployment setup`/`teardown` and `baas admin image build`; `operator-policy.json` → the stack-created
`<prefix>-role-operator`, narrow, for `baas jobs`/`results`/`config`. `operator-policy.json` reaches the
**test** classpath only; the core template and the two deployer policy templates ship in the JAR
because the CLI renders them at runtime.

## Resource names and stack updates

- **Every resource name is `<prefix>` or `<prefix>-<type>-<name>`, and the prefix is the deployment's
  name, verbatim.** One rule, no exceptions: `ResourceNamePrefix` is the whole name stem and knowing
  it predicts every name (`<prefix>` stack and bucket, `<prefix>-results`, `<prefix>-role-runner`,
  `<prefix>-profile-runner`, `<prefix>-pipeline-runner`, `/<prefix>/runner/ami-id`), and only
  `DeploymentNames` composes them (C5). The default name, `baas-<accountId>`, carries the `baas-`
  namespace inside it — composing `baas-` again at a use site yields `baas-baas-123456789012` — and
  a named deployment carries whatever its name does.
  The default is derived from `sts:GetCallerIdentity().account()` and **nothing about the calling
  principal reaches it** — that is the point. It used to be
  `lowercase(base32(sha256(callerArn)))[0:8]`, which moved when an SSO permission set was switched
  or re-provisioned and differed per human on one account; a moved prefix did not fail, it deployed
  a second complete deployment beside the first (finding A10). **A second deployment exists only
  because someone typed its name** into the global `--deployment` (`baas --deployment wiktor-dev
  admin deployment setup`), which is what keeps A10 closed (ADR 0006): nothing derives a name from
  the caller, and nothing derives one silently. Setup validates a name once, as the lowest common
  denominator of every service carrying it — 3–47 characters (the longest composed role name within
  IAM's 64), `^[a-z][a-z0-9-]*[a-z0-9]$`, no `--`, not starting `aws`/`ssm` (SSM refuses the
  `/<prefix>/…` hierarchy) or `sthree-`/`amzn-s3-demo-`, not ending `-s3alias` — and checks nothing
  else; a name that looks like another account's default is the user's call.
- **An update carries forward only the parameters the new template declares.**
  `updateStackParameters` sends every other deployed parameter as `UsePreviousValue` — which is how a
  plain setup or an image build cannot revoke CI's federation — but CloudFormation refuses a whole
  update naming a parameter the template no longer has. Every stack created before
  `setup-prerequisites` still carries `UseExistingVpc` and its three companions, so without the filter
  their first update after the removal fails. A removed template parameter needs no other handling.

## Network, bucket and sessions

- **Every runner is connectable, always, through the stack's own session document.** `RunnerRole`
  holds `ssm:UpdateInstanceInformation` and the four `ssmmessages:*Channel` actions, nothing else of
  SSM — no Run Command, no `ec2messages`, no managed policy — and `RunnerSecurityGroup` gains no
  ingress. The document is `<prefix>-session-runner`, never the account-global
  `SSM-SessionManagerRunShell`: settings someone puts on that one (S3/CloudWatch logging, KMS) would
  need grants the runner lacks, and every session would then fail for a reason BaaS cannot see. The
  operator's `StartSession` names the deployment's `baas-deployment` tag and that document, with
  `ssm:SessionDocumentAccessCheck` making the document mandatory. In a private subnet this needs
  `ssm` and `ssmmessages` interface endpoints (~$7/month per AZ each), which is why
  `private-runner-network` stays parked (ADR 0010).
- **The runner's bucket grant is an allow-list: `PutObject`/`GetObject` on `jobs/*`,
  `GetObject` on `releases/*`, and nothing else** — no delete, no listing, no other prefix. The
  operator holds `PutObject`/`GetObject`/`ListBucket` and no delete either; teardown empties the
  bucket under the deployer's rights. A new top-level prefix a runner must write to fails its first
  job loudly until it is added (`CoreTemplateTest` pins both grants).
- **Bucket versioning is `Suspended` and no lifecycle rule expires current objects under `jobs/`.**
  Both are load-bearing. The deleted `expire-uploaded-benchmark-jars` rule assumed everything under
  `jobs/` was re-creatable from source; results live there now, so restoring it is silent data loss
  (`CoreTemplateTest` pins its absence). Versioning only ever guarded an overwrite that the job
  id's 32-bit entropy suffix now prevents — and the consequence is stated, not implied: there is no
  server-side recovery from one.
- **`RunnerSecurityGroup` allows egress on 443 only — no 80, no 27017, and adding either back is a
  regression.** 27017 existed because Atlas does not serve clients on 443; nothing connects to Atlas
  now. 80 existed for `dnf`, which only the image bake runs, and the bake has its own
  `ImageBuildSecurityGroup` (443 + 80) — sharing one group handed the runner the builder's internet
  egress. `CoreTemplateTest` pins both absences rather than merely not testing for them — a security
  group rule nobody can explain is one somebody restores. Every deployment uses the VPC its own
  stack creates: existing-VPC support was removed in `setup-prerequisites`, no deployment used it.
- **Editing `RunnerSecurityGroup`'s `GroupDescription` replaces the security group.** It is an
  immutable property, so CloudFormation deletes and recreates the resource and the group *id
  changes*. Anything holding the old id is then pointing at a group that no longer exists —
  a deployment's `~/.baas/deployments/<name>.yaml` most obviously, which `baas admin deployment setup` rewrites, but not a job already in
  flight. Observed: removing the 27017 rule also touched the description, and the id moved. Change
  the rules without touching the description unless you intend the replacement. Its text still says
  "443/80" for exactly this reason, and `theRunnerSecurityGroupDescriptionIsNeverEdited` pins it.

## Deployer policy

**A deployer policy is prefix-exact, so it covers one deployment only** — except its Image
Builder statement, whose `<prefix>-*` also matches a deployment named `<prefix>-<more>` (accepted in
`CLAUDE.md`'s *Accepted risks*). Setup hands over the one it needs and creates nothing until it is
attached.

**The deployer policy has a size ceiling.** It is attached as the customer-managed
`BaasCliDeployerPolicy-<deployment>`, which IAM caps at 6144 non-whitespace characters to itself (an
inline policy gets 5120, shared across every inline policy on the principal, so two deployments'
documents never fit inline side by side). The rendered document sits at about 4990 non-whitespace
characters (measured 2026-10-23), and `renderedPolicyLeavesRoomInACustomerManagedPolicyBudget` holds it under 5632,
keeping 512 in reserve. That is why whole verb classes are wildcarded (`ec2:Describe*`, `s3:Get*`,
`imagebuilder:Get*`, `dynamodb:Describe*`) rather than enumerated — naming every action
CloudFormation's bucket read handler needs is what pushed it over. `Create` is deliberately *not*
wildcarded: `imagebuilder:CreateImage` must stay excluded, and `s3:Put*` would grant bucket policies
and ACLs. The deployer's object and item writes are named: `s3:PutObject` and `dynamodb:PutItem`
(with `dynamodb:Scan`) exist for `admin deployment export`/`import` alone, and no batch write,
item update or delete is granted — import's writes are conditional, and a batch cannot carry the
condition.

**BaaS targets the commercial `aws` partition only.** `deployer-policy.json` writes `arn:aws:`
literally while `cf-template-core.yaml` uses `${AWS::Partition}`; the template's form is free and
harmless, but making the policy partition-aware alone would imply support nothing else has — the
CLI fetches the runner JAR from GitHub releases, for one. Not a gap to close piecemeal.

**`deployer-policy.json` is a template, never a policy.** It carries `${ACCOUNT_ID}` / `${REGION}`
/ `${PREFIX}` placeholders and is rendered per caller by `DeployerPolicyRenderer` — every resource
it names is prefix-exact, so two developers cannot reach each other's stack, bucket or SSM
parameter (Image Builder alone is `<prefix>-*`; see *Accepted risks*). Attaching the file as-is grants nothing. There is no command that prints it:
`baas admin deployment setup` renders it for the deployment it is about to create — `--region`
included, since the region is in seven of its ARNs and conditions — and, when the caller's rights
fall short, hands it over having created nothing: unattended it prints it on standard output and
exits 1, so `setup > policy.json` works; on a terminal it saves it to a file or prints it, as the
user chooses, and waits for Enter to check again. `--policy-file` also writes it to a file before
the check, whatever it finds, then setup carries on. Setup prints the IAM administrator's
filled-in commands whenever it reports missing rights, a permission failure in stack events
included.

**The preflight probes what the rendered policy grants, never a list kept beside it.** Every action of
every `Allow` statement without a `Condition` is simulated with that statement's resources as written
— the simulator accepts wildcard actions and pattern resources — one call per action, because one
call mixing actions that need "different authorization information" is refused, and judged on the
action's overall decision, because the per-resource results report `implicitDeny` for every resource
type an action does not apply to. A hand-kept list let a stale policy pass three times
(`dynamodb:CreateTable`, `iam:UpdateRole`, 7.1's `sts:AssumeRole`). Conditioned statements stay
unprobed: the simulator has no `aws:RequestedRegion` or `iam:AWSServiceName` to evaluate.

`baas admin deployment setup`'s preflight (opportunistic `SimulatePrincipalPolicy`, plus translating any
`AccessDenied` into the rendered policy) is a **UX affordance, not a control** — anyone holding the
policy can call IAM directly. Don't try to make it one.
