# Code-complexity review — production code (baas-cli, benchmark-runner, baas-model)

Re-checked against slice-architecture (post-refactor) on 2026-10-15.

Branch `codebase-review`, written 2026-10-13 against dabbc55 and re-verified against `next-release`
7d9660a on 2026-10-14. This re-check reads branch `slice-architecture` after the refactor recorded in
ADR 0009 (`git log --oneline 0308fb8..HEAD`): packages regrouped into a kernel (`cli`, `console`,
`session`, `aws`) and five slices (`jobs`, `results`, `deployment` with `deployment.setup`, `image`,
`config`); `JobLaunch`/`RunAws` extracted from `RunCommand`; `console.Json`/`Csv`; `DeployerSession`;
`cli.Invocation`; runner builders replaced by constructors and `StorageService.of`; runner options in
their own leaf package; then the simplification pass recorded in `simplification-review.md`
(a8be1a0: runner builders and `StorageServiceBuilder` deleted, `SsmService` inlined into
`ImageBuilderService`, `olderBaseRefusal` moved to `RunnerImageBuild`, `configService()` wrappers removed,
`NamesStep.rolledBack` deleted). **Measured state:** a8be1a0 (the worktree's code is identical to it).
Read-only. Helper scripts (throwaway, not committed) in the session scratchpad's
`recheck-k/`: `metrics.py` (method length, cyclomatic indicator, parameter counts), `top15.py`,
`loc.py`, `deps.py` (package import matrix by `package` declaration, with SCC detection).

## Summary

1. The refactor closed the two largest structural findings. `RunCommand.execute()` went from 197 lines /
   cc 14 to 63 / cc 9; the launch sequence is `JobLaunch.run` over the `RunAws` port, and
   `JobLaunchTest` drives it against `FakeRunAws` — the uncovered success path K1 named (K1 fixed).
   Artifact and resource names have one builder each again (K8 fixed), the runner builders are gone
   (K7 fixed), and the `commands.admin` ↔ `setup` cycle is gone with `RunnerImageBuild` in `image` (K13,
   cycle half).
2. The slice graph is acyclic and follows ADR 0009's order (deployment → jobs → {image, results}); the
   only strongly connected pair left is `deployment` ↔ `deployment.setup`, parent and child of one slice
   (`SetupCommand` → setup; `AwsSetupAws` → `DeployerPreflight`, `PermissionsStep` →
   `DeployerPolicyRenderer`). `SliceBoundaryTest` treats them as one slice. No command class imports an
   AWS SDK service package.
3. What the refactor moved but did not change: the early-exit pattern (K2: 52 `return 1/2`, 65
   `logger.error`, now including `JobLaunch`'s 4 / 8), `--format` by hand (K4), the 16-parameter
   `UserDataScriptBuilder.build` (K5, now called from `JobLaunch`), `JobSession`'s derivable exit code
   (K11), the runner's static `@Spec` (K12), and the three JMH runner services (K6) — **the
   async-profiler missing-directory bug is still present** (`JmhWithAsyncProfilerSubcommandService.java:107`).
4. Partly fixed: K3 (one escaper now — the unescaped-field bug is closed — but the five templates and the
   duplicated job object remain), K9 (`configService()` wrappers and deployer-factory copies gone; the
   memoised `session()`/`console()` remain), K13 (SDK `StackStatus` still on the setup port), K14
   (`NamesStep.rolledBack` deleted; the AccessDenied hand-over is still written twice).
5. Best payoff/effort now: K6 (a real bug), K2, K5, then K3's residue.

## Index

| ID | Title | Status after slice-architecture | Necessity | Effort | Location |
|---|---|---|---|---|---|
| K1 | `RunCommand` is a god class; `execute()` is a 197-line launch script | **Fixed by the refactor** | — | — | `jobs/JobLaunch.java:104`, `jobs/RunCommand.java:241` |
| K2 | Commands hand-roll "log error, return 1/2" — 52 early exits block method extraction | Open, relocated | Should | S | slices' `*Command`/`*Subcommand`, `jobs/JobLaunch.java` |
| K3 | JSON payloads hand-built in 5 commands; job object duplicated | Open, changed (escapers unified) | Could | XS | `JobsList:243`, `JobsShow:191`, `ResultsQuery:403`, `ConfigList:86`, `RunCommand:595` |
| K4 | `--format` validated by hand in 5 commands | Open, relocated | Could | XS | 5 commands |
| K5 | `UserDataScriptBuilder.build` takes 16 positional parameters, 11 of them `String` | Open, relocated | Should | S | `jobs/UserDataScriptBuilder.java:298` |
| K6 | Three JMH runner services duplicate `executeCommand` and artifact upload — and have already diverged | Open, relocated | Should | M | `benchmark-runner/services/Jmh*SubcommandService.java` |
| K7 | Four runner service builders (227 lines) build objects with only required fields | **Fixed by the refactor** | — | — | — |
| K8 | Job-artifact keys and resource names composed by hand | **Fixed by the refactor** | — | — | residue `setup/PolicyHandOver.java:82` |
| K9 | Command plumbing (`session()`, `console()`) copied into 7–8 commands | Open, changed | Could | S | `jobs/**`, `results/**`, `config/**`, `deployment/**` |
| K11 | `JobSession.Outcome.exitCode` is a derivable field; terminal-read check repeated 5× | Open, relocated | Could | XS | `jobs/JobSession.java:46, 217–281` |
| K12 | Mutable `static @Spec` in a runner mixin shared by three subcommands | Open, relocated | Could | XS | `benchmark-runner/commands/ApiJmhOptions.java:21` |
| K13 | The setup port leaks an SDK type (the cycle half is fixed) | Open, changed | Could | XS | `deployment/setup/SetupAws.java:64, 67` |
| K14 | The AccessDenied hand-over is duplicated inside the setup package | Open, changed | Could | XS | `setup/SetupWalkthrough.java:64–73`, `setup/StackStep.java:75–80` |

Fixed by the slice-architecture refactor: K1, K7, K8 (and parts of K3, K9, K13, K14). Resolved earlier:
K10 (interactive-setup). Withdrawn: none.

## Metrics

### Top 15 files by size (production code, all three modules)

"Code" = non-blank lines after stripping comments and string/text-block contents. Overall: 16,738
lines, 11,315 code (68 %) — baas-cli 12,821 / 8,428, benchmark-runner 2,849 / 2,218, baas-model 1,068 /
669 (was 16,344 / 11,169 at 7d9660a; the growth is `JobLaunch`/`RunAws`/`AwsRunAws`, the contract tests'
model constants and the slice-boundary rationale). Σcc = sum of a rough cyclomatic indicator
(1 + `if`/`for`/`while`/`case`/`catch`/`&&`/`||`/`?`) per method.

| # | File | Lines | Code | Methods | Σcc | max cc | vs 7d9660a |
|---|---|---|---|---|---|---|---|
| 1 | `baas/jobs/RunCommand.java` | 615 | 362 | 27 | 73 | 9 | was 938 / 593, Σcc 111 (K1) |
| 2 | `baas/image/ImageBuilderService.java` | 539 | 339 | 28 | 65 | 8 | was `infra/`, 505 lines |
| 3 | `baas/results/ResultsQuerySubcommand.java` | 437 | 325 | 21 | 77 | 22 | was `commands/`, 497 / Σcc 94 |
| 4 | `baas/jobs/UserDataScriptBuilder.java` | 429 | 108 | 7 | 10 | 3 | was `infra/`, 382; model-name tokens |
| 5 | `baas/jobs/JobLaunch.java` | 390 | 262 | 13 | 40 | 7 | **new** (from `RunCommand`) |
| 6 | `baas/aws/CloudFormationService.java` | 347 | 235 | 22 | 49 | 7 | was `infra/` |
| 7 | `baas/jobs/JobSession.java` | 307 | 194 | 19 | 63 | 16 | |
| 8 | `baas/jobs/JobsListSubcommand.java` | 295 | 246 | 14 | 47 | 10 | was `commands/` |
| 9 | `baas/model/dynamodb/MeasurementItemMapper.java` | 287 | 223 | 18 | 46 | 11 | was `baas/model/` |
| 10 | `baas/deployment/TeardownCommand.java` | 277 | 185 | 14 | 30 | 7 | was `commands/admin/` |
| 11 | `baas/session/OperatorSession.java` | 263 | 164 | 19 | 39 | 6 | was `infra/` |
| 12 | `baas/jobs/JobsShowSubcommand.java` | 257 | 215 | 19 | 43 | 9 | was `commands/` |
| 13 | `baas/image/RunnerImageRenderer.java` | 253 | 102 | 10 | 17 | 3 | was `infra/` |
| 14 | `baas/session/ConfigService.java` | 250 | 171 | 22 | 40 | 5 | was `config/` |
| 15 | `baas/session/BaasConfig.java` | 243 | 134 | 39 | 54 | 4 | was `config/` |

Just below: `results/ResultsQueryService.java` 225 / 137 (was #15), `deployment/setup/AwsSetupAws.java`
220 / 190, `deployment/DeployerPreflight.java` 218 / 145 (Σcc 37), `BaasApp.java` 212 / 143.

`deployment.setup` as a whole: 21 files, 1,918 lines, 1,402 code, 106 methods, Σcc 318 (was 1,870 /
1,369 / 104 / 314). benchmark-runner's largest file is still `commands/ApiJmhOptions.java` (204 / 157);
baas-model's is `model/dynamodb/MeasurementItemMapper.java` (above).

### Top 15 methods by length / branching

Record compact bodies (`RunnerImageParameters`, `EnvironmentManifest`, `RunnerImageDefinition`,
`PackagesDiff`, `ResultRow`, `DeploymentNames`, `console.Table`, the runner `options` records) are
excluded, as before — the script reads each as one "method".

| # | Method | Lines | cc | Params | Note |
|---|---|---|---|---|---|
| 1 | `JobLaunch.run` (`jobs/JobLaunch.java:104`) | 116 | 6 | 1 | new; numbered steps 1–8, 4 early exits (K2) |
| 2 | `ResultsQuerySubcommand.call` (`results/ResultsQuerySubcommand.java:144`) | 78 | **22** | 0 | 10 validation exits, K2/K4 |
| 3 | `JobsDownloadSubcommand.call` (`jobs/JobsDownloadSubcommand.java:63`) | 77 | 11 | 0 | |
| 4 | `TeardownCommand.removeDeployment` (`deployment/TeardownCommand.java:78`) | 73 | 7 | 2 | |
| 5 | `JobsDiffSubcommand.call` (`jobs/JobsDiffSubcommand.java:74`) | 67 | 11 | 0 | |
| 6 | `MeasurementItemMapper.toItem` (`baas-model/.../model/dynamodb/MeasurementItemMapper.java:76`) | 64 | 11 | 1 | flat attribute mapping — fine |
| 7 | `RunCommand.execute` (`jobs/RunCommand.java:241`) | 63 | 9 | 0 | was #1, 197 lines / cc 14 (K1) |
| 8 | `ImageShowSubcommand.call` (`image/ImageShowSubcommand.java:41`) | 63 | 5 | 0 | |
| 9 | `RunnerImageBuild.run` (`image/RunnerImageBuild.java:32`) | 58 | 6 | 4 | moved from `commands/admin` (K13) |
| 10 | `JCStressSubcommandService.executeCommand` (runner, `services/…:64`) | 58 | 4 | 0 | |
| 11 | `StackStep.run` (`deployment/setup/StackStep.java:29`) | 56 | 10 | 1 | K14 |
| 12 | `CloudFormationService.updateStackParameters` (`aws/…:113`) | 50 | 4 | 3 | |
| 13 | `ConfigSyncSubcommand.call` (`config/…:62`) | 49 | 5 | 0 | |
| 14 | `JobsListSubcommand.call` (`jobs/…:114`) | 48 | 10 | 0 | |
| 15 | `Ec2ProvisioningService.runInstance` (`jobs/Ec2ProvisioningService.java:50`) | 48 | 1 | 8 | one flat request builder — fine |

Left the list: `JobSession.await` (40 lines, still the most-branching method in `jobs`: cc **16**, 6
params, `jobs/JobSession.java:217` — K11).

Other branching outliers: `ResultsTable.print` cc 14 / 37 lines (`results/ResultsTable.java:50`);
`ConfigSetSubcommand.call` cc 11 / 24; `JobTermination.terminate` cc 10 / 47. In setup:
`ImageStep.run`, `GitHubStep.decide`, `PolicyHandOver.handOver` (cc 12 each), then
`SetupWalkthrough.run` and `DeploymentStep.run` (cc 11) — unchanged.

Longest parameter lists: `UserDataScriptBuilder.build` **16** (K5); `JobLaunch.Request` 12 (a record —
a data shape, named fields); `ResultRow` canonical ctor 11 (+ 10- and 9-arg overloads);
`JmhMeasurementMapper.toMeasurement` 9; `Ec2ProvisioningService.runInstance` and its port twin
`AwsRunAws.runInstance` (`jobs/AwsRunAws.java:78`) 8; `SetupContext` ctor 7; `JobLaunch` ctor 6;
`JobSession.await` 6. `RunCommand.recordLaunchFailure` (7) became `JobLaunch.recordLaunchFailure` with 5
(`jobs/JobLaunch.java:279`); `RunCommand.report` (6) left with the launch.

### baas-cli package dependency matrix (import counts, row → column; same-package refs omitted)

`model` = both baas-model packages (`model`, `model.dynamodb`).

| from \ to | (root) | cli | session | aws | config | console | deployment | deployment.setup | image | jobs | results | model | fan-out |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| (root) `BaasApp`, `AdminCommand` | — | 4 | 2 |  | 1 |  | 1 |  | 1 | 2 | 2 |  | 7 |
| cli |  | — | 1 |  |  |  |  |  |  |  |  |  | 1 |
| session |  |  | — | 4 |  |  |  |  |  |  |  |  | 1 |
| aws |  |  |  | — |  |  |  |  |  |  |  |  | 0 |
| config (slice) |  | 9 | 5 | 2 | — | 5 |  |  |  |  |  |  | 4 |
| console |  |  |  |  |  | — |  |  |  |  |  |  | 0 |
| deployment |  | 5 | 4 | 2 |  | 3 | — | **4** | 3 | 1 |  |  | 7 |
| deployment.setup |  |  | 18 | 9 |  | 3 | **2** | — | 7 |  |  |  | 5 |
| image |  | 5 | 6 | 4 |  | 2 |  |  | — |  |  |  | 4 |
| jobs |  | 14 | 16 | 10 |  | 22 |  |  | 4 | — | 7 | 37 | 7 |
| results |  | 3 | 3 |  |  | 9 |  |  |  |  | — | 8 | 4 |

Fan-in (packages depending on it): session 8, cli 6, aws 6, console 6, image 4, deployment 2, jobs 2,
results 2, model 2, config 1, deployment.setup 1, (root) 0.
AWS SDK service packages imported: `aws` 8 (cloudformation, dynamodb, ec2, iam, imagebuilder, s3, ssm,
sts), `image` 3 (ec2, imagebuilder, ssm), `jobs` 3 (dynamodb, ec2, s3), `deployment.setup` 3
(cloudformation, dynamodb, iam), `session` 2 (s3, sts), `results` 1, `deployment` 1 (iam, in
`DeployerPreflight`). **No `*Command`/`*Subcommand` class imports an SDK service package.**

Reading: the kernel is a DAG of leaves and near-leaves (`aws` and `console` import nothing; `session` →
`aws`; `cli` → `session`) and imports no slice. Slice-to-slice edges are exactly ADR 0009's order —
deployment → jobs (1) and image (3), jobs → image (4) and results (7); `config` is a slice that imports
only the kernel. `(root)` is imported by nothing — the 26 `commands → (root)` imports K9 named are now
`cli.Invocation`/`cli.LoggingMixin` (the `cli` column). The single strongly connected pair is
`deployment` ↔ `deployment.setup` (K13); it lies inside one slice, which is where `SliceBoundaryTest`
draws its line, so it is recorded, not filed. `jobs` → `model` 37 is the contract the ADR made central.

## Findings

### K2 — Commands hand-roll "log error, return 1/2"
- **Status after slice-architecture:** Open, relocated. Counts unchanged once `JobLaunch` is counted with
  the command it came from; every site moved package.
- **Necessity:** Should · **Effort:** S
- **Location:** examples `results/ResultsQuerySubcommand.java:144–221` (10 validate-log-return blocks,
  exits at `:147, 153, 158, 162, 166, 174, 179, 183, 193, 201`), `jobs/RunCommand.java:241–297` (4) plus
  `jobs/JobLaunch.java:104–219` (4), `jobs/JobsDownloadSubcommand.java` (6),
  `image/ImageBuildSubcommand.java:60, 67, 82` (3; two behind `catch (IllegalStateException e)` at `:65,
  :80`).
- **Metric:** 52 `return 1;`/`return 2;` and 65 `logger.error` across the command classes, `JobLaunch` and
  the setup walkthrough (identical to 7d9660a: `RunCommand` 8 → `RunCommand` 4 + `JobLaunch` 4). 11
  `catch (IllegalStateException|IllegalArgumentException)` blocks in commands/steps (plus one in
  `ImageBuilderService:206`). Per file: ResultsQuery 10, JobsDownload 6, JobsList 5, Run 4, JobLaunch 4,
  JobsDiff 4, Teardown 4, JobsShow 3, ImageBuild 3, ConfigSync 3, ConfigSet 2, SetupWalkthrough 2. Setup
  still returns `new Outcome.Stop(exit, message)` values (10 sites) logged once by
  `SetupWalkthrough.run:34–38`. `JobLaunch.run` returns `int` and logs at each exit — the refactor
  extracted the launch without adopting the returned-outcome form K1 suggested.
- **Recommendation:** unchanged. A `CommandRefusal extends RuntimeException { int exitCode; }` handled by
  `BaasApp.reportFailure` (`BaasApp.java:193`) the way `session/DeploymentSelectionException` is, for the
  flat validators (`ResultsQuery`, `JobsList`, `ConfigSet`, `ImageBuild`); for `JobLaunch`, a sealed
  `Launched | Refused(exit, message)` as setup does.
- **Payoff:** ~35 `if/log/return` triples; `ResultsQuerySubcommand.call` cc 22 → ~8.

### K3 — JSON payloads are hand-built in five commands; the job object is duplicated
- **Status after slice-architecture:** Open, changed. The escaping half is fixed: one
  `console/Json.java` (`string`, `object`, `number`) now escapes every string field (quotes, backslash,
  control characters as `\uXXXX`), so the three disagreeing escapers and the unescaped `printJson`
  fields are gone (ADR 0009 lists the visible change). The structural half remains.
- **Necessity:** Could (was Should — the bug class is closed) · **Effort:** XS
- **Location:** templates `jobs/JobsListSubcommand.java:243–257`, `jobs/JobsShowSubcommand.java:191–205`,
  `results/ResultsQuerySubcommand.java:390–413`, `config/ConfigListSubcommand.java:86–97`,
  `jobs/RunCommand.java:595–601`; private one-line wrappers `json(String)` → `Json.string` at
  `ConfigListSubcommand.java:99`, `JobsListSubcommand.java:277`, `JobsShowSubcommand.java:247`.
- **Metric:** 5 `printf`/`formatted` JSON templates; 3 copies of the `i < size() - 1 ? "," : ""` array loop
  (`ConfigList:94`, `ResultsQuery:410`, `JobsList:254`); the 10-field job object written twice, from two
  templates (`JobsList:249–254`, `JobsShow:195–200`); 3 private `json()` wrappers that only rename
  `Json.string`. `console.Json`'s Javadoc keeps hand-building deliberately ("each command's field order is
  part of its documented output") — the recommendation does not dispute that.
- **Recommendation:** within that choice: `Json.array(List<String>)` for the loop, one
  `jobJson(JobListing.Row)` shared by list and show, and drop the three wrappers.
- **Payoff:** ~25 lines; list and show cannot drift apart.

### K4 — `--format` validated by hand in five commands
- **Status after slice-architecture:** Open, relocated.
- **Necessity:** Could · **Effort:** XS
- **Location:** `config/ConfigListSubcommand.java:37, 53, 57–58`, `results/ResultsQuerySubcommand.java:133,
  141, 145–146`, `jobs/JobsShowSubcommand.java:72, 112–114`, `jobs/JobsListSubcommand.java:47, 111,
  115–116`, `jobs/RunCommand.java:160, 244–246`.
- **Metric:** 5 `String format` options, 3 `FORMATS` lists and 2 inline value lists, 5 "Unknown --format"
  messages, exit 2 each.
- **Recommendation:** unchanged — enum-typed options with
  `setCaseInsensitiveEnumValuesAllowed(true)` once in `BaasApp`; keep `run`'s `text`.
- **Payoff:** ~30 lines; exhaustive `switch`.

### K5 — `UserDataScriptBuilder.build` takes 16 positional parameters
- **Status after slice-architecture:** Open, relocated (class to `jobs`, caller to `JobLaunch`).
- **Necessity:** Should · **Effort:** S
- **Location:** `baas-cli/.../jobs/UserDataScriptBuilder.java:298–303`; sole production caller
  `jobs/JobLaunch.java:162–167`; 4 test call sites.
- **Metric:** 16 parameters, 11 `String`s, unchanged. The case for objects is now stronger: at the call
  site `JobItem job` is built three lines above (`JobLaunch.java:159`, carrying jobId, project, createdAt,
  resultPath, sort key, tags), `RunnerImage runnerImage` is in scope, and `JobLaunch.Request` carries
  benchmarkType, params, timeout and watchdog — the call unpacks all three back into positions.
  `deployment.resultsTable()`/`bucket()` come from `DeploymentNames`. The contract-name side of the class
  improved (artifact names now come from `JobLayout` tokens, `:352–354`), the signature did not.
- **Recommendation:** `build(String region, DeploymentNames deployment, JobItem job, RunnerImage image,
  JobLaunch.Request request, String runnerJarKey)`, deriving `benchmarkJarKey` via
  `JobLayout.benchmarkJarKey(job.project(), job.jobId())`. `RunCommand.Timings`
  (`jobs/RunCommand.java:391`) is no longer needed by the builder at all.
- **Payoff:** removes a compile-invisible swap hazard on the artifact that only fails on a paid instance.

### K6 — Three JMH runner services duplicate the run-and-store flow, and have already diverged
- **Status after slice-architecture:** Open, relocated (constructors now public, builders gone — K7 —
  bodies unchanged). **The async-profiler missing-directory bug is still present.**
- **Necessity:** Should · **Effort:** M
- **Location:** `benchmark-runner/.../services/JmhSubcommandService.java:39–70`,
  `JmhWithProfilerSubcommandService.java:52–87, 99–121`, `JmhWithAsyncProfilerSubcommandService.java:52–87,
  103–119`.
- **Metric:** `executeCommand()` ~35 lines in each, differing only in `-prof` arguments, output file name
  and whether artifacts are uploaded; `createDirectories` (`:43`/`:56`/`:56`), the non-zero-exit throw
  (`:57`/`:72`/`:72`), `JobLogs.upload`, `JmhRunResults.uploadJsonAndMap` and `resultsStore.write` repeated
  in all three. `uploadProfilerArtifacts` is duplicated and drifted: the `-prof` variant skips a missing
  per-benchmark directory (`JmhWithProfilerSubcommandService.java:105–108`, with the comment naming the job
  failure it fixed); the async variant calls `Files.list(localDir)` unguarded
  (`JmhWithAsyncProfilerSubcommandService.java:107`), so a benchmark for which async-profiler writes no
  directory throws `NoSuchFileException` → `JavaWonderlandException` after the benchmark finished and
  before `resultsStore.write` (`:86`) — the measurements are lost.
- **Recommendation:** unchanged — one service parameterised by a small profiling strategy
  (`profilerArgs()`, `outputFileName()`, optional `localArtifactDir(JmhResult)`), the artifact upload
  written once with the `isDirectory` guard. At minimum, port the guard to the async variant now: it is
  a one-line bug fix independent of the consolidation.
- **Payoff:** ~120 runner lines, one place for exit-code/upload ordering, and the divergence cannot recur.

### K9 — Command plumbing copied into 7–8 commands
- **Status after slice-architecture:** Open, changed. Two of the four copied members are gone: the ten
  private `configService()` wrappers became direct `cli.Invocation.configService(spec)` calls (a static
  helper over `cli.Root`, no longer `BaasApp`), and the deployer `new AwsClientFactory(region,
  AwsClientFactory.profile(…))` copies became `session.DeployerSession.factory(config)`
  (`ImageBuildSubcommand:75`, `ImageShowSubcommand:46`, `TeardownCommand:79`, `AwsSetupAws:42`).
- **Necessity:** Could · **Effort:** S
- **Location:** memoised `OperatorSession session()` in 7 (`jobs/JobsDownloadSubcommand.java:54`,
  `JobsDiffSubcommand:51`, `JobsShowSubcommand:57`, `JobsListSubcommand:69`,
  `JobsTerminateSubcommand:39`, `results/ResultsQuerySubcommand.java:55`, `jobs/RunCommand.java:80` as
  `operatorSession()`); memoised `Console console()` in 8 (`config/ConfigListSubcommand.java:107`,
  `results/ResultsQuerySubcommand.java:307`, `deployment/TeardownCommand.java:217`,
  `jobs/JobsDiffSubcommand.java:185`, `JobsTerminateSubcommand:95`, `JobsListSubcommand:289`,
  `RunCommand:345`, `JobsShowSubcommand:251`); un-memoised `Console.of(...)` in
  `config/ConfigShowSubcommand.java:37` and `image/ImageShowSubcommand.java:48`; injectable
  `consoleFor` in `deployment/SetupCommand.java:102`.
- **Metric:** ~15 near-identical 4–6-line members (was ~25). Console construction still comes in three
  forms. `RunCommand` still holds six mutable `summary*` fields (`:174–181`), `summaryDeployment` still
  only for `hints()` (`:227`); `Invocation.configService(spec)` is now called 20 times, several commands
  constructing it 3–4 times per invocation (`TeardownCommand:55, 68, 70, 105`, `ConfigSyncSubcommand:65,
  101, 108`).
- **Recommendation:** unchanged in shape — a picocli `@Mixin` (it would sit naturally in `cli`) holding
  `@Spec(MIXEE)` with `console()`, `configs()`, `operator()`, `deployer(BaasConfig)`, `hint(rest)`.
- **Payoff:** ~70 lines; one way to build a console.

### K11 — `JobSession.Outcome.exitCode` is derivable; the terminal-read check is repeated
- **Status after slice-architecture:** Open, relocated (line shift only).
- **Necessity:** Could · **Effort:** XS
- **Location:** `baas-cli/.../jobs/JobSession.java:46` (`record Outcome(String status, int exitCode)`,
  which already has a `completed()` method at `:53`), `:231, 252, 270, 281`
  (`JobStatus.COMPLETED.equals(x) ? 0 : 1` ×4); `:135, 226, 235, 244` and `jobs/JobStop.java:46`
  (`x.isPresent() && x.get().isTerminal()` ×5).
- **Metric:** `await` (`:217`) cc 16, 6 parameters.
- **Recommendation:** unchanged — `exitCode()` as `completed() ? 0 : 1`; `readTerminal()` =
  `readQuietly().filter(JobItem::isTerminal)`.
- **Payoff:** removes a state-consistency hazard and ~10 lines.

### K12 — Mutable `static @Spec` in a runner mixin shared by three subcommands
- **Status after slice-architecture:** Open, relocated.
- **Necessity:** Could · **Effort:** XS
- **Location:** `benchmark-runner/.../commands/ApiJmhOptions.java:21–22` (`@Spec static CommandSpec spec;`).
- **Metric:** still the only mutable static field in production code across the three modules; the mixin
  is used by `jmh`, `jmh-with-prof` and `jmh-with-async`, so `setBenchmarkPath`'s `ParameterException`
  reports against whichever command was injected last.
- **Recommendation:** `@Spec(Spec.Target.MIXEE) CommandSpec spec;` as an instance field.
- **Payoff:** one line; removes global state.

### K13 — The setup port leaks an SDK type (the cycle is fixed)
- **Status after slice-architecture:** Open, changed. The cycle half is **fixed**: `RunnerImageBuild` moved
  to `image/RunnerImageBuild.java` together with `olderBaseRefusal` (`:100`), called by
  `image/ImageBuildSubcommand` and `deployment/setup/AwsSetupAws`; the slice graph is acyclic and
  `SliceBoundaryTest` pins it. The port half is unchanged.
- **Necessity:** Could · **Effort:** XS
- **Location:** `deployment/setup/SetupAws.java:5, 64, 67` (`Optional<StackStatus> stackStatus/awaitSettled`);
  consumers `setup/SetupContext.java:6, 69, 142`, `setup/StackStep.java:8, 33, 42`; tests
  `FakeSetupAws`, `SetupStepsTest`, `RepeatCommandTest`, `deployment/SetupCommandTest`.
- **Metric:** four main classes (port, adapter, `SetupContext`, `StackStep`; `NamesStep` no longer) and four
  test classes import `software.amazon.awssdk.services.cloudformation.model.StackStatus`, against the
  port's own Javadoc. Separately, within the slice: `deployment` ↔ `deployment.setup` import each other
  (`SetupCommand` → 4 setup types; `AwsSetupAws` → `DeployerPreflight`, `PermissionsStep` →
  `DeployerPolicyRenderer`). Same slice, so not a boundary violation. Those two setup classes are the
  deployer-policy classes' only users in main code, so moving them into `setup` would make the child a
  leaf — noted, not required.
- **Recommendation:** a three-value setup enum (`Absent`, `RolledBack`, `Exists`) on the port, mapped in
  `AwsSetupAws`.
- **Payoff:** setup steps become SDK-free, so "commands and steps import no SDK" holds for the whole
  `deployment` slice except its one adapter (and `DeployerPreflight`).

### K14 — The AccessDenied hand-over is duplicated inside the setup package
- **Status after slice-architecture:** Open, changed. `NamesStep.rolledBack` (the uncalled method) is
  deleted; the `ROLLBACK_COMPLETE` test is down from three copies to two; the AccessDenied branch is
  unchanged.
- **Necessity:** Could · **Effort:** XS
- **Location:** AccessDenied → policy hand-over: `deployment/setup/SetupWalkthrough.java:64–73` and
  `deployment/setup/StackStep.java:75–80` (both `AwsErrors.isAccessDenied(e)` → identical "This identity is
  missing a permission…" log → `permissionFailure(context)`). Rolled-back test:
  `setup/SetupContext.java:142` (`creating()`) and `setup/StackStep.java:42`.
- **Metric:** `StackStep.run` catches `RuntimeException` and repeats exactly what `SetupWalkthrough.outcome`
  does for every step once `context.policy` is set — which `PermissionsStep.run:24` guarantees before the
  stack step runs.
- **Recommendation:** delete `StackStep`'s generic `RuntimeException` branch (keep the
  `StackOperationException` one); give `StackStep` the predicate `creating()` uses (or K13's enum).
- **Payoff:** one place decides when a refusal becomes the hand-over; ~8 lines.

## Fixed by the slice-architecture refactor

- **K1 — `RunCommand` is a god class; `execute()` is a 197-line launch script.** Fixed. Steps 1–8 of the
  old `execute()` (image lookup, networking, naming, upload, user-data, reserve, launch,
  `recordLaunchFailure`, poll) are `JobLaunch.run` (`jobs/JobLaunch.java:104–219`, 116 lines, cc 6) over
  the `RunAws` port (`jobs/RunAws.java`, production `jobs/AwsRunAws.java`, test
  `baas-cli/src/test/.../jobs/FakeRunAws.java`), taking the immutable `DeploymentNames` rather than
  `BaasConfig`. `JobLaunchTest` runs the whole launch, success path included, in-process — closing the
  "success path executed by no JVM test" gap. The command keeps the shutdown hook (armed through
  `Listener.reserved`, `RunCommand.java:527`), the JSON summary and hints. `RunCommand` went from 938 /
  593 code / Σcc 111 to 615 / 362 / 73; `execute()` from 197 lines / cc 14 / 7 exits to 63 / 9 / 4, and
  constructs no AWS collaborator. `projectFromToplevel` is gone. Residue filed elsewhere: the early exits
  (K2 — `JobLaunch` returns `int` and logs per exit rather than a returned outcome), the six `summary*`
  fields and `summaryDeployment` (K9), and two zero-value indirections K1 named, still present:
  `resolveResultsTable` (`RunCommand.java:445`, returns `config.resultsTable()` after a null check) and
  `RESERVED_TAG_KEYS` (`:434`, an alias of `TagKeys.MACHINE_OBSERVED`). `RunCommand` is still the largest
  file, mostly option declarations and their Javadoc.
- **K7 — Four runner service builders.** Fixed (a8be1a0): `JmhSubcommandServiceBuilder`, `JmhWithProfilerSubcommandServiceBuilder`,
  `JmhWithAsyncProfilerSubcommandServiceBuilder`, `JCStressSubcommandServiceBuilder` and
  `infra/StorageServiceBuilder` are deleted; the services have public constructors and the subcommands
  call `StorageService.of(s3Options)` (`infra/StorageService.java:16`). `ResultsStoreBuilder` is kept, as
  recommended. Residue: the constructors still `requireNonNull` only `storageService`/`resultsStore`
  (e.g. `JmhSubcommandService.java:31–32`), not `commonOptions`/`jmhOptions` — now a compile error for a
  missing argument but still an NPE later for a null one.
- **K8 — Job-artifact keys and resource names composed by hand.** Fixed. `JobLayout` (baas-model) gained
  `ENVIRONMENT_NAME`, `PACKAGES_NAME`, `BOOT_LOG_NAME`, `RESULT_JSON_NAME` (`JobLayout.java:29–35`) and
  `artifactKey(resultPath, name)` (`:76`); every former site uses them — `jobs/JobsDiffSubcommand.java:119–120,
  193`, `jobs/JobsShowSubcommand.java:100`, `jobs/JobLaunch.java:313`, runner
  `results/JmhRunResults.java:43, 47`, `services/JCStressSubcommandService.java:117` — and user-data takes
  the same constants as tokens (`UserDataScriptBuilder.java:352–354`). The three setup `-results`
  concatenations are gone (`DeploymentNames.resultsTable()`), and teardown's bucket is
  `DeploymentNames.of(resolvedStack).bucket()` (`deployment/TeardownCommand.java:116`). Residue (was
  "related, optional"): `"BaasCliDeployerPolicy-" + deployment` (`deployment/setup/PolicyHandOver.java:82`)
  still has no `DeploymentNames` entry; and the listing prefix `path + "/"`
  (`JobsShowSubcommand.java:101, 225`; `JobsDownloadSubcommand.java:93` with a trailing-slash check) is
  hand-joined — a prefix, not an artifact key, so not filed.
- **Parts of open findings:** K3's escaping (one `console.Json`), K9's `configService()` wrappers and
  deployer-factory copies, K13's cycle, K14's dead `NamesStep.rolledBack` — each recorded under its
  finding above.

## Resolved by interactive-setup (7d9660a)

- **K10 — `SetupCommand` threads a five-value policy context through three methods.** Resolved:
  `refusalForMissingRights` and `handOverPolicy` are gone; the policy, caller, region and deployment are
  `SetupContext` fields and the hand-over is `PolicyHandOver.handOver(SetupContext)`
  (`deployment/setup/PolicyHandOver.java:27`). Still true after the refactor.

## Withdrawn

None.

## Looked at and not filed

- Package layering in baas-cli — the kernel imports no slice, slice edges follow ADR 0009, `(root)` is
  imported by nothing, and no command imports the SDK. The one strongly connected pair
  (`deployment` ↔ `deployment.setup`) is intra-slice (K13 notes it).
- `JobLaunch` (390 lines, 13 methods, Σcc 40) — new, cohesive, one use case; its `run` is long (116) but
  linear (cc 6) with numbered steps whose order is the invariant. Not a hotspot; its exits are K2.
- `JobLaunch.Request` (12 components) — a record with named accessors, not a positional hazard like K5.
- Interfaces (`JobRecorder`, `JobSession.Instances`, `RunAws`, `StorageService`, `ResultsStore`,
  `OperatorSession.Aws`, `JobsShowSubcommand.Sources`, `SetupAws`, `SetupStep`, `cli.Root`) — each has a
  test fake or a second implementation.
- The setup package's size (1,402 code lines over 21 files) — unchanged in kind; no method above 56 lines.
- `ImageBuilderService` (539 lines), `CloudFormationService`, `OperatorSession` — large but cohesive.
- `MeasurementItemMapper.toItem` (cc 11) and the runner `options` records — flat mapping and picocli
  shapes.
- The two `LoggingMixin`s (cli in `cli/`, runner in `commands/`) differ for the documented reason (argv
  pre-scan); not duplication.
- Comment density (~32 % of lines) — deliberate project style carrying invariants.
