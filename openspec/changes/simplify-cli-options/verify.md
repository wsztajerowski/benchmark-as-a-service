# Verify — simplify-cli-options

Run 2026-10-01 against commit `5d30b93` (PR #69) plus the design correction noted under W2.

## Summary

| Dimension    | Status |
|--------------|--------|
| Completeness | 35/36 tasks (8.1 open, environmental — see W5); 21 requirements across 6 capability deltas, all implemented |
| Correctness  | 21/21 requirements traced to code; 4 scenarios without an automated test (W4) |
| Coherence    | Design followed; one drift corrected in design.md (W2); one spec over-statement (W1) |

## Requirement → code → test → gap

Paths are under `baas-cli/src/`; `T:` names the covering test class.

### cli-command-structure

| Requirement | Code | Test | Gap |
|---|---|---|---|
| ADDED Every command accepts an alternative configuration file | `main/…/BaasApp.java` (`configPath`, `configService(spec)`), `main/…/config/ConfigService.java` (`at`, `load`, `loadOrEmpty`) | T: `ConfigPathTest` (both sides, nested, missing file, config set creates), `DownloadArgumentTest.noCommandDeclaresATableOrBucketOverride` | *sync creates a second file* needs a live stack (W4); retired-installation read checked by hand (8.5) |
| ADDED Git is consulted only when the operator enables it | `BaasConfig.GitConfig`, `ConfigSetSubcommand --git-resolve-project`, `ConfigShowSubcommand`; the only `git` subprocess is `GitProject`, called from the two gated sites | T: `BaasConfigYamlTest.gitDerivationIsOffByDefault`, `ResultsProjectSelectionTest.gitDerivationIsNotConsultedWhenOff`, `ConfigPathTest.gitDerivationCanBeTurnedBackOff` | `config show` reporting it is not asserted (W4) |
| ADDED The project is explicit unless derivation is enabled | `RunCommand.resolveProject(BaasConfig)` | T: `RunCommandTest` (required when off, explicit wins, JAR repo not cwd, outside any repo) | — |
| ADDED `commit` and `branch` are caller-supplied tags, omitted when absent | `RunCommand.buildRunnerTags(type, project)` | T: `RunCommandTest.neitherCommitNorBranchIsDerived…`, `theRemovedOptionsAreUnknown`; paid run 8.2 | — |
| ADDED The watchdog fires a fixed margin after the benchmark timeout | `RunCommand.watchdogBound`, `--watchdog-margin`, `ec2.watchdogMarginSeconds` | T: `RunCommandTest` (7500, 1120, floor), `ConfigPathTest` (config set floor, `--max-wall-clock` unknown); paid run 8.3 (`WALL_CLOCK_HARD_KILL='660'`) | poll cap = bound is wiring only (`RunCommand.java:428`), no unit test (W4) |
| MODIFIED Results filters cover the supported query patterns | `ResultsCommand` options, `requestIdConflict`, `--project`/`--all-projects` check | T: `ResultsProjectSelectionTest` (exclusive pair, removed options) | see W1 |
| REMOVED ×4 (project derived, commit/branch derived, AMI override, read-only table override) | options and code deleted | T: unknown-option tests in `RunCommandTest`, `ResultsProjectSelectionTest`, `DownloadArgumentTest`; `RunnerImageResolutionTest` lost its override cases | — |

### benchmark-results-query

| Requirement | Code | Test | Gap |
|---|---|---|---|
| ADDED Results are read from one partition unless every project is requested | `ResultsQueryService.queryProject`, `scanAllProjects`, `listVisibleProjects` | T: `ResultsQueryServiceIT` (partition query, every-project scan, past-first-page) | — |
| ADDED A project is chosen when none is named | `ResultsCommand.resolveProject`, `pickProject` | T: `ResultsProjectSelectionTest` (7 cases); pty run 8.4 | `--watch` choosing once before the first frame is ordering in `call()` only; Linux TTY untested (W4) |
| ADDED Every project can be reported at once | `--all-projects`, `ResultsTable.print(…, withProject, …)` | T: `ResultsQueryServiceIT.everyProjectIsOneScan…`, `TableTest.aProjectColumnLeadsOnlyWhenAskedFor` | — |
| ADDED Output carries a measurement's tags, and the table omits the score error | `ResultsTable`, `ResultsCommand.printJson`/`printCsv`/`csvField`/`jsonObject` | T: `TableTest`, `ResultsFormatTest` (tags JSON with escapes, CSV quoting under pl-PL) | — |
| MODIFIED Excluded results are filtered out | `queryProject(project, includeExcluded)`, `ResultRow.excluded()`, faint row style | T: `ResultsQueryServiceIT` (sweep incl. excluded, whole table), `TableTest.anExcludedRowIsFaintAndStillAligned` | — |
| MODIFIED Results are grouped with the best score kept | `ResultsGrouping.bestPerGroup` (project in key), `sortedForDisplay` | T: `ResultsGroupingTest.twoProjectsNeverShareAGroup`, `displaySortsByProjectBeforeBenchmark` | — |
| REMOVED ×2 (single-query requirement, living branches) | `byLivingBranches` and `gitRemoteBranches` deleted | T: living-branch tests deleted; `--living-branches` unknown | — |

### results-store-schema, core-stack-provisioning, ci-benchmark-execution, cli-console-output

| Requirement | Code | Test | Gap |
|---|---|---|---|
| ADDED `project` is supplied explicitly or derived from the benchmark JAR's repository | as cli-command-structure | as above; runner refusal unchanged in `benchmark-runner` | — |
| MODIFIED Tags are the queryable dimensions… | `buildRunnerTags`, `RESERVED_TAG_KEYS` unchanged | T: `RunCommandTest` source/branch cases | — |
| REMOVED `project` is derived from the git repository name | — | — | — |
| MODIFIED Poll loop detects a dead instance | unchanged dead-instance check; cap now `watchdogBound` | existing | — |
| ADDED The self-test names its project, branch and commit explicitly | `.github/workflows/e2e-cloud-test.yml` | **CI:** run [36885099741](https://github.com/wsztajerowski/benchmark-as-a-service/actions/runs/36885099741) green — tags asserted from `.tags`, `source=ci`, both exclusion halves | see W6 |
| MODIFIED Tables are coloured when interactive, without losing alignment | `ResultsTable` row style | T: `TableTest` (excluded faint + stripped-escape alignment, NaN faint) | — |

## Warnings

- **W1 — spec overstated `--request-id` exclusivity. Resolved:** `--project` is now refused with
  `--request-id` (`ResultsCommand.requestIdConflict`, T: `ResultsProjectSelectionTest.aRunLookupCannotBeCombinedWithAProject`),
  and the requirement names exactly the refused options — the row selectors `--project`, `--all-projects`,
  `--benchmark-name`, `--tag`; `--limit`, `--group-by`, `--all-runs` stay harmless alongside it. Original finding: The MODIFIED *Results filters* requirement says
  `--request-id` is exclusive with "the other filters" and lists `--project`, `--all-runs`, `--group-by` and
  `--limit` among them, but `ResultsCommand.requestIdConflict` (`ResultsCommand.java:284`) refuses only
  `--benchmark-name`, `--tag` and `--all-projects`. The others are silently ignored with `--request-id`. The
  wording was inherited — the previous spec listed `--project` and `--limit` too, and the code never checked
  them — but this change re-asserted it. *Recommendation:* before archive, either narrow the requirement to
  "filters that select rows" (name, tag, every project) or add `--project` to the conflict check; extending
  it to `--limit`/`--group-by` would refuse harmless flags.
- **W2 — design drift, corrected.** design.md described the picker's `Scan` as projecting `pk` and the
  exclusion tag and filtering client-side; the code filters server-side and projects `project`. design.md
  now describes the implementation and says why. No code change.
- **W3 — help footers showed `baas run` without `--project`. Resolved:** both footers now carry
  `--benchmark-jar … --project …`, and `baas run --help` names `--git-resolve-project`; every rendered line
  stays under 80 columns. Original finding: `BaasApp.java:46` and `RunCommand.java:59-60`
  print examples that now fail on a fresh configuration (they already lacked the required
  `--benchmark-jar`). *Recommendation:* add `--benchmark-jar … --project …` to the examples, or trim them to
  the `--` rule they exist to show.
- **W4 — scenarios without an automated test.** `config sync --config-path` creating a second file (needs a
  live stack); `config show` printing `git.resolveProject`; the CLI poll cap equalling the watchdog bound;
  the `--watch` prompt preceding the alternate screen; the picker on a Linux TTY. Each is a few lines of
  wiring; none is on a path a regression would hide silently except the poll cap, which 8.3's paid run
  exercised end to end.
- **W5 — reactor `verify` red in `benchmark-runner` (task 8.1, open).** Seven Mongo Testcontainers ITs fail
  with `Connection refused` on the mapped port; that module is untouched by this change. Until it is green
  the async-profiler IT is not proven locally by this change — CI's e2e run, which exercises async-profiler on
  the real image, is green.
- **W6 — 7.1 deviations.** The workflow tags `commit` with `github.event.pull_request.head.sha || github.sha`,
  not `github.sha` as the task text says: on a PR `github.sha` is GitHub's merge commit, which a reader cannot
  look up. `actionlint` was not run (not installed); the YAML parses and the PR run is green.

## Deviations from design or tasks

- 5.4 has no test of its own: `baas run`'s summary uses the same two-argument `ResultsTable.print` that
  `TableTest.theScoreErrorIsNotAColumn` covers.
- 4.1's pagination is proven with 1,500 padded rows past one 1 MB page, rather than a configurable page size.
- `ResultsGrouping.java` held a literal NUL byte as the key separator, which made git treat it as binary; it is
  now the `\u0000` escape (same separator).
- Out of scope, seen in 8.4: the results table formats scores with the default locale (`3307897,585` under
  pl-PL). Pre-existing; JSON and CSV are `Locale.ROOT` as required.

## Assessment

One open task (8.1, environmental and outside the touched module). No requirement is missing; W1 is resolved.
