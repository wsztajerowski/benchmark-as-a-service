## Context

`baas-cli-core-ci-split` delivered the core/CI stack split and the deployer/operator identity model, but its manual-verification tasks (9.2 `baas admin setup` + `baas run`, 9.3 `baas admin teardown`) are still unchecked. A desk review of the templates and policies against the two command workflows found failures that would have surfaced on first contact with a real account.

## Goals / Non-Goals

**Goals:**
- Every AWS API call each command makes is actually permitted by the identity that command documents.
- Infra data files get test coverage, so this class of gap fails in CI rather than against a scratch account.
- The operator/deployer boundary is enforced by code, not just described in a README.

**Non-Goals:**
- Not adding a NAT gateway or moving to a private subnet (see Decision 1).
- Not changing the ARN-derived prefix scheme, the stack split, or the command grouping.
- Not automating the Atlas IP Access List.

## Decisions

### 1. Open 27017 egress rather than adding a NAT gateway
**Decision:** Add TCP 27017 to `RunnerSecurityGroup`'s egress to `0.0.0.0/0`, keeping the public-subnet model.
**Alternative considered:** Private subnet + NAT gateway + Elastic IP, giving a single stable egress address to pin in the Atlas IP Access List. Rejected for v1: ~$32/month standing cost plus data processing, incurred whether or not any benchmark runs, to protect a database that is already TLS-authenticated. The honest consequence — an ephemeral per-run public IP means the Atlas allowlist has to be `0.0.0.0/0` — gets documented in `infra/README.md` rather than papered over. `docs/redesign.md:65` already parks the private-networking profile as a future option; this keeps it there.

### 2. `operatorProfile` as a separate config field, with no fallback to `profile`
**Decision:** `config.yaml` gains `aws.operatorProfile`. `run`/`results`/`config show` call `resolveOperatorProfile()`, which returns `operatorProfile` or `null`. `null` means "default credential chain", so `AWS_PROFILE` works. It deliberately does **not** fall back to `aws.profile`.
**Alternative considered:** Falling back to `aws.profile` when `operatorProfile` is unset, for backward compatibility. Rejected — that fallback *is* the bug: `admin setup --aws-profile baas-deployer` writes the deployer profile into `aws.profile`, so falling back means `baas run` silently executes with `iam:CreateRole` and `cloudformation:*` in hand. Per `baas-cli-core-ci-split/design.md:59` no live deployment exists yet, so there is no compatibility debt to service. Commands print a one-line warning when falling through to the default chain, and `admin setup` prints the exact `baas config set --operator-profile` command to run.
**Alternative considered:** A `--aws-profile` flag on every command. Rejected as the primary mechanism — a flag is opt-in per invocation, so forgetting it silently reverts to deployer credentials. A persisted field fails safe.

### 3. Infra data files are tested via `<testResources>`, not `<resources>`
**Decision:** `baas-cli/pom.xml` gets a `<testResources>` entry copying `cf-template-ci.yaml`, `deployer-policy.json`, and `operator-policy.json` into `target/test-classes/infra/`. The core template is already on the test classpath via the existing main-resources entry.
**Alternative considered:** Adding them to `<resources>` alongside the core template. Rejected — it would ship the CI template inside the JAR, violating the scenario at `baas-cli-core-ci-split/specs/core-stack-provisioning/spec.md:17-19`.
**Alternative considered:** Reading the files with a relative `../infra/` path from the test. Rejected — breaks when tests run from a different working directory, and hides the files from the classpath abstraction the production code already uses.

### 4. CloudFormation intrinsics are collapsed, not resolved, for testing
**Decision:** `InfraFixtures` loads templates through SnakeYAML with an undefined-tag constructor that collapses `!Sub`/`!Ref`/`!GetAtt`/`!If` to their argument value.
**Alternative considered:** `cfn-lint`/`aws cloudformation validate-template` in CI. Rejected as the primary mechanism — `validate-template` needs credentials, and neither tool asserts project-specific invariants like "egress includes 27017" or "the operator policy and the reference JSON grant the same actions". Worth adding alongside later; not a substitute.

### 5. Deployer policy IAM scoping uses role-name patterns, not a permissions boundary
**Decision:** Scope `iam:CreateRole`/`PutRolePolicy`/etc. to `arn:aws:iam::*:role/*-runner-role` and `*-operator-role`.
**Alternative considered:** A permissions boundary attached to created roles. Rejected for this change — boundaries need `iam:PutRolePermissionsBoundary` plus a managed boundary policy created out-of-band, expanding exactly the bootstrap problem the deployer policy already suffers from. Name-pattern scoping closes the escalation path with no new bootstrap step. Revisit if role naming ever becomes user-controlled.

## Risks / Trade-offs

- **[Risk] Removing the `aws.profile` fallback breaks any existing local `config.yaml`** → Mitigation: `run`/`results`/`config show` print exactly what to run (`baas config set --operator-profile <name>`), and `admin setup` prints it on every deploy. No live deployment exists per the previous change's assessment.
- **[Risk] `DeletionPolicy: Retain` means `baas admin teardown` leaves an orphaned bucket that a later `baas admin setup` cannot recreate under the same name** → Mitigation: teardown already prints the retained bucket name and tells the user to delete it manually; `--delete-bucket` empties and removes it before the stack delete, and Task 5 makes that path actually work on a versioned bucket.
- **[Trade-off] Collapsing intrinsics means template tests assert on argument values, not resolved values** — e.g. a `!Sub` resource ARN reads as its literal template string. Accepted: the assertions this plan needs are structural.
- **[Risk] The IAM read-back actions added to the deployer policy are inferred from CloudFormation resource-handler behavior, not from an observed failure** → Mitigation: they are additive and low-risk; the manual scratch-account run in Task 19 is what confirms the set is complete.

## Open Questions

None outstanding.
