# Apply Receipt

> Generated at the end of the apply phase to mark code-implementation
> complete and provide verify with the state it needs.
> Overwritten on each apply iteration; iteration counter grows.

**Change**: `unified-run-prefix`
**Iteration**: `1`
**Applied at**: `2026-08-21 09:55`
**Executor**: `manual` — the task-by-task loop the apply instruction names as its fallback, not
`subagent-driven-development` or `executing-plans`. See *Workspace* for why.

---

## Workspace

- **Worktree**: none — this session is configured to work in place in the primary working
  directory, so `superpowers:using-git-worktrees` was deliberately not invoked.
- **Branch**: `feat/baas-cli-openspec-test` (a feature branch, so finalize's git-side closeout has
  something to merge into).

Subagent-driven development was likewise not used: this session's operating instructions forbid
dispatching agents unless the user asks. Each plan task was implemented directly, following the
plan's RED → GREEN → commit step sequence, with every new behaviour test-first.

---

## Commits

- **Range**: `f9b46d8..fc41910` (artifacts committed at `f9b46d8`; base `82d36f8`)
- **Count**: `10`

| SHA | Plan task | What |
|---|---|---|
| `711e092` | 1 | `Implementation-Version` in the shaded manifest, `BaasVersion`, `release.yml` rebuild after `versions:set` + CLI JAR and `.sha256` assets |
| `b30755b` | 2 | `RunId`, `RunLayout`, `TagKeys.BRANCH` |
| `13aa085` | 3 | Runner takes `--created-at`; `RunId`/`RunLayout` defaults; `"unknown"` project fallback removed |
| `9c11983` | 4 | One id, one prefix, one instant in `baas run`; `branch` tag; `--git-common-dir`; manifest identity fields |
| `4a5ec70` | 5 | `RunnerJarResolver`; GitHub branch deleted from user-data; `runner.sourceRepo` config; reactor hard-fail |
| `39e7ec7` | 6 | `baas download <runId>`; `REQUEST_ID` column widened to 28, truncation dropped |
| `3d2b53b` | 7 | Bucket versioning `Suspended`; `runs/` expiry rule deleted; dead `ci/*` grant removed |
| `bc21b16` | 8 | `e2e-cloud-test.yml`: two jobs, two runs, two prefixes; explicit `--project` |
| `cdcaac4` | 9 | CLAUDE.md layout + invariants, A7/A9 marked Fixed, A11 filed, diagram/ADR/`env diff` footer |
| `fc41910` | 10 (partial) | `scripts/migrate-run-layout.sh`, written and self-tested; not yet run |

---

## Tasks

- **Completed**: `50 of 65` checkboxes in tasks.md
- **Remaining**: `1.1, 1.2, 1.6, 2.5, 11.4–11.7, 12.2–12.8`

**Every code, infrastructure, CI and documentation task is complete.** All 15 remaining items
require credentials this session does not have: a GitHub token (`gh auth status` reports an invalid
keyring token) or AWS credentials (`sts:GetCallerIdentity` reports none). None of them is a coding
task, and none was skipped for convenience.

---

## Verification performed

- Full reactor `mvn -B clean verify` with a real `ASYNC_PATH` — **BUILD SUCCESS**, all 6 modules,
  6m41s. `JmhWithAsyncProfilerSubcommandServiceIT` reports `Tests run: 1 … Skipped: 0` in its
  failsafe report, so the async-profiler path genuinely executed rather than silently skipping.
  This is tasks.md **12.1**.
- `baas-model` + `baas-cli` unit suites — **267/267**; `baas-cli` LocalStack ITs — **16/16**;
  `benchmark-runner` unit suite — **36/36**.
- **New tests**, all written before their implementation: `RunIdTest` (7), `RunLayoutTest` (7),
  `BaasVersionTest` (4), `RunnerJarResolverTest` (8), `DownloadArgumentTest` (5), plus additions to
  `ApiCommonSharedOptionsTest`, `RunCommandTest`, `UserDataScriptBuilderTest`, `TagKeysTest`,
  `BaasConfigYamlTest`, `CoreTemplateTest`, `CiTemplateTest`, `ResultsFormatTest`, `EnvCommandTest`
  and `S3UploadServiceIT`.
- **The rendered user-data script is now checked with `bash -n`** (new test). It is heredoc-heavy,
  nothing else parses it before cloud-init does on a paid instance after the watchdog has started,
  and this change edits the heredoc.
- **`git rev-parse --path-format=absolute --git-common-dir` verified against real git** in both
  layouts (tasks.md **1.3**): an ordinary clone and a linked worktree both resolve to
  `…/benchmark-as-a-service/.git`.
- **The CI identity snippet was executed on an `ubuntu-latest` image** (`catthehacker/ubuntu:act-latest`).
  It produces 28-character ids matching `RunId.LENGTH`, a `created_at` equal to the id's own
  instant, distinct values per job, and — for the fixed instant `2026-08-20T17:44:32.812Z` —
  exactly `20260820T174432812Z`, byte-identical to `RunId.generate(Instant)`.
- **The migration script was self-tested against a synthetic scan** covering an old-layout run, a
  `ci/` run under `RESULT#unknown`, an already-relocated run and an item with no stored path. The
  emitted `aws` calls contain no `pk`/`sk` write, copy precedes rewrite, and a second pass over the
  post-migration state makes zero AWS calls (tasks.md **11.3**).
- **Workflow scope checked**: `git diff --name-only 82d36f8..HEAD -- .github/workflows/` names only
  `release.yml` and `e2e-cloud-test.yml`. `exec-single-benchmark.yml` and `benchmark-runner.yml`
  are untouched — `gha-workflow-migration-to-dynamodb` owns them (tasks.md **9.4**).
- **Both edited workflows parse as YAML**, and the generated `release.config.js` was produced by
  running the workflow's own `echo` block and loading it with `node`: the `${nextRelease.version}`
  template survives unexpanded and the `cut -d" "` quoting is intact.

---

## Deviations from plan.md

| Plan text | What was done, and why |
|---|---|
| Task 5 adds the `benchmarkJarS3Key` parameter to `UserDataScriptBuilder.build(...)` | Added in **Task 4** instead. Task 4 changes the benchmark JAR's upload key to the `input/` prefix while the script still hardcoded `runs/${REQUEST_ID}/benchmark.jar`, so deferring the parameter would have made the Task 4 commit ship a run that could not find its own JAR. Each commit is coherent on its own. |
| Task 5 Step 8 asserts `containsOnlyOnce("/app/benchmark-runner.jar")` | Dropped that line. The path legitimately appears twice — the `aws s3 cp` and the `java -jar`. The assertion that carries the meaning, `containsOnlyOnce` on the full bucket-copy line, is kept. |
| Task 4 Step 10/11 sketch a new schema-version assertion | Folded into the existing `manifestSchemaVersionIsBumpedForTheNewField`, which already pinned the value, rather than adding a second test asserting the same constant. |
| Task 7 Steps 1–4 assert against raw template text | Written against the parsed-YAML fixtures (`InfraFixtures`), matching how every other assertion in `CoreTemplateTest`/`CiTemplateTest` is written. Same facts, structurally checked. |
| Task 8 Step 2 mints the id in a step inside each benchmark job | The two benchmark jobs are reusable-workflow `uses:` calls and cannot have steps. Both identities are minted in `setup-env` instead, one per job, and `--project`/`--created-at` travel through the existing free-form `parameters` input — so `exec-single-benchmark.yml`, which another change owns, stays untouched. |
| Task 5 Step 6 suggests `BaasConfig.AwsConfig` for the source repository | Put in a new `runner:` block. It is not an AWS concept, and the file already groups by concern (`aws`, `ec2`, `benchmark`). |
| Plan's migration script rewrites `resultJsonKey`/`environmentJsonKey` unconditionally | Rewrites only attributes actually present, and adds `profilerOutputPath` — which tasks.md 11.2 requires and the plan's sketch omitted. Writing an absent attribute as `""` would invent data. |
| Apply instruction step 1–2: create a worktree, execute via subagents | Neither used, per this session's operating instructions (work in place; do not dispatch agents unless asked). Recorded here rather than silently diverged from. |

---

## Additional work not in the plan

- **`docs/adr/0001-self-contained-baas-cli.md`** carried the request-ID-scoped upload rule as a
  standing invariant. Marked superseded with the reason, rather than left to contradict CLAUDE.md.
- **`EnvDiffSubcommand`'s footer** still told users result paths are `<branch>/<type>/<timestamp>`.
  Updated to the unified shape, keeping the historical shape documented because both still resolve.
  Its test now pins both.
- **`docs/review/benchmark-runner-findings.md`'s "Deliberately excluded" section** named runner-JAR
  checksum verification as an accepted risk not to re-raise. That risk is now closed, so the
  exclusion is annotated rather than left to mislead the next reviewer.

---

## Not done — the remaining gate

Everything below needs credentials this session does not have. Nothing is blocked on code.

**Needs a GitHub token**

- **1.1 / 1.2** — confirm the published `benchmark-runner.jar` carries the placeholder version, and
  that no `baas-cli.jar` asset exists yet. The local half of 1.2 *is* verified: the built
  `baas-cli.jar` manifest carried no `Implementation-Version` before this change and carries it
  after. If 1.1 turns out false, design.md's `release.yml` premise is wrong and Task 1 collapses to
  publishing the CLI JAR and the checksum — the code would not change.
- **2.5** — cut a real release. **This gates everything else**: until a release exists carrying its
  own version *and* a `.sha256` asset, `RunnerJarResolver` has nothing to pin to, so 12.2–12.8
  cannot run.

**Needs AWS credentials**

- **1.6** — inventory the bucket and table. 11.4 checks the dry run against these counts.
- **11.4–11.7** — dry-run the migration, run it, spot-check a relocated run, then delete the
  script. `scripts/migrate-run-layout.sh` is committed and self-tested; it is deliberately still
  present because 11.7 says to delete it in the commit that records it ran.
- **12.2–12.8** — `baas admin setup`, then the manual end-to-end checks.

**Ordering.** design.md's migration plan governs: release (2.5) → deploy (12.2) → first real run
(12.3–12.7) → migrate (11.4–11.7). Step 11.5 is the only one-way door; the copies are additive, but
the rewritten path attributes would need a reverse pass, which is why 11.4's dry run is mandatory.

---

## Open decisions carried forward

None block verify.

1. **CI's `--project` value is `${GITHUB_REPOSITORY##*/}`** — `benchmark-as-a-service`. Nothing
   specified a value; this is what `GitProject.repositoryName` would derive locally for the same
   checkout. It does mean CI fixture runs now land in a real project partition rather than
   `RESULT#unknown`. If they should be quarantined instead, that is a one-line change.
2. **`RESULT#unknown` history stays where it is.** The hard failure stops new rows appearing; the
   36 existing CI fixture rows are relocated to `runs/unknown/` with keys untouched, per design.md.
3. **A11 (`@Param` sort-key collision) is filed, not fixed.** Pre-existing and live today; it is a
   stored-shape change, not a layout one.

---

## Next step

`Run /opsx:verify`. The remaining 14 tasks are a human gate, not implementation work — start with
tasks.md **2.5** (cut a release), since 12.2–12.8 cannot be exercised without one.
