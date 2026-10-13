# Exploration — setup-prerequisites

Explored 2026-10-10 → 2026-10-13 with the user, after the live end-to-end test of v7.0.0
(`archive/2026-10-12-pr83-review-fixes/verify.md`, *Full end-to-end test*), re-grounded on `main` at
v7.1.1 (`derived-deployment-config`, `narrow-bucket-grants`). It is the first of two changes: this
one makes setup's permission handling and inputs fit for a guided flow; the walkthrough itself is
[`interactive-setup`](../interactive-setup/exploration.md), which builds on it. Next step:
`/opsx:propose setup-prerequisites`.

## Why

**A stale deployer policy keeps passing the preflight.** `DeployerPreflight.criticalActionsToResources`
simulates eight hand-picked actions. Each release that adds a deployer statement can therefore ship a
policy the preflight never checks:

| When | Missed action | What it cost |
|---|---|---|
| before `dynamodb-results-store` | `dynamodb:CreateTable` | a stack update failed partway and rolled back (comment in the code) |
| `cli-driven-ci-workflows` | `iam:UpdateRole` | `baas-3q7i7s65` in `UPDATE_ROLLBACK_FAILED`, needing `ContinueUpdateRollback` and a human |
| 7.1.0 (`derived-deployment-config`) | `sts:AssumeRole` (`AssumeOwnOperatorRole`) | setup passed; **every day-to-day command** then stopped with `not authorized to perform: sts:AssumeRole` (that change's `verify.md`, row "C from the deployer, **old** policy"). Fixed by hand-attaching a separate inline policy; no `baas` command offered the new document |

Each time the fix was to add one more action to the list. The class stays open.

**The policy can only be obtained when the caller lacks rights.** Setup prints it on standard output
when the preflight finds a gap (`setup > policy.json`). An administrator who holds every right
cannot render it for a teammate, and a deployer whose stale policy passes the preflight (above) is
never shown the new one. `jobs-command` D11 accepted the first; the third miss above is the new
evidence that the second matters.

**A permission failure inside CloudFormation prints no policy.** The spec says *"an `AccessDenied`
during the deployment SHALL still be reported together with the rendered policy"*
(`core-stack-provisioning`, *Setup renders the deployer policy and stops when the caller lacks it*).
The code does that only for an SDK-level denial (`SetupCommand.call`, the `RuntimeException` branch).
A denial CloudFormation reports in stack events arrives as the `IllegalStateException` from
`CloudFormationService.failureReport` and is printed as a message only. That is the common case for a
stale policy, since the stack performs the resource calls.

**Existing-VPC support is a path no deployment uses.** Both live deployments run with
`UseExistingVpc=false` (checked 2026-10-13). The option costs four setup flags, four template
parameters with their conditions, a networking-immutability check on update, a security-group
special case, and it would need an interactive VPC/subnet/SG picker in `interactive-setup`. The user
decided to remove it rather than carry it into the walkthrough.

## Decisions

### D1 — The preflight probes what the rendered policy grants

The probe list is derived from the rendered document instead of maintained by hand: every statement
without a `Condition`, with concrete (non-`*`) resources, simulated as that statement's actions
against its resources. That is the filter the hand-picked list already applies in prose (the EC2
statement is left out for its `aws:RequestedRegion` condition, which the simulator cannot evaluate).
A new statement is then probed the release it is added, with no second edit.

*Spike before designing:* whether `SimulatePrincipalPolicy` accepts wildcard action names
(`ec2:Describe*`, `s3:Get*`, `imagebuilder:Get*`) and pattern resources (`…:${PREFIX}-*`). If not,
wildcard actions are skipped (they are reads) and pattern resources are probed with one concrete
name each, from `DeploymentNames`.

*Shipped first and on its own* (user, 2026-10-13): it is a fix, not a feature, and every existing
deployer gains from it on the next release.

### D2 — `--policy-file <path>`: write, then continue

Setup writes the rendered policy to the path before it acts on the preflight's result, whether or
not rights are missing, and then proceeds as it would without the flag: deploys when rights are
present or cannot be checked, stops having created nothing when they are missing. Chosen by the user
over *render only, create nothing* (2026-10-10): one flag, and the file is there whatever happens
next. On an upgrade that then fails inside CloudFormation (D4), the file already holds the document
to attach.

Alongside it, setup prints the commands an IAM administrator runs, filled in and named per
deployment, `BaasCliDeployerPolicy-<deployment>`. Both `create-policy` (+ `attach-user-policy`) and
`create-policy-version --set-as-default` are printed, since the deployer cannot read customer-managed
policies to tell which applies.

Unchanged: on a shortfall the policy still goes to standard output too, so `setup > policy.json`
keeps working; the spec's *There SHALL be no separate command that prints the policy* still holds
(this is an option of setup).

### D3 — A day-to-day command denied `AssumeRole` from the deployer source says why

When `OperatorSession` assumes `<name>-role-operator` from `aws.deployerProfile` (the fallback) and STS
denies it, the error adds: *the attached deployer policy predates this CLI; re-run
`baas --deployment <name> admin deployment setup --policy-file <file>` and attach the result*. A
denial from an operator profile keeps today's message: there the grant is the teammate's, not the
deployer policy's.

### D4 — A permission failure in stack events is reported with the policy

When `failureReport`'s root-cause event reads as a permission failure (`not authorized`,
`AccessDenied`), setup reports it as the SDK path does: names the file from D2 when given, otherwise
prints the rendered policy. This brings the code back to the spec text quoted above.

### D5 — Existing-VPC support is removed

- **CLI:** `--use-existing-vpc`, `--vpc-id`, `--subnet-id`, `--sg-id` go, with
  `validateNetworkingOptions` and the update-time networking comparison.
- **Template:** `UseExistingVpc`, `ExistingVpcId`, `ExistingSubnetId`, `ExistingSecurityGroupId` and
  their conditions go; the networking resources and both security groups become unconditional. Both
  live stacks were created with `UseExistingVpc=false`, so the resources already exist under the same
  logical IDs and an update replaces nothing. `RunnerSecurityGroup`'s `GroupDescription` must stay
  byte-identical (CLAUDE.md; `theRunnerSecurityGroupDescriptionIsNeverEdited`).
- **Specs (`core-stack-provisioning`):** REMOVED *VPC parameters are immutable once the deployment
  exists*, verbatim; MODIFIED *The image build and the runner do not share a security group*, which
  carries the `UseExistingVpc` sentence and the scenario *An existing VPC keeps the operator's group*.
  Check the exact headers in `openspec/specs/` before writing the deltas (CLAUDE.md gotcha).
- **Docs:** CLAUDE.md (*Networking is immutable once a deployment exists*, the `--use-existing-vpc`
  sentence under the security-group rule, the core stack's description), `infra/README.md`, diagrams
  `baas-setup.mmd`, `baas-lifecycle.mmd`, `baas-build-image.mmd` (render and look at each).
- **Tests:** `SetupCommandTest`, `CoreTemplateTest`.
- **Not touched:** `private-runner-network` (parked) references the option; whoever unparks it updates
  it.

### D6 — The stale policy section of `infra/README.md` is corrected

Plain fixes found while exploring:
- lines 61 and 71 use `--deployment baas-core` in `aws cloudformation deploy` / `describe-stacks`
  (it is `--stack-name`);
- line 190, `baas-<accountId>[-dev]`;
- line 205, "~3.9 KB", with the held ceiling;
- lines 238-245, the per-person `BaasCliDeployerPolicy-alice` naming, replaced by D2's per-deployment
  name, together with the paragraph about per-caller scoping.

The CI stack's section (line 132's `--deployment baas-ci` included) is left to `interactive-setup`,
which deletes that stack.

## Out of scope

- The interactive walkthrough, `--non-interactive`, `--build-image`, the OIDC provider and the
  next-step hints (E2): all `interactive-setup`.
- An `admin policy` noun or any other command printing the policy (D11 stands).

## Findings this closes or touches

- None in `docs/review/open-findings.md` by ID. The stale-policy misses were never filed as one
  finding. D4 closes a spec/code divergence found here.

## Open for the proposal

- The simulator spike under D1.
- Whether D3's hint can tell a stale deployer policy from a deployer that never had one (both are a
  denial on the same ARN). If not, the hint names both.
