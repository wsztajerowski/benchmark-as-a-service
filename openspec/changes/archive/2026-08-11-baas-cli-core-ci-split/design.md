## Context

`baas-cli` (Phase 1/2 of the earlier `docs/redesign.md` migration) already replaced the GitHub Actions `workflow_dispatch` trigger with a self-contained Java CLI that provisions its own CloudFormation stack, runs benchmarks on EC2, and tears everything down. That stack currently bundles GitHub OIDC/`WorkflowRole` resources alongside the VPC/S3/`RunnerRole` resources the CLI itself needs, because the GHA CI path (`benchmark-runner.yml`, `e2e-cloud-test.yml`) still depends on the same stack's outputs.

Uncommitted work already on this branch (`infra/cf-template-core.yaml`, `infra/cf-template-ci.yaml`, `infra/deployer-policy.json`) splits these concerns. It is partly wired into `baas-cli` already — `SetupCommand` loads the core template (which `pom.xml` sources from `infra/`) and no longer takes GitHub/OIDC options — but the command grouping, the config-field rename, and an unresolved bucket-naming inconsistency between the `S3MainBucket` resource and `RunnerRole`'s S3 policy statement remain.

## Goals / Non-Goals

**Goals:**
- The local `baas` CLI never creates, reads, updates, or deletes anything related to GitHub OIDC/`WorkflowRole` — that's a hard security boundary, not a convenience split.
- Command names make elevated-privilege operations visually distinct from routine ones.
- Preserve all working behavior from the current implementation (networking model, MongoDB flow, EC2 watchdog layers, jar delivery) — this change touches only stack topology, command grouping, the operator/deployer IAM identity model, and config schema naming.

**Non-Goals:**
- Not reconsidering CloudFormation as the IaC tool (Terraform stays out of scope, despite the `agents/terraform-template-baas-installation` exploration branch).
- Not changing MongoDB provisioning model, networking model (public-subnet outbound-only stays default), or benchmark-runner.jar delivery mechanism.
- Not building an interactive setup wizard or enforcing `config set` before `admin setup` — `admin setup` stays self-sufficient.
- Not building the CLI distribution mechanism (install.sh/Homebrew) in this change — that was previously confirmed as still-desired but is separate implementation work, not blocked by this one.
- Not touching `RunCommand`/`ResultsCommand`/`ConfigSetSubcommand`/`ConfigShowSubcommand` internals beyond the `stackName` → `coreStackName` field rename.

## Decisions

### 1. Two independent CloudFormation stacks, not one
**Decision:** `cf-template-core.yaml` (VPC/S3/RunnerRole — what `baas-cli` needs) and `cf-template-ci.yaml` (OIDC/WorkflowRole — GHA-only) are separate stacks with no shared lifecycle. The CI stack takes `RunnerRoleArn`/`BucketName` as plain input parameters (cross-stack reference via manual value passing, not `Fn::ImportValue`), so it can be deployed independently after the core stack exists.
**Alternative considered:** Single unified stack (current/original `redesign.md` design). Rejected because it forces the local CLI's IAM identity to have `iam:CreateOIDCProvider`/`WorkflowRole` create-update-delete permissions even though it never uses them — the local tool's blast radius should be limited to what it actually operates.

### 2. CI stack is 100% outside `baas-cli`'s code
**Decision:** No `baas` command ever creates, updates, deletes, or even reads the CI stack. It's deployed via plain `aws cloudformation deploy` by whoever operates GHA, documented in `infra/README.md`.
**Alternative considered:** A read-only `baas admin ci-status` command to check CI stack outputs. Rejected as unnecessary — it would still require the CLI's IAM identity to have `cloudformation:DescribeStacks` scoped to the CI stack, reintroducing exactly the coupling this change removes, for a capability nobody asked for.

### 3. `admin` subcommand group for deployer-privileged commands
**Decision:** `baas admin setup` / `baas admin teardown`, with `run`/`results`/`config` staying top-level. This makes the required IAM policy self-evident from the command name.
**Alternative considered:** Flat command list with renamed verbs (e.g. `baas provision`/`baas deprovision`). Rejected — a nested group scales better if more admin-only commands are added later, and picocli supports it natively without extra parsing logic.

### 4. Operator identity is an assumable role created by the stack; deployer stays a static, out-of-band policy
**Decision:** `BaasCliDeployerPolicy` (matches `infra/deployer-policy.json`) is created manually, before the core stack exists — CloudFormation can never grant permission to create CloudFormation stacks, so this policy has an unavoidable bootstrap step. `BaasCliOperatorRole` has no such problem: by the time the core stack is deployed, the deployer already holds deployer privileges, so the core stack creates the operator identity itself, as an `OperatorRole` (`AWS::IAM::Role`) resource in `cf-template-core.yaml` — not a policy meant to be attached to a human's IAM user. Its trust policy allows the account root (`arn:aws:iam::${AWS::AccountId}:root`), keeping `baas admin setup` parameter-free; actual gating of who can use it happens per-identity, via an `sts:AssumeRole` grant on that specific role ARN (documented in `infra/README.md`). `baas admin setup` prints the role's ARN, which is also a stack output (`OperatorRoleArn`).
**Alternative considered:** `OperatorPolicy` as an `AWS::IAM::ManagedPolicy`, attached directly to a human's IAM user. Rejected: it leaves standing, non-expiring access sitting on a user's long-lived access keys, is inconsistent with how every other actor in this design (`RunnerRole`, `WorkflowRole`) is modeled as an assumable role rather than a directly-attached policy, and it required extra deployer permissions (`iam:CreatePolicy`/`CreatePolicyVersion`/`DeletePolicy`/etc.) that a Role doesn't need — it reuses the `iam:CreateRole`/`PutRolePolicy` actions already granted for `RunnerRole`.
**Alternative considered:** Single policy covering everything. Rejected — would mean every day-to-day user permanently holds `iam:CreateRole`/`cloudformation:*`, which is disproportionate standing privilege for running benchmarks.

### 5. `admin setup` stays self-sufficient (no forced `config set` first)
**Decision:** `baas admin setup [--region ...] [--aws-profile ...] [--mongo-uri ...]` works standalone with defaults and writes `config.yaml` itself (the resource prefix is derived from the caller's ARN — see Decision 8 — not passed as a flag). `config set`/`config show` remain independent, usable before or after.
**Alternative considered:** Require `~/.baas/config.yaml` to exist before `admin setup` will run (forcing an explicit `config set` step first). Rejected — adds friction to the first-run path without a corresponding safety benefit (unlike the install-vs-setup split, where the risk was a *silent* cost-incurring action inside what looks like a simple install; here the user is already explicitly invoking a command named `admin setup`).

### 6. Bucket naming: keep `baas-${ResourceNamePrefix}`, fix the policy instead
**Decision:** `S3MainBucket`'s existing WIP name (`baas-${ResourceNamePrefix}`) is kept; `RunnerRole`'s S3 policy statement (currently `${ResourceNamePrefix}-main`) is corrected to match it.
**Alternative considered:** Rename the bucket to `${ResourceNamePrefix}-main` (matching `docs/redesign.md` and the policy as originally written). Rejected in favor of keeping the WIP template's convention, since `ResourceNamePrefix` is meant to vary per deployment (e.g. `dev`, `prod`) while `baas-` is a fixed namespace prefix across all such deployments.

### 7. `config.yaml`: `stackName` → `coreStackName`
**Decision:** Renamed field, to make explicit that it only ever refers to the core stack — there is no config field for the CI stack, since the CLI has no relationship to it. `baas admin setup` sets it to `baas-<prefix>` (Decision 8); the static default literal is only a placeholder.

### 8. Resource prefix and core-stack name are derived from the caller's AWS identity
**Decision:** `baas admin setup` derives the resource prefix deterministically from the caller's ARN (`lowercase(base32(sha256(arn)))[0:8]`) instead of accepting a `--prefix` flag, and names the core stack `baas-<prefix>`. The prefix flows into `ResourceNamePrefix` (all stack/resource names) and the SSM mongo connection-string path.
**Alternative considered:** A user-supplied `--prefix`/`--stack-name` on setup (as earlier drafts of this plan assumed). Rejected — deriving from identity gives one stable stack per AWS principal with no name collisions between developers sharing an account, and removes a footgun where two users pick the same prefix and clobber each other's stack. `--stack-name` survives only on `teardown`/`config set` for targeting an already-named stack.

## Risks / Trade-offs

- **[Risk] Two stacks means two deploy operations for anyone setting up both CLI and CI paths from scratch** → Mitigation: `infra/README.md` documents the two-command sequence (`baas admin setup` then `aws cloudformation deploy --template-file cf-template-ci.yaml ...`); this is a one-time setup cost, not a recurring one.
- **[Risk] `cf-template-ci.yaml`'s `RunnerRoleArn`/`BucketName` parameters must be kept in sync with core-stack outputs manually** (no `Fn::ImportValue`, since these are two unrelated stacks with independent lifecycles) → Mitigation: `baas admin setup`'s existing output-printing behavior already surfaces `RunnerRoleArn`/`BucketName`; document copying them into the CI stack's parameter overrides.
- **[Risk] Existing deployments on the old single-stack `cf-template-main.yaml` need a migration path** → Mitigation: out of scope for this change's implementation (no live production stack depends on this yet per current repo state — `cf-template-main.yaml` exists on disk but the WIP core/ci split was never deployed); if a live migration is needed later, `docs/aws-migration-plan.md`'s phased-rollout pattern (deploy new stack alongside old, verify, then delete old) applies directly.
- **[Trade-off] Losing single-stack simplicity** (one `describe-stacks` call showed everything) in exchange for the security boundary — accepted as the explicit goal of this change.
- **[Risk] `deployer-policy.json`'s initial action list was insufficient for `baas admin teardown` to actually run** — manual verification against a real scratch AWS account found `baas admin teardown` crashes at its very first safety gate (`ec2:DescribeInstances`, needed to check for active runs) and would also fail at the SSM cleanup step (`ssm:DeleteParameter`) and be unable to delete the stack at all (`cloudformation:DeleteStack` was missing) → Mitigation: all three actions added to `infra/deployer-policy.json`; this class of gap is exactly why task 9.3 (manual teardown verification) exists as a required task before this change is considered done, not just unit tests against mocked AWS calls.
- **[Risk] `BaasCliOperatorRole` is unattached by design — if nobody grants `sts:AssumeRole` on it, `baas run` fails** → Mitigation: `baas admin setup` prints the role's ARN and the exact `sts:AssumeRole` grant + profile config needed, and `infra/README.md` documents the same steps.

## Migration Plan

1. Commit the WIP `infra/cf-template-core.yaml` (with the bucket-policy fix), `infra/cf-template-ci.yaml`, `infra/deployer-policy.json`.
2. Update `SetupCommand`/`TeardownCommand`: move to `commands/admin/` package, register under a new `AdminCommand` picocli subcommand group in `BaasApp.java`, drop all GitHub/OIDC options and messaging.
3. Point the JAR at `infra/cf-template-core.yaml` as the single source of truth: delete the old `resources/templates/cf-template-main.yaml`, add a `pom.xml` resource entry copying `infra/cf-template-core.yaml` into `templates/`, and update `loadTemplate()` to read `/templates/cf-template-core.yaml`. *(Done.)*
4. Rename `BaasConfig.AwsConfig.stackName` → `coreStackName` and update all call sites: `SetupCommand`, `TeardownCommand`, `ConfigSetSubcommand` (its `--stack-name` handling), and `ConfigShowSubcommand` (its printed `stackName:` label).
5. Update `infra/README.md` with the two-stack deploy procedure and a pointer to `BaasCliOperatorRole`/`BaasCliDeployerPolicy`.
6. Delete the now-superseded `infra/cf-template-main.yaml` once the core template is confirmed equivalent-or-better (diff the two to confirm no accidental resource loss beyond the intentional OIDC/WorkflowRole removal). *(Done — `infra/cf-template-main.yaml` is deleted.)*
7. No rollback complexity beyond standard CloudFormation stack deletion — this change has not yet been deployed to any real AWS account per current repo state, so there's no live-traffic cutover to sequence.

## Open Questions

None outstanding — all decisions above were confirmed during brainstorming.
