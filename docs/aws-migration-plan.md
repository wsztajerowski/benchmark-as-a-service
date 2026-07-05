# AWS Migration Plan — BaaS Infrastructure

> Companion to [`redesign.md`](redesign.md). This document covers the step-by-step procedure for migrating from the current two-stack CloudFormation deployment + GitHub Actions trigger to the new single-stack deployment driven by the self-contained `baas` CLI.

---

## Current State

| Stack | Template | Resources |
|---|---|---|
| `baas-parameters` (bootstrap) | `cf-template-bootstrap.yaml` | S3 bucket `<prefix>-lambda`, SSM params `/<prefix>/github/{org,repo,workflowid,workflowbranch}` |
| `baas-main` (main) | `cf-template-main.yaml` | S3 bucket `<prefix>-main`, Lambda function + execution role + S3 trigger, WorkflowRole (GHA OIDC), RunnerRole + InstanceProfile, OIDC provider |
| _(external)_ | — | VPC, subnet, security group with **internet egress** (passed as GHA variables `SUBNET_ID`, `SECURITY_GROUP_ID`) |
| _(GHA secret)_ | — | `MONGO_CONNECTION_STRING` (injected into the runner by `exec-single-benchmark.yml`) |

**Key facts that shape the migration:**
- The current runner subnet already has **outbound internet egress** — it must, because the GHA self-hosted runner registers with `api.github.com` and the runner downloads async-profiler / the JDK / `benchmark-runner.jar` from GitHub. The same egress reaches MongoDB Atlas. *The new design reuses this model.*
- `WorkflowRole`'s trust policy resolves the bootstrap stack's `/<prefix>/github/*` SSM params via `{{resolve:ssm:...}}` dynamic references.

---

## Target State

| Stack | Template | Resources |
|---|---|---|
| `baas-main` (single) | New `cf-template-main.yaml` | **VPC + public subnet + internet gateway + S3 gateway endpoint + security group** (outbound-only) + S3 bucket + RunnerRole/InstanceProfile + WorkflowRole + OIDC + inlined `/github/*` SSM params |
| _(manual via CLI)_ | — | SSM SecureString `/<prefix>/mongo/connection-string` (written by `baas config set --mongo-uri`) |

**Removed:** Bootstrap stack, Lambda function, Lambda execution role, S3 event notification, `<prefix>-lambda` S3 bucket.

**Kept for GHA CI only** (the `baas` CLI does **not** use these): `WorkflowRole`, OIDC provider, `/<prefix>/github/*` SSM params. The GHA workflows (`benchmark-runner.yml`, `e2e-cloud-test.yml`, `release.yml`) remain for automation.

**MongoDB:** connect-only. No cluster is provisioned. The user supplies a connection string (e.g. free-tier Atlas) and `baas` stores it in SSM SecureString. **Atlas IP allowlist is managed manually** (add the runner egress IP + laptop IP).

---

## Pre-Migration Checklist

- [ ] Verify no active benchmark runs are in progress (`baas run` or GHA `benchmark-runner.yml`)
- [ ] Record current GHA secrets/variables: `WORKFLOW_ROLE_ARN`, `RUNNER_ROLE_NAME`, `GHA_EC2_PAT`, `MONGO_CONNECTION_STRING`, `SUBNET_ID`, `SECURITY_GROUP_ID`, `AWS_REGION`, `ASYNC_PROFILER_VERSION`
- [ ] Back up SSM params: `aws ssm get-parameters-by-path --path /<prefix>/github/ --query Parameters`
- [ ] Note bootstrap stack S3 bucket name: `aws cloudformation describe-stacks --stack-name baas-parameters --query 'Stacks[0].Outputs'`
- [ ] Have the MongoDB connection string ready (existing cluster or a new free-tier Atlas cluster)
- [ ] Ensure `baas-cli` is built and the `baas` binary is available

---

## Migration Steps

### Phase 1 — Deploy new unified stack alongside existing stacks

Additive. Existing stacks remain untouched; the GHA workflow keeps using the old `SUBNET_ID`/`SECURITY_GROUP_ID`.

**Step 1.1 — Deploy the new stack with fresh networking**

```bash
baas setup \
  --prefix baas \
  --region eu-central-1 \
  --stack-name baas-main-v2 \
  --github-org wsztajerowski \
  --github-repo benchmark-as-a-service \
  --workflow-id <WORKFLOW_ID> \
  --oidc-provider-arn <EXISTING_OIDC_ARN> \
  --mongo-uri "mongodb+srv://<user>:<pass>@<host>/<db>"
```

This creates:
- VPC `10.0.0.0/16` with a **public subnet**, internet gateway, route to `0.0.0.0/0`, and a free **S3 gateway endpoint**
- S3 bucket `<prefix>-main` (CF fails if the bucket already exists — use a distinct `--prefix` for parallel testing)
- IAM roles (RunnerRole with self-terminate + SSM mongo read; WorkflowRole + OIDC for the GHA path)
- Inlined `/github/*` SSM params
- The `--mongo-uri` value written to SSM SecureString `/<prefix>/mongo/connection-string` (validated to contain a DB name)

> If `--mongo-uri` is omitted, `baas` prints the Atlas free-tier signup link and you supply it later via `baas config set --mongo-uri`.

**Step 1.2 — (If not passed to setup) store the MongoDB connection string**

```bash
baas config set --mongo-uri "mongodb+srv://<user>:<pass>@<host>/<db>"
```

Then add the runner's egress IP and your laptop's IP to the **Atlas IP Access List** (manual, v1).

**Step 1.3 — Smoke test via CLI**

```bash
baas run jmh --skip-build --runner-jar ./benchmark-runner/target/benchmark-runner.jar \
  -- FakeBenchmark -f 1 -wi 0 -i 1
```

Verify:
- [ ] EC2 instance launches in the new public subnet with a public IP
- [ ] Instance reaches S3 (gateway endpoint), SSM, and MongoDB Atlas (public egress)
- [ ] async-profiler downloads from GitHub on a `jmh-with-async` run
- [ ] Instance self-terminates after completion (and within `wallClockHardKillSeconds` if it hangs)
- [ ] `run-status` sentinel appears in S3
- [ ] MongoDB document is created
- [ ] `baas results --request-id <id>` displays results

---

### Phase 2 — Migrate the GHA workflow to the new networking

`benchmark-runner.yml` uses `SUBNET_ID`/`SECURITY_GROUP_ID` pointing at the old networking. Repoint them to the new stack outputs.

**Step 2.1 — Read new stack outputs**

```bash
aws cloudformation describe-stacks \
  --stack-name baas-main-v2 \
  --query 'Stacks[0].Outputs' --output table
```

Note `SubnetId`, `SecurityGroupId`, `WorkflowRoleArn`, `RunnerRoleName`, `BucketName`.

**Step 2.2 — Update GHA variables**

| GHA variable/secret | Old value | New value |
|---|---|---|
| `SUBNET_ID` | `subnet-old-xxx` | `SubnetId` output from new stack |
| `SECURITY_GROUP_ID` | `sg-old-xxx` | `SecurityGroupId` output from new stack |
| `WORKFLOW_ROLE_ARN` | old stack ARN | `WorkflowRoleArn` (only if stack name changed) |
| `RUNNER_ROLE_NAME` | old role name | `RunnerRoleName` (only if prefix changed) |

If the prefix is unchanged, role names are stable — only `SUBNET_ID`/`SECURITY_GROUP_ID` need updating.

**Step 2.3 — GHA smoke test.** Trigger `benchmark-runner.yml` manually; verify the runner starts in the new subnet and completes.

**Step 2.4 — E2E cloud test.** Trigger `e2e-cloud-test.yml`; verify `jmh-with-async` and `jmh-with-prof` runs produce S3 artifacts + MongoDB documents and all assertions pass.

---

### Phase 3 — Delete old infrastructure & clean up the repo

Only after both CLI and GHA paths are verified on the new stack.

**Step 3.1 — Delete the bootstrap stack**

> **⚠️ SSM param name collision:** the new stack inlines `/<prefix>/github/*` params that the bootstrap stack still owns. Resolve in one of two ways:
> - **Option A (recommended):** delete the bootstrap stack FIRST, then deploy the new stack — params disappear, then the new stack recreates them.
> - **Option B:** `cloudformation import` the existing params into the new stack before deleting the bootstrap stack.

```bash
aws s3 rm s3://<prefix>-lambda --recursive --profile <profile>
aws cloudformation delete-stack --stack-name baas-parameters --profile <profile>
aws cloudformation wait stack-delete-complete --stack-name baas-parameters --profile <profile>
```

**Step 3.2 — Delete the old main stack (if a new stack name was used)**

```bash
# The old <prefix>-main bucket has DeletionPolicy: Retain — it survives stack deletion.
aws cloudformation delete-stack --stack-name baas-main --profile <profile>
aws cloudformation wait stack-delete-complete --stack-name baas-main --profile <profile>
```

If old and new stacks share the same bucket name, ensure the new stack manages it before deleting the old one. For routine teardown of a `baas`-managed stack, prefer `baas teardown` (safety gates in `redesign.md §6`).

**Step 3.3 — Clean up old externally-managed networking (if any)**

If the old VPC/subnet/SG were created outside CloudFormation, delete them once nothing else uses them.

**Step 3.4 — Remove the `s3-hook-lambda` module**

```bash
rm -rf s3-hook-lambda/
# root pom.xml: delete <module>s3-hook-lambda</module>
rm infra/cf-template-bootstrap.yaml
# replace infra/cf-template-main.yaml with the new template (also bundled in baas-cli as a classpath resource)
```

**Step 3.5 — Remove the benchmark-workflow scripts** (replaced by `baas`)

```bash
rm scripts/run-remote-benchmark.zsh \
   scripts/wait-for-gha-run.sh \
   scripts/benchmark_overview.sh \
   scripts/logger.sh scripts/git_helpers.sh scripts/aws_helpers.sh
# KEEP scripts/get-version-property*.sh and scripts/update-dependencies.sh — used by GHA CI/release.
```

**Step 3.6 — Update docs/config.** `release.yml` (publish `baas-cli.jar` + wrapper + `install.sh`), `AGENTS.md`, `README.md`, `CLAUDE.md`.

---

## Rollback Plan

- **Phase 1 fails (new stack deploy):** `aws cloudformation delete-stack --stack-name baas-main-v2`. No impact on existing infra.
- **Phase 2 fails (GHA migration):** revert GHA variables to old values; the old stack is intact.
- **Phase 3 partially fails:** the old main stack's `<prefix>-main` bucket has `DeletionPolicy: Retain` and survives; IAM roles can be recreated by redeploying the old template.

---

## Post-Migration Verification

- [ ] `baas run jmh -- FakeBenchmark -f 1 -wi 0 -i 1` → succeeds; EC2 terminates; results in MongoDB
- [ ] `baas run jmh-with-async -- FakeBenchmark -f 1 -wi 1 -i 1 --async-event=wall` → succeeds; flamegraph in S3; async-profiler pulled from GitHub
- [ ] `baas results --request-id <id>` → displays table (reads URI from SSM, no `mongosh`)
- [ ] `baas config show` → shows config with masked MongoDB URI
- [ ] `baas teardown` (in a scratch stack) → aborts when a run is in flight; with `--yes` deletes the stack but retains the bucket unless `--delete-bucket`
- [ ] GHA `benchmark-runner.yml` manual dispatch → succeeds with new `SUBNET_ID`/`SECURITY_GROUP_ID`
- [ ] GHA `e2e-cloud-test.yml` → all assertions pass
- [ ] No orphaned EC2 instances: `aws ec2 describe-instances --filters "Name=tag:baas-role,Values=benchmark-runner" "Name=instance-state-name,Values=running"`
- [ ] Old `baas-parameters` stack deleted; `<prefix>-lambda` bucket emptied and deleted
- [ ] `s3-hook-lambda/` removed; benchmark-workflow scripts removed; version/dependency scripts retained

---

## Cost Impact

Both before and after run the EC2 instance in a subnet with internet egress, so there is **no NAT change** to account for. Networking deltas are small:

| Resource | Before | After | Delta |
|---|---|---|---|
| NAT gateway | (none — externally-managed egress) | (none) | $0 |
| VPC interface endpoints | $0 | $0 (not used in default model) | $0 |
| S3 gateway endpoint | n/a | $0 (free) | $0 |
| Public IPv4 address (runner) | (already had egress) | ~$0.005/hr while running | ~+$0 to a few $/month, prorated to run time |
| Lambda function + `<prefix>-lambda` bucket | ~$0.02/month | $0 (removed) | ~−$0.02/month |
| **Net** | | | **≈ neutral** |

EC2 instance costs are unchanged (same instance types, same per-run duration).

> The `--private-networking` hardening profile (private subnet + interface endpoints, requires paid Atlas tier for private connectivity) would add ~$29/month for endpoints; out of scope for v1.

---

## Timeline

| Week | Phase | Description |
|---|---|---|
| 1 | Phase 1 | Build `baas-cli`; deploy new stack; store Mongo URI; smoke test CLI path |
| 2 | Phase 2 | Repoint GHA variables; run E2E cloud test |
| 2 | Phase 3 | Delete bootstrap + old main stack; remove `s3-hook-lambda` + workflow scripts; set up distribution; update docs |
