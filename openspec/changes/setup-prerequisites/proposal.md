# Proposal

## Why

Setup's preflight checks eight hand-picked actions. Three times a release has added a deployer
statement it never checked, most recently 7.1.0's `sts:AssumeRole`: a stale policy passed setup, and
every day-to-day command then failed. Nothing offered the new document, because the policy is printed
only when the preflight finds a gap. This change makes setup's permission handling exact and its
inputs smaller, as the base the `interactive-setup` walkthrough builds on. It also drops an
existing-VPC path no deployment uses, and stops `jobs diff` reporting a few KiB of memory as a
difference.

## What Changes

- **The preflight probes what the rendered policy grants.** Every unconditioned statement of the
  rendered deployer policy is simulated as written, its actions against its resources, instead of a
  hand-maintained list. A statement added in a later release is checked in that release. Simulation
  of wildcard actions (`ec2:Describe*`) and pattern resources (`…:<prefix>-*`) was confirmed live on
  2026-10-13.
- **`--policy-file <path>`** on `baas admin deployment setup` writes the rendered policy to the path
  before the preflight's result is acted on, then setup continues as without it: deploys when rights
  are present or cannot be checked, and stops having created nothing when they are missing. Setup
  prints the IAM administrator's commands filled in, named `BaasCliDeployerPolicy-<deployment>`:
  `create-policy` plus `attach-user-policy` for a first attachment, `create-policy-version
  --set-as-default` for an upgrade. The policy still goes to standard output on a shortfall, so
  `setup > policy.json` is unchanged.
- **A permission failure reported in stack events comes with the policy**, as an SDK-level
  `AccessDenied` already does. This brings the code in line with the existing requirement.
- **A day-to-day command refused `AssumeRole` from the deployer profile says why**: the attached
  deployer policy may predate this CLI, together with the setup command (with `--policy-file`) that
  renders the current one.
- **BREAKING: existing-VPC support is removed.** `--use-existing-vpc`, `--vpc-id`, `--subnet-id` and
  `--sg-id` go from setup, with the update-time networking comparison. The template's
  `UseExistingVpc` and `Existing*` parameters and their conditions go too, so every deployment gets
  the stack's own VPC. Released as a feature release, with the removed flags named in the release
  note (user, 2026-10-13): no deployment or script uses them, as with `derived-deployment-config`'s
  removals.
- **`baas jobs diff` compares memory in MiB.** The `memory` group's KiB fields (`totalKb`,
  `swapTotalKb`) are compared, and printed when they differ, in MiB. `environment.json` keeps the raw
  values.
- `infra/README.md`'s deployer-policy section is corrected: `--stack-name` rather than
  `--deployment` in its `aws cloudformation` commands, the current prefix rule and policy size, and
  per-deployment rather than per-person policy names.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `core-stack-provisioning`:
  - the preflight requirement gains the derived probes, `--policy-file`, the printed commands and the
    stack-event case;
  - *VPC parameters are immutable once the deployment exists* is removed;
  - *The image build and the runner do not share a security group* is removed and re-added, without
    its existing-VPC case, as *The image build and the runner use separate groups in the stack's own
    VPC* (OpenSpec cannot drop a scenario from a modified requirement);
  - *Day-to-day commands resolve operator credentials* gains the stale-policy hint.
- `runner-image-provisioning`: *Two jobs' environments are compared group by group* compares memory
  in MiB.

## Impact

- **Code (`baas-cli`):**
  - `DeployerPreflight`: probes from the rendered document.
  - `SetupCommand`: `--policy-file`, the printed commands, the stack-event case, and the networking
    options and their checks removed.
  - `CloudFormationService`: the root-cause event exposed to the permission check, and the
    networking parameters removed.
  - `OperatorSession`: the hint.
  - `EnvironmentManifest` / `JobsDiffSubcommand`: MiB.
- **Tests:** `SetupCommandTest`, `CoreTemplateTest`, `DeployerPreflight`'s tests, `OperatorSessionTest`,
  `EnvironmentManifestTest`.
- **Template:** `infra/cf-template-core.yaml` loses four parameters and their conditions; its
  networking resources become unconditional.
- **Docs:**
  - CLAUDE.md: *Networking is immutable once a deployment exists*, the `--use-existing-vpc`
    sentence under the security-group rule, and the core stack's description.
  - `infra/README.md`.
  - Diagrams `baas-setup.mmd`, `baas-lifecycle.mmd` and `baas-build-image.mmd`.
  - `docs/review/open-findings.md`.
- **Findings closed:** jobs-command/W1 (`memory.totalKb` noise in `jobs diff`).
- **Cost:** none. No AWS resource is added. Both live stacks were created with
  `UseExistingVpc=false` (checked 2026-10-13), so on their next update the networking resources keep
  their logical IDs and nothing is replaced. The preflight makes more `SimulatePrincipalPolicy` calls
  (one per probed statement, unbilled).
- **IAM:** none. The deployer policy's content is unchanged; only how setup checks it changes.
- **Comparability:** none for measurements: nothing reaches user-data, the runner, the image or a
  stored item. `jobs diff` reports less: two environments a few KiB apart in memory are no longer
  called different.
- **Deliberately not changed:**
  - no separate command prints the policy (`jobs-command` D11);
  - the preflight stays an affordance, not a control;
  - conditioned statements (EC2's `aws:RequestedRegion`, the service-linked role's
    `iam:AWSServiceName`) stay unprobed, as today;
  - `RunnerSecurityGroup`'s `GroupDescription` is untouched (editing it replaces the group);
  - setup never deletes a leftover;
  - `environment.json` and `jobs show` keep raw KiB;
  - `private-runner-network` (parked) is not updated.
