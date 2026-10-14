# Code-complexity review — production code (baas-cli, benchmark-runner, baas-model)

Re-verified against 7d9660a on 2026-10-14.

Branch `codebase-review`, written 2026-10-13 against dabbc55; re-verified after the rebase onto
`next-release` 7d9660a, which landed `interactive-setup` (`SetupCommand` split into the
`commands.admin.setup` package, `RunnerImageBuild` extracted from `ImageBuildSubcommand`, deployment-named
hints). Read-only. Helper scripts (throwaway, not committed): `metrics.py` (method length, cyclomatic
indicator, parameter counts), `deps.py` (baas-cli package import matrix), `loc.py` (code vs comment lines),
`dead.py` (unused / test-only public members), `table.py`. All metrics below are HEAD numbers.

## Summary

1. The codebase is mostly well-factored: interfaces all have a test seam, dead code is almost nil, and
   `config`/`console` are true leaves. One regression from the setup split: the baas-cli package graph,
   acyclic at dabbc55, now has one cycle — `commands.admin` ↔ `commands.admin.setup` through
   `RunnerImageBuild` (K13).
2. Complexity concentrates in `RunCommand` (593 code lines, Σcc 111, `execute()` 197 lines) and
   `ResultsQuerySubcommand` (`call()` cc 22). `SetupCommand` is no longer a hotspot: it is a 142-line
   picocli adapter over 21 small classes (1,369 code lines, Σcc 314, no method above 56 lines or cc 12).
   `ImageBuilderService` grew to the second-largest file (505 lines, Σcc 60) but stays cohesive.
3. The single biggest enabler is still error-exit duplication: 52 `return 1/2` early exits and 65
   `logger.error` calls in `commands/` (down from 61/69). The setup package now demonstrates the cure in
   value form — steps return `Outcome.Stop(exitCode, message)`, logged once by the walkthrough (K2).
4. Clear duplication worth removing: hand-built JSON in 5 commands with 3 disagreeing escapers (K3),
   three near-identical JMH runner services plus 227 lines of builders (K6/K7), and job-artifact keys /
   resource names composed by hand — now 10 sites, 3 of them new in setup (K8); plus two small duplicated
   rules inside the new setup package (K14).
5. Nothing here is Must. K10 is resolved by the split; K13 and K14 are new. The setup split is a
   workable model for K1 (port + context + returned outcome), at a deliberately smaller scale. Best
   payoff/effort: K8, K3, K5, K2, then K13.

## Index

| ID | Title | Status at 7d9660a | Necessity | Effort | Location |
|---|---|---|---|---|---|
| K1 | `RunCommand` is a god class; `execute()` is a 197-line launch script | Valid, relocated | Should | M | `commands/RunCommand.java:300` |
| K2 | Commands hand-roll "log error, return 1/2" — 52 early exits block method extraction | Changed | Should | S | `commands/**` |
| K3 | JSON payloads hand-built in 5 commands with 3 disagreeing escapers; job object duplicated | Valid, relocated | Should | S | `ResultsQuerySubcommand:397`, `JobsList:243`, `JobsShow:195`, `ConfigList:85`, `RunCommand:918` |
| K4 | `--format` validated by hand in 5 commands | Valid, relocated | Could | XS | 5 commands |
| K5 | `UserDataScriptBuilder.build` takes 16 positional parameters, 11 of them `String` | Valid, relocated | Should | S | `infra/UserDataScriptBuilder.java:293` |
| K6 | Three JMH runner services duplicate `executeCommand` and artifact upload — and have already diverged | Valid | Should | M | `benchmark-runner/services/Jmh*SubcommandService.java` |
| K7 | Four runner service builders (227 lines) build objects with only required fields | Valid | Could | S | `benchmark-runner/services/*Builder.java` |
| K8 | Job-artifact keys and resource names composed by hand, bypassing `JobLayout`/`DeploymentNames` | Changed | Should | XS | 10 sites |
| K9 | Command plumbing (`session()`, `console()`, `configService()`, deployer factory) copied into 7–10 commands | Changed | Could | S | `commands/**` |
| K11 | `JobSession.Outcome.exitCode` is a derivable field; terminal-read check repeated 5× | Valid | Could | XS | `jobs/JobSession.java:39, 210–274` |
| K12 | Mutable `static @Spec` in a runner mixin shared by three subcommands | Valid | Could | XS | `benchmark-runner/commands/ApiJmhOptions.java:21` |
| K13 | The setup split introduced the package graph's only cycle, and its AWS port leaks an SDK type | New | Could | XS | `commands/admin/RunnerImageBuild.java`, `setup/SetupAws.java:64` |
| K14 | Two rules duplicated inside the setup package (AccessDenied hand-over, rolled-back test) | New | Could | XS | `setup/StackStep.java:41, 74`, `setup/NamesStep.java:87` |

Resolved: K10 (see *Resolved by interactive-setup*). Withdrawn: none.

## Metrics

### Top 15 files by size (production code, all three modules)

"Code" = non-blank lines after stripping comments and string/text-block contents. Overall: 16,344
lines, 11,169 code (68 %) — up from 14,727 / 9,930, almost all of it the setup package; the rest is the
deliberate rationale prose — not counted as a smell here. Σcc = sum of a rough cyclomatic indicator
(1 + `if`/`for`/`while`/`case`/`catch`/`&&`/`||`/`?`) per method.

| # | File | Lines | Code | Methods | Σcc | max cc | vs dabbc55 |
|---|---|---|---|---|---|---|---|
| 1 | `baas/commands/RunCommand.java` | 938 | 593 | 36 | 111 | 14 | +11 lines |
| 2 | `baas/infra/ImageBuilderService.java` | 505 | 316 | 25 | 60 | 8 | was #4 (400 lines, Σcc 40) |
| 3 | `baas/commands/ResultsQuerySubcommand.java` | 497 | 370 | 28 | 94 | 22 | |
| 4 | `baas/infra/UserDataScriptBuilder.java` | 382 | 69 | 6 | 7 | 2 | |
| 5 | `baas/infra/CloudFormationService.java` | 347 | 235 | 22 | 49 | 7 | +36 lines |
| 6 | `baas/jobs/JobSession.java` | 300 | 193 | 19 | 63 | 16 | |
| 7 | `baas/commands/JobsListSubcommand.java` | 295 | 246 | 14 | 48 | 10 | |
| 8 | `baas/model/MeasurementItemMapper.java` | 282 | 218 | 18 | 46 | 11 | |
| 9 | `baas/commands/admin/TeardownCommand.java` | 280 | 188 | 15 | 31 | 7 | +19 lines |
| 10 | `baas/infra/OperatorSession.java` | 264 | 165 | 19 | 39 | 6 | |
| 11 | `baas/commands/JobsShowSubcommand.java` | 260 | 218 | 19 | 44 | 9 | |
| 12 | `baas/infra/RunnerImageRenderer.java` | 253 | 102 | 10 | 17 | 3 | |
| 13 | `baas/config/ConfigService.java` | 250 | 171 | 22 | 40 | 5 | |
| 14 | `baas/config/BaasConfig.java` | 243 | 134 | 39 | 54 | 4 | |
| 15 | `baas/results/ResultsQueryService.java` | 238 | 143 | 11 | 23 | 6 | new in top 15 |

Left the top 15: `commands/admin/SetupCommand.java` (was #2, 627 lines → 142). Just below:
`infra/DeployerPreflight.java` 233 / 157, Σcc 42.

The new `commands.admin.setup` package, as a whole: 21 files, 1,870 lines, 1,369 code, 104 methods,
Σcc 314. Largest files `AwsSetupAws` 218 / 188 (pure SDK mapping, max cc 4), `GitHubStep` 188 / 144
(Σcc 38), `SetupContext` 150 / 104. It is ~3.6× the old `SetupCommand`'s code, most of it new
behaviour (prompts, waits, OIDC provider creation, repeat command, image adoption), spread so that no
single file or method is a hotspot.

benchmark-runner's largest file is `commands/ApiJmhOptions.java` (203 lines, picocli option
declarations); baas-model's is `MeasurementItemMapper.java` (above). Neither module changed in the
rebase. `UserDataScriptBuilder`'s size is the bash text block (stripped to one line by the metric) — its
complexity is the parameter list (K5).

### Top 15 methods by length / branching

Record compact bodies (`RunnerImageParameters`, `EnvironmentManifest`, `RunnerImageDefinition`,
`PackagesDiff`, `ResultRow`, `DeploymentNames`, `console.Table`, the runner option records) were picked up
by the script as one "method" each and are excluded — they are several small methods each or data shapes.

| # | Method | Lines | cc | Params | Note |
|---|---|---|---|---|---|
| 1 | `RunCommand.execute` (`commands/RunCommand.java:300`) | 197 | 14 | 0 | K1, K2 |
| 2 | `ResultsQuerySubcommand.call` (`ResultsQuerySubcommand.java:150`) | 78 | **22** | 0 | 10 validation exits, K2/K4 |
| 3 | `JobsDownloadSubcommand.call` (`JobsDownloadSubcommand.java:69`) | 77 | 11 | 0 | |
| 4 | `TeardownCommand.removeDeployment` (`TeardownCommand.java:81`) | 73 | 7 | 2 | K8 |
| 5 | `JobsDiffSubcommand.call` (`JobsDiffSubcommand.java:80`) | 67 | 11 | 0 | K8 |
| 6 | `MeasurementItemMapper.toItem` (`baas-model/.../MeasurementItemMapper.java:71`) | 64 | 11 | 1 | flat attribute mapping — fine |
| 7 | `ImageShowSubcommand.call` (`ImageShowSubcommand.java:49`) | 63 | 5 | 0 | K9 |
| 8 | `RunnerImageBuild.run` (`commands/admin/RunnerImageBuild.java:36`) | 58 | 6 | 4 | new (from `ImageBuildSubcommand.call`); K13 |
| 9 | `JCStressSubcommandService.executeCommand` (runner, `:59`) | 58 | 4 | 0 | |
| 10 | `StackStep.run` (`commands/admin/setup/StackStep.java:28`) | 56 | 10 | 1 | new; K14 |
| 11 | `CloudFormationService.updateStackParameters` (`:113`) | 50 | 4 | 3 | |
| 12 | `ConfigSyncSubcommand.call` (`:66`) | 49 | 5 | 0 | |
| 13 | `JobsListSubcommand.call` (`:114`) | 48 | 10 | 0 | |
| 14 | `Ec2ProvisioningService.runInstance` (`infra/Ec2ProvisioningService.java:51`) | 48 | 1 | 8 | one flat request builder — fine |
| 15 | `JobSession.await` (`jobs/JobSession.java:210`) | 40 | **16** | 6 | K11 |

Left the list: `SetupCommand.call` (110 lines, cc 18 → 9 lines), `SetupCommand.deploy` (79 → split
across `NamesStep`/`StackStep`), `ImageBuildSubcommand.call` (104 → 44 lines, cc 11 → 5).

Other branching outliers: `ResultsTable.print` cc 14 / 37 lines; `ConfigSetSubcommand.call` cc 11 / 24
lines; `JobTermination.terminate` cc 10. In setup the most-branching methods are `ImageStep.run`,
`GitHubStep.decide` and `PolicyHandOver.handOver` (cc 12 each, 35–44 lines), then `SetupWalkthrough.run`
and `DeploymentStep.run` (cc 11).

Longest parameter lists: `UserDataScriptBuilder.build` **16**; `ResultRow` canonical ctor 11 (+ 10- and
9-arg overloads, both test-only); `JmhMeasurementMapper.toMeasurement` 9; `Ec2ProvisioningService.runInstance` 8;
`RunCommand.recordLaunchFailure` 7 (`:559`); `SetupContext` ctor 7 (`setup/SetupContext.java:95`, all
collaborators, built once in `SetupSteps.run`); `JobSession.await` 6; `RunCommand.report` 6; `SetupSteps.run` 6.
(Records such as `StoredMeasurement` (18), `JmhResult` (19) and `SetupOptions` (8) are data shapes, not
smells.) `SetupCommand.refusalForMissingRights` (6) is gone (K10).

### baas-cli package dependency matrix (import counts, row → column; same-package refs omitted)

| from \ to | (root) | commands | commands.admin | commands.admin.setup | config | console | infra | jobs | model | results | fan-out |
|---|---|---|---|---|---|---|---|---|---|---|---|
| (root) `BaasApp`… | | 5 | 1 | | 2 | | | | | | 3 |
| commands | 26 | | | | 19 | 22 | 30 | 8 | 7 | 13 | **7** |
| commands.admin | 11 | | | **4** | 9 | 5 | 27 | | | | 5 |
| commands.admin.setup | | | **1** | | 10 | 3 | 18 | | | | 4 |
| config | | | | | | | | | | | 0 |
| console | | | | | | | | | | | 0 |
| infra | | | | | 4 | | | 2 | 3 | | 3 |
| jobs | | | | | | | | | 14 | 1 | 2 |
| results | | | | | | 4 | | 1 | 7 | | 3 |

Fan-in (packages depending on it): config 5, console 4, model 4, infra 3, jobs 3, (root) 2,
commands.admin 2, results 2, commands 1, commands.admin.setup 1.
AWS SDK service packages imported: `infra` 8 (cloudformation, dynamodb, ec2, iam, imagebuilder, s3, ssm,
sts), `commands.admin.setup` 3 (cloudformation, dynamodb, iam), `jobs` 1, `results` 1, `commands` **0**,
`commands.admin` **0** (was 2).

Reading: the two leaf packages are still true leaves. The old layering leak — `SetupCommand.tableExists`
calling `DynamoDbClient` from a command — is gone: SDK use in setup now sits behind the `SetupAws` port,
in its adapter `AwsSetupAws` (dynamodb and iam exceptions), except `StackStatus`, which the port itself
exposes (K13). The one new edge pair, `commands.admin` → `setup` (4, `SetupCommand`) and `setup` →
`commands.admin` (1, `AwsSetupAws` → `RunnerImageBuild`), is the graph's only cycle (K13). The
`commands → (root)` 26 imports are still `BaasApp.configService/deployment` and `LoggingMixin` — K9's
plumbing.

## Findings

### K1 — `RunCommand` is a god class; `execute()` is a 197-line launch script
- **Status at 7d9660a:** Valid, relocated. The rebase added deployment-named hints, a `noImageMessage`
  helper and one more mutable summary field; nothing was extracted.
- **Necessity:** Should · **Effort:** M
- **Location:** `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java:300–496` (and the whole file)
- **Metric:** 938 lines / 593 code, 36 methods, Σcc 111 (highest in the repo); `execute()` 197 lines, 7 early
  `return`s, 6 separate `try (var x = factory.x())` client scopes, constructs 7 collaborators
  (`ImageBuilderService`, `S3UploadService` ×2, `RunnerJarResolver`, `UserDataScriptBuilder`,
  `Ec2ProvisioningService`, `DynamoDbJobRecorder` ×2, `JobSession`). The class also owns: option validation,
  project resolution, tag policy (`buildRunnerTags`), timing policy (`watchdogBound`), launch-failure
  reporting (writes `launch-error.txt`, `:559`), polling display, the outcome messages (`report`, `:616`),
  the shutdown hook, now **six** mutable `summary*` fields (`:222–229`; `summaryDeployment` was added only
  to carry the deployment name to `hints()`), the JSON summary and hints. CLAUDE.md itself records the
  consequence: "`RunCommand.call()`'s success path is executed by no JVM test".
- **Recommendation:** Keep `RunCommand` as the picocli adapter (options → a resolved `LaunchRequest`
  record; summary/hints/status line). Move steps 1–8 of `execute()` (JAR check, image + networking
  resolution, upload, user-data, reserve, launch, `recordLaunchFailure`) into a `jobs.JobLauncher` that
  returns a `JobSession`. The invariants stay byte-identical — one clock read, table resolved before
  upload, hook registered before `RunInstances` (the launcher takes a `Runnable beforeLaunch` or returns
  before launch so the command registers the hook). While there, drop the zero-value indirections:
  `resolveResultsTable` (`:806`, returns `config.resultsTable()`), `projectFromToplevel` (`:710`, test-only
  delegate to `GitProject`), `RESERVED_TAG_KEYS` (`:795`, alias of `TagKeys.MACHINE_OBSERVED`).

  **The setup split is a usable model — take three of its parts, not its step engine.**
  1. *The port.* `SetupAws` (`setup/SetupAws.java`) answers in setup's terms, `AwsSetupAws` is the only
     class that opens SDK clients, and `FakeSetupAws` (test) drives the whole walkthrough in-process. The
     launcher equivalent is a `LaunchAws` port — `runnerImage(prefix)`, `stackOutputs(stack)`,
     `upload(key, path)`, `seedRunnerJar(version)`, `reserve(job)`, `runInstance(spec)`,
     `recordLaunchError(job, text)` — over today's services. That is what closes CLAUDE.md's "success path
     executed by no JVM test": the full launch runs against a fake, as `SetupStepsTest` runs setup.
  2. *The context.* `SetupContext` is "the one mutable object"; `RunCommand`'s six `summary*` fields plus
     `session`/`detached` are the same thing scattered over the command, and the hook thread reads them. A
     `LaunchContext` (fields kept `volatile` where the hook reads them, as today) gives the hook, `hints()`
     and `printJobSummary` one object to read.
  3. *The returned outcome.* `SetupStep.Outcome.Stop(exitCode, message)` logged once in
     `SetupWalkthrough.run:34–39` is K2 realised without an exception; the launcher returning
     `Launched(session) | Refused(exit, message)` removes `execute()`'s seven `logger.error … return 1/2`
     exits the same way.

  What not to copy: setup's steps are check-then-act and re-runnable, hence `Wait`/`GoTo`, re-entry and a
  step list; a launch is not idempotent (reservation, `RunInstances` with a client token, one clock read),
  so a straight-line launcher with a fixed order — pinned by a test against the fake — is the right size.
  Setup's split went from 378 to 1,369 code lines (much of it new behaviour); `run` should aim at one port,
  one context and one launcher class, not eleven steps.
- **Payoff:** `RunCommand` to ~300 code lines; the launch sequence becomes testable against fakes,
  closing the uncovered success path CLAUDE.md names. Do K2 first (or adopt the returned-outcome form as
  part of this) — it removes the early-return tangle that makes this extraction awkward.

### K2 — Commands hand-roll "log error, return 1/2"
- **Status at 7d9660a:** Changed. Setup no longer contributes (its exits became `Outcome.Stop` values);
  the rest is unchanged, and the setup package now shows a second, value-based form of the fix.
- **Necessity:** Should · **Effort:** S
- **Location:** `baas-cli/.../commands/**`; examples `ResultsQuerySubcommand.java:150–228` (10
  validate-log-return blocks), `RunCommand.java:300–496` (7), `JobsDownloadSubcommand` (6),
  `ImageBuildSubcommand.java:88–111` (3, two of them `catch (IllegalStateException e) { logger.error(e.getMessage()); return 1; }`).
- **Metric:** 52 `return 1;`/`return 2;` (was 61) and 65 `logger.error` (was 69) in `commands/`, of which
  setup holds 2 and 8. 11 `catch (IllegalStateException|IllegalArgumentException)` blocks (was 12), 9
  outside setup. Per file: ResultsQuery 10, Run 8, JobsDownload 6, JobsList 5, JobsDiff 4, Teardown 4,
  JobsShow 3, ConfigSync 3, ImageBuild 3. These early exits are why `call()` methods cannot be split — a
  helper cannot "return 2 from the caller". In setup, by contrast, nine `new Outcome.Stop(exit, message)`
  sites return a value and `SetupWalkthrough.run:34–39` logs and exits once.
- **Recommendation:** Two forms now exist in the codebase; pick per command shape. For flat validators
  (`ResultsQuery`, `JobsList`, `ConfigSet`, `ImageBuild`): add one `CommandRefusal extends RuntimeException
  { int exitCode; }` (or reuse the pattern `DeploymentSelectionException` already has) and have
  `BaasApp.reportFailure` (`BaasApp.java:197`) log its message and return its exit code without the "-v for
  stack trace" hint — exactly the treatment `DeploymentSelectionException` gets today; validations become
  `require(cond, 2, "…")` one-liners and service-layer `IllegalStateException`s that are user-facing
  refusals are thrown as-is instead of caught per call site. For a multi-step flow (`run`, K1): return a
  sealed outcome as setup does. Keep the "Nothing was launched/changed" wording in the messages.
- **Payoff:** removes ~35 `if/log/return` triples; `ResultsQuerySubcommand.call` cc 22 → ~8; makes K1's
  extraction mechanical. Fails safe: an uncaught refusal still exits non-zero through the existing handler.

### K3 — JSON payloads are hand-built in five commands, with three escapers that disagree
- **Status at 7d9660a:** Valid, relocated (line shifts only).
- **Necessity:** Should · **Effort:** S
- **Location:** `ResultsQuerySubcommand.java:397–416` (`printJson`), `:452–478` (`jsonObject`, `jsonString`),
  `:481` (`jsonOrNull`); `JobsListSubcommand.java:243–257, 277`; `JobsShowSubcommand.java:195–208, 250`;
  `ConfigListSubcommand.java:85–99`; `RunCommand.java:918–933`.
- **Metric:** 5 `printf`-templated JSON objects, 3 copies of the `i < size() - 1 ? "," : ""` array loop,
  3 private `json(...)` wrappers plus 2 independent escapers. They disagree: `RunCommand.jsonString`
  (`:928`) escapes only `\` and `"` (no control characters); `ResultsQuerySubcommand.printJson` interpolates
  `jobId`, `benchmarkName`, `benchmarkType`, `mode`, `scoreUnit`, `createdAt` inside `"%s"` with no escaping
  at all, and `jsonOrNull` (`:481`) wraps `imageVersion`/`instanceType` unescaped.
  `JobsListSubcommand:249–254` and `JobsShowSubcommand:198–203` print the identical 10-field job object from
  two templates. Jackson databind is already on the CLI classpath (via `jackson-dataformat-yaml`) and
  already used in 5 classes (`EnvironmentManifest`, `ConfigService`, `DeployerPreflight`, …).
- **Recommendation:** One `console.Json` helper backed by a shared `ObjectMapper`: build `LinkedHashMap`s
  (or small records) and `writeValueAsString`. Keep the existing contract explicitly: non-finite → `null`,
  scores as `%.6f` (e.g. a `BigDecimal.valueOf(score).setScale(6, HALF_EVEN)` or a custom `double`
  serializer — Jackson's default shortest-repr would change the output), key-sorted tag maps (`TreeMap`),
  `Locale.ROOT` untouched since Jackson is locale-independent. Extract `jobJson(JobListing.Row)` once for
  list and show.
- **Payoff:** ~80 lines of string assembly removed, one escaping rule, and the unescaped-field class of bug
  closed (today latent: the unescaped values are machine-generated, but `benchmarkName` comes from user
  benchmark code).

### K4 — `--format` validated by hand in five commands
- **Status at 7d9660a:** Valid, relocated.
- **Necessity:** Could · **Effort:** XS
- **Location:** `ConfigListSubcommand.java:36, 52–57`, `ResultsQuerySubcommand.java:136–153`,
  `JobsShowSubcommand.java:75–117`, `JobsListSubcommand.java:47, 111–116`, `RunCommand.java:167, 303–304`.
- **Metric:** 5 `String format` options, each with its own `FORMATS` list / `equalsIgnoreCase` chain /
  "Unknown --format" message and exit 2; switch on `format.toLowerCase(Locale.ROOT)` repeated.
- **Recommendation:** Enum-typed options (`enum Format { TABLE, JSON, CSV }`, per-command subsets,
  `CommandLine.setCaseInsensitiveEnumValuesAllowed(true)` once in `BaasApp.commandLine`). picocli then
  rejects unknown values with exit 2 and lists `${COMPLETION-CANDIDATES}` in help. Keep `run`'s value name
  `text` (an enum constant `TEXT`) — renaming it would break CI scripts.
- **Payoff:** ~30 lines and five copies of one rule; `switch` over an enum is exhaustive.

### K5 — `UserDataScriptBuilder.build` takes 16 positional parameters
- **Status at 7d9660a:** Valid, relocated (caller moved).
- **Necessity:** Should · **Effort:** S
- **Location:** `baas-cli/.../infra/UserDataScriptBuilder.java:293–297`; sole production caller
  `RunCommand.java:428–433`.
- **Metric:** 16 parameters — 11 `String`s, including the runs `jobId, resultPath, createdAt,
  benchmarkJarS3Key` and `imageVersion, amiId, runnerJarS3Key, resultsTableName, jobSortKey`. Any adjacent
  swap compiles and produces a script that only fails on a paid instance (the class's own Javadoc names that
  as the risk it guards against for quoting). Most of the values are already in objects at the call site:
  `JobItem` (jobId, project → resultPath, createdAt, sortKey, tags — built two lines above, `:426`),
  `RunnerImage` (imageVersion, amiId), `RunCommand.Timings` (timeout, watchdog).
- **Recommendation:** `build(String region, String bucket, String resultsTable, JobItem job, RunnerImage
  image, Timings timings, String benchmarkType, String runnerJarKey, List<String> params)`, deriving
  `benchmarkJarKey` via `JobLayout.benchmarkJarKey(job.project(), job.jobId())`, `createdAt` from
  `job.createdAt()` and the tags from `job.tags()`. (`Timings` moves out of `RunCommand` to `infra` or
  `jobs`.) The `export(...)` lines and every invariant (`JOB_SORT_KEY` handed down verbatim, quoting) are
  unchanged.
- **Payoff:** removes a compile-invisible swap hazard on the most expensive-to-debug artifact; 5 test call
  sites get shorter.

### K6 — Three JMH runner services duplicate the run-and-store flow, and have already diverged
- **Status at 7d9660a:** Valid (benchmark-runner untouched by the rebase; lines unchanged).
- **Necessity:** Should · **Effort:** M
- **Location:** `benchmark-runner/.../services/JmhSubcommandService.java:34–67`,
  `JmhWithProfilerSubcommandService.java:48–83, 97–118`, `JmhWithAsyncProfilerSubcommandService.java:48–83, 99–114`.
- **Metric:** `executeCommand()` is ~35 lines in each, identical except for (a) extra `-prof` arguments,
  (b) the process-output file name (`jmh-output.txt` / `jmh-profiler-output.txt` /
  `jmh-with-async-output.txt`), (c) whether artifacts are uploaded; each file repeats `createDirectories`,
  the non-zero-exit block, `JobLogs.upload`, `JmhRunResults.uploadJsonAndMap` and `resultsStore.write` (the
  "non-zero code" / `JobLogs.upload` / `createDirectories` trio appears 3× per file).
  `uploadProfilerArtifacts` is duplicated between the two profiler services — and has drifted: the `-prof`
  variant skips a missing per-benchmark directory (`:100–106`, with a comment explaining the job failure it
  fixed), the async variant does not (`:101–113`), so a benchmark for which async-profiler writes no
  directory fails the job after the benchmark finished, before measurements are stored.
- **Recommendation:** One `JmhRun` (or keep `JmhSubcommandService`) parameterised by a small
  `JmhProfiling` strategy: `List<String> profilerArgs()`, `String outputFileName()`,
  `Optional<Function<JmhResult, Path>> localArtifactDir()`. The three subcommands pick the strategy; the
  artifact upload is written once with the `isDirectory` guard. Sequence it with `retire-mongodb` (C4) or
  right after, since that change touches the same constructors.
- **Payoff:** ~120 runner lines removed (≈ 4 % of the module), one place for the exit-code/upload ordering,
  and the guard divergence cannot recur.

### K7 — Four runner service builders build objects that have only required fields
- **Status at 7d9660a:** Valid (unchanged).
- **Necessity:** Could · **Effort:** S
- **Location:** `benchmark-runner/.../services/JmhSubcommandServiceBuilder.java`,
  `JmhWithProfilerSubcommandServiceBuilder.java`, `JmhWithAsyncProfilerSubcommandServiceBuilder.java`,
  `JCStressSubcommandServiceBuilder.java`.
- **Metric:** 227 lines for 4–5 fields per service, no defaults, no optional fields, and inconsistent null
  checks (only `storageService`/`resultsStore` are `requireNonNull`ed; `commonOptions`/`jmhOptions` are
  passed through unchecked). The services' constructors are package-private, so the builders exist only to
  make construction public. Used by 4 subcommands and 6 tests.
- **Recommendation:** Make the constructors public (with `requireNonNull` on every argument) and delete the
  builders; with K6 this collapses to two constructors. Keep `ResultsStoreBuilder` — it carries the real
  exactly-one-of-two store rule.
- **Payoff:** −227 lines of ceremony; a missing argument becomes a compile error instead of a runtime NPE.

### K8 — Job-artifact keys and resource names composed by hand outside `JobLayout`/`DeploymentNames`
- **Status at 7d9660a:** Changed. The two `SetupCommand` sites are gone with the class, but the split
  re-created the same concatenation three times in setup; every other site only moved.
- **Necessity:** Should · **Effort:** XS
- **Location:**
  - `baas-cli/.../commands/JobsDiffSubcommand.java:199` (`resultPath + "/environment.json"`), `:125–126`
    (`pathA + "/packages.txt"`, `pathB + …`)
  - `baas-cli/.../commands/JobsShowSubcommand.java:103` (`path + "/environment.json"`)
  - `baas-cli/.../commands/RunCommand.java:596` (`… + "/cloud-init-output.log"`)
  - `benchmark-runner/.../results/JmhRunResults.java:42, 46` (`"jmh-result.json"`, `"environment.json"`),
    `services/JCStressSubcommandService.java:112`
  - `baas-cli/.../commands/admin/setup/NamesStep.java:33, 61` (`context.deployment + "-results"`,
    `deployment + "-results"`), `setup/PlanStep.java:36` (`context.deployment + "-results"` in the plan
    text) — replacing `SetupCommand.java:317, 389`
  - `baas-cli/.../commands/admin/TeardownCommand.java:119` (`String bucket = resolvedStack;`)
- **Metric:** 10 hand-built names (was 9). CLAUDE.md: "Everything a job produces … built only by
  `JobLayout`/`JobId` … A hand-built prefix does not fail to compile; it points at nothing, and that
  presents as an empty download", and "only `DeploymentNames` composes them (C5)". `JobLayout` already has
  constants for `launch-error.txt`, `benchmark.jar`, `runner.jar` but none for `environment.json`,
  `packages.txt`, `cloud-init-output.log`, `jmh-result.json`; `DeploymentNames.resultsTable()`/`bucket()`
  already exist (`config/DeploymentNames.java:39, 41`) — and setup does use `DeploymentNames` elsewhere
  (`OperatorRoleStep.java:31`, `AwsSetupAws.java:177`), so the misses are oversights, not a missing
  dependency. Related, same shape: the per-deployment deployer-policy name `"BaasCliDeployerPolicy-" +
  deployment` (`setup/PolicyHandOver.java:82`) has no `DeploymentNames` entry.
- **Recommendation:** Add `JobLayout.ENVIRONMENT_NAME`/`PACKAGES_NAME`/`BOOT_LOG_NAME`/`RESULT_JSON_NAME`
  plus `…Key(resultPath)` helpers and use them on both sides (the CLI reads what the runner writes — the
  reason `baas-model` exists). Replace the three `-results` concatenations with
  `DeploymentNames.of(deployment).resultsTable()` and the teardown bucket with `config.bucket()`;
  optionally add `DeploymentNames.deployerPolicy()`. The user-data bash keeps its literals (it cannot call
  Java), but a test can pin them to the constants.
- **Payoff:** restores two documented single-builder invariants for ~20 minutes of work; a rename can no
  longer silently split reader from writer.

### K9 — Command plumbing copied into 7–10 commands
- **Status at 7d9660a:** Changed. The deployer-factory copies dropped from 4 to 3 (setup builds its
  factory once, behind `SetupAws`); the hint half of the recommendation was done another way (E2 closed by
  `Hints.command(deployment, …)`, threaded per call site); setup added a third `Console` variant.
- **Necessity:** Could · **Effort:** S
- **Location:** memoised `OperatorSession session()` in 7 commands (`JobsDownload:57`, `JobsDiff:54`,
  `JobsShow:60`, `JobsList:69`, `ResultsQuery:58`, `JobsTerminate:43`, `RunCommand:94` as
  `operatorSession()`); memoised `Console console()` in 8 (`JobsDiff:191`, `ConfigList:106`, `JobsShow:254`,
  `JobsList:289`, `ResultsQuery:313`, `JobsTerminate:99`, `Teardown:220`, `RunCommand:685`); private
  `configService()` → `BaasApp.configService(spec)` in 10 (`ConfigShow:26`, `ResultsQuery:142`, `Run:235`,
  `ImageBuild:73`, `JobsDiff:75`, `JobsDownload:64`, `ConfigSync:39`, `Teardown:52`, `ConfigSet:63`,
  `ImageShow:44`), plus inline in `SetupCommand:111`; deployer
  `new AwsClientFactory(region, AwsClientFactory.profile(config.getAws().getDeployerProfile()))` in 3 admin
  commands (`ImageBuild:104`, `Teardown:82`, `ImageShow:54`) — setup's equivalent is
  `AwsSetupAws:40`, behind its port.
- **Metric:** ~25 near-identical 4–6-line members; these account for most of the 26 `commands → (root)`
  imports in the matrix. Console construction now comes in three forms: memoised `console()` (8),
  un-memoised `Console.of(...)` (`ImageShowSubcommand:56`), and an injectable
  `Function<PrintWriter, Console> consoleFor` (`SetupCommand:102`). The deployment name for hints is
  likewise fetched per command: `RunCommand` gained a sixth mutable field (`summaryDeployment`, `:224`) solely
  to pass it to `hints()`.
- **Recommendation:** One picocli `@Mixin CommandContext` holding `@Spec(MIXEE)`, with `console()`,
  `configs()`, `operator()` (memoised `OperatorSession`), `deployerFactory(BaasConfig)` and
  `hint(String rest)` (= `Hints.command(deployment, rest)`). Tests keep their seam by assigning
  `command.context.console = …` (today they assign `command.console`, or `SetupCommand.consoleFor`).
- **Payoff:** ~120 lines; a new command cannot get the plumbing subtly different (e.g.
  `ImageShowSubcommand:56` builds an un-memoised `Console` while every other command memoises).

### K11 — `JobSession.Outcome.exitCode` is derivable; the terminal-read check is repeated
- **Status at 7d9660a:** Valid (`jobs/` untouched; lines unchanged).
- **Necessity:** Could · **Effort:** XS
- **Location:** `baas-cli/.../jobs/JobSession.java:39` (`record Outcome(String status, int exitCode)`),
  `:224, 245, 263, 274` (`JobStatus.COMPLETED.equals(x) ? 0 : 1` ×4); `:128, 219, 228, 237` and
  `JobStop.java:46` (`x.isPresent() && x.get().isTerminal()` ×5).
- **Metric:** `await` is cc 16 with 6 parameters; a quarter of its branches are these repeats.
- **Recommendation:** Drop the `exitCode` component and make `exitCode()` a method returning
  `completed() ? 0 : 1` (it can never disagree with `status` then). Add `Optional<JobItem> readTerminal()`
  = `readQuietly().filter(JobItem::isTerminal)` so each check is `readTerminal().map(this::finish)`.
- **Payoff:** removes a state-consistency hazard and ~10 lines from the most-branching method in `jobs`.

### K12 — Mutable `static @Spec` in a runner mixin shared by three subcommands
- **Status at 7d9660a:** Valid (unchanged).
- **Necessity:** Could · **Effort:** XS
- **Location:** `benchmark-runner/.../commands/ApiJmhOptions.java:20–31`
- **Metric:** the only mutable static field in production code across all three modules. `ApiJmhOptions`
  is a `@Mixin` in `jmh`, `jmh-with-prof` and `jmh-with-async`; a static `@Spec` is overwritten by each
  instantiation, so `setBenchmarkPath`'s `ParameterException` is raised against whichever command was
  injected last (wrong usage text on error).
- **Recommendation:** `@Spec(Spec.Target.MIXEE) CommandSpec spec;` as an instance field.
- **Payoff:** one line; removes global state.

### K13 — The setup split introduced the package graph's only cycle, and its AWS port leaks an SDK type
- **Status at 7d9660a:** New.
- **Necessity:** Could · **Effort:** XS
- **Location:** `baas-cli/.../commands/admin/setup/AwsSetupAws.java:3, 205` →
  `commands/admin/RunnerImageBuild.java:23, 56` → `commands/admin/ImageBuildSubcommand.java:51`
  (`olderBaseRefusal`), closing on `commands/admin/SetupCommand.java:11–14` → `setup`. Port:
  `setup/SetupAws.java:12–17, 64, 67`; `setup/SetupContext.java:6, 69`, `NamesStep.java:6`,
  `StackStep.java:7`.
- **Metric:** `commands.admin` → `commands.admin.setup` 4 imports, `commands.admin.setup` →
  `commands.admin` 1: the only cycle in baas-cli (acyclic at dabbc55). `RunnerImageBuild` is not a command —
  a static service over `AwsClientFactory` and seven `infra` types — but lives in the command package and
  calls back into a picocli command's static (`ImageBuildSubcommand.olderBaseRefusal`). Separately, the
  `SetupAws` Javadoc says the port answers "in terms setup reasons about rather than SDK types", yet
  `stackStatus`/`awaitSettled` return `Optional<software.amazon.awssdk…StackStatus>`, so five main classes
  (the port, its adapter, `SetupContext`, `NamesStep`, `StackStep`) and three test classes
  (`FakeSetupAws`, `SetupStepsTest`, `RepeatCommandTest`) import the CloudFormation SDK.
- **Recommendation:** Move `RunnerImageBuild` together with `olderBaseRefusal` to `infra` (beside
  `ImageBuilderService`, whose `publish`/`adoptOrBuild` it orchestrates); `ImageBuildSubcommand` and
  `AwsSetupAws` both call it from there, and the cycle disappears. For the port, a three-value setup enum
  (`Absent`, `RolledBack`, `Exists`) — the only distinctions any step makes — keeps the SDK inside
  `AwsSetupAws`.
- **Payoff:** restores the acyclic graph the original review recorded; setup steps become SDK-free, so the
  "commands import no SDK" property holds for the whole `commands` tree except the one adapter.

### K14 — Two rules duplicated inside the setup package
- **Status at 7d9660a:** New.
- **Necessity:** Could · **Effort:** XS
- **Location:** AccessDenied → policy hand-over: `setup/SetupWalkthrough.java:64–73` and
  `setup/StackStep.java:74–79`. Rolled-back test: `setup/SetupContext.java:141–142` (`creating()`),
  `setup/StackStep.java:41`, `setup/NamesStep.java:87–89` (`rolledBack`).
- **Metric:** `StackStep.run` catches `RuntimeException`, tests `DeployerPreflight.isAccessDenied`, logs the
  identical "This identity is missing a permission…" message and returns `permissionFailure` — exactly what
  `SetupWalkthrough.outcome` does for every step once `context.policy` is set, which it always is by the
  stack step (`PermissionsStep.run:24` renders it before anything can stop). The `ROLLBACK_COMPLETE`
  comparison is written three times, and `NamesStep.rolledBack` has no caller in main or test code.
- **Recommendation:** Delete `StackStep`'s generic `RuntimeException` branch (keep the
  `StackOperationException` one, which carries CloudFormation's own permission verdict), delete
  `NamesStep.rolledBack`, and give `StackStep` the same predicate `creating()` uses (a static
  `rolledBack(Optional<StackStatus>)`, or K13's enum).
- **Payoff:** one place decides when a refusal becomes the hand-over, so a change to that message or rule
  cannot apply to one step and not the others; ~10 lines.

## Resolved by interactive-setup (7d9660a)

- **K10 — `SetupCommand` threads a five-value policy context through three methods.** Resolved:
  `refusalForMissingRights` and `handOverPolicy` are gone; the policy, caller, region and deployment are
  `SetupContext` fields (`setup/SetupContext.java:50–66`) and the hand-over is
  `PolicyHandOver.handOver(SetupContext)` (`setup/PolicyHandOver.java:27`), called with one argument from
  `PermissionsStep:43` and `StackStep:90`; `SetupCommand.call` is 9 lines.

## Withdrawn

None.

## Looked at and not filed

- Package layering in baas-cli — leaves are leaves; the one cycle and the SDK type on the setup port are
  K13. The dabbc55 leak (`SetupCommand.tableExists` on `DynamoDbClient`) is gone: setup's SDK use now sits
  in the `AwsSetupAws` adapter.
- Interfaces (`JobRecorder`, `JobSession.Instances`, `StorageService`, `ResultsStore`, `OperatorSession.Aws`,
  `JobsShowSubcommand.Sources`, `SetupAws`, `SetupStep`) — each has a test fake or a second implementation
  (`FakeSetupAws`, eleven steps); not needless.
- The setup package's size (1,369 code lines over 21 files) — most is new behaviour, and per-file and
  per-method numbers are all modest (largest method `StackStep.run`, 56 lines / cc 10). Not a hotspot.
- `ImageBuilderService` (now 505 lines, 25 methods; +113 for running-build adoption), `CloudFormationService`,
  `OperatorSession` — large but cohesive (one AWS service / one concern each).
- `MeasurementItemMapper.toItem` (cc 11) and the runner option records (11–15 params) — flat mapping and
  picocli shapes; splitting would add indirection.
- Dead code — `dead.py` found only framework callbacks (picocli setters, `IVersionProvider.getVersion`,
  Jackson `handleUnknownProperty`, `FileVisitor` overrides, Morphia getters). Test-only production members
  are minor: `ResultRow`'s 10- and 9-arg constructors (`ResultRow.java:35, 46`), `DeploymentNames.runnerRole`,
  `StoredMeasurement.withBenchmarkClass`; Mongo-side items are covered by C4. New: `NamesStep.rolledBack`
  has no caller at all (K14).
- The two `LoggingMixin`s (cli 79 lines, runner 28) differ for the documented reason (argv pre-scan); not
  duplication.
- Comment density (32 % of lines) — deliberate project style carrying invariants; not a complexity finding.
