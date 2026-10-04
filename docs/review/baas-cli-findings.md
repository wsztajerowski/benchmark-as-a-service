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
the `baas run` project-layout assumption, and distribution beyond `scripts/install.sh` — a
Homebrew tap, jpackage bundles, a native image, a Docker image.

## Status

| # | ID | Finding | Sev | Status |
|---|-----|---------|-----|--------|
| 1 | S4 | Deployer policy is an escalation primitive | High | **Partly fixed / partly accepted** |
| 2 | S5 | User-data built by concatenation, one value of eleven escaped | Med | **Fixed** |
| 3 | S6 | `eval` on benchmark parameters | Med | **Fixed** |
| 4 | S7 | `RunnerRole` can delete the entire results history | Med | **Partly fixed** |
| 5 | S8 | Shared-tag `TerminateInstances` — any runner can kill any other | Med | Accepted (decided 2026-10-02) |
| 6 | S9 | `OperatorRole` trusts the account root unconditionally | Med | Accepted (decided 2026-10-02) |
| 7 | D1 | `baas results` filtering/grouping documented but not implemented | Med | **Fixed** |
| 8 | A8 | `yum update -y` per run — unpinned OS under a benchmarking tool | Med | **Fixed** |
| 9 | A7 | Runner-JAR discovery hardcodes the upstream repo | Med | **Fixed** |
| 10 | A6 | Config: silent unknown keys, no schema version, stale default | Low | **Fixed** |
| 11 | A9 | `requestId` collides at second granularity | Low | **Fixed** |
| 12 | A5 | Sibling-command statics; `validateMongoUri` in three places | Low | **Fixed** |
| 13 | A3 | Mongo schema read by raw string paths, no shared contract | Low | **Fixed** |
| 14 | S11 | No TLS-only bucket policy; `~/.baas` default permissions | Low | Won't fix (decided 2026-10-02) |
| 15 | A10 | Caller-ARN prefix is unnormalised, so an SSO identity moves it every session | Med | **Fixed** |
| 16 | U1 | Teardown leaves the AMI pointer, AMI, snapshot and image record — and a later setup inherits them | Med | **Fixed** |
| 17 | U3 | A run whose CLI died is invisible: no `baas` command lists or stops it | Med | **Fixed** (`run-status-in-dynamodb`) |
| 18 | U5 | Region is never read from the environment; CI is right only because `AWS_REGION` equals the default | Med | **Fixed** |
| 19 | U11 | Runner shares the image builder's security group, so its 80/443 internet egress is unenforced policy | Med | **Fixed** |
| 20 | U2 | No CLI path from teardown residue to an empty account; the deployer cannot even list the residue | Low | **Partly fixed** (image); table and bucket → `export-before-teardown` |
| 21 | U4 | `env diff` takes result paths only; a run id fails with a misleading reason | Low | **Fixed** |
| 22 | U6 | `--aws-profile` on three of five admin commands, persisted by one | Low | **Fixed** |
| 23 | U7 | `config set --prefix` adopts an installation without the check `config sync` exists for | Low | **Fixed** |
| 24 | U9 | `run` uploads the benchmark JAR before checking the stack exists | Low | **Fixed** |
| 25 | U10 | `admin image` demands deployer credentials for a read the operator role can do | Low | Won't fix (decided 2026-10-02) |
| 26 | U12 | A rolled-back first create leaves a retained table only `aws` can clear | Low | → `export-before-teardown` (decided 2026-10-02) |
| 27 | F1 | `config show` wrote its payload to stderr | Low | **Fixed** |
| 28 | F2 | `--format` unvalidated on `run` and `results` | Low | **Fixed** |
| 29 | F3 | Teardown gate ignored `pending` runners | Low | **Fixed** |
| 30 | F4 | `ROLLBACK_COMPLETE` refusal unreachable from setup | Low | **Fixed** |
| 31 | F5 | Denied `DescribeImages` reported as "no image" | Low | **Fixed** |
| 32 | U16 | `--tag project=…` duplicated the instance's `project` tag and failed `RunInstances` | Low | **Fixed** |
| 33 | U19 | `deployer-policy` could not be told the region it renders for | Low | **Fixed** |
| 34 | U20 | An installed CLI cannot bake a changed runner image: the definition is read only from the JAR | Med | **Fixed** (`custom-runner-image`: operator extension; live check is its task 8.3) |
| 35 | U21 | Only `eu-central-1` can reach a built image: setup submits the bundled parent AMI and build-image refuses other regions | Med | Open → `custom-runner-image` (uncommitted apply); live check deferred in `openspec/changes/QUEUE.md` |
| 36 | U23 | `setup --region B` on an account installed in A blames a "retained" bucket and advises `aws s3 rb --force` on the live one | Med | Decided 2026-10-04: detect the bucket's region; not yet implemented |
| 37 | U22 | `config set --region` re-aims a machine without checking an installation is there | Low | Decided 2026-10-04: remove it; `config sync` derives the region from the bucket (also closes U18); not yet implemented |
| 38 | U24 | `--project A --tag project=B` stores measurements under B while the run item and S3 prefix say A | Low | Decided 2026-10-04: reject `--tag project=` outright; not yet implemented |
| 39 | U25 | A reserved `--tag` key is rejected only after the JAR upload, stranding an unlisted `input/` | Low | Decided 2026-10-04: queued batch after `custom-runner-image`, own commit |
| 40 | U26 | `--timeout` unvalidated: `0` disables the process timeout, a negative value fails the run as `failed:125` | Low | Decided 2026-10-04: queued batch after `custom-runner-image`, own commit |
| 41 | U27 | `run --format json` reports only `completed`/`failed`, never the stored status | Low | Decided 2026-10-04: add a `runStatus` field; not yet implemented |
| 42 | U28 | No lookup of one run by id; CI lists 50 runs and filters with `jq` | Low | Decided 2026-10-04 → `runs-command`: `runs show` prints the run item (prebaked review §11) |
| 43 | U30 | `runs terminate` cuts off the boot-log upload of a run whose instance already recorded its outcome | Low | Decided 2026-10-04: queued batch after `custom-runner-image`, own commit |
| 44 | U32 | Teardown's confirmation crashes without a terminal and exits 0 on abort | Low | Decided 2026-10-04: queued batch after `custom-runner-image`, own commit |
| 45 | U34 | Networking ids without `--use-existing-vpc` are silently ignored on create | Low | Decided 2026-10-04: queued batch after `custom-runner-image`, own commit |
| 46 | U37 | (uncommitted apply) the parent lookup omits deprecated AMIs, so a pinned release stops resolving | Low | **Fixed** in `custom-runner-image` before it shipped: `includeDeprecated(true)`; the pinned parent's `DeprecationTime` is 2026-11-01 |
| 47 | S13 | `baas download` writes outside its output directory for a key containing `../` | Med | Decided 2026-10-04: direct fix (normalise + refuse, literal path too, unit test), own branch off `next-release`, no OpenSpec change; not yet implemented |
| 48 | S14 | `RunnerRole` can overwrite the pinned runner JAR every later run executes | Med | Decided 2026-10-04: scope the runner grant + conditional seed, with S15, as one small OpenSpec change; no bucket policy; not yet proposed |
| 49 | S15 | `OperatorRole` (so CI too) holds bucket-wide `s3:DeleteObject` that no command uses | Low | Decided 2026-10-04: removed in S14's change |
| 50 | A16 | `retireQuietly` catches only `Ec2Exception`, so a network error fails a published build | Low | Decided 2026-10-04: catch `SdkException` + unit test; review-fixes branch; not yet implemented |
| 51 | C5 | Installation names hand-built in five places beside `BaasConfig`'s derivations | Low | Decided 2026-10-04: no dedicated work; the next change that adds a resource name introduces the helper and moves these onto it |
| 52 | C6 | `SetupCommand` carries an alias, repeats its reads, and duplicates `loadTemplate()` | Low | Decided 2026-10-04: drop the `resolvedStack` alias, share `loadTemplate()`; repeated DescribeStacks left; review-fixes branch |
| 53 | C7 | CLI dead code: an unused method, an unreachable branch, unused imports, stale comments | Low | Decided 2026-10-04: remove all, `STORE_ARGS` included; review-fixes branch |
| 54 | C8 | Repetition worth folding: `AwsClientFactory` ×9, two SHA-256 encoders, two run-item queries | Low | Decided 2026-10-04: all four now (generic client builder, `HexFormat`, reuse `find`, drop the two wrappers); review-fixes branch |
| 55 | U38 | A run that finishes before its `launched` write is reported as cancelled, exits 1 and is terminated again | Low | Open — needs a decision (§55) |

**Next up: U38 needs a decision; nothing else in this file is undecided.** U20 and U37 are fixed by `custom-runner-image`.

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

## 2. S5 — user-data built by string concatenation · FIXED

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

**Fixed (2026-10-02).** By the time of the fix the branch had left the path (the unified run
layout) and `PROJECT_NAME`, `BRANCH_NAME`, `BENCHMARK_PARAMETERS` and `RUNNER_TAGS` were escaped,
but the project still reached `RESULT_PATH` and `BENCHMARK_JAR_S3_KEY` raw. The sharper
consequence was not injection — whoever passes `--project` already owns the instance — but a
stray `'` leaving bash unable to parse the script at all, *before* the watchdog, so only the CLI's
poll cap would terminate the instance. Two parts:

- Every export goes through one `UserDataScriptBuilder.export` helper, machine-generated values
  included; `everyExportedValueSurvivesAQuoteAsLiteralText` feeds a quote into every value and has
  bash run the block.
- `baas run` refuses a project outside `[A-Za-z0-9._-]+`, explicit or git-derived, before any AWS
  call. That is exactly GitHub's repository-name alphabet, so no repository is rejected; a clone
  into a directory GitHub would not name is told to pass `--project`.

The `eval` second parse of `BENCHMARK_PARAMETERS` was S6, fixed separately.

## 3. S6 — `eval` on benchmark parameters · FIXED

`UserDataScriptBuilder` line ~68: `eval "BENCHMARK_PARAMS_ARRAY=(${BENCHMARK_PARAMETERS})"`, and
`build()` only quotes params containing a space. Any param with `$`, a backtick, `;` or `"` either
breaks or executes. Same user, so robustness more than escalation — but avoidable.

**Proposed fix:** base64 the argument vector and `readarray` it, or write it to a file the runner
reads. Fix alongside S5, same file.

**Fixed (2026-10-02)** with neither proposal: `build()` writes `BENCHMARK_PARAMS_ARRAY=(…)` and
`RUNNER_TAGS_ARRAY=(…)` as array literals, one single-quoted element per argument, by the same
rule the exports use. Bash parses each value once, as data, and both `eval` lines are gone — and
with them `escapeForEvaledDoubleQuote`, the tags' separate escaper for that second parse. No decode
step precedes the watchdog, and the runner's interface is unchanged.
`benchmarkParametersReachTheRunnerExactlyAsTyped` runs `$`, backticks, `$(…)`, `"`, `'`, `\`, `;`
and a space through bash and expects the argv back verbatim.

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

**Tightened** by `run-status-in-dynamodb`. The runner's `PutItem`/`BatchWriteItem` are restricted by
`dynamodb:LeadingKeys` to `RESULT#*`, and its one new grant, `UpdateItem`, to the `RUN` partition:
it can write measurements and its own run's status, and nothing else in the table. Still not
closed, for the S3 reason above, and `BatchWriteItem` still carries `DeleteRequest`s within
`RESULT#`.

## 5. S8 — self-termination can terminate everyone else's runs · ACCEPTED

`${ResourceNamePrefix}-runner-ec2-terminate-policy` (~line 222-231) and the operator equivalent
(~line 326) gate `ec2:TerminateInstances` on `aws:ResourceTag/baas-role: benchmark-runner`, shared
by every concurrent runner.

**Proposed fix:** scope the runner's copy to itself using the `ec2:SourceInstanceARN` policy
variable in `Resource`. Verify against the account before rollout — the pattern is documented but
was not tested here.

**Accepted (2026-10-02), recorded in CLAUDE.md's *Accepted risks*.** The only code on a runner
that could cross-terminate is the benchmark JAR, which only an operator can supply — and
`OperatorRole` already terminates any runner. The proposed self-only scoping was declined on its
failure mode: a subtly wrong condition denies the runner its *own* termination, disabling both the
normal path and the watchdog, and shows up only when a paid run hangs.

## 6. S9 — `OperatorRole` trusts the account root · ACCEPTED

`cf-template-core.yaml` ~line 271: `Principal: {AWS: <account>:root}` with no condition. The
template comment argues authorization is delegated to identity policies, which is a legitimate
pattern, but any principal holding a broad `sts:AssumeRole` on `*` silently becomes an operator.

**Proposed fix:** add an `sts:ExternalId` or `aws:PrincipalTag` condition. Interacts with S4's
escalation chain — worth doing together with any further IAM hardening.

**Accepted (2026-10-02), recorded in CLAUDE.md's *Accepted risks*.** Account-root trust is the
standard same-account delegation, and the principals that hold `sts:AssumeRole` on `*` —
administrators, `PowerUserAccess` — already hold the rights the role grants. A principal allow-list
would cost a deployer re-run per teammate and can lock every operator out on a wrong SSO pattern;
`sts:ExternalId` guards a cross-account confused deputy and is a shared string within one account.

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

*Later (`simplify-cli-options`):* `--all` is now `--all-runs` and also lifts the exclusion filter;
`--living-branches` is removed; grouping keys on `(project, benchmark, <group-tag>)`. The fix above
still holds — read the option names in it as historical.

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

**Fixed** by `unified-run-prefix`, by removing the call rather than parameterising it. The instance
no longer contacts GitHub at all: it copies a version-pinned JAR from
`releases/<version>/benchmark-runner.jar` in the working bucket, and the CLI seeds that slot from
the release matching *its own* version. The source repository became `runner.sourceRepo` in
`config.yaml`, so a fork points at its own releases; the fetch fails loudly, naming the repository,
version and asset, and uploads nothing. Both halves of the finding are closed — and because the
download moved to the laptop, it also became verifiable, which closed the separately-accepted
checksum risk.

## 10. A6 — config handling · FIXED

`ConfigService` disables `FAIL_ON_UNKNOWN_PROPERTIES`, so a typo'd key in a hand-edited
`config.yaml` is silently dropped. No schema version for migrations. `CONFIG_FILE` is a static
resolved from `user.home` at class-load, so the class is awkward to test without mutating system
properties. Separately, `BaasConfig.AwsConfig.coreStackName` still defaults to `"baas-main"` — a
stack name the current templates never produce.

**Narrowed by `installable-cli-command`.** One instance of the stale-default problem is gone:
`BenchmarkConfig.jarPath` defaulted to `jmh-benchmarks/target/jmh-benchmarks.jar` — a path from an
older project layout — and stood in as the build's fallback JAR whenever `--benchmark-jar` was
omitted. The whole key is deleted along with the build it fed: `--benchmark-jar` is now required,
with no config-file substitute, so there is nothing left to go stale there. `coreStackName`'s
default above is a separate instance of the same problem and is untouched by this change.

**Narrowed again by `account-derived-installation-naming`.** `aws.coreStackName` is deleted
outright — the stack name and the installation prefix are the same string now — taking its
`"baas-main"` default with it, and `prefix`'s own stale `"baas"` default is gone too. Two keys that
were read by nothing but `config show` are deleted as well: `aws.vpcId`, and
`benchmark.asyncProfilerVersion`, which reported `4.0` regardless of what the AMI actually held
because the manifest's value comes from `asprof --version` on the instance. Status stays **Open**:
silent unknown keys and the absent schema version remain.

**Fixed (2026-10-02).** `ConfigService` now logs `Ignoring unknown key '<dotted.path>' in <file>`
for each unknown key and keeps loading. Failing instead would break every upgrade, since files
from older releases carry retired keys, and any `save()` drops them anyway. That makes both the typo
(`operatorProfil:` silently falling back to ambient credentials) and the rename
(`ec2.wallClockHardKillSeconds` → `ec2.watchdogMarginSeconds`) visible. The testability complaint had
already gone with `ConfigService.at(path)`. **A schema version is declined:** no migration has
needed one, and should one ever be needed, an absent version can be read as version 1.

## 11. A9 — `requestId` collides at second granularity · Low

`RunCommand` ~line 129-131 builds it from `benchmarkType + yyyyMMdd_HHmmss`; `resultPath` from
`branch/type/timestamp`. CLAUDE.md justifies request-ID-scoped S3 paths by "two developers on the
same branch overwrite each other's JARs" — which two developers starting the same type in the same
second still do. A short random suffix closes it.

**Fixed** by `unified-run-prefix`. `RunId` is `<UTC instant, ISO basic, milliseconds>Z-<8 hex>` from
`SecureRandom` — the collision is closed rather than narrowed: it now needs the same millisecond
*and* the same 32-bit draw. The two identifiers this finding names are also gone; there is one
`runId` and one prefix, `runs/<project>/<runId>/`, and the CLI reads the clock once to produce
both. The fixed 28-character width additionally removed `ResultsQueryService`'s `truncate(…, 17)`,
which had been cutting inside the shared `<type>-<date>` prefix and rendering distinct rows
identically.

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

## 14. S11 — smaller hardening · WON'T FIX

No bucket policy denying `aws:SecureTransport: false`. `ConfigService` creates `~/.baas` with
default permissions; 0700 is free.

**Won't fix (2026-10-02).** Neither part protects anything currently exposed. Every bucket client
— the CLI and runner through the SDK, the instance through `aws s3 cp` — already speaks HTTPS, so
the deny policy would only guard a hypothetical future client, at the price of a template resource,
two deployer actions and a live `setup` to prove them. `~/.baas/config.yaml` holds a prefix, a
region, profile *names* and preferences; the secrets live in `~/.aws`, so 0700 would guard nothing.
Revisit (a) if a compliance check ever requires it.

## 15. A10 — the caller-ARN prefix is unnormalised · Med · FIXED

`SetupCommand.computePrefix` hashes the raw ARN from `sts:GetCallerIdentity`. For an IAM user
(`arn:aws:iam::123:user/alice`) that is stable. For an SSO or assumed-role identity the ARN is
`arn:aws:sts::123:assumed-role/Role/<session-name>`, and the session name changes per login.

Only `SetupCommand:76` and `DeployerPolicyCommand:54` derive the prefix; every other command reads
`config.getPrefix()`. So day-to-day use is unaffected, and the damage is concentrated in a second
`baas admin setup`: it creates a duplicate stack, bucket and results table — both retained by
`DeletionPolicy` — and rewrites `~/.baas/config.yaml` to point at the empty one. It presents as
"all my benchmark history is gone". The history is intact in the first table; nothing says so.
`baas admin deployer-policy` compounds it by rendering a policy for a prefix that does not match
the user's existing stack.

**Fixed** by `account-derived-installation-naming`, but not by the fix proposed below, and one
premise above is wrong: the SSO ARN does **not** change per login. `AWSReservedSSO_<set>_<hex>` is
minted when the permission set is provisioned into the account and does not rotate, and the session
name is a stable identity-store attribute. The conclusion survives on five other triggers, none of
which fails loudly: switching permission set, re-provisioning one, an explicit
`--role-session-name`, an IAM-user-to-SSO migration, and two humans on one permission set.

The proposed fix was **rejected**: normalising `assumed-role` ARNs to their role ARN
(`arn:aws:sts::123:assumed-role/Role/session` → `arn:aws:iam::123:role/Role`) survives re-login and
session-name variation, but still forks on a permission-set switch, still leaves the name
underivable without local state, and still gives every identity its own runner AMI and results
table. It also moves every SSO user's prefix, paying a full migration for a partial fix.

What changed the trade-off is that the pinned AMI and the DynamoDB results table both arrived
*after* this finding was written. Both are account-level assets that want exactly one instance, so
a name that moves with the caller does not merely duplicate infrastructure — it silently produces
incomparable measurements. The prefix is now `baas-<accountId>[-dev]`, derived from
`GetCallerIdentity().account()`, with nothing about the calling principal reaching it.

---

## 16–46. CLI usage analysis (U*, F*) · 2026-10-01, refreshed 2026-10-04

Found by the command-surface review and paid lifecycle test in
[`docs/analysis/cli-usage-analysis.md`](../analysis/cli-usage-analysis.md), which holds each
entry's evidence, the state graphs that locate it, and the proposed simplification. The table
above lists the actionable ones; U8, U13, U14, U15, U17, U18, U29, U31, U33, U35 and U36 are
informational and live only there. Rows 35–46 come from the 2026-10-04 static refresh of that file.
F1–F5 were fixed on the `cli-usage-analysis` branch with a test each.

**U3 fixed by `run-status-in-dynamodb` (2026-10-03).** Run status lives on a run item in the results
table (`pk = RUN`), written by the CLI before and after the launch and by the instance itself.
`baas runs list` shows every run of every project, with a run whose instance is gone and has no
outcome shown as vanished. `baas runs terminate` stops one. Teardown's refusal names run ids and
that command.

## 47–54. Whole-repository pass (S13–S15, A16, C5–C8) · 2026-10-04

Static read of every main source file, the templates, both policies, the workflows and `scripts/`,
looking for security issues, dead code and simplifications. The then-uncommitted `custom-runner-image`
work was read but is not judged here — it has its own verify. Excluded on purpose: everything in
CLAUDE.md's *Accepted risks*, the JMH-service template refactor A1 declined, and
`teardown --delete-bucket` destroying results now that `runs/` holds them (`export-before-teardown`
removes the flag). `C` marks cleanup: dead code or a simplification with no behaviour change.

### S13 — `baas download` writes outside its output directory · Med

`DownloadCommand.call()` resolves each listed key's suffix with
`destinationRoot.resolve(key.substring(prefix.length()))` and writes there, without normalising.
An S3 key is an arbitrary string, so `runs/p/<runId>/../../../.zshrc` is a legal key under the run's
prefix, and `S3UploadService.download` writes it to `./<runId>/../../../.zshrc`, which is outside
the output directory. Anything holding `s3:PutObject` on the bucket can plant such a key:
`RunnerRole`, i.e. any code in a benchmark JAR or its dependencies, and `OperatorRole`, i.e. every
workflow CI federates. The operator who later runs `baas download` usually holds deployer
credentials in `~/.aws`, so this crosses the trust boundary from a throwaway instance to the laptop.
This is the zip-slip pattern.

**Proposed fix:** `Path target = destinationRoot.resolve(suffix).normalize()`, then refuse and log
any key whose target does not `startsWith(destinationRoot.normalize())`, plus a unit test with a
`../` key. `RunReference` should apply the same check to a literal `<resultPath>` argument.

**Decided (2026-10-04):** as proposed, as a direct fix on its own branch from `next-release`, with no
OpenSpec change (no behaviour change for a well-formed key).

### S14 — `RunnerRole` can overwrite the pinned runner JAR · Med

`${prefix}-policy-runner-s3` grants `s3:PutObject` on `${ResourceNamePrefix}/*`, which includes
`releases/<version>/benchmark-runner.jar`. CLAUDE.md rests the whole pinning argument on that object
being "seeded once and never overwritten", but that holds only by CLI convention:
`RunnerJarResolver.resolve` treats any object already present as trusted and never re-verifies it.
So code in one benchmark JAR can replace the runner that **every later run of that version
executes**, under `RunnerRole`, for as long as the version is in use. That persists across runs,
which S7 (deleting history) does not cover. Versioning is `Suspended`, so the original cannot be
recovered.

**Proposed fix:**
1. Scope the runner's S3 grant to what it actually does. `PutObject` and `GetObject` go on `runs/*`.
   `GetObject` also goes on `releases/*`, for user-data's `aws s3 cp` of the runner. Drop
   `DeleteObject` entirely, since no runner code path deletes. That also closes S7's S3 half, apart
   from overwriting another run's objects under `runs/*`, which has no per-run IAM scope to use.
   Pin it in `CoreTemplateTest`.
2. Make the seed itself non-overwriting: `PutObject` with `If-None-Match: *` in
   `RunnerJarResolver`, which also removes the head-then-put race between two first runs of a new
   version. A bucket-policy condition enforcing conditional writes on `releases/*` would make it
   server-side for the operator too. Confirm that the `s3:if-none-match` condition key exists before
   relying on it.

**Decided (2026-10-04):** step 1 plus the conditional seed of step 2, together with S15, as one small
OpenSpec change. One template edit means one `baas admin setup` re-run per installation. The
bucket-policy enforcement is left out as extra mechanism.

### S15 — `OperatorRole` holds bucket-wide `s3:DeleteObject` that no command uses · Low

`${prefix}-policy-operator-s3` and `operator-policy.json` grant `s3:DeleteObject` on the whole
bucket. Nothing in `baas-cli` running under the operator role deletes an object (the only delete is
`S3UploadService.deleteAllObjects`, called by teardown under deployer credentials). CI federates into
this role on `pull_request`, so any same-repository branch's workflow can wipe every run's
artifacts, unrecoverably (versioning is `Suspended`). The `cli-driven-ci-workflows` note under S3 in
the runner file already called it "unexercised". **Proposed fix:** remove it from both documents.
`OperatorPolicyDriftTest` keeps them in step.

### A16 — `retireQuietly` catches only `Ec2Exception` · Low

`ImageBuilderService.publish` repoints the pointer and then calls `retireQuietly`, whose contract is
that retiring the replaced AMI "must not fail a command whose paid work has succeeded" (P2). It
catches `Ec2Exception` only, so an `SdkClientException` (timeout, DNS, expired session) from
`describeImages`/`deregisterImage` propagates and `build-image` exits non-zero over a published
image, which invites the needless ~15-minute rebuild P2 was fixed to prevent. **Proposed fix:**
catch `SdkException`, as `retireInstallation` already does.

### C5 — installation names hand-built beside `BaasConfig`'s derivations · Low

CLAUDE.md: *every resource name is `<prefix>` or `<prefix>-<type>-<name>`, one rule*. `BaasConfig`
derives the table, profile and pointer path, but the same strings are rebuilt by hand in
`RunCommand.resolveRunnerImage` (`"/" + prefix + "/runner/ami-id"`), `BuildImageCommand`
(pointer path and `-component-runner`), `TeardownCommand` (pointer path, `-results`,
`-recipe-runner`) and `DeployerPreflight` (pointer ARN, `-role-runner`, `-role-operator`,
`-results`). Teardown needs names for a prefix other than the configured one, which is presumably
why. **Proposed fix:** one `InstallationNames.of(prefix)` (or static methods on `BaasConfig` taking
the prefix) that every caller uses, the same move `RunLayout` made for S3 keys.

No live bug: every copy matches today. The risk is silent drift, e.g. teardown "retiring" a pointer
that does not exist, which brings U1 back. Names are effectively frozen by deployed installations, so
drift is likeliest when a *new* name is added by copying the pattern. **Decided (2026-10-04):** no
dedicated work. The next change that adds a resource name introduces the helper and moves the
existing copies onto it.

### C6 — `SetupCommand` duplication · Low

*Corrected 2026-10-04:* the original entry also listed a duplicated `params.put("ResourceNamePrefix", …)`.
There is none. The review's own view printed one line twice from overlapping ranges.

In `deploy()`: `resolvedStack` is an alias of `resolvedPrefix` threaded through two methods; the update branch calls
`getStackParameters(resolvedStack)` twice and `stackExists` is asked twice in one invocation, across
four separately built CloudFormation clients. `loadTemplate()` is copied verbatim in
`BuildImageCommand`. **Proposed fix:** one client and one `describeStack` per invocation; move
`loadTemplate()` beside `CloudFormationService`.

### C7 — CLI dead code, an unreachable branch, stale text · Low

- `SsmService.putSecureParameter`: no caller. It is the last trace of the Mongo connection-string
  parameter.
- `DownloadCommand`: the run-id block that calls `config.resultsTable()` inside a `try` can never
  catch. `config.bucket()` above it already ran `requirePrefix()`, the only thing `resultsTable()` can
  throw from. `tableUnresolvable()` is called only by tests.
- `RunCommand.resolveResultsTable` re-implements `requirePrefix()`'s check with a second message;
  `jarPath` is an alias of `benchmarkJar`.
- Unused imports: `SsmService` in `RunCommand`, `MessageDigest` in `SetupCommand`.
- Stale comments: `Ec2ProvisioningService.instanceState` ("before writing its sentinel" — the S3
  `run-status` object is gone); `UserDataScriptBuilder.build` ("`benchmarkMetadata.tags` is the query
  surface" — the item's top-level `tags` map is).
- `UserDataScriptBuilder`'s `STORE_ARGS` is a one-element array that no longer has alternatives;
  inline `--results-table "${RESULTS_TABLE}"`.

### C8 — repetition worth folding (optional) · Low

`AwsClientFactory` repeats the same three builder-and-profile lines nine times; one generic
`configure(builder)` helper removes them. `RunnerJarResolver.sha256Hex` hand-rolls the hex encoding
`RunnerImageExtension.hash` gets from `HexFormat`. `ResultsQueryService.resultPathForRun` and
`DynamoDbRunRecorder.find` issue the same `gsi1sk = RUN` query. `Ec2ProvisioningService.state` only
delegates to `instanceState`, and `RunSession.stop` only to `stopWith`. None of these is worth a
change on its own; fold them into whichever change next touches the file.

**Decided (2026-10-04): all four now.** The fixes:
- `AwsClientFactory` gets one generic `build(builder)` that sets the region and profile, and each method becomes one line.
- `sha256Hex` becomes `HexFormat`.
- `resultPathForRun` reuses `DynamoDbRunRecorder.find`.
- `instanceState` is renamed to `state`, and `stop` returns the status so `stopWith` can go.

## 55. U38 — a run that finished before its `launched` write is reported as cancelled · Low

Observed live 2026-10-04 during `custom-runner-image` verify (its `verify.md`, W5), run
`20261004T162557253Z-1cf464a0`. On a very slow connection `RunInstances` answered about five minutes
late. By then the instance had booted, run the benchmark, stored its measurement and recorded
`completed`. The CLI's conditional `launched` write was then refused, correctly, because the status was
terminal. But `RunSession.confirmLaunched` reads *any* terminal status after a refusal as "stopped
while it was launching". So it logged `Run … was stopped (completed) while it was launching`,
terminated an instance that was already terminating itself, and `RunCommand` exited 1. The run had
succeeded, and its run item and measurement are correct. Only the CLI's exit code and message are
wrong, but CI would show a red job for a good run.

The case the branch exists for is `cancelled` (and `timed-out`/`launch-failed`) written by this CLI's
own shutdown hook or a `baas runs terminate` while `RunInstances` was in flight. `completed` and
`failed:<n>` can only come from the instance, so they mean that the instance got there first, not
that the run was stopped.

**Proposed fix:** in `confirmLaunched`, treat a refusal over `completed`/`failed:<n>` as confirmed and
let the poll report the outcome it finds (the poll already handles a terminal status on its first
read). Keep `CANCELLED_WHILE_LAUNCHING` for the statuses the CLI side writes. Do not terminate in the
first case: the instance is mid-upload and terminates itself, which is the same rule `RunSession.stop`
already follows. Add a unit test with a recorder that refuses `launched` over `completed`. This
belongs to `run-status-in-dynamodb`'s logic (archived), so it is a direct fix with no OpenSpec change
unless the spec's scenario names this path.
