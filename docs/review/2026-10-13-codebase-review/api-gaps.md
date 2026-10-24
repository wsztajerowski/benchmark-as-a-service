# API gap review — `codebase-review` @ dabbc55, 2026-10-13

Re-checked against slice-architecture (post-refactor) on 2026-10-15.

Re-verified against 7d9660a on 2026-10-14.

Post-refactor re-check: every citation was re-read at the head of `slice-architecture` (ADR 0009,
`0308fb8..a8be1a0`), moved classes found by name. `commands/`, `infra/` and `config/` became the slices
`jobs`, `results`, `deployment` (+ `deployment/setup`), `image`, `config` over the kernel packages `cli`,
`console`, `session` and `aws`; `baas-model` gained `BenchmarkType`, `RunnerArgs`, `JobItemConditions`
and `JobLayout`'s artifact names; `JobLaunch`/`RunAws` were extracted from `RunCommand`; every JSON payload
escapes its strings through `console.Json`, and CSV payloads use `console.Csv`. `infra/`, `.github/` and `openspec/specs/` did not
change, so their citations stand. G16 is fixed, and the escaping half of G17 is fixed (G17 stays open,
narrowed). Every other finding is open with its citations rewritten; none was withdrawn. The CLI's help
and offline behaviour were verified byte-identical before and after, so the appendix is unchanged. The
*Status @ slice-architecture* column of the index records each outcome.

Re-verification at 7d9660a: every citation was re-read at 7d9660a (`next-release` after `interactive-setup`).
`benchmark-runner`, `baas-model`, `.github/` and the cited result, job and config classes did not
change. `RunCommand`, `ResultsQuerySubcommand`, `ImageShowSubcommand`, `TeardownCommand`,
`ImageBuilderService` and `cf-template-core.yaml` shifted, and `SetupCommand` was split into
`commands/admin/setup/*`. No finding was resolved or withdrawn. G14 changed in substance. G7, G12 and
G20 were widened to the new setup surface rather than filed again. The *Status* column of the index
records each outcome in *Status @ 7d9660a*.

Scope: the `baas` command surface, the runner's CLI contract as user-data drives it, `baas-model`, and
the stored data contract. Read-only; the CLI was built offline and every `--help` captured (appendix).
Grades follow `docs/review/open-findings.md` (Must/Should/Could; XS–L). Nothing below re-files an
open finding, a U-series item, an ADR 0005 decision or a CLAUDE.md accepted risk; overlaps are cited.

## Summary

1. **One false green (G2, Must):** a JCStress job whose tests fail exits `completed`/0, and its pass/fail counts — stored on every item — are readable by no `baas` command; JMH secondary metrics (`-prof gc`) are stored and unreadable the same way.
2. **One guard bypass (G1):** `baas run`'s `--tag`/`--project` guards (U24 and the machine-observed rule) are bypassed by putting the same runner options after `--`, because PARAMS are appended last and the runner's `--tag` map is last-wins and `--project` beats the tag.
3. **CI integration gaps:** machine formats are silently cut to 20 rows (G6); there is no waiter, so CI hand-copies the terminal-status set into bash (G5); exit codes do not separate "benchmark failed" from "couldn't launch" (G7); `jobs diff` cannot gate (G11).
4. **Lifecycle asymmetries:** you can write measurements but never correct, re-tag or exclude one afterwards (G3); compare environments but not scores (G4); record a job but not *what* it ran (G8); configure a deployment locally but not forget one (G13); grow storage with no prune short of teardown (G15).
5. **Deployment surface after `interactive-setup`:** setup now reads the deployed GitHub trust back and offers it as the default, which narrows G14 to a read-only, machine-readable `deployment show`. Its unattended mode inherits the existing gaps: a step waiting on an IAM administrator exits 1 like a failure (G7), its outcome is prose only (G12), and its image step, like `admin image build`, never points to the build logs (G20).
6. **Schema versioning is in good shape** — `environment.json` parses tolerantly and `jobs diff` warns on mismatch; items are read field-by-field (see *Considered, not filed*). The remaining model issue is small: contract docs are stale (G19). The duplicated type vocabulary (G16) was fixed by the slice-architecture refactor's `BenchmarkType`.

## Index

| ID | Gap | Necessity | Effort | Who | Already queued? | Status @ 7d9660a | Status @ slice-architecture |
|---|---|---|---|---|---|---|---|
| G1 | PARAMS after `--` override the runner options the CLI guards (`--tag imageVersion=`, `--tag project=`, `--project`, `-m`) | Should | XS | user, CI | No (reopens U24 by another route); `-m` alone goes with `retire-mongodb` (QUEUE #3) | Valid, relocated | Open, relocated (`RunnerArgs` now holds the shared option names; nothing scans PARAMS) |
| G2 | JCStress verdict and JMH secondary metrics are stored but readable by no command; a failing JCStress job is a green `completed` | **Must** | S | user, CI | No | Valid | Open, relocated |
| G3 | No way to exclude, re-tag or delete a measurement after the run | Should | M | user, operator | No | Valid, relocated | Open, relocated (no citation moved) |
| G4 | No score comparison between jobs/baselines (`results compare`); CI regression gates hand-roll jq | Should | M | CI, user | Anticipated in `jobs-command` exploration, not queued | Valid, relocated | Open, relocated |
| G5 | No `jobs wait`; CI copies the terminal-status set into bash | Should | S (XS for a `terminal` field) | CI | Declared non-goal "additive later" in `detached-run` (archived) | Valid | Open, relocated |
| G6 | `--format json/csv` silently truncated to 20 rows; no total/hasMore in the payload | Should | XS | CI, scripts | No | Valid, relocated | Open, relocated |
| G7 | Exit codes don't separate failure kinds; runner's own exit code isn't propagated; unattended setup exits 1 both for a failure and for a step awaiting an IAM admin | Could | S | CI, admin | No (U27 fixed the JSON side only; `interactive-setup` kept wait = exit 1 on purpose) | Valid, relocated; widened to setup | Open, relocated (exit-1 paths split across `RunCommand`/`JobLaunch`) |
| G8 | The job record doesn't say what ran: benchmark args, runner version/override, timeout, JCStress mode, JAR digest | Should | S | user, CI | Mode tag deferred in `jcstress-e2e` | Valid, relocated | Open, relocated (runner-JAR log now in `JobLaunch`) |
| G9 | Runner PARAMS are first validated on the paid instance (`failed:2` after boot) | Could | M | user | No | Valid | Open, relocated (CLI-owned args now contract-tested; PARAMS still not) |
| G10 | Runner can't pass JMH `-p` param overrides or more than one include regex | Should | S | user | No | Valid | Open, relocated |
| G11 | `jobs diff` has no JSON and exits 0 whether or not environments differ | Could | S | CI | No | Valid | Open, relocated |
| G12 | `config show`, `admin image show`, `admin image build`, unattended `admin deployment setup`, `jobs terminate`, `jobs download` have no machine-readable output | Could | XS each | CI, admin | No | Changed (scope widened to setup and image build) | Open, relocated |
| G13 | No way to forget a deployment locally (or reset a preference to default) | Should | XS | operator | No | Valid, relocated | Open, relocated |
| G14 | No read-only, machine-readable view of the deployment's server-side state; unattended `--github-repo` still replaces the list | Could | S | admin | No | Changed (setup now reads the trust back interactively) | Open, relocated |
| G15 | No prune/retention: `jobs/` (incl. every `input/benchmark.jar`) and items grow until teardown | Could | M | admin | Partly adjacent to `export-before-teardown` (QUEUE #2, U2), N3 | Valid | Open, relocated (no code cited) |
| G16 | Benchmark-type vocabulary defined twice (CLI list vs runner subcommands), not in `baas-model` | Could | XS | maintainer | No | Valid | **Fixed by the refactor** (`BenchmarkType`) |
| G17 | `results query` JSON: unescaped string fields; no `project`, `kind`, artifact keys | Could | XS | CI | W5 covers only `mode:"null"` | Valid, relocated | Open, changed (escaping fixed; missing fields remain) |
| G18 | No non-interactive project listing | Could | XS | CI, scripts | No | Valid, relocated | Open, relocated |
| G19 | Contract docs are stale (JobLayout javadoc, StoredMeasurement, CLAUDE.md "CI mints its id", `admin image show` "for this account") | Could | XS | maintainer | `StoredMeasurement` goes with `retire-mongodb` (QUEUE #3) | Valid (one bullet added) | Open, relocated (all five bullets still true) |
| G20 | Image-build logs under `image-builds/` are never pointed to, by `admin image build` or by setup's image step | Could | XS | admin | No | Valid, relocated; widened to setup | Open, relocated |
| G21 | `baas run` has no caller idempotency key; a retried CI step launches a second paid instance | Could | S | CI | No | Valid | Open, relocated |

---

## Findings

### G1 — PARAMS can override the runner options the CLI guards · Should · XS · user, CI

**Evidence.** User-data runs the runner with the CLI's own options first, then caller tags, then
the observed tags, and **then** `"${BENCHMARK_PARAMS_ARRAY[@]}"`, which is everything after `--`
(`jobs/UserDataScriptBuilder.java:269-280`). The comment at `:252-268` says the observed tags come last
"as defence in depth" because picocli's `--tag` map is last-wins. PARAMS come later still. The runner
declares `--tag` as a `Map` (`ApiCommonSharedOptions.java:24-25`), and `getProject()` prefers
`--project` over the `project` tag (`ApiCommonSharedOptions.java:108-121`). `RunCommand` checks only its
own `--tag` against `RESERVED_TAG_KEYS`/`project` (`jobs/RunCommand.java:434,466-484`). Nothing scans
`benchmarkParams` (`jobs/RunCommand.java:120-122`; `JobLaunch.java:167` hands it to user-data unread). So:

- `baas run … jmh -- MyBench --tag imageVersion=1.0.0` stores a forged `imageVersion` that disagrees with `environment.json`, which CLAUDE.md says is rejected outright.
- `-- --project other` (or `-- --tag project=other`) writes measurements to `RESULT#other` while the job item and S3 prefix say the original project: exactly the U24 split.
- `-- --results-table x` / `--s3-bucket` / `--job-id` fail on the instance as picocli overwritten options, after boot, as `failed:2`.
- `-- -m sanity` (the JCStress gotcha in CLAUDE.md) reaches the Mongo option.

**Recommendation.** In `RunCommand`, before any AWS call, refuse any PARAMS token naming a
runner-owned option: `--tag`, `--project`, `-id/--job-id`, `--created-at`, `--result-path`,
`--s3-bucket`, `--s3-service-endpoint`, `--results-table`, `--dynamodb-endpoint`, `-m/--mongo-connection-string`
and `--benchmark-path`. Name the `baas run` option to use instead, and for `-m` point to
`--mode`. Keep the list in `baas-model` next to `TagKeys` so the runner and the CLI share it.

*Post-refactor.* That home now exists: `baas-model`'s `RunnerArgs` names the CLI-owned options
(`--job-id`, `--created-at`, `--result-path`, `--s3-bucket`, `--benchmark-path`, `--results-table`,
`--tag`, `--project`), and the runner's annotations read them. The guard is a check of PARAMS against
`RunnerArgs` plus the three it lacks (`-m/--mongo-connection-string`, `--s3-service-endpoint`,
`--dynamodb-endpoint`).

### G2 — The JCStress verdict and JMH secondary metrics are written but unreadable · **Must** · S · user, CI

**Evidence.** Every measurement item carries `jcstress` (`totalTests`, `passedTests`, `failedTests`,
`errorTests` and the failed/error/interesting test maps; `baas-model` `JcstressSummary.java:9-17`) and
`secondaryMetrics` (`StoredMeasurement.java:30-31`; `results-store-schema/spec.md:85,98`). `ResultRow.from`
(`results/ResultRow.java:53-68`) copies neither, so the table, JSON and CSV of `results query`, and `baas run`'s
post-job table, never show them. The JCStress runner deliberately does not fail on failed tests
(`JCStressSubcommandService.java:83-89`). A job whose concurrency tests fail therefore ends
`completed`, `baas run` exits 0, and its single row has no score. The only signal is in the
`index.html` that `baas jobs download` fetches. CLAUDE.md confirms CI "asserts the stored item's shape
only, never pass/fail counts". The same applies to `jmh-with-prof -pr gc`, whose purpose is
`gc.alloc.rate.norm`: it is stored and never shown.

**Recommendation.** Carry `jcstress` and `secondaryMetrics` into `ResultRow`. In JSON add `jcstress:
{total,passed,failed,errors,failedTests[]}` and `secondaryMetrics: {name:{score,unit}}`. In the table,
show a JCStress row's score cell as `passed/total` with a failure mark, and put secondary metrics on
`-v` lines the way params are shown. Decide separately whether `baas run` should exit non-zero (or
report `jobStatus` plus a `verdict`) when `failedTests + errorTests > 0`. That is the false-green fix.

### G3 — A measurement cannot be corrected after the fact · Should · M · user, operator

**Evidence.** `exclude_from_results` is "the" way to hide noise, but it is set only by `baas run --tag`.
The operator's only table write is `UpdateItem` on `LeadingKeys = JOB` (`infra/cf-template-core.yaml:752-762`),
and no command writes `RESULT#*`. A job run with a wrong flag, on a noisy host (E6), or before a fix
stays in every `--best-per` and default listing for the deployment's life. The only remedy is raw
`dynamodb update-item` with an identity the operator role does not have.

**Recommendation.** Add `baas results tag --job-id <id> --set k=v | --unset k`, the job being the unit,
for `exclude_from_results` and corrections such as a missed `branch`. It needs an operator grant on
`RESULT#*` restricted to `UpdateItem` of `tags` (or an admin-side command, if the narrowing matters
more). Measurement fields themselves stay immutable. Delete is the larger decision, and belongs with G15.

### G4 — No score comparison between jobs or against a baseline · Should · M · CI, user

**Evidence.** `jobs diff` compares environments only (`jobs-command` design D6). `results query
--job-id` takes one id (`results/ResultsQuerySubcommand.java:81-83`; `:176-184` and `jobIdConflict` at `:319-325` refuse combinations). The
`jobs-command` exploration expected it: "A later action (comparing two branches) gets its own verb,
e.g. `results compare`" (`archive/2026-10-07-jobs-command/exploration.md:196`). It is in no queue. A
PR regression gate, the obvious CI use of a benchmarking service, means two queries and a jq join on
`(benchmarkName, mode, paramsKey)` written by every consumer.

**Recommendation.** `baas results compare <jobA|--tag k=v> <jobB|--tag k=v> [--format json] [--threshold pct]`,
keyed exactly as `--best-per` keys. Report the delta and whether the error intervals overlap. Exit
non-zero past the threshold. Refuse, or warn, when `jobs diff` would report an environment
difference (reuse `EnvironmentManifest.diff`).

### G5 — No waiter for a detached job · Should · S · CI

**Evidence.** `detached-run`'s non-goal: "a `jobs wait` command (exploration §9: additive later)"
(`archive/2026-10-12-detached-run/design.md:41`). As a result this repository's own CI carries a 25-line
poll loop that hard-codes the terminal vocabulary `completed|timed-out|cancelled|launch-failed|failed:*|vanished`
(`.github/workflows/e2e-cloud-test.yml:162-189`), a copy of `JobStatus.EXACT_TERMINAL` plus the
computed `vanished`. A new status would make every consumer's loop spin until its deadline.
`jobs show --format json` exposes `status`/`storedStatus` but no `terminal` flag
(`jobs/JobsShowSubcommand.java:192-204`).

**Recommendation.** At minimum (XS), add `"terminal": true|false` to `jobs show` and `jobs list`
JSON. Better (S), add `baas jobs wait <jobId> [--timeout s] [--format json]`, which polls by id up to the
watchdog bound, prints the same summary object as `run --format json` and exits as an attached run
would. It reuses `JobSession.await` minus the termination authority.

### G6 — Machine formats are silently truncated to 20 rows · Should · XS · CI, scripts

**Evidence.** `DEFAULT_LIMIT = 20` applies whatever the format (`results/ResultsQuerySubcommand.java:114-129,255-261`);
`jobs list` does the same (`jobs/JobsListSubcommand.java:79-81,194-199`). The "Reporting 20 of N" note
goes to the logger on stderr. JSON is a bare array with no count or continuation, so
`baas query --project p --format json | jq` over "all results" gets 20 rows, and CSV export does too.
CI's own use escapes this only because `--job-id` is unlimited.

**Recommendation.** Either default to no limit for `json`/`csv`, keeping 20 for the table, or keep the
limit and say so in the payload. The first is simpler and matches what a pipe expects. If an envelope
is ever introduced (`{"total":N,"offset":…,"items":[…]}`), version it, since the bare array is a
contract today.

### G7 — Exit codes do not separate failure kinds · Could · S · CI

**Evidence.** Any job outcome other than `completed` maps to 1 (`jobs/JobSession.java:231,252,270,281`). So
do config errors, AWS/auth errors and launch failures (`jobs/RunCommand.java:262,295`, and since the
refactor `jobs/JobLaunch.java:114,187,207,257`; picocli's `exitCodeOnExecutionException`, `BaasApp.java:203`). 2 is for usage. The runner's own exit code,
recorded as `failed:<n>`, is not propagated, so a runner argument error (`failed:2`) looks the same as a
benchmark crash. U27's `jobStatus` fixed this for `--format json` callers only.

*Widened at 7d9660a.* `admin deployment setup --non-interactive`, or setup without a terminal, flattens
the same way. A step that waits on someone outside `baas` (the deployer policy to attach, the OIDC
provider's audience) returns 1 (`deployment/setup/SetupWalkthrough.java:41-48`), as do every `Stop` and every failure.
A pipeline running unattended setup cannot tell "an IAM administrator must act, then re-run" from "setup
broke". The design kept this deliberately to preserve the old unattended behaviour
(`archive/2026-10-14-interactive-setup/design.md:43-45`), so it is a contract choice, not a bug.

**Recommendation.** Document and adopt a small table: 0 completed; 1 benchmark failed (`failed:*`);
3 timed-out; 4 cancelled; 5 vanished/status-lost; 6 launch-failed; 2 usage; 1x for config/credentials.
Keep `exitCode` in the JSON summary consistent with it. Give setup's unattended wait its own code
from the same table ("action required outside baas"). This is the kind of contract that is cheap
before consumers exist and expensive after.

### G8 — The job record doesn't say what ran · Should · S · user, CI

**Evidence.** `JobItem` holds identity, status, instance and tags (`baas-model` `JobItem.java:20-30`). It does not hold
the benchmark arguments (PARAMS), the runner version or whether `--runner-jar` overrode it (the CLI only
*logs* "Runner JAR: … pinned to CLI version" at `jobs/JobLaunch.java:150,155`), the timeout or watchdog
margin, or the benchmark JAR's digest. `environment.json` (`jobs/UserDataScriptBuilder.java:188-227`) has
none of these either. The JCStress mode is "not recorded in any tag", deferred
(`archive/2026-10-06-jcstress-e2e/design.md:92-94`). PARAMS live only in user-data, which dies with the
instance. Two results can differ because of `-f 1` vs `-f 5`, or because of runner 7.0 vs 7.1, and no
`baas` command can say so. The only partial trace is JMH's banner in `jmh-output.txt`.

**Recommendation.** Write `args` (the PARAMS list), `runnerVersion` (or `override`), `timeoutSeconds`
and `benchmarkJarSha256` into the job item at reservation (CLI-side facts, so no instance clock or
observation rule is involved). Surface them in `jobs show` and in `run --format json`. Have `jobs diff`
compare them in a separate "invocation" group. Add `mode` for JCStress as a derived tag.

### G9 — PARAMS are first validated on the paid instance · Could · M · user

**Evidence.** The CLI forwards PARAMS verbatim (`jobs/RunCommand.java:120-122` → `JobLaunch.java:167` →
`UserDataScriptBuilder.java:335`), so a mistyped JMH flag is
first parsed by the runner's picocli after AMI boot and JAR download. It surfaces as `failed:2`, read
from `cloud-init-output.log`. The help text pre-empts one such mistake (the missing `--`) but not the
general case. The refactor's `RunnerArgsContractTest` now proves that every argv the CLI *composes*
parses through the runner's command tree, which closes the CLI-owned half; PARAMS are outside it.

**Recommendation.** Parse PARAMS locally against the runner's option model before launching. The CLI
already resolves the runner JAR (pinned from S3, or `--runner-jar`). Alternatively, move the runner's
`Api*Options` classes into a small shared module so the CLI can do a dry-parse without classloading the
JAR. Pairs naturally with G1's reserved list.

### G10 — JMH `-p` (param override) and multiple include patterns are unreachable · Should · S · user

**Evidence.** `ApiJmhOptions` exposes one optional positional regex
(`@Parameters(index = "0", arity = "0..1")`, `ApiJmhOptions.java:83`) and no `-p name=v1,v2`. So every
`@Param` sweep runs all annotation values, and a sweep can't be narrowed or widened from `baas run`.
That matters because the store's `params` dimension (sort key, `--best-per` key) was built for exactly
these sweeps (CLAUDE.md, *Results table*).

**Recommendation.** Add `-p/--param name=v1,v2` (repeatable, mapped to `OptionsBuilder.param`) and allow
`arity = "0..*"` include regexes. Both are pass-through, with no change to the store.

### G11 — `jobs diff` cannot act as a gate · Could · S · CI

**Evidence.** No `--format` option (help output). It exits 0 both for "No differences" and for a
populated diff (`jobs/JobsDiffSubcommand.java:139,151`). A pipeline that wants to refuse comparing results from
different CPUs (E6) has to scrape the table.

**Recommendation.** Add `--format json` (`{"schemaVersions":[a,b],"groups":{group:{field:{left,right}}},"packages":…}`)
and `--exit-code`, following `git diff --exit-code`: 1 when anything differs.

### G12 — Remaining human-only outputs · Could · XS each · CI, admin

`config show` prints aligned prose only (`config/ConfigShowSubcommand.java:42-83`), though `config list` has
JSON. `admin image show` is prose only (`image/ImageShowSubcommand.java:41-103`), so an admin script can't read the
published label or AMI. `jobs terminate` reports through the logger only. `jobs download` prints a
count, not the paths it wrote.

*Widened at 7d9660a.* Setup now invites unattended use (`--non-interactive`, and the "To repeat this
without prompts" line, `deployment/setup/RepeatCommand.java:15-44`). What such a run produces is prose on the logger,
though. The operator role ARN that CI's `OPERATOR_ROLE_ARN` needs is one line of `DoneStep.nextSteps`
(`deployment/setup/DoneStep.java:79-111`), and the only stdout payload is the deployer policy when rights are missing
(`deployment/setup/PolicyHandOver.java:49-52`). `admin image build`, whose build setup's image step now shares
(`image/RunnerImageBuild.java:32-91`), likewise reports the AMI, label and pointer only through the logger
(`image/ImageBuildSubcommand.java:84-90`).

Add `--format json` to `config show`, `admin image show`, `admin image build` and setup. For setup that
is one object with `deployment`, `region`, `operatorRoleArn`, `created`/`updated`, `imageLabel`/`amiId`
when built, and `pending` naming a waiting step. `jobs terminate` and `jobs download` stay optional.

### G13 — No way to forget a deployment on this machine · Should · XS · operator

**Evidence.** `ConfigService.delete` (`session/ConfigService.java:178`) is reachable only from teardown (`deployment/TeardownCommand.java:68`).
Suppose a teammate tears a deployment down, or one is rebuilt under a new name. The stale
`~/.baas/deployments/<name>.yaml` keeps "two or more configured" true, so every command demands
`--deployment` (CLAUDE.md, *Absent `--deployment`*). The fix is `rm` under `~/.baas`, which the CLI never
tells the user about. Likewise `config set` can set but never unset a preference back to "(default)",
though defaults are deliberately not written.

**Recommendation.** `baas config remove --deployment <name>`: local only, no AWS call, prompt or `--yes`.
Add `config set --unset <key>`. Both are one-file operations on `ConfigService`.

### G14 — No read-only view of the deployment's server-side state · Could · S · admin

*Changed at 7d9660a.* Half of the original evidence is gone. Setup now reads the deployed trust back
from the stack's `GitHubOrg`/`GitHubRepo` parameters (`deployment/setup/GitHubStep.java:122-135`, names now `CoreStack.PARAM_GITHUB_*`), shows it
interactively ("GitHub Actions: … may run benchmarks on this deployment", then "Change that?"), and
pre-fills the current organisation and repositories as the defaults to edit (`GitHubStep.java:82-97`).
The plan says "unchanged (…)" when nothing moves (`deployment/setup/PlanStep.java:40-48`). Adding one repository no
longer needs `aws cloudformation describe-stacks`, provided a human runs setup on a terminal.

**Remaining evidence.** That read lives inside a deployer-credential command that can change the stack,
and it prints nothing unless the trust is asked about. `admin deployment` still offers only
`setup`/`teardown` (`deployment/DeploymentCommand.java:14`). No command shows stack status, last update, outputs, deployed image-parameter
versions or whether the image pointer resolves, and nothing about the deployment is machine-readable.
Unattended, `--github-repo` still submits exactly the list given (`GitHubStep.java:73-77`). A script
that adds a repository must already know the current ones, and the only non-interactive way to learn
them is the AWS CLI.

**Recommendation.** `baas admin deployment show [--format json]`: stack status and last update, the
federation (org, repos, provider present), outputs, the image label and pointer, and drift against the
bundled base. Read-only, deployer credentials. `--add-github-repo`/`--remove-github-repo` for unattended
setup are optional once `show` exists.

### G15 — No retention or prune short of teardown · Could · M · admin

**Evidence.** By design there is no lifecycle rule under `jobs/`, versioning is suspended, and neither
runner nor operator can delete (CLAUDE.md). Every job keeps `input/benchmark.jar` forever (shaded
benchmark JARs are tens of MB), plus its artifacts and items. Teardown is the only deletion, and it
deletes everything. N3 covers one stranded case. U2/`export-before-teardown` covers keeping data
*across* a teardown, not trimming it in place.

**Recommendation.** An admin-credential `baas admin jobs prune --older-than <d> [--inputs-only] [--project p] --yes`.
Use the deployer's delete rights, keep the operator's narrowing intact, and default to deleting `input/`
only, the re-creatable part. Fold it into the `export-before-teardown` design discussion rather than a
separate change.

### G16 — The benchmark-type vocabulary lives in two places · Could · XS · maintainer

**Fixed by the slice-architecture refactor.** See *Fixed by the slice-architecture refactor* below.

*Original evidence.* `RunCommand.VALID_TYPES` repeated the runner's subcommand names
(`JmhSubcommand.java:9`, `JCStressSubcommand.java:13`, …), and the same strings are the `type` tag. CLAUDE.md's
*Adding a benchmark type* named only `TestWrapper`, so a new type was rejected by `baas run` as "Unknown
benchmark type". The recommendation was to move the list into `baas-model` and have a runner test assert
that `TestWrapper`'s subcommands equal it.

### G17 — `results query` JSON contract rough edges · Could · XS · CI

*Changed by the slice-architecture refactor.* The escaping half is fixed: `printJson`
(`results/ResultsQuerySubcommand.java:391-413`) now writes every string through `console.Json.string`
(`Json.java:21-43`), the always-quoted fields via `quoted()` (`:434-436`), which keeps W5 deliberately.
*Original evidence:* `jobId`, `benchmarkName`, `mode`, `scoreUnit`, `createdAt` were formatted with raw
`"%s"`, and `imageVersion`/`instanceType` through an unescaping `jsonOrNull`.

**Remaining.** The object has no `project` field,
though `--all-projects` mixes projects and `ResultRow` carries it, and no `kind`, `resultJsonKey` or
`profilerOutputPath`, so a consumer cannot fetch the full result or flame graph without rebuilding keys,
which CLAUDE.md forbids. Add `project`, `kind` and the two keys. pr83/W5 (`"mode":"null"`, now kept on
purpose by `quoted()`) and pr83/W3 (6-decimal zero, now `Json.number`, `Json.java:56-58`) are the same
printer and could share one change. A related edge, not filed separately: the CSV writer quotes only the
`tags` and `params` cells (`ResultsQuerySubcommand.java:423-428`, `Csv.field`); the other string cells are
written raw, which is safe only while benchmark names, units and image labels carry no comma or quote.

### G18 — No non-interactive project listing · Could · XS · CI, scripts

The picker is interactive only. Without a terminal, `results query` fails listing the projects on
stderr (`results/ResultsQuerySubcommand.java:350-359`). `ResultsQueryService.listVisibleProjects` exists (`ResultsQueryService.java:114`). Expose it as
`baas results projects [--format json]`, or as a `--list-projects` flag if a new verb is unwanted.

### G19 — Contract documentation drift · Could · XS · maintainer

- `JobLayout` javadoc: the bucket "name derives from a hash of the caller ARN" (`baas-model` `JobLayout.java:8-10`). It has been the deployment name since `account-derived-installation-naming`.
- `StoredMeasurement`: "one DynamoDB item, one Mongo document" (`StoredMeasurement.java:7`). This is fine until `retire-mongodb`, which should update it.
- CLAUDE.md (*One job is one prefix…*): "CI mints its id in bash and must pass the same instant". CI no longer mints ids; it calls `baas run`, which has no `--job-id` option. (Still at CLAUDE.md:277 on `slice-architecture`, whose CLAUDE.md edit left this line.)
- `admin image show`'s description says "Report the runner image currently published for this account" (`image/ImageShowSubcommand.java:23`). The image is per deployment. `interactive-setup` reworded the command's warnings to "Deployment {} has no runner image yet", but not the description.
- `jobs/UserDataScriptBuilder.java:184-186` comment: the manifest carries "the job's own identity". Schema 6 dropped identity. The comment is stripped before the instance sees it, so this is cosmetic.

### G20 — Image-build logs are never pointed to · Could · XS · admin

Image Builder writes to `<bucket>/image-builds/` (`infra/cf-template-core.yaml:548-550`), but a failed
build reports only Image Builder's reason (`image/ImageBuilderService.java:265-270`) and never that prefix.

*Widened at 7d9660a.* The build moved into `RunnerImageBuild`, which `admin image build` and setup's
image step share. Setup stops with the same bare message (`deployment/setup/ImageStep.java:60-66`). The new wait-or-adopt
path (`ImageBuilderService.adoptOrBuild`, `:167-200`) logs the build it waits for, but a failure there
ends in the same `await` message. Print `s3://<bucket>/image-builds/<…>` with an `aws s3 cp` line on
failure, once, in `RunnerImageBuild`, so both entry points get it.

### G21 — `baas run` has no caller idempotency key · Could · S · CI

`RunInstances` uses the job id as client token (`jobs/Ec2ProvisioningService.java:59-62`), but the id is minted per invocation from the CLI's
clock. A CI step retried after a dropped runner or network (the case `continue-on-error`/re-run
produces) launches a second paid instance and a second job. An optional `--idempotency-key <k>` would
let a retry find and attach to (or report) the earlier job instead. It would need a conditional
reservation on a `gsi1pk`-style key or a small lookup item. Low priority while CI runs one job per push.

---

## Fixed by the slice-architecture refactor

- **G16 — fixed.** `baas-model`'s `BenchmarkType` enum (`BenchmarkType.java:12-45`) is the one list: its
  compile-time `*_NAME` constants name the runner subcommands (`JmhSubcommand.java:9`,
  `JmhWithAsyncProfilerSubcommand.java:20`, `JmhWithProfilerSubcommand.java:12`, `JCStressSubcommand.java:15`),
  `RunCommand.VALID_TYPES` is `BenchmarkType.commandNames()` (`jobs/RunCommand.java:71`), and `run`'s
  `<type>` help and completion derive from it (`:115-118`, `:609-612`). `RunnerArgsContractTest
  .theRunnerHasExactlyTheTypesTheCliAccepts` (`:68`) asserts `TestWrapper`'s subcommands equal it, which is
  the test the recommendation asked for, and CLAUDE.md's *Adding a benchmark type* now starts from the
  `BenchmarkType` constant.
- **G17 — escaping fixed, the rest open.** Every string field of `results query --format json` goes
  through `console.Json.string`, which escapes quotes, backslashes and control characters; `jobs show` and
  `jobs list` JSON share the writer. The missing `project`, `kind`, `resultJsonKey` and
  `profilerOutputPath` fields are still missing, so G17 stays open, narrowed (above).

Partial progress that closes nothing: `RunnerArgs` gives G1's reserved-option list its `baas-model`
home, and `RunnerArgsContractTest` validates the CLI-composed half of the runner argv that G9 wants
checked before launch. Neither touches PARAMS.

## Considered, not filed

- **`--since/--until` filters**: declined on evidence on 2026-08-20 (`archive/2026-08-20-dynamodb-results-store/design.md:296`). `--limit/--offset/--sort-by created` cover current use. Revisit with G4.
- **Schema versioning.** `environment.json` has `schemaVersion` (6). `EnvironmentManifest.parse` reads grouped and flat manifests alike, and `jobs diff` warns on a mismatch (`jobs/EnvironmentManifest.java`, `jobs/JobsDiffSubcommand.java:129-136`). Measurement items have no version attribute but are mapped field-by-field with absent-tolerant reads, and `params` was added as an optional trailing key segment. The job-item break for older CLIs was accepted 2026-10-02. No gap worth a change.
- **`admin image` for operators** (U10, won't fix). **Reattach** (ADR 0007). **Export** (U2, `export-before-teardown`). **`-m` vs `--mode`** (`retire-mongodb`, though G1 offers a better error meanwhile). **Table locale** (ADR 0005/U15). **Full-partition reads in `results query`/`jobs list`**: scale only, and `jobs-command` D14 already names the index as the answer.

## Appendix — current command surface (from `--help`, built offline at 7d9660a)

Unchanged on `slice-architecture`: its help output and offline behaviour were verified byte-identical
before and after the refactor.

Global (inherited): `--deployment <name>` (help now reads "Optional when exactly one is configured…
Printed hints always include it"), `-v`, `-h`, `-V`. Aliases: `run` = `jobs run`, `query` = `results query`.

| Noun | Verb | Key options | Output formats |
|---|---|---|---|
| jobs | run `<type> [-- PARAMS]` | `--benchmark-jar` (req), `--project`, `--tag k=v`…, `--instance-type`, `--timeout`, `--watchdog-margin`, `--runner-jar`, `--detach`, `--format` | text, json (summary object) |
| jobs | list | `--project`, `--tag`/`--exclude-tag`, `--in-flight`, `--sort-by created\|status\|project`, `--asc`, `--limit`(20; 0 = none)/`--offset`, `--watch` | table, json, csv |
| jobs | show `<jobId>` | — | table, json (`job`, raw `environment`, `artifacts`) |
| jobs | diff `<jobA> <jobB>` | — | table only; exit 0 always |
| jobs | download `<jobId>` | `-o/--output-dir` | files; log line |
| jobs | terminate `<jobId>` | `--yes` | log only |
| results | query | `--project`/`--all-projects`, `--job-id`, `--benchmark-name` (regex), `--tag`/`--exclude-tag`, `--best-per <tag>`, `--show-excluded`, `--sort-by created\|benchmark\|score\|project`, `--asc`, `--limit`(20; none with `--job-id`; 0 = none)/`--offset`, `--watch` | table, json, csv |
| config | list | — | table, json |
| config | set | `--operator-aws-profile`, `--deployer-aws-profile`, `--instance-type`, `--timeout`, `--watchdog-margin`, `--git-resolve-project` | — |
| config | show | — | text only |
| config | sync | `--operator-aws-profile` | — |
| admin deployment | setup | `--region`, `--deployer-aws-profile`, `--policy-file`, `--non-interactive`, `--build-image`, `--github-org`, `--github-repo`…, `--revoke-github-oidc`. `--oidc-provider-arn` is gone: setup derives the provider ARN and creates the provider when absent (ADR 0008) | text, with prompts on a terminal. Unattended: the policy JSON on stdout when rights are missing, and exit 1 at any step awaiting outside action. After an interactive run that changed something, a "To repeat this without prompts" line |
| admin deployment | teardown | `--yes` | text |
| admin image | build | `--extension <file>`. Waits for, or adopts, a build an interrupted run left behind | text |
| admin image | show | `--extension` (prints the AWSTOE doc) | text |

Runner contract (user-data → `benchmark-runner.jar <type>`), unchanged since dabbc55 (since the refactor the CLI-owned names come from `baas-model`'s `RunnerArgs`, same strings): CLI-owned `--job-id --created-at --result-path --s3-bucket --benchmark-path --results-table --tag…` (caller tags, then observed tags), then the caller's PARAMS verbatim (G1). Type-specific options: `jmh` (`ApiJmhOptions`: one include regex, `-f -wi -i -bm -t -tu -e -jvmArgs*`…; no `-p`), `jmh-with-prof` (`-pr name=opts`), `jmh-with-async` (`-ae -aot -ai -aop -aap`), `jcstress` (`--mode`, `-c -f -fsm -hs -r -jvmArgs*`, one test regex). Inconsistencies in the help itself, all still present: `--deployment <name>` (`jobs run`, `jobs list`, `jobs terminate`) vs `--deployment=<name>` elsewhere; the `--tag` label rendered `<String=String>` where `--exclude-tag` says `<key=value>`; and `admin image build`'s footer wraps mid-sentence ("…allowed to discard" / "their extension."). Cosmetic, not filed.
