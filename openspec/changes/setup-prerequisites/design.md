# Design

## Context

See proposal.md — Why, and `exploration.md` for the evidence. The current shape, on `next-release`
after v7.1.1:

- `DeployerPreflight.simulateCriticalActions` simulates the eight entries of
  `criticalActionsToResources`, one `SimulatePrincipalPolicy` call per action, against the caller's
  principal (an assumed-role session is resolved to its role ARN first). A caller that may not
  simulate gets no check (`AwsServiceException` → empty list).
- `DeployerPolicyRenderer.render` returns the rendered document as a string; setup prints it through
  `Console` only on a shortfall or an SDK-level `AccessDenied`.
- The rendered policy has 13 statements, all `Allow`, none using `NotAction`/`NotResource`. Nine
  carry no `Condition`. The four conditioned ones are `CloudFormationValidateTemplate`, `EC2`,
  `ImageBuilderRead` and `ImageBuilderServiceLinkedRole`.
- A stack failure surfaces as the `IllegalStateException` built by `CloudFormationService
  .failureReport`, which already isolates the current operation's root-cause event; setup logs its
  message and exits 1.
- `OperatorSession` throws `OperatorRoleException` on a failed assumption, naming the role and
  `sourceDescription()`, which already distinguishes `aws.operatorProfile`, `aws.deployerProfile` and
  the default chain.
- The core template gates eleven networking resources on `CreateNetworking` (`UseExistingVpc=false`),
  and picks subnet and security group with `!If` in the image infrastructure and three outputs.
- `EnvironmentManifest.diff` compares the seven groups field by field as strings.

**Spike (2026-10-13, `baas-admin`, us-east-1):** `SimulatePrincipalPolicy` accepts wildcard action
names and pattern resource ARNs and evaluates them by policy matching. `ec2:Describe*` on `*` →
`allowed`. `imagebuilder:CreateComponent` on `…:component/baas-development-*` → `allowed`.
`cloudformation:CreateStack` on `…:stack/nobody-here/*` → `implicitDeny`. `s3:Get*` on
`arn:aws:s3:::nobody-here` → `implicitDeny`.

## Goals / Non-Goals

**Goals:**
- What the preflight checks cannot drift from what the policy grants.
- A policy that falls short is always handed over: in a file on request, and on every reported
  permission failure, whichever API reported it.

**Non-Goals:**
- Probing conditioned statements (would need simulator context entries per condition key).
- Telling which attached policy or statement is stale: the report names missing actions, as today.
- Any change to the deployer policy's content.

## Decisions

### The probe list is the rendered policy's unconditioned statements, simulated as written

`DeployerPreflight` parses the rendered JSON, keeps each `Allow` statement without a `Condition`,
and issues one `SimulatePrincipalPolicy` per **action** of those statements, carrying that statement's
`Resource` list verbatim. An action counts as denied when its overall decision is not `allowed`. The
report keeps today's form, the denied action names, de-duplicated, so the error text and
`refusalForMissingRights` are unchanged.

Per action, and judged on the overall decision, because of task 1.1 (`verify.md`). One call with a
statement's whole action list is rejected (`InvalidInput`: actions "require different authorization
information"). Within one call, a resource whose type the action does not apply to is reported
`implicitDeny` in the resource-specific results while the overall decision stays `allowed`; a real gap
of the right type makes the overall decision a denial. That is about 56 calls for the nine
statements, made sequentially.

The question being asked is whether every statement of the rendered policy is effectively attached.
Simulating each statement against its own resources answers that literally, and the spike shows the
simulator matches wildcards and patterns the way IAM does. A broader attachment (an administrator)
passes, as today.

`criticalActionsToResources` and its tests are deleted. The test that replaces them asserts the probe
set over the rendered fixture: the nine statement IDs, and none of the four conditioned ones.

*Alternatives:*
- **Keep the hand-picked list and add the new actions.** This is what was done three times; the class
  stays open.
- **Expand wildcards into concrete actions, and patterns into names from `DeploymentNames`.** Not
  needed after the spike, and the expansion would itself be a list that drifts.
- **Probe conditioned statements with context entries** (`aws:RequestedRegion`,
  `iam:AWSServiceName`). It is correct in principle, but it is per-key mechanism for four statements
  that have never been the stale ones, and a wrong context entry raises the false alarm the current
  Javadoc warns against.

### `--policy-file` is written right after rendering, and a write failure stops setup

The file is written at the point where the policy is rendered today (after the name validation and the
bucket-region check, before the preflight), overwriting an existing file as a shell redirect would.
When the write fails, setup stops with exit 1 having created nothing: the user asked for the file,
and deploying without it would leave them holding nothing to attach if the deploy then fails on a
permission. Standard output on a shortfall is unchanged, so `> policy.json` still works with or
without the flag.

*Alternative:* write it only on a shortfall. Rejected by the user in exploration ("write and
continue"): an administrator rendering a teammate's policy, and the upgrade case whose failure comes
later from CloudFormation, both need the file when the preflight passes.

### The administrator's commands follow the caller's principal type

Printed on standard error whenever missing rights are reported, filled in from the account, the
deployment and the caller ARN already in hand:

```
aws iam create-policy --policy-name BaasCliDeployerPolicy-<deployment> --policy-document file://<file>
aws iam attach-user-policy --user-name <user> --policy-arn arn:aws:iam::<account>:policy/BaasCliDeployerPolicy-<deployment>
# an upgrade of an existing one:
aws iam create-policy-version --policy-arn arn:aws:iam::<account>:policy/BaasCliDeployerPolicy-<deployment> \
  --policy-document file://<file> --set-as-default
```

`<file>` is the `--policy-file` path, or `policy.json` with a reminder to redirect into it. For an
IAM user the attach line is `attach-user-policy`; for an assumed role it is `attach-role-policy
--role-name <role>`. For a role named `AWSReservedSSO_…` it is instead a note that IAM Identity Center
owns the role, so the policy goes into its permission set. Both variants are printed because the
deployer cannot read customer-managed policies (`iam:GetPolicy` on `policy/*` is not granted) to tell
a first attachment from an upgrade.

A customer-managed policy, not inline: the infra README already says so, and a second deployment
needs it (two rendered documents exceed the shared 5120-character inline budget).

*Alternative:* print the commands in the README only. That is today, and the README's own example is
stale (`BaasCliDeployerPolicy-alice`).

### A stack failure is a permission failure when its root-cause reason says so

`failureReport` already selects the current operation's root-cause events. It additionally reports
whether any of them reads as a permission failure: the reason, lower-cased, contains `not
authorized to perform`, `accessdenied`, `access denied` or `unauthorizedoperation`. These are the IAM,
EC2 and S3 shapes CloudFormation relays from resource calls (task 1.2). Setup then appends what the SDK path appends: the rendered policy on standard output, or the
file's path when `--policy-file` was given. `CloudFormationService` returns a small failure value
(message plus that flag) instead of only an exception message, so `SetupCommand` decides what to
print and the service prints nothing.

*Alternative:* print the policy on every stack failure. Rejected: most failures (a taken name, a quota,
an Image Builder version clash) have nothing to do with the policy, and printing it would point the
reader at the wrong cause.

### The stale-policy hint is added where the failure is already built

`OperatorSession`'s `OperatorRoleException` message gains one paragraph when the source is
`aws.deployerProfile` and the failure is an access denial. It says the deployer policy attached to
that identity may predate this CLI (7.1 added `AssumeOwnOperatorRole`), and gives `baas --deployment
<name> admin deployment setup --policy-file <name>-deployer-policy.json`. The existing advice (ask the
admin, or choose another source) stays. A denial from `aws.operatorProfile` or the default chain is
unchanged: there the missing grant is the teammate's own, which the deployer policy does not hold.

### Existing-VPC support is removed from CLI, template and specs together

- **CLI:** the four options, `validateNetworkingOptions`, the update-time networking comparison and
  their parameters to `CloudFormationService` go.
- **Template:**
  - the four parameters and `CreateNetworking` go;
  - the eleven `Condition: CreateNetworking` lines are deleted;
  - each `!If [CreateNetworking, X, Existing…]` becomes `!Ref X`;
  - output names and logical IDs stay.

  A stack created with `UseExistingVpc=false` (both live ones) therefore sees an update that removes
  parameters and conditions but no resource, provided setup stops sending the removed keys (next
  decision).
- `RunnerSecurityGroup`'s `GroupDescription` (it still says "443/80") is not touched.
- **Specs:** the spec deltas remove *The image build and the runner do not share a security group*
  and re-add it under a new name without the existing-VPC case. OpenSpec refuses to drop a scenario
  from a MODIFIED requirement at both validate and archive time, so a REMOVED + ADDED pair is the
  supported form.

*Alternative:* keep the template parameters but drop the CLI flags. Rejected: dead template branches
are what `CoreTemplateTest` would then have to keep pinning, and a hand-deployed stack could still
reach them.

### An update carries forward only the parameters the new template declares

`updateStackParameters` builds its carried-forward set from the deployed stack's parameters. A
deployed key the new template no longer declares (`UseExistingVpc` and the three `Existing*` keys,
after this change) would be sent as `UsePreviousValue`, and CloudFormation refuses the whole update
(`Parameters: [...] do not exist in the template`, task 1.3). The set becomes the deployed keys the
new template declares, plus the changed ones. `CloudFormationService` reads the declared keys from
the template body's top-level `Parameters:` block, the bundled template being the only one it ever
submits. A test pins that read against the full YAML parse the template tests already use.

Without this, the removal would break the first setup and the first `admin image build` on every
existing deployment. With it, a later template that removes a parameter needs no special handling
either.

*Alternative:* `cloudformation:GetTemplateSummary`, which returns the declared parameters. Rejected:
it adds an action to the deployer policy, which this change leaves unchanged, and it is one more call
for something the CLI holds locally.

### `jobs diff` normalises the memory group before comparing

`EnvironmentManifest.diff` maps `memory.totalKb` and `memory.swapTotalKb` on both sides to
`<KiB / 1024, rounded down> MiB` before the string comparison, and prints the normalised values.
That is one transformation of the two inputs: the grouping, `Differs in:`, ordering and payload code
are untouched. A value that does not parse as a number is compared as is, so a malformed manifest
still shows what it holds. `jobs show` and `environment.json` keep KiB.

*Alternatives:*
- **A tolerance band (say ±1 MiB).** It removes the boundary case below, but it is a second rule to
  explain for a rare and harmless outcome.
- **Leaving `totalKb` out of the verdict while still printing it** (the other candidate in W1). That
  hides a real memory difference between instance types, which MiB keeps.

## Risks / Trade-offs

- [About 56 simulator calls instead of 8] → Setup runs once per deployment change; the calls are
  unbilled, and the time they add is measured in the live check. A throttling error falls into the existing "cannot simulate" path (no
  check), which is the documented fail-open stance of an affordance.
- [A broader attachment still passes] → Intended: the preflight asks whether the caller *can*, not
  whether it holds this exact document.
- [A permission failure phrased differently by some resource handler] → It is reported as a plain
  stack failure, as today; nothing regresses. The two matched shapes come from observed failures
  (`iam:UpdateRole`, `iam:CreateServiceLinkedRole`).
- [MiB rounding straddles a boundary] → Two values a few KiB apart either side of a MiB boundary
  still differ, about 1 in 128 pairs for an 8 KiB gap. Accepted as harmless: the diff then shows two
  MiB values one apart.
- [An existing-VPC deployment unknown to us] → Its next setup would make CloudFormation *create* the
  now-unconditional VPC resources in its stack, and drop the supplied subnet and group. Its
  networking would move to a new VPC rather than be deleted. None is known (both live stacks
  checked), and the release note names the removal.

## Migration Plan

1. Ship as a feature release (user, 2026-10-13); the release note names the four removed flags.
2. Re-run `baas --deployment <name> admin deployment setup` on each deployment with the new CLI. The
   template update drops the networking parameters and conditions and changes no resource. Confirm
   with a change-set preview or the stack events that no networking resource is replaced.
3. Rollback: the previous CLI's template re-adds the parameters with their defaults
   (`UseExistingVpc=false`), again replacing nothing.

## Open Questions

_None._

## Resolved Questions

- **Does `SimulatePrincipalPolicy` accept wildcard actions and pattern resources?** Yes, spiked live
  2026-10-13 (Context).
- **Does an update drop parameters the template no longer declares?** Only if they are not sent;
  setup sent every deployed key, so it now filters them (task 1.3, new decision).
- **One call per statement?** No: mixed actions are rejected, and per-resource results flag type
  mismatches. One call per action, judged on the overall decision (task 1.1).
- **Should `--policy-file` write and continue, or render only?** Write and continue (user,
  2026-10-10).
- **How should the flag removal release?** As a feature release (user, 2026-10-13).
- **MiB or a tolerance for memory?** MiB, rounded down (user, 2026-10-13).
