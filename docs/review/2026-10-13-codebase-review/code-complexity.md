# Code-complexity review — production code (baas-cli, benchmark-runner, baas-model)

Branch `codebase-review`, 2026-10-13. Read-only. Helper scripts in this directory: `metrics.py`
(method length, cyclomatic indicator, parameter counts), `deps.py` (baas-cli package import matrix),
`loc.py` (code vs comment lines), `dead.py` (unused / test-only public members), `table.py`.

## Summary

1. The codebase is mostly well-factored: the baas-cli package graph is acyclic and layered (`config`, `console` have fan-out 0; only `infra` touches 8 AWS SDKs; `commands` touch none directly except `admin`), interfaces all have a test seam, and dead code is almost nil (every "unused" hit is a picocli/Jackson/visitor callback).
2. Complexity concentrates in three picocli command classes — `RunCommand` (585 code lines, Σcc 110, `execute()` 200 lines), `SetupCommand` (`call()` cc 18) and `ResultsQuerySubcommand` (`call()` cc 22) — which mix option parsing, AWS orchestration, rendering and exit-code policy.
3. The single biggest enabler is error-exit duplication: 61 `return 1/2` early exits and 69 `logger.error` calls in `commands/`; a refusal exception handled once in `BaasApp.reportFailure` is what makes the long `call()`s extractable (K2 → K1).
4. Clear duplication worth removing: hand-built JSON in 5 commands with 3 escapers that disagree (K3), three near-identical JMH runner services plus 227 lines of builders (K6/K7), and job-artifact keys/resource names composed by hand despite the `JobLayout`/`DeploymentNames` single-builder rules (K8).
5. Nothing here is Must; ~10 findings, roughly 3–5 days of work in total, the best payoff/effort being K8, K3, K5 and K2.

## Index

| ID | Title | Necessity | Effort | Location |
|---|---|---|---|---|
| K1 | `RunCommand` is a god class; `execute()` is a 200-line launch script | Should | M | `commands/RunCommand.java:287` |
| K2 | Every command hand-rolls "log error, return 1/2" — 61 early exits block method extraction | Should | S | `commands/**` |
| K3 | JSON payloads hand-built in 5 commands with 3 disagreeing escapers; job object duplicated | Should | S | `ResultsQuerySubcommand:396`, `JobsList:243`, `JobsShow:195`, `ConfigList:85`, `RunCommand:907` |
| K4 | `--format` validated by hand in 5 commands | Could | XS | 5 commands |
| K5 | `UserDataScriptBuilder.build` takes 16 positional parameters, 11 of them `String` | Should | S | `infra/UserDataScriptBuilder.java:293` |
| K6 | Three JMH runner services duplicate `executeCommand` and artifact upload — and have already diverged | Should | M | `benchmark-runner/services/Jmh*SubcommandService.java` |
| K7 | Four runner service builders (227 lines) build objects with only required fields | Could | S | `benchmark-runner/services/*Builder.java` |
| K8 | Job-artifact keys and resource names composed by hand, bypassing `JobLayout`/`DeploymentNames` | Should | XS | 8 sites |
| K9 | Command plumbing (`session()`, `console()`, `configService()`, deployer factory) copied into 7–10 commands | Could | S | `commands/**` |
| K10 | `SetupCommand` threads a 5-value policy context through 3 methods | Could | XS | `commands/admin/SetupCommand.java:181–250` |
| K11 | `JobSession.Outcome.exitCode` is a derivable field; terminal-read check repeated 5× | Could | XS | `jobs/JobSession.java:39, 210–274` |
| K12 | Mutable `static @Spec` in a runner mixin shared by three subcommands | Could | XS | `benchmark-runner/commands/ApiJmhOptions.java:21` |

## Metrics

### Top 15 files by size (production code, all three modules)

"Code" = non-blank lines after stripping comments and string/text-block contents. Overall: 14,727
lines, 9,930 code (67 %); the rest is the deliberate rationale prose — not counted as a smell here.
Σcc = sum of a rough cyclomatic indicator (1 + `if`/`for`/`while`/`case`/`catch`/`&&`/`||`/`?`) per method.

| # | File | Lines | Code | Methods | Σcc | max cc |
|---|---|---|---|---|---|---|
| 1 | `baas/commands/RunCommand.java` | 927 | 585 | 35 | 110 | 14 |
| 2 | `baas/commands/admin/SetupCommand.java` | 627 | 378 | 22 | 81 | 18 |
| 3 | `baas/commands/ResultsQuerySubcommand.java` | 496 | 369 | 28 | 94 | 22 |
| 4 | `baas/infra/ImageBuilderService.java` | 400 | 241 | 16 | 40 | 5 |
| 5 | `baas/infra/UserDataScriptBuilder.java` | 382 | 69 | 6 | 7 | 2 |
| 6 | `baas/infra/CloudFormationService.java` | 311 | 214 | 19 | 43 | 7 |
| 7 | `baas/jobs/JobSession.java` | 300 | 193 | 19 | 63 | 16 |
| 8 | `baas/commands/JobsListSubcommand.java` | 295 | 246 | 14 | 48 | 10 |
| 9 | `baas/model/MeasurementItemMapper.java` | 282 | 218 | 18 | 46 | 11 |
| 10 | `baas/infra/OperatorSession.java` | 264 | 165 | 19 | 39 | 6 |
| 11 | `baas/commands/admin/TeardownCommand.java` | 261 | 175 | 14 | 29 | 6 |
| 12 | `baas/commands/JobsShowSubcommand.java` | 260 | 218 | 19 | 44 | 9 |
| 13 | `baas/infra/RunnerImageRenderer.java` | 253 | 102 | 10 | 17 | 3 |
| 14 | `baas/config/ConfigService.java` | 250 | 171 | 22 | 40 | 5 |
| 15 | `baas/config/BaasConfig.java` | 243 | 134 | 39 | 54 | 4 |

benchmark-runner's largest file is `commands/ApiJmhOptions.java` (203 lines, picocli option
declarations); baas-model's is `MeasurementItemMapper.java` (above). `UserDataScriptBuilder`'s size is
the bash text block (stripped to one line by the metric) — its complexity is the parameter list (K5).

### Top 15 methods by length / branching

Record compact bodies (`RunnerImageParameters`, `EnvironmentManifest`, `RunnerImageDefinition`,
`PackagesDiff`) were picked up by the script as one "method" each and are excluded — they are several
small methods each.

| # | Method | Lines | cc | Params | Note |
|---|---|---|---|---|---|
| 1 | `RunCommand.execute` (`commands/RunCommand.java:287`) | 200 | 14 | 0 | K1, K2 |
| 2 | `SetupCommand.call` (`commands/admin/SetupCommand.java:102`) | 110 | 18 | 0 | K2, K10 |
| 3 | `ImageBuildSubcommand.call` (`commands/admin/ImageBuildSubcommand.java:78`) | 104 | 11 | 0 | K2 (4 catch-log-return blocks) |
| 4 | `SetupCommand.deploy` (`SetupCommand.java:296`) | 79 | 7 | 4 | K8 |
| 5 | `ResultsQuerySubcommand.call` (`ResultsQuerySubcommand.java:150`) | 77 | **22** | 0 | 10 validation exits, K2/K4 |
| 6 | `JobsDownloadSubcommand.call` (`JobsDownloadSubcommand.java:69`) | 77 | 11 | 0 | |
| 7 | `TeardownCommand.removeDeployment` (`TeardownCommand.java:79`) | 68 | 6 | 2 | K8 |
| 8 | `JobsDiffSubcommand.call` (`JobsDiffSubcommand.java:80`) | 67 | 11 | 0 | K8 |
| 9 | `MeasurementItemMapper.toItem` (`baas-model/.../MeasurementItemMapper.java:71`) | 64 | 11 | 1 | flat attribute mapping — fine |
| 10 | `ImageShowSubcommand.call` (`ImageShowSubcommand.java:48`) | 62 | 5 | 0 | |
| 11 | `JCStressSubcommandService.executeCommand` (runner, `:59`) | 58 | 4 | 0 | |
| 12 | `ConfigSyncSubcommand.call` (`:66`) | 49 | 5 | 0 | |
| 13 | `JobsListSubcommand.call` (`:114`) | 48 | 10 | 0 | |
| 14 | `CloudFormationService.updateStackParameters` (`:79`) | 48 | 4 | 3 | |
| 15 | `JobSession.await` (`jobs/JobSession.java:210`) | 40 | **16** | 6 | K11 |

Other branching outliers: `ResultsTable.print` cc 14 / 37 lines; `ConfigSetSubcommand.call` cc 11 / 24
lines; `JobTermination.terminate` cc 10.

Longest parameter lists: `UserDataScriptBuilder.build` **16**; `ResultRow` canonical ctor 11 (+ 10- and
9-arg overloads, both test-only); `JmhMeasurementMapper.toMeasurement` 9; `Ec2ProvisioningService.runInstance` 8;
`RunCommand.recordLaunchFailure` 7; `SetupCommand.refusalForMissingRights` 6; `JobSession.await` 6.
(Records such as `StoredMeasurement` (18) and `JmhResult` (19) are data shapes, not smells.)

### baas-cli package dependency matrix (import counts, row → column; same-package refs omitted)

| from \ to | (root) | commands | commands.admin | config | console | infra | jobs | model | results | fan-out |
|---|---|---|---|---|---|---|---|---|---|---|
| (root) `BaasApp`… | | 5 | 1 | 2 | | | | | | 3 |
| commands | 26 | | | 19 | 22 | 30 | 8 | 7 | 13 | **7** |
| commands.admin | 11 | | | 11 | 2 | 29 | | | | 4 |
| config | | | | | | | | | | 0 |
| console | | | | | | | | | | 0 |
| infra | | | | 4 | | | 2 | 3 | | 3 |
| jobs | | | | | | | | 14 | 1 | 2 |
| results | | | | | 4 | | 1 | 7 | | 3 |

Fan-in (packages depending on it): config 4, model 4, console 3, jobs 3, infra 2, results 2.
AWS SDK service packages imported: `infra` 8 (cloudformation, dynamodb, ec2, iam, imagebuilder, s3, ssm,
sts), `commands.admin` 2 (cloudformation, dynamodb), `jobs` 1, `results` 1, `commands` **0**.

Reading: no cycles, the two leaf packages are true leaves, and the only layering leak is
`commands.admin` reaching the SDK (`SetupCommand.tableExists` uses `DynamoDbClient` directly,
`SetupCommand.java:415`; `StackStatus` from cloudformation). The `commands → (root)` 26 imports are
`BaasApp.configService/deployment` and `LoggingMixin` — K9's plumbing.

## Findings

### K1 — `RunCommand` is a god class; `execute()` is a 200-line launch script
- **Necessity:** Should · **Effort:** M
- **Location:** `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java:287–486` (and the whole file)
- **Metric:** 927 lines / 585 code, 35 methods, Σcc 110 (highest in the repo); `execute()` 200 lines, 8 early `return`s, 6 separate `try (var x = factory.x())` client scopes, constructs 7 collaborators (`ImageBuilderService`, `S3UploadService` ×2, `RunnerJarResolver`, `UserDataScriptBuilder`, `Ec2ProvisioningService`, `DynamoDbJobRecorder` ×2, `JobSession`). The class also owns: option validation, project resolution, tag policy (`buildRunnerTags`), timing policy (`watchdogBound`), launch-failure reporting (writes `launch-error.txt`), polling display, the outcome messages (`report`), the shutdown hook, five mutable `summary*` fields read by the hook thread, the JSON summary and hints. CLAUDE.md itself records the consequence: "`RunCommand.call()`'s success path is executed by no JVM test".
- **Recommendation:** Keep `RunCommand` as the picocli adapter (options → a resolved `LaunchRequest` record; summary/hints/status line). Move steps 1–8 of `execute()` (JAR check, image + networking resolution, upload, user-data, reserve, launch, `recordLaunchFailure`) into a `jobs.JobLauncher` that takes the `AwsClientFactory` (or narrow interfaces it already has: `JobSession.Instances`, `JobRecorder`) and returns a `JobSession`. The invariants stay byte-identical — one clock read, table resolved before upload, hook registered before `RunInstances` (the launcher takes a `Runnable beforeLaunch` or returns before launch so the command registers the hook). While there, drop the zero-value indirections: `resolveResultsTable` (`:795`, returns `config.resultsTable()`), `projectFromToplevel` (`:699`, test-only delegate to `GitProject`), `RESERVED_TAG_KEYS` (`:784`, alias of `TagKeys.MACHINE_OBSERVED`).
- **Payoff:** `RunCommand` to ~300 code lines; the launch sequence becomes testable against fakes, closing the uncovered success path CLAUDE.md names. Do K2 first — it removes the early-return tangle that makes this extraction awkward.

### K2 — Every command hand-rolls "log error, return 1/2"
- **Necessity:** Should · **Effort:** S
- **Location:** `baas-cli/.../commands/**`; examples `ResultsQuerySubcommand.java:151–190` (10 consecutive validate-log-return blocks), `ImageBuildSubcommand.java:90–165` (4 `catch (IllegalStateException e) { logger.error(e.getMessage()); return 1; }`), `SetupCommand.java:110–125, 186–210`.
- **Metric:** 61 `return 1;`/`return 2;` and 69 `logger.error` in `commands/`; 12 `catch (IllegalStateException|IllegalArgumentException)` blocks, 10 of which only log and return. These early exits are why `call()` methods cannot be split — a helper cannot "return 2 from the caller".
- **Recommendation:** Add one `CommandRefusal extends RuntimeException { int exitCode; }` (or reuse the pattern `DeploymentSelectionException` already has) and have `BaasApp.reportFailure` (`BaasApp.java:195`) log its message and return its exit code without the "-v for stack trace" hint — exactly the treatment `DeploymentSelectionException` gets today. Then validations become `require(cond, 2, "…")` one-liners and service-layer `IllegalStateException`s that are user-facing refusals can be thrown as-is instead of caught per call site. Keep the "Nothing was launched/changed" wording in the messages.
- **Payoff:** removes ~40 `if/log/return` triples; `ResultsQuerySubcommand.call` cc 22 → ~8; makes K1's extraction mechanical. Fails safe: an uncaught refusal still exits non-zero through the existing handler.

### K3 — JSON payloads are hand-built in five commands, with three escapers that disagree
- **Necessity:** Should · **Effort:** S
- **Location:** `ResultsQuerySubcommand.java:396–415` (`printJson`), `:451–477` (`jsonObject`, `jsonString`), `:480` (`jsonOrNull`); `JobsListSubcommand.java:243–257, 277`; `JobsShowSubcommand.java:195–208, 250`; `ConfigListSubcommand.java:85–99`; `RunCommand.java:907–922`.
- **Metric:** 5 `printf`-templated JSON objects, 3 copies of the `i < size() - 1 ? "," : ""` array loop, 3 private `json(...)` wrappers plus 2 independent escapers. They disagree: `RunCommand.jsonString` (`:917`) escapes only `\` and `"` (no control characters); `ResultsQuerySubcommand.printJson` interpolates `jobId`, `benchmarkName`, `benchmarkType`, `mode`, `scoreUnit`, `createdAt` inside `"%s"` with no escaping at all, and `jsonOrNull` (`:480`) wraps `imageVersion`/`instanceType` unescaped. `JobsListSubcommand:249–254` and `JobsShowSubcommand:198–203` print the identical 10-field job object from two templates. Jackson databind is already on the CLI classpath (via `jackson-dataformat-yaml`) and already used in 5 classes (`EnvironmentManifest`, `ConfigService`, `DeployerPreflight`, …).
- **Recommendation:** One `console.Json` helper backed by a shared `ObjectMapper`: build `LinkedHashMap`s (or small records) and `writeValueAsString`. Keep the existing contract explicitly: non-finite → `null`, scores as `%.6f` (e.g. a `BigDecimal.valueOf(score).setScale(6, HALF_EVEN)` or a custom `double` serializer — Jackson's default shortest-repr would change the output), key-sorted tag maps (`TreeMap`), `Locale.ROOT` untouched since Jackson is locale-independent. Extract `jobJson(JobListing.Row)` once for list and show.
- **Payoff:** ~80 lines of string assembly removed, one escaping rule, and the unescaped-field class of bug closed (today latent: the unescaped values are machine-generated, but `benchmarkName` comes from user benchmark code).

### K4 — `--format` validated by hand in five commands
- **Necessity:** Could · **Effort:** XS
- **Location:** `ConfigListSubcommand.java:51–57`, `ResultsQuerySubcommand.java:135–153`, `JobsShowSubcommand.java:74–117`, `JobsListSubcommand.java:110–116`, `RunCommand.java:163, 290`.
- **Metric:** 5 `String format` options, each with its own `FORMATS` list / `equalsIgnoreCase` chain / "Unknown --format" message and exit 2; switch on `format.toLowerCase(Locale.ROOT)` repeated.
- **Recommendation:** Enum-typed options (`enum Format { TABLE, JSON, CSV }`, per-command subsets, `CommandLine.setCaseInsensitiveEnumValuesAllowed(true)` once in `BaasApp.commandLine`). picocli then rejects unknown values with exit 2 and lists `${COMPLETION-CANDIDATES}` in help. Keep `run`'s value name `text` (an enum constant `TEXT`) — renaming it would break CI scripts.
- **Payoff:** ~30 lines and five copies of one rule; `switch` over an enum is exhaustive.

### K5 — `UserDataScriptBuilder.build` takes 16 positional parameters
- **Necessity:** Should · **Effort:** S
- **Location:** `baas-cli/.../infra/UserDataScriptBuilder.java:293–297`; sole production caller `RunCommand.java:419–424`.
- **Metric:** 16 parameters — 11 `String`s, including the runs `jobId, resultPath, createdAt, benchmarkJarS3Key` and `imageVersion, amiId, runnerJarS3Key, resultsTableName, jobSortKey`. Any adjacent swap compiles and produces a script that only fails on a paid instance (the class's own Javadoc names that as the risk it guards against for quoting). Most of the values are already in objects at the call site: `JobItem` (jobId, project → resultPath, createdAt, sortKey, tags), `RunnerImage` (imageVersion, amiId), `RunCommand.Timings` (timeout, watchdog).
- **Recommendation:** `build(String region, String bucket, String resultsTable, JobItem job, RunnerImage image, Timings timings, String benchmarkType, String runnerJarKey, List<String> params)`, deriving `benchmarkJarKey` via `JobLayout.benchmarkJarKey(job.project(), job.jobId())`, `createdAt` from `job.createdAt()` and the tags from `job.tags()`. (`Timings` moves out of `RunCommand` to `infra` or `jobs`.) The `export(...)` lines and every invariant (`JOB_SORT_KEY` handed down verbatim, quoting) are unchanged.
- **Payoff:** removes a compile-invisible swap hazard on the most expensive-to-debug artifact; 5 test call sites get shorter.

### K6 — Three JMH runner services duplicate the run-and-store flow, and have already diverged
- **Necessity:** Should · **Effort:** M
- **Location:** `benchmark-runner/.../services/JmhSubcommandService.java:34–67`, `JmhWithProfilerSubcommandService.java:48–83, 97–118`, `JmhWithAsyncProfilerSubcommandService.java:48–83, 99–114`.
- **Metric:** `executeCommand()` is ~35 lines in each, identical except for (a) extra `-prof` arguments, (b) the process-output file name (`jmh-output.txt` / `jmh-profiler-output.txt` / `jmh-with-async-output.txt`), (c) whether artifacts are uploaded; each file repeats `createDirectories`, the non-zero-exit block, `JobLogs.upload`, `JmhRunResults.uploadJsonAndMap` and `resultsStore.write` (the "non-zero code" / `JobLogs.upload` / `createDirectories` trio appears 3× per file). `uploadProfilerArtifacts` is duplicated between the two profiler services — and has drifted: the `-prof` variant skips a missing per-benchmark directory (`:100–106`, with a comment explaining the job failure it fixed), the async variant does not (`:101–113`), so a benchmark for which async-profiler writes no directory fails the job after the benchmark finished, before measurements are stored.
- **Recommendation:** One `JmhRun` (or keep `JmhSubcommandService`) parameterised by a small `JmhProfiling` strategy: `List<String> profilerArgs()`, `String outputFileName()`, `Optional<Function<JmhResult, Path>> localArtifactDir()`. The three subcommands pick the strategy; the artifact upload is written once with the `isDirectory` guard. Sequence it with `retire-mongodb` (C4) or right after, since that change touches the same constructors.
- **Payoff:** ~120 runner lines removed (≈ 4 % of the module), one place for the exit-code/upload ordering, and the guard divergence cannot recur.

### K7 — Four runner service builders build objects that have only required fields
- **Necessity:** Could · **Effort:** S
- **Location:** `benchmark-runner/.../services/JmhSubcommandServiceBuilder.java`, `JmhWithProfilerSubcommandServiceBuilder.java`, `JmhWithAsyncProfilerSubcommandServiceBuilder.java`, `JCStressSubcommandServiceBuilder.java`.
- **Metric:** 227 lines for 4–5 fields per service, no defaults, no optional fields, and inconsistent null checks (only `storageService`/`resultsStore` are `requireNonNull`ed; `commonOptions`/`jmhOptions` are passed through unchecked). The services' constructors are package-private, so the builders exist only to make construction public. Used by 4 subcommands and 6 tests.
- **Recommendation:** Make the constructors public (with `requireNonNull` on every argument) and delete the builders; with K6 this collapses to two constructors. Keep `ResultsStoreBuilder` — it carries the real exactly-one-of-two store rule.
- **Payoff:** −227 lines of ceremony; a missing argument becomes a compile error instead of a runtime NPE.

### K8 — Job-artifact keys and resource names composed by hand outside `JobLayout`/`DeploymentNames`
- **Necessity:** Should · **Effort:** XS
- **Location:**
  - `baas-cli/.../commands/JobsDiffSubcommand.java:199` (`resultPath + "/environment.json"`), `:125–126` (`pathA + "/packages.txt"`, `pathB + …`)
  - `baas-cli/.../commands/JobsShowSubcommand.java:103` (`path + "/environment.json"`)
  - `baas-cli/.../commands/RunCommand.java:585` (`… + "/cloud-init-output.log"`)
  - `benchmark-runner/.../results/JmhRunResults.java:42, 46` (`"jmh-result.json"`, `"environment.json"`), `services/JCStressSubcommandService.java:112`
  - `baas-cli/.../commands/admin/SetupCommand.java:317, 389` (`resolvedPrefix + "-results"`, `deployment + "-results"`)
  - `baas-cli/.../commands/admin/TeardownCommand.java:115` (`String bucket = resolvedStack;`)
- **Metric:** 9 hand-built names. CLAUDE.md: "Everything a job produces … built only by `JobLayout`/`JobId` … A hand-built prefix does not fail to compile; it points at nothing, and that presents as an empty download", and "only `DeploymentNames` composes them (C5)". `JobLayout` already has constants for `launch-error.txt`, `benchmark.jar`, `runner.jar` but none for `environment.json`, `packages.txt`, `cloud-init-output.log`, `jmh-result.json`; `DeploymentNames.resultsTable()`/`bucket()` already exist.
- **Recommendation:** Add `JobLayout.ENVIRONMENT_NAME`/`PACKAGES_NAME`/`BOOT_LOG_NAME`/`RESULT_JSON_NAME` plus `…Key(resultPath)` helpers and use them on both sides (the CLI reads what the runner writes — the reason `baas-model` exists). Replace the `-results` concatenations with `DeploymentNames.of(prefix).resultsTable()` and the teardown bucket with `config.bucket()`. The user-data bash keeps its literals (it cannot call Java), but a test can pin them to the constants.
- **Payoff:** restores two documented single-builder invariants for ~20 minutes of work; a rename can no longer silently split reader from writer.

### K9 — Command plumbing copied into 7–10 commands
- **Necessity:** Could · **Effort:** S
- **Location:** memoised `OperatorSession session()` in 7 commands (`JobsDownload:57`, `JobsDiff:54`, `JobsShow:60`, `JobsList:69`, `ResultsQuery:58`, `JobsTerminate:43`, `RunCommand:94` as `operatorSession()`); memoised `Console console()` in 8 (`JobsDiff:191`, `ConfigList:106`, `JobsShow:254`, `JobsList:289`, `ResultsQuery:312`, `JobsTerminate:99`, `Teardown:202`, `RunCommand:674`); `configService()` → `BaasApp.configService(spec)` in 10; deployer `new AwsClientFactory(region, AwsClientFactory.profile(config.getAws().getDeployerProfile()))` in 4 admin commands (`ImageBuild:109`, `Teardown:80`, `Setup:134`, `ImageShow:53`).
- **Metric:** ~25 near-identical 4–6-line members; these account for most of the 26 `commands → (root)` imports in the matrix.
- **Recommendation:** One picocli `@Mixin CommandContext` holding `@Spec(MIXEE)`, with `console()`, `configs()`, `operator()` (memoised `OperatorSession`) and `deployerFactory(BaasConfig)`. Tests keep their seam by assigning `command.context.console = …` (today they assign `command.console`). Hints can then be rendered through the context, which is the natural one place to fix open finding E2 (hints omit `--deployment`).
- **Payoff:** ~120 lines; a new command cannot get the plumbing subtly different (e.g. `ImageShowSubcommand:55` builds an un-memoised `Console` while every other command memoises).

### K10 — `SetupCommand` threads a five-value policy context through three methods
- **Necessity:** Could · **Effort:** XS
- **Location:** `baas-cli/.../commands/admin/SetupCommand.java:181, 197, 208` (call sites), `:218` `refusalForMissingRights(denied, policy, accountId, region, deployment, callerArn)` (6 params), `:233` `handOverPolicy(policy, accountId, region, deployment, callerArn)` (5).
- **Metric:** the same five values passed together at 4 call sites; `call()` is 110 lines, cc 18.
- **Recommendation:** `record DeployerPolicy(String document, String accountId, String region, String deployment, String callerArn)` with `handOver()`/`refusal(denied)` methods (or keep them on the command taking the record). Combined with K2, `call()` shrinks to resolve → probe → preflight → deploy.
- **Payoff:** small; mainly readability of the longest-branching admin method.

### K11 — `JobSession.Outcome.exitCode` is derivable; the terminal-read check is repeated
- **Necessity:** Could · **Effort:** XS
- **Location:** `baas-cli/.../jobs/JobSession.java:39` (`record Outcome(String status, int exitCode)`), `:224, 245, 263, 274` (`JobStatus.COMPLETED.equals(x) ? 0 : 1` ×4); `:128, 219, 228, 237` and `JobStop.java:46` (`x.isPresent() && x.get().isTerminal()` ×5).
- **Metric:** `await` is cc 16 with 6 parameters; a quarter of its branches are these repeats.
- **Recommendation:** Drop the `exitCode` component and make `exitCode()` a method returning `completed() ? 0 : 1` (it can never disagree with `status` then). Add `Optional<JobItem> readTerminal()` = `readQuietly().filter(JobItem::isTerminal)` so each check is `readTerminal().map(this::finish)`.
- **Payoff:** removes a state-consistency hazard and ~10 lines from the most-branching method in `jobs`.

### K12 — Mutable `static @Spec` in a runner mixin shared by three subcommands
- **Necessity:** Could · **Effort:** XS
- **Location:** `benchmark-runner/.../commands/ApiJmhOptions.java:20–31`
- **Metric:** the only mutable static field in production code across all three modules. `ApiJmhOptions` is a `@Mixin` in `jmh`, `jmh-with-prof` and `jmh-with-async`; a static `@Spec` is overwritten by each instantiation, so `setBenchmarkPath`'s `ParameterException` is raised against whichever command was injected last (wrong usage text on error).
- **Recommendation:** `@Spec(Spec.Target.MIXEE) CommandSpec spec;` as an instance field.
- **Payoff:** one line; removes global state.

## Looked at and not filed

- Package layering in baas-cli — acyclic, leaves are leaves; no finding beyond the `SetupCommand.tableExists` SDK use noted above.
- Interfaces (`JobRecorder`, `JobSession.Instances`, `StorageService`, `ResultsStore`, `OperatorSession.Aws`, `JobsShowSubcommand.Sources`) — each has a test fake or a second implementation; not needless.
- `ImageBuilderService`, `CloudFormationService`, `OperatorSession` — large but cohesive (one AWS service / one concern each).
- `MeasurementItemMapper.toItem` (cc 11) and the runner option records (11–15 params) — flat mapping and picocli shapes; splitting would add indirection.
- Dead code — `dead.py` found only framework callbacks (picocli setters, `IVersionProvider.getVersion`, Jackson `handleUnknownProperty`, `FileVisitor` overrides, Morphia getters). Test-only production members are minor: `ResultRow`'s 10- and 9-arg constructors (`ResultRow.java:35, 46`), `DeploymentNames.runnerRole`, `StoredMeasurement.withBenchmarkClass`; Mongo-side items are covered by C4.
- The two `LoggingMixin`s (cli 79 lines, runner 28) differ for the documented reason (argv pre-scan); not duplication.
- Comment density (33 % of lines) — deliberate project style carrying invariants; not a complexity finding.
