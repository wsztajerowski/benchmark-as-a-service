# BaaS Infra Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the gaps found in the AWS infrastructure review so that `baas admin setup` → `baas run` → `baas admin teardown` works end to end under the intended two-identity IAM model, without silent failures.

**Architecture:** Three artifact families change together: the CloudFormation core template (`infra/cf-template-core.yaml`), the two IAM policy documents (`infra/deployer-policy.json`, `infra/operator-policy.json`), and the `baas-cli` Java code that consumes them. Because the template and policies are data files with no compiler behind them, this plan introduces a small test fixture layer (`InfraFixtures`) that loads them from the test classpath and asserts on their parsed structure — every infra change in this plan is driven by one of those assertions failing first.

**Tech Stack:** Java 25, Maven multi-module, picocli, AWS SDK v2, Jackson (YAML + JSON), SnakeYAML (transitive via jackson-dataformat-yaml), JUnit 5, AssertJ, TestContainers + LocalStack.

## Global Constraints

- Java 25; source/target set in the root `pom.xml`. Use `switch` pattern matching and records where they fit the surrounding style.
- `infra/cf-template-core.yaml` is the **single source of truth** for the core template. `baas-cli/pom.xml` copies it into the JAR at `templates/cf-template-core.yaml`. Never create a second copy under `src/main/resources/`.
- **The shipped JAR must contain only `cf-template-core.yaml` under `templates/`.** `cf-template-ci.yaml`, `deployer-policy.json`, and `operator-policy.json` must reach the *test* classpath only, via `<testResources>` — an existing spec scenario (`openspec/changes/baas-cli-core-ci-split/specs/core-stack-provisioning/spec.md:17-19`) asserts the CI template is absent from the JAR.
- Resource-name prefix is ARN-derived: `lowercase(base32(sha256(arn)))[0:8]`. Bucket name is `baas-${ResourceNamePrefix}`. Do not change either.
- Tag convention is **dash-separated**: `baas-role`, `baas-request-id`. (Commit `d591931` moved away from the colon form.)
- No new production dependencies. Test-scoped additions are fine.
- Conventional commits (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `chore:`).
- Run `mvn -pl baas-cli test` for unit tests; `mvn -pl baas-cli verify` for integration tests (`*IT.java`, needs Docker).

---

## File Structure

**New files:**

| File | Responsibility |
|---|---|
| `openspec/changes/baas-infra-hardening/{.openspec.yaml,README.md,proposal.md,design.md,tasks.md}` | OpenSpec change tracking this work |
| `openspec/changes/baas-infra-hardening/specs/core-stack-provisioning/spec.md` | Spec deltas amending the capability introduced by `baas-cli-core-ci-split` |
| `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/InfraFixtures.java` | Loads CF templates + IAM policy JSON from the test classpath; collapses CFN short-form intrinsics so a stock YAML parser can read them |
| `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java` | Structural assertions on `cf-template-core.yaml` |
| `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/DeployerPolicyTest.java` | Action/resource assertions on `deployer-policy.json` |
| `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/OperatorPolicyDriftTest.java` | Asserts `operator-policy.json` and the template's `OperatorRole` grant the same action set |
| `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CiTemplateTest.java` | Structural assertions on `cf-template-ci.yaml` |
| `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/S3UploadServiceIT.java` | LocalStack IT proving version-aware bucket emptying |
| `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilderTest.java` | Assertions on the generated user-data script |
| `baas-cli/src/test/java/pl/wsztajerowski/baas/config/OperatorProfileTest.java` | Credential-profile resolution rules |

**Modified files:**

| File | Change |
|---|---|
| `infra/cf-template-core.yaml` | 27017 egress, bucket retain + lifecycle, tag convention, partition, `RunInstances` conditions |
| `infra/deployer-policy.json` | Add `ssm:PutParameter` + S3 delete + IAM read-back; drop OIDC actions; scope resources |
| `infra/operator-policy.json` | Tighten cross-account wildcards; mirror new `RunInstances` conditions |
| `infra/cf-template-ci.yaml` | `ssm:GetParameter` alignment, `s3:GetObject` |
| `infra/README.md` | Operator-profile flow, Atlas allowlist honesty, failure-log location |
| `baas-cli/pom.xml` | `<testResources>` for infra data files; TestContainers test deps |
| `baas-cli/src/main/java/.../config/BaasConfig.java` | `aws.operatorProfile` field + `resolveOperatorProfile()` |
| `baas-cli/src/main/java/.../commands/ConfigSetSubcommand.java` | `--operator-profile` option |
| `baas-cli/src/main/java/.../commands/ConfigShowSubcommand.java` | Print `operatorProfile`; use operator credentials |
| `baas-cli/src/main/java/.../commands/RunCommand.java` | Operator credentials; instance-state check in poll loop |
| `baas-cli/src/main/java/.../commands/ResultsCommand.java` | Operator credentials |
| `baas-cli/src/main/java/.../commands/admin/SetupCommand.java` | Validate Mongo URI first; print operator-profile hint |
| `baas-cli/src/main/java/.../infra/S3UploadService.java` | Version-aware `deleteAllObjects` |
| `baas-cli/src/main/java/.../infra/Ec2ProvisioningService.java` | Tag convention; `describeInstanceState` |
| `baas-cli/src/main/java/.../infra/UserDataScriptBuilder.java` | Ship cloud-init log to S3 before terminate |

---

## Task 1: OpenSpec change scaffold

**Files:**
- Create: `openspec/changes/baas-infra-hardening/.openspec.yaml`
- Create: `openspec/changes/baas-infra-hardening/README.md`
- Create: `openspec/changes/baas-infra-hardening/proposal.md`
- Create: `openspec/changes/baas-infra-hardening/design.md`
- Create: `openspec/changes/baas-infra-hardening/tasks.md`
- Create: `openspec/changes/baas-infra-hardening/specs/core-stack-provisioning/spec.md`

**Interfaces:**
- Consumes: nothing.
- Produces: the change directory that Task 18 syncs back into at the end. No code depends on it.

> **Context for the implementer:** this repo tracks infra work as OpenSpec changes. `openspec/changes/baas-cli-core-ci-split/` is the existing, in-flight change — **do not modify it**; its unchecked tasks 9.2/9.3 (manual scratch-account verification) stay meaningful. This new change layers on top.

- [ ] **Step 1: Create the change metadata**

`openspec/changes/baas-infra-hardening/.openspec.yaml`:

```yaml
schema: spec-driven
created: 2026-07-24
goal: Close the infrastructure review findings — Atlas egress, deployer policy
  gaps, bucket teardown, operator-role enforcement, and run observability
```

`openspec/changes/baas-infra-hardening/README.md`:

```markdown
# baas-infra-hardening

Fix the blocking gaps and security-boundary leaks found reviewing the core stack and the setup/run command workflows
```

- [ ] **Step 2: Write the proposal**

`openspec/changes/baas-infra-hardening/proposal.md`:

```markdown
## Why

Reviewing `baas-cli-core-ci-split`'s delivered infrastructure against the two command workflows it defines (`baas admin setup` under `BaasCliDeployerPolicy`, `baas run` under `BaasCliOperatorRole`) surfaced three hard failures and a security-boundary leak that unit tests could not have caught, because they live in CloudFormation YAML and IAM JSON rather than in Java.

The three hard failures: `RunnerSecurityGroup` allows egress on ports 443/80 only, but MongoDB Atlas listens on 27017, so every run fails at its database write; `deployer-policy.json` lacks `ssm:PutParameter`, so the documented first-run command `baas admin setup --mongo-uri "..."` deploys the stack and then throws `AccessDenied`; and `deployer-policy.json` lacks every S3 delete action while `S3MainBucket` carries no `DeletionPolicy`, so `baas admin teardown` cannot delete the stack in either its default or its `--delete-bucket` mode.

The boundary leak: `RunCommand` reads `aws.profile` from `config.yaml`, which `baas admin setup` populates with the *deployer's* profile. Nothing in the code or docs redirects day-to-day commands at `BaasCliOperatorRole`, so the operator role — the whole point of the previous change's Decision 4 — is never actually assumed on the happy path.

## What Changes

- `RunnerSecurityGroup` gains TCP 27017 egress. The `docs/redesign.md` claim that Atlas is reachable over 443 is corrected.
- `S3MainBucket` gains `DeletionPolicy: Retain` + `UpdateReplacePolicy: Retain`, making the spec's "bucket retained by default" real rather than an accident of a failing delete, plus lifecycle rules bounding noncurrent-version and incomplete-multipart storage growth.
- `S3UploadService.deleteAllObjects` becomes version-aware (`listObjectVersions` + delete markers); the current `listObjectsV2` implementation can never empty a versioned bucket.
- `deployer-policy.json` gains `ssm:PutParameter`, the S3 delete/list/version action set, and the IAM read-back actions CloudFormation's `AWS::IAM::Role` handler calls after create.
- **BREAKING**: `config.yaml` gains `aws.operatorProfile`. `run`/`results`/`config show` resolve credentials from it; `admin setup`/`admin teardown` keep using `aws.profile`. When `operatorProfile` is unset these commands fall through to the default credential chain (honouring `AWS_PROFILE`) and print a one-line warning — they no longer silently inherit the deployer profile.
- **Security**: `deployer-policy.json` drops `iam:CreateOpenIDConnectProvider`, `iam:GetOpenIDConnectProvider`, `iam:TagOpenIDConnectProvider`, and `iam:UpdateAssumeRolePolicy`. These are CI-stack actions that `design.md:25` of the previous change explicitly rejected putting on the local identity. Its `cloudformation:*` and `iam:*` blocks move off `Resource: "*"` onto `stack/baas-*/*` and `role/*-runner-role`/`*-operator-role` patterns, closing a create-role-then-attach-admin escalation path.
- **Observability**: user-data uploads `/var/log/cloud-init-output.log` to the run's S3 prefix before terminating, so a `failed:N` run is diagnosable; `RunCommand`'s poll loop checks instance state, so a boot failure fails in seconds instead of after the full 7500s wall-clock cap.
- `SetupCommand` validates `--mongo-uri` before any AWS call instead of after the stack deploy.
- Hygiene: dash-form tags throughout, `${AWS::Partition}` instead of a literal `arn:aws:`, `ec2:RunInstances` constrained by instance type and region, `operator-policy.json` wildcards pinned to the account, CI template `ssm:GetParameter`/`s3:GetObject` alignment.
- Infra data files (`cf-template-*.yaml`, `*-policy.json`) reach the `baas-cli` **test** classpath via `<testResources>`, enabling structural assertions on them. The shipped JAR is unchanged.

## Capabilities

### Modified Capabilities
- `core-stack-provisioning`: adds requirements for runner egress connectivity, bucket retention and lifecycle, deployer-policy completeness and scoping, operator credential resolution, and run observability.

## Impact

- **Infra**: `infra/cf-template-core.yaml`, `infra/cf-template-ci.yaml`, `infra/deployer-policy.json`, `infra/operator-policy.json`, `infra/README.md`.
- **Code**: `config/BaasConfig.java`, `commands/RunCommand.java`, `commands/ResultsCommand.java`, `commands/ConfigSetSubcommand.java`, `commands/ConfigShowSubcommand.java`, `commands/admin/SetupCommand.java`, `infra/S3UploadService.java`, `infra/Ec2ProvisioningService.java`, `infra/UserDataScriptBuilder.java`, `baas-cli/pom.xml`.
- **Docs**: `docs/redesign.md` (the "outbound 443 reaches Atlas" claim), `docs/aws-migration-plan.md` (the "add the runner's egress IP" instruction, which assumes a stable IP that does not exist).
- **No changes** to `benchmark-runner`, GHA workflow YAML, the ARN-derived prefix scheme, or the public-subnet networking model.
```

- [ ] **Step 3: Write the design decisions**

`openspec/changes/baas-infra-hardening/design.md`:

```markdown
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
```

- [ ] **Step 4: Write the spec deltas**

`openspec/changes/baas-infra-hardening/specs/core-stack-provisioning/spec.md`:

```markdown
## ADDED Requirements

### Requirement: Runner egress reaches MongoDB Atlas
`RunnerSecurityGroup` SHALL permit outbound TCP on port 27017 in addition to 443 and 80, so the benchmark runner can reach a MongoDB Atlas cluster.

#### Scenario: Atlas port is open
- **WHEN** the core stack is deployed with `UseExistingVpc=false`
- **THEN** `RunnerSecurityGroup`'s egress rules include a TCP rule covering port 27017

### Requirement: Working bucket survives stack deletion by default
`S3MainBucket` SHALL declare `DeletionPolicy: Retain` and `UpdateReplacePolicy: Retain`, and SHALL declare lifecycle rules expiring noncurrent versions and aborting incomplete multipart uploads.

#### Scenario: Default teardown retains the bucket by design
- **WHEN** `baas admin teardown --yes` deletes the core stack without `--delete-bucket`
- **THEN** the stack reaches `DELETE_COMPLETE` and the bucket still exists

### Requirement: Bucket emptying handles object versions
`S3UploadService.deleteAllObjects` SHALL delete every object version and delete marker, not only current versions.

#### Scenario: Versioned bucket is fully emptied
- **WHEN** `deleteAllObjects` runs against a versioning-enabled bucket whose keys have multiple versions
- **THEN** a subsequent `listObjectVersions` returns no versions and no delete markers

### Requirement: Deployer policy covers the full setup path
`BaasCliDeployerPolicy` SHALL include `ssm:PutParameter` on the mongo connection-string path, the S3 actions needed to empty and delete the working bucket (`s3:ListBucket`, `s3:ListBucketVersions`, `s3:DeleteObject`, `s3:DeleteObjectVersion`, `s3:DeleteBucket`), and the IAM read-back actions CloudFormation invokes after role creation (`iam:GetRolePolicy`, `iam:ListRolePolicies`, `iam:ListAttachedRolePolicies`).

#### Scenario: Setup with a Mongo URI succeeds end to end
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin setup --mongo-uri "mongodb+srv://user:pass@host/db"`
- **THEN** the stack deploys, the SecureString parameter is written, and the command exits 0

### Requirement: Deployer policy holds no CI-stack permissions
`BaasCliDeployerPolicy` SHALL NOT grant any OIDC-provider action or `iam:UpdateAssumeRolePolicy`.

#### Scenario: No OIDC actions present
- **WHEN** `infra/deployer-policy.json` is inspected
- **THEN** it contains no action beginning `iam:` and ending `OpenIDConnectProvider`, and no `iam:UpdateAssumeRolePolicy`

### Requirement: Deployer policy is resource-scoped
`BaasCliDeployerPolicy`'s CloudFormation statement SHALL be scoped to `stack/baas-*/*` and its IAM statement to `role/*-runner-role`, `role/*-operator-role`, and the corresponding instance-profile ARNs, rather than `Resource: "*"`.

#### Scenario: Cannot create an arbitrarily-named role
- **WHEN** the deployer policy is evaluated for `iam:CreateRole` on `arn:aws:iam::<acct>:role/admin-backdoor`
- **THEN** the request is not permitted

### Requirement: Day-to-day commands resolve operator credentials
`~/.baas/config.yaml` SHALL carry `aws.operatorProfile`. `baas run`, `baas results`, and `baas config show` SHALL resolve AWS credentials from it; `baas admin setup` and `baas admin teardown` SHALL continue using `aws.profile`. When `aws.operatorProfile` is unset, day-to-day commands SHALL fall through to the default credential chain and SHALL NOT use `aws.profile`.

#### Scenario: Deployer profile is never silently reused
- **WHEN** `config.yaml` has `aws.profile: baas-deployer` and no `aws.operatorProfile`, and `baas run jmh` is invoked
- **THEN** the AWS client is built without an explicit profile, and a warning naming `baas config set --operator-profile` is printed

#### Scenario: Operator profile is honoured
- **WHEN** `config.yaml` has `aws.operatorProfile: baas-operator` and `baas run jmh` is invoked
- **THEN** the AWS client is built with the `baas-operator` profile

### Requirement: Operators can bootstrap config without the deployer's machine
`BaasCliOperatorRole` SHALL be granted `cloudformation:DescribeStacks` scoped to its own core stack, and `baas config sync --core-stack-name <name>` SHALL populate `bucket`, `subnetId`, `securityGroupId`, `vpcId`, `runnerInstanceProfileName`, `prefix`, and `coreStackName` in `~/.baas/config.yaml` from that stack's outputs.

#### Scenario: Operator on a fresh machine
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas config sync --core-stack-name baas-a1b2c3d4` with no prior `config.yaml`
- **THEN** `config.yaml` is written with the stack's outputs and `baas run` works without hand-copying any file

### Requirement: Failed runs leave diagnosable output
The user-data script SHALL upload `/var/log/cloud-init-output.log` to `s3://<bucket>/<resultPath>/cloud-init-output.log` before terminating the instance, on both the success and failure paths.

#### Scenario: Log survives self-termination
- **WHEN** a benchmark run exits non-zero and the instance self-terminates
- **THEN** the run's S3 prefix contains `cloud-init-output.log` alongside `run-status`

### Requirement: Poll loop detects a dead instance
`baas run` SHALL check the runner instance's state while polling and SHALL abort with a non-zero exit as soon as the instance reaches `terminated` or `shutting-down` without a `run-status` sentinel, rather than waiting for the wall-clock cap.

#### Scenario: Boot failure fails fast
- **WHEN** the runner instance terminates before writing `run-status`
- **THEN** `baas run` reports the instance state and exits non-zero well before `wallClockHardKillSeconds` elapses

### Requirement: Mongo URI is validated before provisioning
`baas admin setup` SHALL validate `--mongo-uri` before making any AWS API call.

#### Scenario: Bad URI costs no deploy
- **WHEN** `baas admin setup --mongo-uri "mongodb+srv://host"` (no database) is invoked
- **THEN** the command fails with a validation error and no CloudFormation stack operation is started

### Requirement: Operator policy reference copy stays in sync
`infra/operator-policy.json` SHALL grant exactly the same set of actions as the `OperatorRole` inline policies in `infra/cf-template-core.yaml`.

#### Scenario: Drift is caught
- **WHEN** an action is added to `OperatorRole` but not to `operator-policy.json`
- **THEN** the drift test fails
```

- [ ] **Step 5: Write the task list**

`openspec/changes/baas-infra-hardening/tasks.md`:

```markdown
## 1. Test harness for infra data files
- [ ] 1.1 Add `<testResources>` to `baas-cli/pom.xml` copying `cf-template-ci.yaml`, `deployer-policy.json`, `operator-policy.json` into `target/test-classes/infra/`.
- [ ] 1.2 Add `InfraFixtures` (SnakeYAML with an undefined-tag constructor + Jackson JSON loader + policy-action flattener).

## 2. Phase A — blocking fixes
- [ ] 2.1 `RunnerSecurityGroup`: add TCP 27017 egress.
- [ ] 2.2 `deployer-policy.json`: add `ssm:PutParameter`, S3 delete/list/version actions, IAM read-back actions.
- [ ] 2.3 `S3MainBucket`: `DeletionPolicy: Retain` + `UpdateReplacePolicy: Retain`.
- [ ] 2.4 `S3UploadService.deleteAllObjects`: version-aware.
- [ ] 2.5 `SetupCommand`: validate `--mongo-uri` before any AWS call.

## 3. Phase B — security boundary
- [ ] 3.1 `BaasConfig`: `aws.operatorProfile` + `resolveOperatorProfile()`.
- [ ] 3.2 `run`/`results`/`config show` use operator credentials; `admin` commands keep `aws.profile`.
- [ ] 3.3 `deployer-policy.json`: drop OIDC actions and `iam:UpdateAssumeRolePolicy`.
- [ ] 3.4 `deployer-policy.json`: scope CloudFormation and IAM resources.
- [ ] 3.5 `operator-policy.json`: pin account wildcards; add the drift test.
- [ ] 3.6 `baas config sync --core-stack-name <name>` + scoped `cloudformation:DescribeStacks` on `OperatorRole`, so an operator can populate `config.yaml` without hand-copying it.

## 4. Phase C — operability
- [ ] 4.1 User-data ships `/var/log/cloud-init-output.log` to S3 before terminating.
- [ ] 4.2 `RunCommand` poll loop checks instance state.
- [ ] 4.3 `S3MainBucket` lifecycle rules.

## 5. Phase D — hygiene
- [ ] 5.1 Tag convention (`baas-role` on the bucket, `baas-request-id` on instances).
- [ ] 5.2 `${AWS::Partition}` throughout both templates.
- [ ] 5.3 `ec2:RunInstances` instance-type and region conditions.
- [ ] 5.4 CI template `ssm:GetParameter` alignment + `s3:GetObject`.

## 6. Documentation
- [ ] 6.1 `infra/README.md`: operator-profile flow, Atlas allowlist reality, failure-log location.
- [ ] 6.2 Correct the "443 reaches Atlas" claim in `docs/redesign.md` and the "runner's egress IP" instruction in `docs/aws-migration-plan.md`.

## 7. Manual verification
- [ ] 7.1 `baas admin setup --mongo-uri "..."` against a scratch account under the revised deployer policy only.
- [ ] 7.2 `baas run jmh` under an assumed `BaasCliOperatorRole` — confirm the Mongo write succeeds and results appear.
- [ ] 7.3 `baas admin teardown --yes --delete-bucket` — confirm the bucket empties and the stack reaches `DELETE_COMPLETE`.
```

- [ ] **Step 6: Commit**

```bash
git add openspec/changes/baas-infra-hardening
git commit -m "docs: add baas-infra-hardening OpenSpec change"
```

---

## Task 2: Infra test-fixture harness

**Files:**
- Modify: `baas-cli/pom.xml:66-81` (add `<testResources>` after the `</resources>` close tag)
- Create: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/InfraFixtures.java`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces — every later infra task uses these:
  - `InfraFixtures.coreTemplate() -> Map<String, Object>`
  - `InfraFixtures.ciTemplate() -> Map<String, Object>`
  - `InfraFixtures.deployerPolicy() -> Map<String, Object>`
  - `InfraFixtures.operatorPolicy() -> Map<String, Object>`
  - `InfraFixtures.properties(Map<String,Object> template, String logicalId) -> Map<String, Object>`
  - `InfraFixtures.resource(Map<String,Object> template, String logicalId) -> Map<String, Object>`
  - `InfraFixtures.actions(Map<String,Object> policyDocument) -> Set<String>`

> **Context:** `infra/cf-template-core.yaml` already reaches `target/classes/templates/` (main resources, `baas-cli/pom.xml:74-80`), so it is on the test classpath at `/templates/cf-template-core.yaml`. The other three files are not — hence `<testResources>`. SnakeYAML arrives transitively through `jackson-dataformat-yaml` (a compile-scope dependency), so no new dependency is needed.

- [ ] **Step 1: Wire the infra data files onto the test classpath**

In `baas-cli/pom.xml`, immediately after the closing `</resources>` tag (line 81) and before `<plugins>`:

```xml
        <!-- Infra data files reach the TEST classpath only. They must never enter
             the shipped JAR — cf-template-core.yaml is the only bundled template. -->
        <testResources>
            <testResource>
                <directory>src/test/resources</directory>
            </testResource>
            <testResource>
                <directory>${project.basedir}/../infra</directory>
                <targetPath>infra</targetPath>
                <includes>
                    <include>cf-template-ci.yaml</include>
                    <include>deployer-policy.json</include>
                    <include>operator-policy.json</include>
                </includes>
            </testResource>
        </testResources>
```

- [ ] **Step 2: Write the failing test**

`baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java`:

```java
package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoreTemplateTest {

    private final Map<String, Object> template = InfraFixtures.coreTemplate();

    @Test
    void bucketIsNamedFromTheResourcePrefix() {
        Map<String, Object> bucket = InfraFixtures.properties(template, "S3MainBucket");

        assertThat(bucket.get("BucketName")).isEqualTo("baas-${ResourceNamePrefix}");
    }
}
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest`
Expected: FAIL — `InfraFixtures` does not exist (compilation error).

- [ ] **Step 4: Write the fixture loader**

`baas-cli/src/test/java/pl/wsztajerowski/baas/infra/InfraFixtures.java`:

```java
package pl.wsztajerowski.baas.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Loads the CloudFormation templates and IAM policy documents under {@code infra/}
 * from the test classpath, so their structure can be asserted on like any other code.
 */
final class InfraFixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InfraFixtures() {
    }

    static Map<String, Object> coreTemplate() {
        return loadYaml("/templates/cf-template-core.yaml");
    }

    static Map<String, Object> ciTemplate() {
        return loadYaml("/infra/cf-template-ci.yaml");
    }

    static Map<String, Object> deployerPolicy() {
        return loadJson("/infra/deployer-policy.json");
    }

    static Map<String, Object> operatorPolicy() {
        return loadJson("/infra/operator-policy.json");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resource(Map<String, Object> template, String logicalId) {
        var resources = (Map<String, Object>) template.get("Resources");
        var resource = (Map<String, Object>) resources.get(logicalId);
        if (resource == null) {
            throw new AssertionError("No resource '" + logicalId + "' in template. Present: " + resources.keySet());
        }
        return resource;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> properties(Map<String, Object> template, String logicalId) {
        return (Map<String, Object>) resource(template, logicalId).get("Properties");
    }

    /** Flattens every {@code Action} entry (string or list) across a policy document's statements. */
    @SuppressWarnings("unchecked")
    static Set<String> actions(Map<String, Object> policyDocument) {
        var statements = (List<Map<String, Object>>) policyDocument.get("Statement");
        Set<String> actions = new TreeSet<>();
        for (Map<String, Object> statement : statements) {
            Object action = statement.get("Action");
            if (action instanceof String single) {
                actions.add(single);
            } else {
                actions.addAll((List<String>) action);
            }
        }
        return actions;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String classpathResource) {
        try (InputStream is = open(classpathResource)) {
            return new Yaml(new IntrinsicTolerantConstructor()).load(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadJson(String classpathResource) {
        try (InputStream is = open(classpathResource)) {
            return JSON.readValue(is, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream open(String classpathResource) {
        InputStream is = InfraFixtures.class.getResourceAsStream(classpathResource);
        if (is == null) {
            throw new AssertionError(
                classpathResource + " is not on the test classpath — check the <testResources> block in baas-cli/pom.xml");
        }
        return is;
    }

    /**
     * CloudFormation's short-form intrinsics (!Sub, !Ref, !GetAtt, !If, !Select, !GetAZs)
     * are unknown tags to a stock YAML parser. Collapse each to its argument value, which
     * is enough to assert on template structure.
     */
    private static class IntrinsicTolerantConstructor extends SafeConstructor {

        IntrinsicTolerantConstructor() {
            super(new LoaderOptions());
            yamlConstructors.put(null, new CollapseIntrinsic());
        }

        private class CollapseIntrinsic extends AbstractConstruct {
            @Override
            public Object construct(Node node) {
                return switch (node) {
                    case ScalarNode scalar -> scalar.getValue();
                    case SequenceNode sequence -> constructSequence(sequence);
                    case MappingNode mapping -> constructMapping(mapping);
                    default -> null;
                };
            }
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest`
Expected: PASS.

- [ ] **Step 6: Verify the shipped JAR is still clean**

Run: `mvn -pl baas-cli clean package -DskipTests && unzip -l baas-cli/target/baas-cli.jar | grep templates`
Expected: exactly one line, `templates/cf-template-core.yaml`. No `cf-template-ci.yaml`, no `*-policy.json`.

- [ ] **Step 7: Commit**

```bash
git add baas-cli/pom.xml baas-cli/src/test/java/pl/wsztajerowski/baas/infra/InfraFixtures.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java
git commit -m "test: load infra templates and IAM policies from the test classpath"
```

---

# Phase A — Blocking fixes

## Task 3: Runner egress reaches Atlas on 27017

**Files:**
- Modify: `infra/cf-template-core.yaml:114-124` (`RunnerSecurityGroup.Properties.SecurityGroupEgress`)
- Modify: `docs/redesign.md:57`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java`

**Interfaces:**
- Consumes: `InfraFixtures.properties(...)` from Task 2.
- Produces: nothing new.

> **Why this matters:** MongoDB Atlas clusters listen on **27017**, not 443. The current egress rules allow 443 and 80 only, so `benchmark-runner`'s database write hangs until the driver's server-selection timeout and then fails — on every single run. This never surfaced before because the pre-CLI setup used an externally-managed security group with default allow-all egress.

- [ ] **Step 1: Write the failing test**

Add to `CoreTemplateTest`:

```java
    @Test
    @SuppressWarnings("unchecked")
    void runnerCanReachMongoAtlasOnItsStandardPort() {
        var egress = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "RunnerSecurityGroup").get("SecurityGroupEgress");

        assertThat(egress)
            .as("MongoDB Atlas listens on 27017 — without it every run fails at the database write")
            .anySatisfy(rule -> {
                assertThat(rule.get("IpProtocol")).isEqualTo("tcp");
                assertThat(rule.get("FromPort")).isEqualTo(27017);
                assertThat(rule.get("ToPort")).isEqualTo(27017);
            });
    }
```

Add the imports `java.util.List` and `java.util.Map` to the test if not already present.

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest`
Expected: FAIL — no egress rule matches port 27017.

- [ ] **Step 3: Add the egress rule**

In `infra/cf-template-core.yaml`, replace the `SecurityGroupEgress` block (lines 114-124) with:

```yaml
      SecurityGroupEgress:
        - IpProtocol: tcp
          FromPort: 443
          ToPort: 443
          CidrIp: 0.0.0.0/0
          Description: HTTPS to GitHub, S3 and AWS APIs
        - IpProtocol: tcp
          FromPort: 80
          ToPort: 80
          CidrIp: 0.0.0.0/0
          Description: HTTP for package downloads (yum)
        - IpProtocol: tcp
          FromPort: 27017
          ToPort: 27017
          CidrIp: 0.0.0.0/0
          Description: MongoDB Atlas (Atlas serves clients on 27017, not 443)
```

Note the corrected description on the 443 rule — it previously claimed to cover Atlas.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest`
Expected: PASS.

- [ ] **Step 5: Correct the design doc that carries the same wrong assumption**

In `docs/redesign.md`, line 57, replace:

```
| `RunnerSecurityGroup` | `AWS::EC2::SecurityGroup` | **No inbound rules**; outbound 443 (HTTPS to Atlas/GitHub/AWS APIs) + outbound to S3 prefix list |
```

with:

```
| `RunnerSecurityGroup` | `AWS::EC2::SecurityGroup` | **No inbound rules**; outbound 443 (HTTPS to GitHub/AWS APIs), 80 (yum), 27017 (MongoDB Atlas) + outbound to S3 prefix list |
```

- [ ] **Step 6: Commit**

```bash
git add infra/cf-template-core.yaml docs/redesign.md \
        baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java
git commit -m "fix: allow runner egress to MongoDB Atlas on 27017"
```

---

## Task 4: Deployer policy covers setup and teardown

**Files:**
- Modify: `infra/deployer-policy.json`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/DeployerPolicyTest.java` (create)

**Interfaces:**
- Consumes: `InfraFixtures.deployerPolicy()`, `InfraFixtures.actions(...)` from Task 2.
- Produces: nothing new.

> **Why this matters:** three separate holes. (1) `SetupCommand.java:112-118` calls `ssm:PutParameter`, which the policy does not grant — so the documented first-run command `baas admin setup --mongo-uri "..."` deploys the stack, saves `config.yaml`, then throws `AccessDenied`. (2) The policy grants no S3 delete actions, so `TeardownCommand`'s `deleteAllObjects` fails at its first list call *and* stack deletion fails at `DeleteBucket`. (3) CloudFormation's `AWS::IAM::Role` handler reads inline policies back after create, which needs `iam:GetRolePolicy`/`ListRolePolicies`/`ListAttachedRolePolicies`.

- [ ] **Step 1: Write the failing test**

`baas-cli/src/test/java/pl/wsztajerowski/baas/infra/DeployerPolicyTest.java`:

```java
package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DeployerPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
        // baas admin setup --mongo-uri writes the SecureString parameter
        "ssm:PutParameter",
        // baas admin teardown --delete-bucket empties the bucket, then CloudFormation removes it
        "s3:ListBucket",
        "s3:ListBucketVersions",
        "s3:DeleteObject",
        "s3:DeleteObjectVersion",
        "s3:DeleteBucket",
        // CloudFormation reads an IAM role's inline policies back after creating it
        "iam:GetRolePolicy",
        "iam:ListRolePolicies",
        "iam:ListAttachedRolePolicies"
    })
    void grantsActionNeededBySetupOrTeardown(String requiredAction) {
        assertThat(InfraFixtures.actions(InfraFixtures.deployerPolicy()))
            .contains(requiredAction);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=DeployerPolicyTest`
Expected: FAIL — all nine parameter cases fail.

- [ ] **Step 3: Add the missing actions**

In `infra/deployer-policy.json`, replace the `SSMMongoCleanup` statement (lines 59-64) with one covering both write and delete:

```json
    {
      "Sid": "SsmMongoParameter",
      "Effect": "Allow",
      "Action": [
        "ssm:PutParameter",
        "ssm:DeleteParameter"
      ],
      "Resource": "arn:aws:ssm:*:*:parameter/*/mongo/connection-string"
    },
```

Add the three read-back actions to the `IAM` statement's action list (keep it alphabetically sorted, as the file already is):

```json
        "iam:GetInstanceProfile",
        "iam:GetRole",
        "iam:GetRolePolicy",
        "iam:ListAttachedRolePolicies",
        "iam:ListRolePolicies",
        "iam:PutRolePolicy",
```

Replace the `S3` statement (lines 87-102) with:

```json
    {
      "Sid": "S3WorkingBucketLifecycle",
      "Effect": "Allow",
      "Action": [
        "s3:CreateBucket",
        "s3:DeleteBucket",
        "s3:DeleteObject",
        "s3:DeleteObjectVersion",
        "s3:GetBucketAcl",
        "s3:GetBucketLocation",
        "s3:GetBucketLogging",
        "s3:GetBucketObjectLockConfiguration",
        "s3:GetBucketPolicy",
        "s3:GetBucketPublicAccessBlock",
        "s3:GetBucketTagging",
        "s3:GetBucketVersioning",
        "s3:GetEncryptionConfiguration",
        "s3:GetLifecycleConfiguration",
        "s3:ListBucket",
        "s3:ListBucketVersions",
        "s3:PutBucketPublicAccessBlock",
        "s3:PutBucketTagging",
        "s3:PutBucketVersioning",
        "s3:PutEncryptionConfiguration",
        "s3:PutLifecycleConfiguration"
      ],
      "Resource": [
        "arn:aws:s3:::baas-*",
        "arn:aws:s3:::baas-*/*"
      ]
    }
```

The extra `GetBucket*` actions cover CloudFormation's read-back of the `AWS::S3::Bucket` resource; `PutLifecycleConfiguration`/`GetLifecycleConfiguration` are needed by Task 12. The `Resource` also tightens from `"*"` to the `baas-` bucket namespace.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl baas-cli test -Dtest=DeployerPolicyTest`
Expected: PASS, all nine cases.

- [ ] **Step 5: Commit**

```bash
git add infra/deployer-policy.json \
        baas-cli/src/test/java/pl/wsztajerowski/baas/infra/DeployerPolicyTest.java
git commit -m "fix: grant deployer policy the SSM write and S3 delete actions setup/teardown need"
```

---

## Task 5: Working bucket is retained by design

**Files:**
- Modify: `infra/cf-template-core.yaml:133-152` (`S3MainBucket`)
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java`

**Interfaces:**
- Consumes: `InfraFixtures.resource(...)` from Task 2.
- Produces: nothing new.

> **Why this matters:** `TeardownCommand.java:84-87` prints "S3 results bucket retained", and the previous change's spec requires retention by default. But `S3MainBucket` carries CloudFormation's default `DeletionPolicy: Delete`, so `deleteStack` *tries* to remove the bucket. Retention today is an accident of that delete failing, which leaves the stack in `DELETE_FAILED`.

- [ ] **Step 1: Write the failing test**

Add to `CoreTemplateTest`:

```java
    @Test
    void workingBucketSurvivesStackDeletion() {
        var bucket = InfraFixtures.resource(template, "S3MainBucket");

        assertThat(bucket)
            .as("teardown promises the bucket is retained — that must be declared, not a side effect of a failing delete")
            .containsEntry("DeletionPolicy", "Retain")
            .containsEntry("UpdateReplacePolicy", "Retain");
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest`
Expected: FAIL — neither key is present.

- [ ] **Step 3: Declare the policies**

In `infra/cf-template-core.yaml`, change the `S3MainBucket` header from:

```yaml
  S3MainBucket:
    Type: AWS::S3::Bucket
    Properties:
```

to:

```yaml
  S3MainBucket:
    Type: AWS::S3::Bucket
    # Benchmark history outlives any single stack. `baas admin teardown` deletes the
    # bucket only when explicitly asked (--delete-bucket), which empties it first.
    DeletionPolicy: Retain
    UpdateReplacePolicy: Retain
    Properties:
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add infra/cf-template-core.yaml baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java
git commit -m "fix: retain the working bucket on stack deletion by declaration"
```

---

## Task 6: Version-aware bucket emptying

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/infra/S3UploadService.java:38-42`
- Modify: `baas-cli/pom.xml` (TestContainers test dependencies)
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/S3UploadServiceIT.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `S3UploadService#deleteAllObjects(String bucket)` — unchanged signature, corrected behavior.

> **Why this matters:** `S3MainBucket` has versioning enabled (`cf-template-core.yaml:137-138`). `deleteAllObjects` uses `listObjectsV2`, which returns only *current* versions. Noncurrent versions and delete markers survive, so the bucket never empties and `DeleteBucket` always fails — `baas admin teardown --delete-bucket` cannot work.
>
> This is an integration test because the behavior under test *is* the S3 versioning semantics. The repo's established pattern for that is TestContainers + LocalStack (see `benchmark-runner/src/test/java/pl/wsztajerowski/TestcontainersWithS3BaseIT.java`). Use a modern LocalStack image here — `benchmark-runner` pins `0.12.16`, which predates reliable `list-object-versions` support.

- [ ] **Step 1: Add the TestContainers test dependencies**

In `baas-cli/pom.xml`, after the `assertj-core` dependency (line 63), add:

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-localstack</artifactId>
            <scope>test</scope>
        </dependency>
```

Versions come from the `testcontainers-bom` already managed in the root `pom.xml:68-70`.

In the `<plugins>` block, alongside the shade plugin, add failsafe so `*IT.java` runs on `mvn verify` (its configuration is already in the root pom's `pluginManagement`):

```xml
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
            </plugin>
```

- [ ] **Step 2: Write the failing test**

`baas-cli/src/test/java/pl/wsztajerowski/baas/infra/S3UploadServiceIT.java`:

```java
package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class S3UploadServiceIT {

    @Container
    private static final LocalStackContainer LOCAL_STACK =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.S3);

    private S3Client s3;
    private String bucket;

    @BeforeEach
    void createVersionedBucket() {
        s3 = S3Client.builder()
            .endpointOverride(LOCAL_STACK.getEndpoint())
            .region(Region.of(LOCAL_STACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCAL_STACK.getAccessKey(), LOCAL_STACK.getSecretKey())))
            .forcePathStyle(true)
            .build();

        bucket = "baas-test-" + UUID.randomUUID();
        s3.createBucket(r -> r.bucket(bucket));
        s3.putBucketVersioning(r -> r.bucket(bucket)
            .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
    }

    @Test
    void emptiesAVersionedBucketCompletely() {
        // Three versions of one key, plus a delete marker on a second key.
        for (int i = 0; i < 3; i++) {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key("runs/benchmark.jar").build(),
                RequestBody.fromString("payload-" + i));
        }
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key("runs/result.json").build(),
            RequestBody.fromString("{}"));
        s3.deleteObject(r -> r.bucket(bucket).key("runs/result.json"));

        new S3UploadService(s3).deleteAllObjects(bucket);

        var remaining = s3.listObjectVersions(r -> r.bucket(bucket));
        assertThat(remaining.versions()).isEmpty();
        assertThat(remaining.deleteMarkers()).isEmpty();
    }

    @Test
    void deletingAnEmptyBucketIsANoOp() {
        new S3UploadService(s3).deleteAllObjects(bucket);

        assertThat(s3.listObjectVersions(r -> r.bucket(bucket)).versions()).isEmpty();
    }
}
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `mvn -pl baas-cli verify -Dit.test=S3UploadServiceIT`
Expected: FAIL on `emptiesAVersionedBucketCompletely` — two noncurrent versions and one delete marker remain.

- [ ] **Step 4: Make the deletion version-aware**

In `S3UploadService.java`, replace `deleteAllObjects` (lines 38-42) with:

```java
    /**
     * Removes every object version and delete marker, which is what a versioning-enabled
     * bucket needs before it can be deleted. Listing only current versions leaves
     * noncurrent ones behind and DeleteBucket then fails with BucketNotEmpty.
     */
    public void deleteAllObjects(String bucket) {
        var paginator = s3.listObjectVersionsPaginator(r -> r.bucket(bucket));
        paginator.stream().forEach(page -> {
            page.versions().forEach(version ->
                s3.deleteObject(r -> r.bucket(bucket).key(version.key()).versionId(version.versionId())));
            page.deleteMarkers().forEach(marker ->
                s3.deleteObject(r -> r.bucket(bucket).key(marker.key()).versionId(marker.versionId())));
        });
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl baas-cli verify -Dit.test=S3UploadServiceIT`
Expected: PASS, both tests.

- [ ] **Step 6: Commit**

```bash
git add baas-cli/pom.xml baas-cli/src/main/java/pl/wsztajerowski/baas/infra/S3UploadService.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/infra/S3UploadServiceIT.java
git commit -m "fix: delete every object version when emptying the working bucket"
```

---

## Task 7: Validate the Mongo URI before provisioning

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/admin/SetupCommand.java:49-128`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/commands/admin/SetupCommandTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `SetupCommand.validateMongoUri(String)` becomes package-private `static` so it can be tested directly.

> **Why this matters:** `validateMongoUri` runs at line 113, *after* `createOrUpdateStack` (line 86) and *after* `configService.save` (line 101). A URI missing its database name costs a full CloudFormation deploy before it is rejected.

- [ ] **Step 1: Write the failing test**

Add to `SetupCommandTest`:

```java
    @Test
    void rejectsMongoUriWithoutDatabaseName() {
        assertThatThrownBy(() -> SetupCommand.validateMongoUri("mongodb+srv://user:pass@host"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("database name");
    }

    @Test
    void acceptsMongoUriWithDatabaseName() {
        SetupCommand.validateMongoUri("mongodb+srv://user:pass@host/benchmarks");
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=SetupCommandTest`
Expected: FAIL — `validateMongoUri` is a private instance method, so it is not resolvable statically (compilation error).

- [ ] **Step 3: Make it static and call it first**

In `SetupCommand.java`, change the method signature (line 170) from:

```java
    private void validateMongoUri(String uri) {
```

to:

```java
    static void validateMongoUri(String uri) {
```

Then in `call()`, insert validation as the very first statement (before `configService.load()` at line 51):

```java
    @Override
    public Integer call() throws Exception {
        // Fail before provisioning anything — a bad URI should not cost a stack deploy.
        if (mongoUri != null) {
            validateMongoUri(mongoUri);
        }

        BaasConfig config = configService.load();
```

And remove the now-redundant call at line 113, changing:

```java
        if (mongoUri != null) {
            validateMongoUri(mongoUri);
            try (var ssm = factory.ssm()) {
```

to:

```java
        if (mongoUri != null) {
            try (var ssm = factory.ssm()) {
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl baas-cli test -Dtest=SetupCommandTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-cli/src/main/java/pl/wsztajerowski/baas/commands/admin/SetupCommand.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/commands/admin/SetupCommandTest.java
git commit -m "fix: validate the Mongo URI before deploying the stack"
```

---

# Phase B — Security boundary

## Task 8: `aws.operatorProfile` config field

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/config/BaasConfig.java:26-59`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/config/OperatorProfileTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces — Tasks 9 and 10 depend on these exact signatures:
  - `BaasConfig.AwsConfig#getOperatorProfile() -> String` (nullable)
  - `BaasConfig.AwsConfig#setOperatorProfile(String)`
  - `BaasConfig.AwsConfig#resolveOperatorProfile() -> String` (nullable; **never** falls back to `profile`)

- [ ] **Step 1: Write the failing test**

`baas-cli/src/test/java/pl/wsztajerowski/baas/config/OperatorProfileTest.java`:

```java
package pl.wsztajerowski.baas.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorProfileTest {

    private final ObjectMapper yaml = new ObjectMapper(
        new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void resolvesTheOperatorProfileWhenSet() {
        var aws = new BaasConfig.AwsConfig();
        aws.setProfile("baas-deployer");
        aws.setOperatorProfile("baas-operator");

        assertThat(aws.resolveOperatorProfile()).isEqualTo("baas-operator");
    }

    @Test
    void neverFallsBackToTheDeployerProfile() {
        var aws = new BaasConfig.AwsConfig();
        aws.setProfile("baas-deployer");

        assertThat(aws.resolveOperatorProfile())
            .as("falling back to the deployer profile is exactly the privilege leak this field exists to close")
            .isNull();
    }

    @Test
    void roundTripsOperatorProfile() throws Exception {
        BaasConfig original = new BaasConfig();
        original.getAws().setOperatorProfile("baas-operator");

        String written = yaml.writeValueAsString(original);
        BaasConfig readBack = yaml.readValue(written, BaasConfig.class);

        assertThat(written).contains("operatorProfile: \"baas-operator\"");
        assertThat(readBack.getAws().getOperatorProfile()).isEqualTo("baas-operator");
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=OperatorProfileTest`
Expected: FAIL — `setOperatorProfile`/`resolveOperatorProfile` do not exist (compilation error).

- [ ] **Step 3: Add the field**

In `BaasConfig.AwsConfig`, add the field after `profile` (line 27):

```java
        private String profile;
        private String operatorProfile;
```

And the accessors after `setProfile` (line 37):

```java
        public String getOperatorProfile() { return operatorProfile; }
        public void setOperatorProfile(String operatorProfile) { this.operatorProfile = operatorProfile; }

        /**
         * Credential profile for day-to-day commands (run/results/config show), which are
         * meant to run under BaasCliOperatorRole. Deliberately does NOT fall back to
         * {@link #profile} — that field holds the deployer profile written by
         * `baas admin setup`, and silently reusing it would hand every benchmark run
         * iam:CreateRole and cloudformation:*. A null return means "default credential
         * chain", so AWS_PROFILE still works.
         */
        public String resolveOperatorProfile() { return operatorProfile; }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl baas-cli test -Dtest=OperatorProfileTest`
Expected: PASS, all three.

- [ ] **Step 5: Commit**

```bash
git add baas-cli/src/main/java/pl/wsztajerowski/baas/config/BaasConfig.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/config/OperatorProfileTest.java
git commit -m "feat: add aws.operatorProfile for day-to-day command credentials"
```

---

## Task 9: Day-to-day commands use operator credentials

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java:102`
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/ResultsCommand.java:43`
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/ConfigShowSubcommand.java:26,44`
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/ConfigSetSubcommand.java`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/config/OperatorProfileTest.java`

**Interfaces:**
- Consumes: `AwsConfig#resolveOperatorProfile()`, `getOperatorProfile()`, `setOperatorProfile(String)` from Task 8.
- Produces: `ConfigSetSubcommand --operator-profile <name>`; a static helper `RunCommand.operatorCredentialsWarning(BaasConfig) -> Optional<String>` reused by `ResultsCommand` and `ConfigShowSubcommand`.

> **Why this matters:** `RunCommand.java:102` builds its AWS clients from `config.getAws().getProfile()`. That value is written by `baas admin setup` from the deployer's `--aws-profile`. So on the happy path `baas run` executes with deployer credentials — the operator role the previous change introduced is never assumed.

- [ ] **Step 1: Write the failing test**

Add to `OperatorProfileTest`:

```java
    @Test
    void warnsWhenNoOperatorProfileIsConfigured() {
        BaasConfig config = new BaasConfig();
        config.getAws().setProfile("baas-deployer");

        assertThat(RunCommand.operatorCredentialsWarning(config))
            .hasValueSatisfying(warning ->
                assertThat(warning).contains("baas config set --operator-profile"));
    }

    @Test
    void staysQuietWhenAnOperatorProfileIsConfigured() {
        BaasConfig config = new BaasConfig();
        config.getAws().setOperatorProfile("baas-operator");

        assertThat(RunCommand.operatorCredentialsWarning(config)).isEmpty();
    }
```

Add the import `pl.wsztajerowski.baas.commands.RunCommand`.

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=OperatorProfileTest`
Expected: FAIL — `operatorCredentialsWarning` does not exist (compilation error).

- [ ] **Step 3: Add the helper and switch the three commands over**

In `RunCommand.java`, add the helper as a static method (place it just above `call()`):

```java
    /**
     * `run`/`results`/`config show` are meant to run under BaasCliOperatorRole. When no
     * operator profile is configured they fall through to the default credential chain
     * rather than reusing `aws.profile`, which holds deployer credentials.
     */
    public static Optional<String> operatorCredentialsWarning(BaasConfig config) {
        if (config.getAws().getOperatorProfile() != null) {
            return Optional.empty();
        }
        return Optional.of(
            "No aws.operatorProfile configured — using the default AWS credential chain. "
                + "Set one with: baas config set --operator-profile <profile-name>");
    }
```

Then in `RunCommand.call()`, replace line 102:

```java
        var factory = new AwsClientFactory(config.getAws().getRegion(), config.getAws().getProfile());
```

with:

```java
        operatorCredentialsWarning(config).ifPresent(System.err::println);
        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());
```

In `ResultsCommand.java`, replace line 43 with the same two lines, adding the import `pl.wsztajerowski.baas.commands.RunCommand` is unnecessary (same package) — call `RunCommand.operatorCredentialsWarning(config)` directly.

In `ConfigShowSubcommand.java`, replace line 44:

```java
            var factory = new AwsClientFactory(config.getAws().getRegion(), config.getAws().getProfile());
```

with:

```java
            var factory = new AwsClientFactory(
                config.getAws().getRegion(), config.getAws().resolveOperatorProfile());
```

and add a display line after line 26:

```java
        System.out.println("  profile:                  " + config.getAws().getProfile() + "  (admin setup/teardown)");
        System.out.println("  operatorProfile:          " + config.getAws().getOperatorProfile() + "  (run/results/config)");
```

replacing the existing single `profile:` line.

In `ConfigSetSubcommand.java`, add the option after `awsProfile` (line 23):

```java
    @Option(names = "--operator-profile",
        description = "AWS CLI profile that assumes BaasCliOperatorRole — used by run/results/config.")
    String operatorProfile;
```

and the assignment after line 53:

```java
        if (operatorProfile != null) config.getAws().setOperatorProfile(operatorProfile);
```

Also switch its own SSM client (line 63) to operator credentials:

```java
            var factory = new AwsClientFactory(
                config.getAws().getRegion(), config.getAws().resolveOperatorProfile());
```

Add `java.util.Optional` to `RunCommand`'s imports (already present at line 23).

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl baas-cli test`
Expected: PASS, whole module.

- [ ] **Step 5: Print the operator-profile hint from setup**

In `SetupCommand.java`, extend the post-deploy block (lines 104-110) to name the follow-up command:

```java
        if (!operatorRoleArn.isEmpty()) {
            System.out.println();
            System.out.println("BaasCliOperatorRole created: " + operatorRoleArn);
            System.out.println("Nobody can assume it yet. Two one-time steps:");
            System.out.println("  1. Grant sts:AssumeRole on this ARN to the IAM user who runs benchmarks,");
            System.out.println("     and add a ~/.aws/config profile with role_arn + source_profile. See infra/README.md.");
            System.out.println("  2. Point the CLI at that profile:");
            System.out.println("       baas config set --operator-profile <profile-name>");
            System.out.println("     Until you do, `baas run` uses the default credential chain, not this role.");
        }
```

- [ ] **Step 6: Commit**

```bash
git add baas-cli/src/main/java/pl/wsztajerowski/baas/commands/ \
        baas-cli/src/test/java/pl/wsztajerowski/baas/config/OperatorProfileTest.java
git commit -m "feat: resolve run/results/config credentials from aws.operatorProfile"
```

---

## Task 10: Deployer policy drops CI-stack permissions

**Files:**
- Modify: `infra/deployer-policy.json` (IAM statement)
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/DeployerPolicyTest.java`

**Interfaces:**
- Consumes: `InfraFixtures.actions(...)`, `InfraFixtures.deployerPolicy()` from Task 2.
- Produces: nothing new.

> **Why this matters:** `baas-cli-core-ci-split/design.md:25` rejects the unified-stack design *specifically* because it "forces the local CLI's IAM identity to have `iam:CreateOIDCProvider`/`WorkflowRole` create-update-delete permissions even though it never uses them". `deployer-policy.json` grants exactly those anyway. The boundary the whole previous change exists to create is punctured by the policy implementing it.

- [ ] **Step 1: Write the failing test**

Add to `DeployerPolicyTest`:

```java
    @Test
    void holdsNoCiStackPermissions() {
        assertThat(InfraFixtures.actions(InfraFixtures.deployerPolicy()))
            .as("the core/CI split exists so the local identity never touches GitHub OIDC trust")
            .noneMatch(action -> action.endsWith("OpenIDConnectProvider"))
            .doesNotContain("iam:UpdateAssumeRolePolicy");
    }
```

Add the import `org.junit.jupiter.api.Test` — the file so far has only `@ParameterizedTest`.

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=DeployerPolicyTest`
Expected: FAIL — three OIDC actions plus `iam:UpdateAssumeRolePolicy` are present.

- [ ] **Step 3: Remove the four actions**

In `infra/deployer-policy.json`'s IAM statement, delete these lines:

```json
        "iam:CreateOpenIDConnectProvider",
        "iam:GetOpenIDConnectProvider",
        "iam:TagOpenIDConnectProvider",
        "iam:UpdateAssumeRolePolicy",
```

The remaining action list should be exactly:

```json
      "Action": [
        "iam:AddRoleToInstanceProfile",
        "iam:CreateInstanceProfile",
        "iam:CreateRole",
        "iam:DeleteInstanceProfile",
        "iam:DeleteRole",
        "iam:DeleteRolePolicy",
        "iam:GetInstanceProfile",
        "iam:GetRole",
        "iam:GetRolePolicy",
        "iam:ListAttachedRolePolicies",
        "iam:ListRolePolicies",
        "iam:PutRolePolicy",
        "iam:RemoveRoleFromInstanceProfile",
        "iam:TagRole",
        "iam:UntagRole"
      ],
```

(`iam:UntagRole` is added here — CloudFormation needs it when a stack update changes role tags.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl baas-cli test -Dtest=DeployerPolicyTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add infra/deployer-policy.json baas-cli/src/test/java/pl/wsztajerowski/baas/infra/DeployerPolicyTest.java
git commit -m "fix: remove CI-stack OIDC permissions from the deployer policy"
```

---

## Task 11: Deployer policy is resource-scoped

**Files:**
- Modify: `infra/deployer-policy.json` (CloudFormation and IAM statements)
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/DeployerPolicyTest.java`

**Interfaces:**
- Consumes: `InfraFixtures.deployerPolicy()` from Task 2.
- Produces: a local test helper `statementWithSid(String sid) -> Map<String, Object>` inside `DeployerPolicyTest`.

> **Why this matters:** `iam:CreateRole` + `iam:PutRolePolicy` on `Resource: "*"` is a textbook escalation to account administrator — create a role, inline `AdministratorAccess`, assume it. `cloudformation:DeleteStack` on `"*"` lets the deployer destroy any stack in the account. Both are scopable because the stack's names are predictable (`baas-<prefix>`, `<prefix>-runner-role`, `<prefix>-operator-role`).

- [ ] **Step 1: Write the failing test**

Add to `DeployerPolicyTest`:

```java
    @Test
    void cloudFormationAccessIsScopedToBaasStacks() {
        assertThat(statementWithSid("CloudFormation").get("Resource"))
            .isEqualTo("arn:aws:cloudformation:*:*:stack/baas-*/*");
    }

    @Test
    @SuppressWarnings("unchecked")
    void iamAccessCannotCreateArbitrarilyNamedRoles() {
        var resources = (List<String>) statementWithSid("IAM").get("Resource");

        assertThat(resources)
            .as("unscoped iam:CreateRole + iam:PutRolePolicy is an escalation path to account admin")
            .doesNotContain("*")
            .allSatisfy(arn -> assertThat(arn).matches("arn:aws:iam::\\*:(role|instance-profile)/\\*-(runner-role|operator-role)"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> statementWithSid(String sid) {
        var statements = (List<Map<String, Object>>) InfraFixtures.deployerPolicy().get("Statement");
        return statements.stream()
            .filter(statement -> sid.equals(statement.get("Sid")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No statement with Sid " + sid));
    }
```

Add imports `java.util.List` and `java.util.Map`, plus `org.junit.jupiter.api.Test`.

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=DeployerPolicyTest`
Expected: FAIL — both statements are `Resource: "*"`.

- [ ] **Step 3: Scope the resources**

In `infra/deployer-policy.json`, change the `CloudFormation` statement's resource from `"*"` to:

```json
      "Resource": "arn:aws:cloudformation:*:*:stack/baas-*/*"
```

and the `IAM` statement's resource from `"*"` to:

```json
      "Resource": [
        "arn:aws:iam::*:role/*-runner-role",
        "arn:aws:iam::*:role/*-operator-role",
        "arn:aws:iam::*:instance-profile/*-runner-role"
      ]
```

Note the instance-profile ARN form is `arn:aws:iam::<account>:instance-profile/<name>` — the service segment is `iam`, not `instance-profile`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl baas-cli test -Dtest=DeployerPolicyTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add infra/deployer-policy.json baas-cli/src/test/java/pl/wsztajerowski/baas/infra/DeployerPolicyTest.java
git commit -m "fix: scope deployer CloudFormation and IAM permissions to baas resources"
```

---

## Task 12: Operator policy drift test and account pinning

**Files:**
- Modify: `infra/operator-policy.json`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/OperatorPolicyDriftTest.java` (create)

**Interfaces:**
- Consumes: `InfraFixtures.coreTemplate()`, `operatorPolicy()`, `actions(...)`, `resource(...)` from Task 2.
- Produces: nothing new.

> **Why this matters:** `infra/README.md:127-130` calls `operator-policy.json` "a static reference copy of the same permission statements". Nothing enforces that. Separately, its `iam:PassRole` on `arn:aws:iam::*:role/*-runner-role` spans *any* AWS account, and `arn:aws:s3:::baas-*` matches any account's bucket with that prefix, since S3 names are global.

- [ ] **Step 1: Write the failing test**

`baas-cli/src/test/java/pl/wsztajerowski/baas/infra/OperatorPolicyDriftTest.java`:

```java
package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorPolicyDriftTest {

    @Test
    void referenceCopyGrantsTheSameActionsAsTheStackRole() {
        Set<String> fromTemplate = operatorRoleActions();
        Set<String> fromReferenceCopy = InfraFixtures.actions(InfraFixtures.operatorPolicy());

        assertThat(fromReferenceCopy)
            .as("infra/operator-policy.json documents OperatorRole — the two must not drift apart")
            .isEqualTo(fromTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void passRoleIsPinnedToASingleAccount() {
        var statements = (List<Map<String, Object>>) InfraFixtures.operatorPolicy().get("Statement");
        var passRole = statements.stream()
            .filter(statement -> "PassRunnerRoleOnly".equals(statement.get("Sid")))
            .findFirst()
            .orElseThrow();

        assertThat((String) passRole.get("Resource"))
            .as("a wildcard account id would let this policy pass roles in someone else's account")
            .doesNotContain("::*:");
    }

    /** Flattens the actions across every inline policy attached to OperatorRole. */
    @SuppressWarnings("unchecked")
    private Set<String> operatorRoleActions() {
        var policies = (List<Map<String, Object>>)
            InfraFixtures.properties(InfraFixtures.coreTemplate(), "OperatorRole").get("Policies");

        Set<String> actions = new TreeSet<>();
        for (Map<String, Object> policy : policies) {
            actions.addAll(InfraFixtures.actions((Map<String, Object>) policy.get("PolicyDocument")));
        }
        return actions;
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=OperatorPolicyDriftTest`
Expected: `referenceCopyGrantsTheSameActionsAsTheStackRole` PASSES (the two files currently agree); `passRoleIsPinnedToASingleAccount` FAILS.

If the drift test also fails, that is a genuine finding — reconcile the two files before continuing.

- [ ] **Step 3: Pin the account wildcards**

In `infra/operator-policy.json`, replace the `PassRunnerRoleOnly` statement:

```json
    {
      "Sid": "PassRunnerRoleOnly",
      "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": "arn:aws:iam::ACCOUNT_ID:role/*-runner-role"
    }
```

and add a note at the top of the file explaining the placeholder. Since JSON has no comments, document it in `infra/README.md` instead (Task 17) and keep the literal `ACCOUNT_ID` token, which is what a reader substitutes when creating the role manually.

Also tighten `S3WorkingBucketAccess` — S3 bucket names are global, so `baas-*` can match another account's bucket. Replace its resources with:

```json
      "Resource": [
        "arn:aws:s3:::baas-RESOURCE_PREFIX",
        "arn:aws:s3:::baas-RESOURCE_PREFIX/*"
      ]
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl baas-cli test -Dtest=OperatorPolicyDriftTest`
Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add infra/operator-policy.json \
        baas-cli/src/test/java/pl/wsztajerowski/baas/infra/OperatorPolicyDriftTest.java
git commit -m "test: pin operator policy ARNs and guard against drift from OperatorRole"
```

---

# Phase C — Operability

## Task 13: Failed runs leave a diagnosable log

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilder.java:10-75`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilderTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing new — `build(...)`'s signature is unchanged.

> **Why this matters:** the instance self-terminates on both the success and failure paths (`UserDataScriptBuilder.java:74`). There is no CloudWatch log group and no Session Manager access. When a run reports `failed:1` there is nothing left to inspect. `RunnerRole` already holds `s3:PutObject` on the bucket, so shipping the log costs nothing but a line of script.

- [ ] **Step 1: Write the failing test**

`baas-cli/src/test/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilderTest.java`:

```java
package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserDataScriptBuilderTest {

    private String script() {
        String encoded = new UserDataScriptBuilder().build(
            "eu-central-1", "baas-a1b2c3d4", "a1b2c3d4", "jmh",
            "jmh-20260724_120000", "main/jmh/20260724_120000", 7200, 7500,
            "4.0", null, List.of("MyBenchmark", "-f", "1"));
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    @Test
    void shipsCloudInitLogToS3BeforeTerminating() {
        String script = script();

        assertThat(script)
            .as("the instance self-terminates, so an unshipped log is a log that no longer exists")
            .contains("/var/log/cloud-init-output.log")
            .contains("${RESULT_PATH}/cloud-init-output.log");

        assertThat(script.indexOf("cloud-init-output.log"))
            .as("the upload must happen before the terminate call")
            .isLessThan(script.lastIndexOf("terminate-instances"));
    }

    @Test
    void passesBenchmarkParametersThrough() {
        assertThat(script()).contains("export BENCHMARK_PARAMETERS='MyBenchmark -f 1'");
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=UserDataScriptBuilderTest`
Expected: FAIL on `shipsCloudInitLogToS3BeforeTerminating`; `passesBenchmarkParametersThrough` passes.

- [ ] **Step 3: Ship the log**

In `UserDataScriptBuilder.SCRIPT_BODY`, replace the cleanup block (lines 72-74):

```
        # Cleanup
        kill $WATCHDOG_PID 2>/dev/null || true
        aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "${AWS_REGION}"
```

with:

```
        # Ship the boot log before self-terminating — the instance is about to disappear
        # and this is the only record of what went wrong on a failed run.
        aws s3 cp /var/log/cloud-init-output.log \
          "s3://${S3_BUCKET}/${RESULT_PATH}/cloud-init-output.log" || true

        # Cleanup
        kill $WATCHDOG_PID 2>/dev/null || true
        aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "${AWS_REGION}"
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl baas-cli test -Dtest=UserDataScriptBuilderTest`
Expected: PASS, both tests.

- [ ] **Step 5: Point the failure message at the log**

In `RunCommand.java:184`, replace:

```java
                    System.err.println("Benchmark failed. Check S3: s3://" + config.getAws().getBucket() + "/" + resultPath + "/");
```

with:

```java
                    System.err.println("Benchmark failed. Runner log: s3://"
                        + config.getAws().getBucket() + "/" + resultPath + "/cloud-init-output.log");
```

- [ ] **Step 6: Commit**

```bash
git add baas-cli/src/main/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilder.java \
        baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilderTest.java
git commit -m "feat: upload the runner boot log to S3 before self-termination"
```

---

## Task 14: Poll loop detects a dead instance

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/infra/Ec2ProvisioningService.java`
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java:156-193`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilderTest.java` (no change) — behavior verified in Task 19's manual run

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `Ec2ProvisioningService#instanceState(String instanceId) -> String` returning the EC2 state name (`pending`, `running`, `shutting-down`, `terminated`, `stopped`), or `"unknown"` when the instance cannot be described.

> **Why this matters:** `RunCommand.poll` only ever looks for the S3 sentinel. If the instance dies during cloud-init the sentinel never appears, so the CLI prints "Still running…" for the full `wallClockHardKillSeconds` — 7500 seconds, over two hours — before giving up. `BaasCliOperatorRole` already grants `ec2:DescribeInstances`; the code simply never calls it.
>
> There is no unit test here: the behavior is a loop over live AWS state, and adding a mocking framework for one call is not worth it in a codebase that has none. Task 19's manual verification covers it.

- [ ] **Step 1: Add the state lookup**

In `Ec2ProvisioningService.java`, add after `terminateInstance` (line 72):

```java
    /**
     * Current EC2 state name, or "unknown" if the instance cannot be described.
     * Used to fail a run fast when the runner dies before writing its sentinel.
     */
    public String instanceState(String instanceId) {
        try {
            var response = ec2.describeInstances(r -> r.instanceIds(instanceId));
            return response.reservations().stream()
                .flatMap(reservation -> reservation.instances().stream())
                .findFirst()
                .map(instance -> instance.state().nameAsString())
                .orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }
```

- [ ] **Step 2: Check state in the poll loop**

In `RunCommand.java`, change the `poll` signature (line 159-160) to accept the instance id:

```java
    private int poll(AwsClientFactory factory, BaasConfig config, String instanceId,
                     String requestId, String resultPath, int wallClockSeconds) throws InterruptedException {
```

and its call site (line 156):

```java
        return poll(factory, config, instanceId, requestId, resultPath, resolvedWallClock);
```

Then replace the `else` branch of the status check (lines 187-189):

```java
            } else {
                System.out.printf("Still running... elapsed: %ds%n", elapsed);
            }
```

with:

```java
            } else {
                String state;
                try (var ec2 = factory.ec2()) {
                    state = new Ec2ProvisioningService(ec2).instanceState(instanceId);
                }
                if ("terminated".equals(state) || "shutting-down".equals(state)) {
                    System.err.println("Instance " + instanceId + " is " + state
                        + " but wrote no run-status sentinel — the runner died before finishing.");
                    System.err.println("Runner log: s3://" + config.getAws().getBucket()
                        + "/" + resultPath + "/cloud-init-output.log");
                    return 1;
                }
                System.out.printf("Still running (%s)... elapsed: %ds%n", state, elapsed);
            }
```

- [ ] **Step 3: Verify the module still compiles and all tests pass**

Run: `mvn -pl baas-cli test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add baas-cli/src/main/java/pl/wsztajerowski/baas/infra/Ec2ProvisioningService.java \
        baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java
git commit -m "feat: fail a run fast when the runner instance dies before reporting"
```

---

## Task 15: Bucket lifecycle rules

**Files:**
- Modify: `infra/cf-template-core.yaml` (`S3MainBucket.Properties`)
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java`

**Interfaces:**
- Consumes: `InfraFixtures.properties(...)` from Task 2.
- Produces: nothing new.

> **Why this matters:** versioning is on and every run uploads a fat benchmark JAR to `runs/`. With no lifecycle rules, each overwrite is retained as a noncurrent version forever, and interrupted multipart uploads accumulate as unbilled-but-charged storage.

- [ ] **Step 1: Write the failing test**

Add to `CoreTemplateTest`:

```java
    @Test
    @SuppressWarnings("unchecked")
    void bucketGrowthIsBounded() {
        var lifecycle = (Map<String, Object>)
            InfraFixtures.properties(template, "S3MainBucket").get("LifecycleConfiguration");

        assertThat(lifecycle).as("versioning without lifecycle rules grows without bound").isNotNull();

        var rules = (List<Map<String, Object>>) lifecycle.get("Rules");
        assertThat(rules).anySatisfy(rule ->
            assertThat(rule).containsKey("NoncurrentVersionExpiration"));
        assertThat(rules).anySatisfy(rule ->
            assertThat(rule).containsKey("AbortIncompleteMultipartUpload"));
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest`
Expected: FAIL — `LifecycleConfiguration` is null.

- [ ] **Step 3: Add the rules**

In `infra/cf-template-core.yaml`, inside `S3MainBucket.Properties`, after the `VersioningConfiguration` block:

```yaml
      LifecycleConfiguration:
        Rules:
          - Id: expire-noncurrent-versions
            Status: Enabled
            NoncurrentVersionExpiration:
              NoncurrentDays: 30
          - Id: abort-incomplete-uploads
            Status: Enabled
            AbortIncompleteMultipartUpload:
              DaysAfterInitiation: 7
          - Id: expire-uploaded-benchmark-jars
            Status: Enabled
            Prefix: runs/
            ExpirationInDays: 30
```

Benchmark *results* under `<branch>/<type>/<timestamp>/` are untouched — only the re-uploadable JARs under `runs/` expire.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add infra/cf-template-core.yaml baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java
git commit -m "feat: bound working-bucket growth with lifecycle rules"
```

---

# Phase D — Hygiene

## Task 16: Tag convention and partition

**Files:**
- Modify: `infra/cf-template-core.yaml:148-152` (bucket tags), all `arn:aws:` literals in both templates
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/infra/Ec2ProvisioningService.java:21-25`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java`

**Interfaces:**
- Consumes: `InfraFixtures.properties(...)` from Task 2.
- Produces: nothing new.

> **Why this matters:** commit `d591931` standardised on dash-separated tags (`baas-role`). The bucket still uses a bare `role` key (`:151`) and instances still get `baas:request-id` with a colon (`Ec2ProvisioningService.java:24`). Mixed conventions break cost-allocation tag filters silently. Separately, hardcoded `arn:aws:` breaks in GovCloud/China partitions.

- [ ] **Step 1: Write the failing test**

Add to `CoreTemplateTest`:

```java
    @Test
    @SuppressWarnings("unchecked")
    void bucketFollowsTheDashedTagConvention() {
        var tags = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "S3MainBucket").get("Tags");

        assertThat(tags).extracting(tag -> tag.get("Key")).contains("baas-role");
        assertThat(tags).extracting(tag -> tag.get("Key")).doesNotContain("role");
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest`
Expected: FAIL — the key is `role`, not `baas-role`.

- [ ] **Step 3: Fix the tags**

In `infra/cf-template-core.yaml`, change the bucket's tag block:

```yaml
      Tags:
        - Key: project
          Value: !Sub ${ResourceNamePrefix}
        - Key: baas-role
          Value: working-bucket
```

In `Ec2ProvisioningService.java:21-25`, change:

```java
            Tag.builder().key("baas:request-id").value(requestId).build()
```

to:

```java
            Tag.builder().key("baas-request-id").value(requestId).build()
```

- [ ] **Step 4: Replace the hardcoded partition**

In both `infra/cf-template-core.yaml` and `infra/cf-template-ci.yaml`, replace every literal `arn:aws:` inside a `!Sub` with `arn:${AWS::Partition}:`. For plain-string ARNs not already wrapped in `!Sub`, convert them — e.g. in `RunnerRole`'s terminate policy:

```yaml
                Resource: "arn:aws:ec2:*:*:instance/*"
```

becomes:

```yaml
                Resource: !Sub "arn:${AWS::Partition}:ec2:*:*:instance/*"
```

Apply the same to `OperatorRole`'s terminate statement and `WorkflowRole`'s terminate and SSM statements.

- [ ] **Step 5: Run all tests**

Run: `mvn -pl baas-cli test`
Expected: PASS. The `OperatorPolicyDriftTest` compares *actions*, not resources, so partition changes do not disturb it.

- [ ] **Step 6: Commit**

```bash
git add infra/cf-template-core.yaml infra/cf-template-ci.yaml \
        baas-cli/src/main/java/pl/wsztajerowski/baas/infra/Ec2ProvisioningService.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java
git commit -m "refactor: apply the dashed tag convention and partition-agnostic ARNs"
```

---

## Task 17: Constrain `ec2:RunInstances`

**Files:**
- Modify: `infra/cf-template-core.yaml` (`OperatorRole` EC2 policy)
- Modify: `infra/operator-policy.json` (`Ec2RunAndDescribe`)
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java`

**Interfaces:**
- Consumes: `InfraFixtures.properties(...)` from Task 2.
- Produces: nothing new.

> **Why this matters:** `OperatorRole` grants `ec2:RunInstances` on `Resource: "*"` with no conditions. A typo in `--instance-type` launches whatever the operator names — a `p5.48xlarge` is roughly $98/hour. Constraining by instance-type family and region bounds the blast radius of a mistake.

- [ ] **Step 1: Write the failing test**

Add to `CoreTemplateTest`:

```java
    @Test
    @SuppressWarnings("unchecked")
    void operatorCannotLaunchArbitrarilyLargeInstances() {
        var policies = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "OperatorRole").get("Policies");

        var runInstances = policies.stream()
            .map(policy -> (Map<String, Object>) policy.get("PolicyDocument"))
            .flatMap(document -> ((List<Map<String, Object>>) document.get("Statement")).stream())
            .filter(statement -> String.valueOf(statement.get("Action")).contains("ec2:RunInstances"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No ec2:RunInstances statement on OperatorRole"));

        assertThat(runInstances)
            .as("an unconstrained RunInstances turns a typo into a four-figure bill")
            .containsKey("Condition");
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=CoreTemplateTest`
Expected: FAIL — the statement has no `Condition`.

- [ ] **Step 3: Add the conditions**

In `infra/cf-template-core.yaml`, split `OperatorRole`'s first EC2 statement so the describe calls stay unconditioned (conditions on `ec2:InstanceType` do not apply to them) and `RunInstances` gains its own:

```yaml
        - PolicyName: !Sub ${ResourceNamePrefix}-operator-ec2-policy
          PolicyDocument:
            Version: "2012-10-17"
            Statement:
              - Effect: Allow
                Action:
                  - ec2:DescribeInstances
                  - ec2:DescribeInstanceStatus
                Resource: "*"
              - Effect: Allow
                Action: ec2:RunInstances
                Resource: "*"
                Condition:
                  StringEquals:
                    aws:RequestedRegion: !Ref AWS::Region
                  # Benchmarks are single-node and CPU-bound; compute/general-purpose
                  # families cover every supported workload. Widen deliberately, not by default.
                  StringLike:
                    ec2:InstanceType:
                      - c5.*
                      - c6i.*
                      - c7i.*
                      - m5.*
                      - m6i.*
                      - m7i.*
```

Leave the `ec2:CreateTags` and `ec2:TerminateInstances` statements untouched.

- [ ] **Step 4: Mirror the change in the reference copy**

In `infra/operator-policy.json`, split `Ec2RunAndDescribe` the same way:

```json
    {
      "Sid": "Ec2Describe",
      "Effect": "Allow",
      "Action": [
        "ec2:DescribeInstances",
        "ec2:DescribeInstanceStatus"
      ],
      "Resource": "*"
    },
    {
      "Sid": "Ec2RunBenchmarkInstances",
      "Effect": "Allow",
      "Action": "ec2:RunInstances",
      "Resource": "*",
      "Condition": {
        "StringLike": {
          "ec2:InstanceType": [
            "c5.*", "c6i.*", "c7i.*", "m5.*", "m6i.*", "m7i.*"
          ]
        }
      }
    },
```

- [ ] **Step 5: Run all tests**

Run: `mvn -pl baas-cli test`
Expected: PASS — including `OperatorPolicyDriftTest`, since the action set is unchanged.

- [ ] **Step 6: Commit**

```bash
git add infra/cf-template-core.yaml infra/operator-policy.json \
        baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java
git commit -m "fix: constrain operator RunInstances by instance family and region"
```

---

## Task 18: CI template permission alignment

**Files:**
- Modify: `infra/cf-template-ci.yaml:96-116`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CiTemplateTest.java` (create)

**Interfaces:**
- Consumes: `InfraFixtures.ciTemplate()`, `properties(...)`, `actions(...)` from Task 2.
- Produces: nothing new.

> **Why this matters:** two asymmetries with the core stack. `WorkflowRole` grants `ssm:GetParameters` (plural) for the AMI lookup while `OperatorRole` grants `ssm:GetParameter` (singular) — these are distinct IAM actions, so whichever path shares code will fail. And `WorkflowRole` has `s3:PutObject` but no `s3:GetObject`, making the CI path write-only.

- [ ] **Step 1: Write the failing test**

`baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CiTemplateTest.java`:

```java
package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class CiTemplateTest {

    @Test
    void grantsBothSingularAndPluralSsmReadsForTheAmiLookup() {
        assertThat(workflowRoleActions())
            .as("GetParameter and GetParameters are distinct IAM actions; the core stack uses the singular form")
            .contains("ssm:GetParameter", "ssm:GetParameters");
    }

    @Test
    void canReadBackWhatItWrites() {
        assertThat(workflowRoleActions()).contains("s3:GetObject");
    }

    @SuppressWarnings("unchecked")
    private Set<String> workflowRoleActions() {
        var policies = (List<Map<String, Object>>)
            InfraFixtures.properties(InfraFixtures.ciTemplate(), "WorkflowRole").get("Policies");

        Set<String> actions = new TreeSet<>();
        for (Map<String, Object> policy : policies) {
            actions.addAll(InfraFixtures.actions((Map<String, Object>) policy.get("PolicyDocument")));
        }
        return actions;
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=CiTemplateTest`
Expected: FAIL — both tests.

- [ ] **Step 3: Align the permissions**

In `infra/cf-template-ci.yaml`, change the AMI lookup statement (lines 96-98) to grant both forms:

```yaml
              - Effect: Allow
                Action:
                  - ssm:GetParameter
                  - ssm:GetParameters
                Resource: !Sub "arn:${AWS::Partition}:ssm:*::parameter/aws/service/ami-amazon-linux-latest/*"
```

and the S3 statement (lines 109-116) to allow reads:

```yaml
              - Effect: Allow
                Action: s3:PutObject
                Resource:
                  - !Sub 'arn:${AWS::Partition}:s3:::${BucketName}/ci/*'
                  - !Sub 'arn:${AWS::Partition}:s3:::${BucketName}/runs/*'
              - Effect: Allow
                Action: s3:GetObject
                Resource: !Sub 'arn:${AWS::Partition}:s3:::${BucketName}/*'
              - Effect: Allow
                Action: s3:ListBucket
                Resource: !Sub 'arn:${AWS::Partition}:s3:::${BucketName}'
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl baas-cli test -Dtest=CiTemplateTest`
Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add infra/cf-template-ci.yaml baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CiTemplateTest.java
git commit -m "fix: align CI workflow role SSM and S3 permissions with the core stack"
```

---

## Task 19: Documentation and manual verification

**Files:**
- Modify: `infra/README.md`
- Modify: `docs/aws-migration-plan.md:38,88`
- Modify: `openspec/changes/baas-infra-hardening/tasks.md` (tick off completed items)

**Interfaces:**
- Consumes: everything.
- Produces: nothing.

- [ ] **Step 1: Document the operator-profile step**

In `infra/README.md`, after the `~/.aws/config` block (ends at line 124), add:

```markdown
Finally, point the CLI at that profile — **this step is required**, and without it
`baas run`/`baas results`/`baas config` fall through to the default AWS credential
chain rather than assuming the operator role:

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
```

- [ ] **Step 2: Correct the Atlas allowlist instruction**

In `infra/README.md`, add a subsection before the IAM section:

```markdown
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
```

In `docs/aws-migration-plan.md`, replace the "add the runner egress IP + laptop IP" phrasing on lines 38 and 88 with a pointer to the section above, since a per-run ephemeral IP cannot be allowlisted.

- [ ] **Step 3: Document where a failed run's log lives**

In `infra/README.md`, add to the Atlas section or a new "Debugging a failed run" subsection:

```markdown
## Debugging a failed run

Runner instances self-terminate on both the success and failure paths, so the boot log is
uploaded to S3 before termination:

```
s3://<bucket>/<branch>/<type>/<timestamp>/cloud-init-output.log
```

`baas run` prints that path when a run fails or when the instance dies before reporting.
```

- [ ] **Step 4: Run the full build**

Run: `mvn clean verify`
Expected: PASS. Confirm `baas-cli`'s new unit tests and `S3UploadServiceIT` all run.

- [ ] **Step 5: Verify the shipped JAR is still clean**

Run: `unzip -l baas-cli/target/baas-cli.jar | grep -E 'templates|policy'`
Expected: exactly one line, `templates/cf-template-core.yaml`.

- [ ] **Step 6: Commit the docs**

```bash
git add infra/README.md docs/aws-migration-plan.md openspec/changes/baas-infra-hardening/tasks.md
git commit -m "docs: document the operator profile step, Atlas connectivity, and run logs"
```

- [ ] **Step 7: Manual verification against a scratch AWS account**

These cannot be automated — they are what tasks 9.2/9.3 of the previous change were for, and they are the only way to confirm the inferred IAM read-back actions are complete.

1. Create an IAM user holding **only** the revised `deployer-policy.json`. Run:
   ```bash
   baas admin setup --aws-profile baas-deployer --mongo-uri "mongodb+srv://user:pass@host/benchmarks"
   ```
   Expected: stack reaches `CREATE_COMPLETE`, SSM parameter written, exit 0. **If this fails with `AccessDenied`, record the exact action and add it to `deployer-policy.json`** — that is the gap this step exists to find.

2. Grant `sts:AssumeRole` on the printed `OperatorRoleArn` to a second IAM user, add the `role_arn`/`source_profile` profile, then:
   ```bash
   baas config set --operator-profile baas-operator
   baas run jmh -- MyBenchmark -f 1 -wi 1 -i 3
   ```
   Expected: instance launches, benchmark completes, **results appear in MongoDB** (this is what the 27017 fix buys), `run-status` reads `completed`, and `cloud-init-output.log` is present in the run's S3 prefix.

3. Kill the run mid-flight and confirm the instance self-terminates via the watchdog, and that a second `baas run` against a deliberately broken benchmark JAR fails fast with the instance-state message rather than hanging for 7500s.

4. Back as the deployer:
   ```bash
   baas admin teardown --yes --delete-bucket
   ```
   Expected: bucket empties (including noncurrent versions), stack reaches `DELETE_COMPLETE`, SSM parameter deleted.

5. Tick off section 7 in `openspec/changes/baas-infra-hardening/tasks.md` and commit.

---

## Task 20: Operator can bootstrap its own config

> **Phase B follow-on.** Numbered last to avoid renumbering, but it belongs with Tasks 8-12 and can be done any time after Task 8.

**Files:**
- Create: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/ConfigSyncSubcommand.java`
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/ConfigCommand.java` (register the subcommand)
- Modify: `infra/cf-template-core.yaml` (`OperatorRole` — add a CloudFormation read policy)
- Modify: `infra/operator-policy.json` (mirror it)
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/CoreTemplateTest.java`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/BaasAppTest.java`

**Interfaces:**
- Consumes: `AwsConfig#resolveOperatorProfile()` (Task 8), `CloudFormationService#getStackOutputs(String)` (existing, `CloudFormationService.java:78`), `InfraFixtures.properties(...)` (Task 2).
- Produces: `baas config sync --core-stack-name <name>` writing `bucket`, `subnetId`, `securityGroupId`, `vpcId`, `runnerInstanceProfileName`, `prefix`, and `coreStackName` into `~/.baas/config.yaml`.

> **Why this matters:** only `baas admin setup` writes `bucket`/`subnetId`/`securityGroupId`/`runnerInstanceProfileName`/`prefix`, and `OperatorRole` grants no `cloudformation:DescribeStacks`. An operator on a different machine from the deployer has no way to obtain those values — `config.yaml` has to be hand-copied, which is undocumented and silently produces confusing `null` failures in `RunCommand`. The stack name is the one thing the operator cannot derive (the prefix comes from the *deployer's* ARN), so it is a required argument; `baas admin setup` already prints it.

- [ ] **Step 1: Write the failing tests**

Add to `CoreTemplateTest`:

```java
    @Test
    @SuppressWarnings("unchecked")
    void operatorCanReadItsOwnStackOutputs() {
        var policies = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "OperatorRole").get("Policies");

        var actions = policies.stream()
            .map(policy -> (Map<String, Object>) policy.get("PolicyDocument"))
            .flatMap(document -> InfraFixtures.actions(document).stream())
            .toList();

        assertThat(actions)
            .as("without this an operator cannot populate config.yaml without hand-copying it")
            .contains("cloudformation:DescribeStacks");
    }
```

Add to `BaasAppTest`:

```java
    @Test
    void resolvesConfigSyncSubcommand() {
        CommandLine cmd = new CommandLine(new BaasApp());

        assertThat(cmd.getSubcommands().get("config").getSubcommands())
            .containsKey("sync");
    }
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `mvn -pl baas-cli test -Dtest='CoreTemplateTest+BaasAppTest'`
Expected: FAIL — no `cloudformation:DescribeStacks` on `OperatorRole`, no `sync` subcommand.

- [ ] **Step 3: Grant the scoped read**

In `infra/cf-template-core.yaml`, add a policy to `OperatorRole.Properties.Policies`:

```yaml
        - PolicyName: !Sub ${ResourceNamePrefix}-operator-cfn-read-policy
          PolicyDocument:
            Version: "2012-10-17"
            Statement:
              # Read-only, and only this stack — enough for `baas config sync` to
              # populate config.yaml, not enough to mutate any stack.
              - Effect: Allow
                Action: cloudformation:DescribeStacks
                Resource: !Sub "arn:${AWS::Partition}:cloudformation:${AWS::Region}:${AWS::AccountId}:stack/baas-${ResourceNamePrefix}/*"
```

Mirror it in `infra/operator-policy.json` (the drift test compares action sets, so this must be added or that test fails):

```json
    {
      "Sid": "CloudFormationReadOwnStack",
      "Effect": "Allow",
      "Action": "cloudformation:DescribeStacks",
      "Resource": "arn:aws:cloudformation:*:ACCOUNT_ID:stack/baas-RESOURCE_PREFIX/*"
    },
```

- [ ] **Step 4: Add the subcommand**

`baas-cli/src/main/java/pl/wsztajerowski/baas/commands/ConfigSyncSubcommand.java`:

```java
package pl.wsztajerowski.baas.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;

import java.util.concurrent.Callable;

@Command(
    name = "sync",
    mixinStandardHelpOptions = true,
    description = "Populate config.yaml from an existing core stack's outputs (operator credentials)."
)
public class ConfigSyncSubcommand implements Callable<Integer> {

    @Option(names = "--core-stack-name", required = true,
        description = "Core stack to read, as printed by `baas admin setup` (e.g. baas-a1b2c3d4).")
    String coreStackName;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        BaasConfig config = configService.load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(System.err::println);

        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

        try (var cf = factory.cloudFormation()) {
            var outputs = new CloudFormationService(cf).getStackOutputs(coreStackName);
            if (outputs.isEmpty()) {
                System.err.println("Stack '" + coreStackName + "' has no outputs, or does not exist in "
                    + config.getAws().getRegion() + ".");
                return 1;
            }
            config.getAws().setCoreStackName(coreStackName);
            config.getAws().setBucket(outputs.getOrDefault("BucketName", ""));
            config.getAws().setSubnetId(outputs.getOrDefault("SubnetId", ""));
            config.getAws().setSecurityGroupId(outputs.getOrDefault("SecurityGroupId", ""));
            config.getAws().setVpcId(outputs.getOrDefault("VpcId", ""));
            config.getAws().setRunnerInstanceProfileName(outputs.getOrDefault("RunnerInstanceProfileName", ""));
        }

        // The SSM mongo path is keyed by the prefix, which is the stack name minus its "baas-" namespace.
        config.setPrefix(coreStackName.startsWith("baas-") ? coreStackName.substring(5) : coreStackName);

        configService.save(config);
        System.out.println("Configuration synced from " + coreStackName + " to " + configService.configFilePath());
        return 0;
    }
}
```

Register it in `ConfigCommand.java` by adding `ConfigSyncSubcommand.class` to the `subcommands` array alongside the existing set and show subcommands.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -pl baas-cli test`
Expected: PASS — including `OperatorPolicyDriftTest`, which now sees `cloudformation:DescribeStacks` on both sides.

- [ ] **Step 6: Document it**

In `infra/README.md`, in the operator setup section added by Task 19, after the `baas config set --operator-profile` step:

```markdown
If you are setting up on a machine that never ran `baas admin setup` — the usual case when
the deployer and the operator are different people — pull the stack's values instead of
copying `config.yaml` by hand:

```bash
baas config sync --core-stack-name baas-a1b2c3d4
```

The stack name is printed by `baas admin setup`; the operator cannot derive it, because the
prefix is a hash of the *deployer's* ARN.
```

- [ ] **Step 7: Commit**

```bash
git add baas-cli/src/main/java/pl/wsztajerowski/baas/commands/ \
        baas-cli/src/test/java/pl/wsztajerowski/baas/ \
        infra/cf-template-core.yaml infra/operator-policy.json infra/README.md
git commit -m "feat: add baas config sync so operators can bootstrap config from stack outputs"
```

---

## Notes for the implementer

- **Task order matters within phases, not across them.** Task 2 must land first (everything else asserts through `InfraFixtures`). Tasks 3-7 are independent of each other. Task 9 depends on Task 8; Task 20 depends on Tasks 8 and 12.
- **When a template test fails in a way you did not expect**, check whether `InfraFixtures`' intrinsic collapsing is the cause: `!Sub "arn:aws:s3:::${X}"` parses as the literal string `arn:aws:s3:::${X}`, and `!Ref Foo` parses as `"Foo"`. Assert against those forms, not against resolved values.
- **Do not touch `openspec/changes/baas-cli-core-ci-split/`.** Its unchecked tasks 9.2/9.3 stay unchecked; Task 19 of this plan supersedes them in practice, and the archive step for both changes is a separate decision.
