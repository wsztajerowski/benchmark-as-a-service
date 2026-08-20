# baas-cli — review findings

Static review of `baas-cli` plus the infrastructure it owns, recorded 2026-07-30 so the walkthrough
can resume in a fresh session. Companion file:
[`benchmark-runner-findings.md`](./benchmark-runner-findings.md).

**Line numbers are as of the commit that added this file and will drift.** Each entry names a
logical anchor (resource name, method, statement `Sid`) — trust that over the line number.

## Scope of this bucket

`baas-cli` owns `cf-template-core.yaml`, `deployer-policy.json` and `operator-policy.json`: the
CLI bundles and deploys them, so every fix is an edit to a file that ships in `baas-cli.jar` or
its test resources. Two entries (S7, S8) constrain the *runner* but
are fixed here, because the template is the CLI's.

GitHub Actions workflows and `cf-template-ci.yaml` are in the benchmark-runner file — `baas`
neither dispatches nor depends on the workflows.

## Deliberately excluded

Everything in CLAUDE.md's *Accepted risks* table was skipped and should not be re-raised as a bug:
Atlas IP allowlist, runner-JAR checksum verification, shared `RunnerRole`, connect-only MongoDB,
the `baas run` project-layout assumption, and distribution mechanism.

## Status

| # | ID | Finding | Sev | Status |
|---|-----|---------|-----|--------|
| 1 | S4 | Deployer policy is an escalation primitive | High | **Partly fixed / partly accepted** |
| 2 | S5 | User-data built by concatenation, one value of eleven escaped | Med | Open |
| 3 | S6 | `eval` on benchmark parameters | Med | Open |
| 4 | S7 | `RunnerRole` can delete the entire results history | Med | **Partly fixed** |
| 5 | S8 | Shared-tag `TerminateInstances` — any runner can kill any other | Med | Open |
| 6 | S9 | `OperatorRole` trusts the account root unconditionally | Med | Open |
| 7 | D1 | `baas results` filtering/grouping documented but not implemented | Med | **Fixed** |
| 8 | A8 | `yum update -y` per run — unpinned OS under a benchmarking tool | Med | **Fixed** |
| 9 | A7 | Runner-JAR discovery hardcodes the upstream repo | Med | Open |
| 10 | A6 | Config: silent unknown keys, no schema version, stale default | Low | Open |
| 11 | A9 | `requestId` collides at second granularity | Low | Open |
| 12 | A5 | Sibling-command statics; `validateMongoUri` in three places | Low | **Fixed** |
| 13 | A3 | Mongo schema read by raw string paths, no shared contract | Low | **Fixed** |
| 14 | S11 | No TLS-only bucket policy; `~/.baas` default permissions | Low | Open |

**Next up: S5.**

---

## 1. S4 — deployer policy is an escalation primitive · PARTLY FIXED, PARTLY ACCEPTED

Two separate problems were bundled under one ID. One is fixed; the other is a deliberate
non-goal. Do not re-raise the second.

**Fixed — cross-deployer reach.** `deployer-policy.json` used to name `*-runner-role`,
`baas-*` and `parameter/*/mongo/…`, so one deployer's credentials reached every other
deployer's roles, bucket and SSM parameter. It is now a **template**
(`${ACCOUNT_ID}`/`${REGION}`/`${PREFIX}`) rendered per caller by `DeployerPolicyRenderer`; every
resource is prefix-exact. `baas admin deployer-policy` prints it, `--for-arn` renders it for
someone else, and `baas admin setup` prints it when a permission is missing
(`DeployerPreflight`: opportunistic `SimulatePrincipalPolicy` plus `AccessDenied` translation).

**Accepted — escalation to account admin.** `iam:CreateRole` writes the *trust* policy, so name
scoping cannot stop a deployer from deleting `<prefix>-operator-role`, recreating it trusting
their own user ARN with an inline `Action:*`, and assuming it. The deployer policy is therefore
equivalent to account admin.

Accepted on 2026-07-30: this is an internal tool for **development environments**, and the
deployer is a **trusted developer**. Recorded in CLAUDE.md's *Accepted risks* table.

A permissions boundary (`BaasCliDeployerBoundary` + a required `PermissionsBoundaryArn` on the
core template + an admin-owned bootstrap step) was built against this tree and dropped before it
landed: it worked, but it added a hard prerequisite before the first `setup` and only pays off in
a multi-principal account. **Don't reintroduce it without that context.** If it is ever needed
again, two non-obvious details cost the most time the first time round: the IAM statement must
split into boundary-conditioned and unconditioned halves
(`iam:PermissionsBoundary` is absent from `GetRole`/`DeleteRole`/`TagRole` request contexts), and
the boundary needs its own two-statement `RunInstances` split for the same reason the template
documents.

**Side effect worth knowing:** `deployer-policy.json` now reaches the **main** classpath, since
the CLI renders it at runtime. That reverses the old "infra files are test-classpath only" rule
for this one file; `operator-policy.json` and `cf-template-ci.yaml` are unchanged.

**Still unverified, and load-bearing for the accepted half:** that a same-account trust policy
naming a *specific* user ARN grants `sts:AssumeRole` without an identity-based allow (unlike
`:root`, which delegates to identity policies). If that turns out to be false, the escalation is
harder than described and the acceptance is on even safer ground.

## 2. S5 — user-data built by string concatenation · Med

`UserDataScriptBuilder.build()` wraps every value in single quotes but only
`BENCHMARK_PARAMETERS` gets `'` → `'\''` escaping. `RESULT_PATH` derives from the git branch, and
git permits `'` in ref names, so `git checkout -b "x'whoami'"` injects into a root cloud-init
script on a host whose instance profile can read the Mongo URI from SSM.

**Proposed fix:** apply one escape helper to every value, or emit the variable block as a
single base64 blob the script decodes so no value is ever parsed as shell.

**Still open, and the surface grew.** `prebaked-runner-ami` replaced `ASYNC_PROFILER_VERSION` with
`IMAGE_VERSION`, `AMI_ID` and `MANIFEST_SCHEMA_VERSION`, so the block is now thirteen exports on
the same unescaped path — the count in the original wording ("all eleven values") is stale. The
three new values are machine-generated (an `ami-` id, a semver from a repo file, an int constant)
and none is attacker-influenced, so the exploitable input is unchanged: `RESULT_PATH` via the
branch name. Fixing this should still be a single helper applied to the whole block rather than
per-value patching.

## 3. S6 — `eval` on benchmark parameters · Med

`UserDataScriptBuilder` line ~68: `eval "BENCHMARK_PARAMS_ARRAY=(${BENCHMARK_PARAMETERS})"`, and
`build()` only quotes params containing a space. Any param with `$`, a backtick, `;` or `"` either
breaks or executes. Same user, so robustness more than escalation — but avoidable.

**Proposed fix:** base64 the argument vector and `readarray` it, or write it to a file the runner
reads. Fix alongside S5, same file.

## 4. S7 — `RunnerRole` can delete the whole results history · Med

`cf-template-core.yaml`, `${ResourceNamePrefix}-runner-s3-policy` (~line 209-221) grants
`PutObject`/`GetObject`/**`DeleteObject`**/`ListBucket` bucket-wide. The runner executes an
arbitrary user JAR, which inherits the instance profile.

**Proposed fix:** drop `s3:DeleteObject` — `S3StorageService` only ever calls `putObject`.
Optionally scope `PutObject` to `${RESULT_PATH}/*`.

**Partly fixed** by `dynamodb-results-store`. `RunnerRole`'s grant on the results table is
`dynamodb:PutItem` + `BatchWriteItem` and nothing else — no `Scan`, no single-item `DeleteItem` —
asserted by a template test, so the runner can no longer sweep the measurement history it writes
to. Its S3 access to the bucket is unchanged, so the finding does not close.

## 5. S8 — self-termination can terminate everyone else's runs · Med

`${ResourceNamePrefix}-runner-ec2-terminate-policy` (~line 222-231) and the operator equivalent
(~line 326) gate `ec2:TerminateInstances` on `aws:ResourceTag/baas-role: benchmark-runner`, shared
by every concurrent runner.

**Proposed fix:** scope the runner's copy to itself using the `ec2:SourceInstanceARN` policy
variable in `Resource`. Verify against the account before rollout — the pattern is documented but
was not tested here.

## 6. S9 — `OperatorRole` trusts the account root · Med

`cf-template-core.yaml` ~line 271: `Principal: {AWS: <account>:root}` with no condition. The
template comment argues authorization is delegated to identity policies, which is a legitimate
pattern, but any principal holding a broad `sts:AssumeRole` on `*` silently becomes an operator.

**Proposed fix:** add an `sts:ExternalId` or `aws:PrincipalTag` condition. Interacts with S4's
escalation chain — worth doing together with any further IAM hardening.

## 7. D1 — `baas results` documents behaviour it does not have · Med

CLAUDE.md (*Result tagging*, ~line 172-174) states `baas results` filters `exclude_from_results`,
groups by `(benchmark, branch)` and keeps the highest-scoring run per group. None of it exists —
`grep -r exclude_from_results` finds only CLAUDE.md. `ResultsQueryService.toRows` does no
filtering, grouping or max-selection.

**Decide:** implement it (it was `benchmark_overview.sh`'s behaviour, now retired) or correct the
doc. Actively misleading either way. `queryAll()` is also unpaginated.

**Fixed** by `dynamodb-results-store`. `ResultsQueryService` was rewritten against the results
table and now does all three: `exclude_from_results` is a server-side filter expression, grouping
by `(benchmark, <group-tag>)` keeps the highest score per group, and the group tag defaults to
`branch`. Rows carrying no group tag are bucketed rather than dropped — dropping them was how the
retired script lost early runs. `--all` opts out of grouping, `--limit` bounds the output, and
`--tag`/`--benchmark-name`/`--living-branches`/`--request-id`/`--project` cover the rest of what
the doc claimed. Unit tests cover grouping and best-score selection, including the same benchmark
under two group values.

## 8. A8 — `yum update -y` on every boot · Med · **Fixed**

`UserDataScriptBuilder` line ~29 unpins the OS between runs of a benchmarking tool, so a kernel or
glibc change lands mid-experiment and reads as a score delta. Also spends paid instance-minutes
every run.

**Proposed fix:** pin the AMI, or drop the update in favour of a periodically rebuilt custom image.

**Fixed** by `prebaked-runner-ami` (see `openspec/changes/prebaked-runner-ami/`). User-data now
installs nothing: `yum update`, the Corretto install and the async-profiler download are gone, and
a test asserts the rendered script contains no `yum` invocation. The runner boots from an AMI
built by `baas admin build-image` from `infra/runner-image.yaml`, which pins the parent AL2023
image by exact ID and pins Corretto, `perf`, the AWS CLI and async-profiler by exact version.

The fix went further than the finding asked, because pinning alone would not have made runs
comparable:

- **The kernel tunables that move benchmark numbers are declared too** —
  `perf_event_paranoid`, `kptr_restrict`, transparent hugepages, swap. Previously these were
  whatever AL2023 defaulted to on the day an instance booted: uncontrolled variance sitting
  directly under a profiler.
- **Every run now records what it actually measured on** (`<result-path>/environment.json` plus
  `packages.txt`), written before the benchmark so it survives a crash, and results carry
  `imageVersion`/`instanceType` tags. Pinning stops drift going forward; the manifest is what lets
  anyone check whether two *existing* results are comparable.

Verified end to end against a live account, not just in unit tests: `perf` is present (the base
AL2023 image ships none, so JMH's `-prof perf`/`perfnorm`/`perfasm` were broken before this),
async-profiler resolves from the baked path and captures kernel stacks with resolved symbols, and
the declared tunables are in effect on a launched runner.

Note the side effect on the finding's second clause: boot time dropped from minutes of package
installation to a **51-second** end-to-end run.

## 9. A7 — runner-JAR discovery hardcodes the upstream repo · Med

`UserDataScriptBuilder` lines ~51-55 curl
`api.github.com/repos/wsztajerowski/benchmark-as-a-service/releases/latest` and extract the URL
with `grep | grep | head | sed`. A fork silently runs upstream's JAR; an unauthenticated rate-limit
yields an empty `RELEASE_URL` and an opaque downstream failure. Distinct from the accepted
checksum risk.

**Proposed fix:** make repo and release tag configurable; fail loudly on an empty URL.

## 10. A6 — config handling · Low

`ConfigService` disables `FAIL_ON_UNKNOWN_PROPERTIES`, so a typo'd key in a hand-edited
`config.yaml` is silently dropped. No schema version for migrations. `CONFIG_FILE` is a static
resolved from `user.home` at class-load, so the class is awkward to test without mutating system
properties. Separately, `BaasConfig.AwsConfig.coreStackName` still defaults to `"baas-main"` — a
stack name the current templates never produce.

## 11. A9 — `requestId` collides at second granularity · Low

`RunCommand` ~line 129-131 builds it from `benchmarkType + yyyyMMdd_HHmmss`; `resultPath` from
`branch/type/timestamp`. CLAUDE.md justifies request-ID-scoped S3 paths by "two developers on the
same branch overwrite each other's JARs" — which two developers starting the same type in the same
second still do. A short random suffix closes it.

## 12. A5 — command classes depend on each other's statics · Low

`RunCommand.operatorCredentialsWarning` is a `public static` on a command, called from
`ResultsCommand`, `ConfigShowSubcommand`, `ConfigSetSubcommand`, `ConfigSyncSubcommand`.
`validateMongoUri` exists three times: `SetupCommand`, `ConfigSetSubcommand`, and a third variant
with a different message inside `ResultsQueryService`. Both belong on `BaasConfig` or a small
`MongoUri` value type.

**Fixed** by `dynamodb-results-store`, by deletion rather than by extraction: `baas-cli` no longer
speaks MongoDB at all, so all three copies of `validateMongoUri` are gone along with `--mongo-uri`
itself. The duplicate-logic half of this finding is closed outright.

`RunCommand.operatorCredentialsWarning` **remains** a public static called from sibling commands,
now from two callers rather than four — `ConfigShowSubcommand` and `ConfigSetSubcommand` both
dropped it when their AWS calls went away. Still worth moving to `BaasConfig`; no longer worth its
own change.

## 13. A3 — Mongo schema read by raw string paths · Low

`ResultsQueryService` reaches into documents by string (`"_id.requestId"`,
`jmhResult.primaryMetric.score`) while `benchmark-runner` writes them through Morphia entities.
Rename a field there and `baas results` reports `0.000` rather than failing. Spans both modules —
the fix may belong in the runner (shared entity module) or here (one integration test that writes
with the runner's mapper and reads with this query service).

**Fixed** by `dynamodb-results-store`, via the shared-module route. `baas-model` now owns the
stored shape (`StoredMeasurement`), the key encoding (`ResultKeys`) and the item layout
(`MeasurementItemMapper`), and both the CLI and the runner depend on it. Neither side names an
attribute by a locally-declared literal, so a rename breaks compilation instead of returning
`0.000` — the exact failure mode this finding described. The key names are also asserted from
`baas-model`'s constants by `CoreTemplateTest` against the CloudFormation table definition, so a
rename cannot silently desync the schema from the infrastructure either.

## 14. S11 — smaller hardening · Low

No bucket policy denying `aws:SecureTransport: false`. `ConfigService` creates `~/.baas` with
default permissions; 0700 is free.
