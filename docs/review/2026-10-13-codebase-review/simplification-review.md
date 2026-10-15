# Simplification review — `slice-architecture` (after ADR 0009 and the implementation review)

**Disposition (2026-10-15, applied on `slice-architecture`).**
- **Applied:** Z1, Z2, Z3, Z5, Z7, Z10 (only where the POM held real duplication), Z11; Z6 in part —
  the nine one-line `configService()` wrappers are gone and `ImageBuildSubcommand` uses
  `DeployerSession.factory(config)`. The lazy `console()`/`session()` getters stay: they are the test
  seams, and a shared helper would shorten each by one expression.
- **Not applied:**
  - Z4: its cycle evidence predates the `options` move; after it, `infra`, `process` and `results`
    import only the leaf `options`, so there is no cycle left to close.
  - Z8: ADR 0005 declined the same template method as A1.
  - Z9: user-visible (a pre-job-item job would answer "No job found"); it stays a finding.
  - Z12: a decision, queued as `retire-mongodb`.
- Z3 keeps each runner command's evaluation order (the request options, which can refuse a run
  without a project, before any client is built), and `StorageService.of` keeps the
  `StorageServiceBuilder` logger name, so the runner's log lines are unchanged.


**Question.** Now that the slices are in place, can the code or its dependencies be made simpler?

**Scope.** This is read-only. Measurements come from small scripts in the session scratchpad (`simplify/deps.py`,
`classes.py`, `dead.py`). They count import edges plus same-package simple-name references, with comments and
strings stripped. They are heuristics, so every finding below was confirmed with `grep`.

The rule applied throughout is "keep it simple — prefer the design whose edge cases fail safe; no extra mechanism;
trim rather than add". Nothing here removes a CLAUDE.md invariant. Proposals that would change user-facing output
or an option are marked.

**Verdict.** The layout is already lean. The CLI has no unused dependency, no dead configuration key and no
interface without a reason.

Most of what is left is in **`benchmark-runner`**, which the refactor touched least:
- five single-use builders;
- three near-identical JMH services;
- the package cycle I3 found, which three file moves close without new mechanism.

On the CLI side, what is left is small:
- one dead method;
- one use case that reaches back into its own command;
- a one-class SSM wrapper;
- the repeated lazy `console()`/`session()`/`configService()` plumbing.

The largest dependency simplification by far is retiring MongoDB. CLAUDE.md already calls that an open decision
that needs its own change, so it is listed (Z12) but not recommended here.

---

## Measured dependencies (main code)

| Module | Classes | Class edges | Package edges | Package cycles |
|---|---|---|---|---|
| baas-cli | 102 | 422 | 42 | 0 |
| baas-model | 16 | 17 | 1 | 0 |
| benchmark-runner | 53 | 172 | 28 | 1 (services ↔ infra/process/results through `options`) |

### baas-cli packages

| Package | Classes | Fan-out | Fan-in | Imports |
|---|---|---|---|---|
| `baas` (root) | 2 | 7 | 0 | cli, config, deployment, image, jobs, results, session |
| `cli` | 4 | 1 | 6 | session |
| `console` | 8 | 0 | 6 | — |
| `session` | 9 | 1 | 8 | aws |
| `aws` | 6 | 0 | 6 | — |
| `jobs` | 23 | 8 | 2 | aws, cli, console, image, model, model.dynamodb, results, session |
| `results` | 7 | 5 | 2 | cli, console, model, model.dynamodb, session |
| `deployment` | 5 | 7 | 2 | aws, cli, console, deployment.setup, image, jobs, session |
| `deployment.setup` | 21 | 5 | 1 | aws, console, deployment, image, session |
| `image` | 12 | 4 | 4 | aws, cli, console, session |
| `config` | 5 | 4 | 1 | aws, cli, console, session |

The kernel (`cli`, `console`, `session`, `aws`) imports no slice. The slice edges are exactly the ADR's
deployment → jobs → {image, results}. Nothing here needs to move.

### benchmark-runner packages

| Package | Classes | Fan-out | Fan-in | Imports |
|---|---|---|---|---|
| root | 2 | 0 | 4 | — |
| `commands` | 9 | 5 | 0 | model, infra, options, root, services |
| `services` | 10 | 8 | 1 | model, entities.*, infra, options, root, process, results |
| `infra` | 12 | 7 | 3 | model, model.dynamodb, entities, entities.*, **options**, root |
| `process` | 2 | 2 | 1 | **options**, root |
| `results` | 3 | 5 | 1 | model, entities.*, infra, **options** |
| `options` | 10 | 0 | 5 | — |
| `entities`, `entities.jcstress`, `entities.jmh` | 1 / 2 / 2 | 1 / 0 / 0 | 1 / 3 / 3 | model / — / — |

The three bold `options` edges come from one class each:
- `infra/StorageServiceBuilder.java:5`
- `process/JmhBenchmarkProcessBuilderFactory.java:3`
- `results/JmhRunResults.java:10`

Each of those classes is called only from `services` or `commands`.

### Maven

- **baas-cli.** Every declared dependency is imported in main code:
  - SDK imports: cloudformation 17, dynamodb 12, ec2 9, iam 4, imagebuilder 6, s3 18, ssm 8, sts 3.
  - Also used: jackson (databind through dataformat-yaml), picocli and slf4j.
  - No MongoDB dependency, direct or transitive (the enforcer in `baas-model/pom.xml:35-57` guards that).
- **baas-model.** Only `dynamodb`, used by `model.dynamodb` alone (2 imports). Both consumers also use it directly.
- **benchmark-runner.**
  - All main dependencies are used: jsoup, gson (`ResultLoaderService` only), s3, dynamodb, mongodb and morphia (the Mongo path), picocli, slf4j.
  - All test dependencies are used: org.json in 6 ITs, json-unit, testcontainers-mongodb, localstack.
- **Leftovers.** `benchmark-runner/pom.xml:15-16` is an empty `<properties>`. `assertj`, `json` and `json-unit` carry
  `<scope>test</scope>` in the root `dependencyManagement` (`pom.xml:81,123,128`) and again in each module.

---

## Proposals, ranked by payoff / risk

| ID | Change | Payoff | Risk | Effort | User-visible? |
|---|---|---|---|---|---|
| Z1 | Delete dead `NamesStep.rolledBack`, or have `StackStep` call it | −4 lines; one spelling of "rolled back" | None | XS | No |
| Z2 | Move `ImageBuildSubcommand.olderBaseRefusal` into `RunnerImageBuild` | Removes the use case → command back-edge; setup stops loading a picocli command class | None | XS | No |
| Z3 | Runner: replace the four `*ServiceBuilder`s with constructors and `StorageServiceBuilder` with a static `StorageService.of(bucket, endpoint)` | −5 classes, ≈−230 LOC, `infra`→`options` edge gone | Low | S | No |
| Z4 | Runner: move `JmhBenchmarkProcessBuilderFactory` and `JmhRunResults` into `services`; fold `JmhUtils` into it | Runner package graph becomes acyclic (closes I3's remainder with no new type); −1 class | Low | S | No |
| Z5 | Inline `SsmService` (56 LOC) into `ImageBuilderService`, its only caller | −1 class, ≈−30 LOC | Low | XS | No |
| Z6 | Collapse command plumbing: `Invocation.console(spec)` and `Invocation.operatorSession(spec)`; drop the nine one-line `configService()` wrappers; `ImageBuildSubcommand` uses `DeployerSession.factory(config)` | ≈−70 LOC over 15 command classes, one spelling of each | Low | S | No |
| Z7 | Delete test-only `BenchmarkType.fromCommandName` | −1 public contract method in the three-runtime model | None | XS | No |
| Z8 | Runner: one JMH flow shared by `JmhSubcommandService`, `JmhWithProfilerSubcommandService` and `JmhWithAsyncProfilerSubcommandService` | ≈−120 LOC; one place for the run → save output → upload logs → exit check → map → store order | Medium (must keep the profiler/async difference on a missing artifact directory) | M | No |
| Z9 | Drop `JobPaths`' measurement fallback for jobs recorded before job items | −`ResultsQueryService.resultPathFromMeasurements` (15 LOC), `JobPaths` becomes a one-liner over `JobRecorder.find`, −1 `jobs`→`results` class edge | Medium: needs confirming that no deployment holds pre-2026-10-04 measurements | S | **Yes, narrowly**: such a job would answer "No job found" |
| Z10 | POM trims: empty runner `<properties>`, duplicated `test` scopes | ≈−10 lines of XML | None | XS | No |
| Z11 | Narrow visibility of members used only inside their package or by tests (`DeployerPreflight.simulate`, `RunnerImageRenderer.javaFloor`) | Smaller public surface | None | XS | No |
| Z12 | Retire MongoDB from `benchmark-runner` (own OpenSpec change; CLAUDE.md "Accepted risks → MongoDB") | Removes 3 main dependencies (driver, morphia, the enforcer rule's reason), testcontainers-mongodb, `entities` + `MongoMeasurementDocument`, `MongoResultsStore`, the exactly-one-of rule in `ResultsStoreBuilder`, frees `-m` | Medium; a decision, not a refactor | M | **Yes**: removes the runner's `--mongo-connection-string` |

### Z1 — dead `NamesStep.rolledBack`

- **Evidence:**
  - `deployment/setup/NamesStep.java:88-90` defines `static boolean rolledBack(SetupContext)`, and nothing in main or test calls it (`grep -rn rolledBack baas-cli/src`).
  - `StackStep.java:42` re-derives the same predicate inline: `status.isPresent() && status.get() == StackStatus.ROLLBACK_COMPLETE`.
- **Change:** Delete the method. The alternative is to have `StackStep` read `context.stackStatus` through it, but the method reads the context while `StackStep` reads a fresh status, so deleting is the honest fix.

### Z2 — use case reaching back into its command

- **Evidence:** `image/RunnerImageBuild.java:52` calls `ImageBuildSubcommand.olderBaseRefusal(...)`, which is defined at `image/ImageBuildSubcommand.java:43-52`.
- **Why it matters:**
  - `RunnerImageBuild` is also the use case setup runs (`AwsSetupAws`), so `deployment.setup` reaches a picocli command class through it.
  - ADR 0009 §4's "commands parse and print; use cases …" runs the other way.
- **Change:** Move the static into `RunnerImageBuild` (or `RunnerImageParameters`, next to `compareVersions`). Repoint `ImageCommandsTest.java:86-93`. The text does not change.

### Z3 — runner builders that build nothing

- **Evidence:**
  - Builders: `services/JmhSubcommandServiceBuilder.java` (48), `JCStressSubcommandServiceBuilder.java` (56), `JmhWithProfilerSubcommandServiceBuilder.java` (61), `JmhWithAsyncProfilerSubcommandServiceBuilder.java` (60), `infra/StorageServiceBuilder.java` (42).
  - Each has exactly one main caller, its subcommand (for example `commands/JmhSubcommand.java:22-30`), plus ITs.
  - Each `build()` only `requireNonNull`s two fields and calls the package-private constructor.
- **Change:**
  - Make the service constructors public and keep the two `requireNonNull` checks inside them.
  - Turn `StorageServiceBuilder.build()` into `StorageService.of(String bucket, URI endpoint)`. The local-versus-S3 choice and its two log lines stay as they are, and the "no `--s3-bucket` → `LocalStorageService`" behaviour CLAUDE.md documents is kept.
  - Update the 6 test files that call the builders.
- **Payoff:**
  - −5 classes and ≈−230 LOC.
  - The `infra → options` edge goes away (`S3Options` is no longer passed down).
- **Risk:** Low. It is mechanical, and the ITs cover every service.

### Z4 — close the runner's last cycle by moving two classes

- **Evidence:**
  - I3: `services` imports `infra`, `process` and `results`, and each imports back into `options`.
  - Z3 removes the `infra` back-edge.
  - The other two back-edges belong to classes whose callers all live in `services`:
    - `JmhBenchmarkProcessBuilderFactory` (callers `JmhSubcommandService`, `JmhWithProfilerSubcommandService`, `JmhWithAsyncProfilerSubcommandService`);
    - `JmhRunResults` (the same three).
  - `JmhUtils` (14 LOC, one static) is used only by the two profiler services.
- **Change:**
  - Move both classes into `services`. `process` keeps the generic `BenchmarkProcessBuilder`, and `results` keeps the two pure mappers.
  - Inline `getProfilerOutputDirSuffix` beside its callers, or into the shared flow of Z8.
  - Then add the missing rule to `RunnerLayeringTest`: `infra`, `process` and `results` import neither `services` nor `options`.
- **Payoff:**
  - Package edges 28 → 25, cycles 1 → 0.
  - The ADR's "acyclic" claim becomes true without the I3 wording change.

### Z5 — `SsmService`

- **Evidence:**
  - `image/SsmService.java` (56 LOC) has one caller, `image/ImageBuilderService.java:421,499,503`, which already holds the `SsmClient` (`:49`).
  - No test references it; `FakeSsm` fakes the client.
  - `getParameter` is public, but its only caller is `getParameterOptional`.
- **Change:** Move the three calls into `ImageBuilderService` as private methods.
- **Payoff:** −1 class, ≈−30 LOC.
- **Risk:** `withDecryption(true)` on a plain `String` parameter is a no-op. Keep it or drop it; either way the behaviour is the same.

### Z6 — the repeated command plumbing

- **Evidence:**
  - **Lazy console:** 8 copies of the `if (console == null) console = Console.of(spec.commandLine().getOut())` getter.
    - `ConfigListSubcommand:107`, `ResultsQuerySubcommand:311`, `TeardownCommand:221`, `JobsTerminateSubcommand:95`, `JobsDiffSubcommand:189`, `RunCommand:349`, `JobsShowSubcommand:251`, `JobsListSubcommand:289`.
    - Two more construct one inline: `ConfigShowSubcommand:41`, `ImageShowSubcommand:52`.
  - **Lazy operator session:** 6 copies of `session = OperatorSession.of(Invocation.configService(spec))`.
    - `ResultsQuerySubcommand:56`, `JobsTerminateSubcommand:39`, `JobsDiffSubcommand:52`, `JobsShowSubcommand:57`, `JobsListSubcommand:69`, `JobsDownloadSubcommand:55`, plus `RunCommand:81-83`.
  - **One-line `configService()` wrappers** around `Invocation.configService(spec)`: 9 copies.
    - `ConfigShowSubcommand:26`, `ConfigSyncSubcommand:39`, `ConfigSetSubcommand:63`, `ImageShowSubcommand:40`, `ResultsQuerySubcommand:140`, `TeardownCommand:53`, `ImageBuildSubcommand:65`, `JobsDiffSubcommand:73`, `RunCommand:188`, `JobsDownloadSubcommand:62`.
  - **Deployer factory:** `ImageBuildSubcommand.java:96` hand-builds `DeployerSession.factory(region, config.getAws().getDeployerProfile())`. That is exactly `DeployerSession.factory(config)` (`session/DeployerSession.java:17-19`), because `region` is `config.getAws().resolveRegion()` (`:73`).
- **Change:**
  - Add `Invocation.console(CommandSpec)` and `Invocation.operatorSession(CommandSpec)`. Each command keeps its package-private field as the test seam: `console != null ? console : (console = Invocation.console(spec))`.
  - Replace each wrapper with a direct call, or keep a local variable where a command calls it more than once (teardown calls it three times).
  - Use `factory(config)` in `ImageBuildSubcommand`.
- **No base class.** An `abstract OperatorCommand` would be inheritance: more mechanism than the duplication it removes.
- **Why the fields stay:** the console must still be cached per command. It owns the `StatusLine` and decides interactivity once (CLAUDE.md "Terminal effects…"). Only the getter bodies shrink.

### Z7 — test-only model API

- **Evidence:** `baas-model/.../BenchmarkType.java:47-49`, `fromCommandName`. Its only caller is `BenchmarkTypeTest.java:24-25`. Validation reads `commandNames()`.
- **Why it matters:** the model is the three-runtime contract (ADR 0009 §1), so every public member there is something the next reader must keep stable.
- **Change:** Delete the method and its two test lines.
- **Not recommended:** `StoredMeasurement.withTags`/`withBenchmarkClass` (`StoredMeasurement.java:69,75`) are also test-only, but they exercise the record's defensive copy and null check, so deleting them is not recommended.

### Z8 — one JMH flow in the runner

- **Evidence:**
  - `services/JmhSubcommandService.java:36-67`, `JmhWithProfilerSubcommandService.java:49-80` and `JmhWithAsyncProfilerSubcommandService.java:47-82` repeat the same sequence word for word:
    1. `createDirectories`
    2. start the process and wait
    3. `saveFile(processOutputName)`
    4. `JobLogs.upload`
    5. the exit-code check and its two log lines
    6. `uploadJsonAndMap`
    7. `resultsStore.write`
  - The three differ only in:
    - the `-prof` argument(s);
    - the `BenchmarkType` that names the output;
    - the artifact upload.
  - The two `uploadProfilerArtifacts` methods differ in one way:
    - the profiler variant skips a missing directory (`JmhWithProfilerSubcommandService.java:98-104`);
    - the async variant lets `Files.list` throw (`JmhWithAsyncProfilerSubcommandService.java:98-112`).
- **Change:** One `JmhRun.execute(type, jmhOptions, extraProfArgs, artifacts)` holding the skeleton. Each service becomes ~20 lines of its own difference, or the three services become one service plus two small artifact functions.
- **Risk:** Medium.
  - The missing-directory difference must survive as an explicit flag, because an async run with no flame-graph directory currently fails the job. Unifying it silently would change that.
  - Log texts must stay byte-identical, because the E2E job and readers grep `cloud-init-output.log`.
- **Payoff:** ≈−120 LOC. The fixed upload-before-store order then lives in one place, the same ordering argument `JmhRunResults`' javadoc makes.

### Z9 — the pre-job-item fallback

- **Evidence:**
  - `jobs/JobPaths.java:27-30` falls back to `ResultsQueryService.resultPathFromMeasurements` (`results/ResultsQueryService.java:190-204`) "for a job recorded before job items existed".
  - Job items arrived in `run-status-in-dynamodb` (archived 2026-10-04).
  - `JobReference.java` javadoc records that the deployments were rebuilt and so no pre-unified jobs exist. The rebuild was `derived-deployment-config`, 2026-10-13, after job items.
  - Since then every `baas run` writes the reservation before `RunInstances` (CLAUDE.md, *Job items*), so every job in a live deployment has a job item.
- **Change:** `JobPaths` becomes `recorder.find(jobId).map(JobItem::resultPath)`. It can then fold into `JobRecorder` callers, and the `ResultsQueryServiceIT` cases at `:314-326` that seed a measurement-only job go.
- **User-visible:** only for a job with measurements but no job item. Today that is none, if the rebuild claim holds for every deployment, including `baas-381492019823`. **Confirm before doing it.**

### Z10 — POM trims

- `benchmark-runner/pom.xml:15-16`: delete the empty `<properties>`.
- Root `dependencyManagement` declares `<scope>test</scope>` for assertj, json and json-unit (`pom.xml:81,123,128`). Each module repeats it. Keep the module copy, which is where Maven readers look, and drop the managed scope. Alternatively drop the module copies; either way the scope is declared once.

### Z11 — visibility

- `DeployerPreflight.simulate` (`deployment/DeployerPreflight.java:60`) is public, and its only callers are `check()` and tests.
- `RunnerImageRenderer.javaFloor()` is public, and its only caller is `RunnerImageRendererTest`.
- Make both package-private.

### Z12 — MongoDB (decision, not recommendation)

- **What it would remove:**
  - `mongodb-driver-sync` and `morphia-core` (`benchmark-runner/pom.xml:36-43`, root `:89-98`);
  - `testcontainers-mongodb`;
  - `entities/MongoMeasurementDocument`, `infra/MongoResultsStore`;
  - the Mongo half of the store contract suite;
  - `ResultsStoreBuilder`'s both-or-neither rule;
  - the Mongo-ban enforcer's reason;
  - the CLAUDE.md gotchas about `-m` and the database-name requirement.
- **Why it is not recommended here:** CLAUDE.md says no live user is known and that retirement "deserv[es] its own change and spec delta". It removes a runner option (user-visible), so it is out of scope for a behaviour-preserving pass.

---

## Before and after the top proposals

| Measure | Now | After Z1–Z7 | After Z1–Z9 |
|---|---|---|---|
| baas-cli classes | 102 | 101 (−`SsmService`) | 101 |
| baas-cli package edges | 42 | 42 | 42 (the `jobs`→`results` edge stays: `JobLaunch`/`AwsRunAws` print `ResultRow`s) |
| baas-cli use case → command edges | 1 (`RunnerImageBuild`→`ImageBuildSubcommand`) | 0 | 0 |
| baas-cli plumbing copies (console / session / configService) | 8 / 7 / 9 | 0 / 0 / 0 bodies (fields kept as seams) | same |
| benchmark-runner classes | 53 | 47 (−5 builders, −`JmhUtils`) | 47 (services slimmer, not fewer, if Z8 keeps three entry points) |
| benchmark-runner package edges | 28 | 25 | 25 |
| benchmark-runner package cycles | 1 | 0 | 0 |
| benchmark-runner main LOC | 3085 | ≈2830 | ≈2710 |

---

## Looks simplifiable — must stay

| Thing | Why it stays |
|---|---|
| `cli.Root`, an interface with one implementation (`BaasApp`) | It is what keeps the kernel from importing the composition root. The old root ↔ commands cycle ran through a static locator (ADR 0009 §2, §5). |
| `RunAws`, `SetupAws`, `JobRecorder`, `JobSession.Instances`, `JobsShowSubcommand.Sources`, `OperatorSession.Aws`: one production implementation each | They are the test seams ADR 0009 §4 exists for. `baas run`'s success path is JVM-tested only because of `RunAws`. |
| Teardown/image commands building clients inline | ADR 0009 Consequences: a port arrives only when a sequence test needs it. Adding one now is mechanism without a consumer. |
| Two `LoggingMixin`s (CLI and runner) | Different modules, and the model must not gain picocli. The CLI one carries the `-v` argv pre-scan (CLAUDE.md "`baas -v` needs the argv pre-scan"). |
| `DeployerSession` (25 LOC) | It is the one place deployer credentials are resolved, the counterpart to `OperatorSession`. Inlining it re-creates the four hand-built factories it replaced. |
| Noun group commands (`JobsCommand`, `ConfigCommand`, `DeploymentCommand`, `AdminImageCommand`, `ResultsCommand`, `AdminCommand`) | The command-tree invariant: a noun alone prints usage, and only `run`/`query` are top-level aliases. |
| The cached `console` field per command | `Console` owns the `StatusLine` and decides interactivity once (CLAUDE.md "Terminal effects…"). Z6 trims the getter, not the cache. |
| `baas-model`'s `dynamodb` dependency, and `model`/`model.dynamodb` as packages rather than modules | Both consumers use the mappers. A third module is the mechanism ADR 0009's alternatives already declined. `ModelBoundaryTest` keeps `model` SDK-free. |
| baas-cli declaring `dynamodb` although `baas-model` brings it | It is imported directly (12 imports). Relying on a transitive dependency is the fragile shape. |
| Runner test-scope dependencies on `fake-jmh-benchmarks`/`fake-stress-tests` next to `dependency:copy` | `dependency:copy` does not order the reactor; the declared dependency does (CLAUDE.md gotcha: those shaded JARs must exist first). |
| `LocalStorageService` | The runner without `--s3-bucket` (CLAUDE.md "Adding a benchmark type"). It is also the LocalStack and local scripts path. |
| `ResultsStoreBuilder.withDynamoDbClient`, `ApiCommonSharedOptions.getMongoConnectionString`: test-only | They exercise the exactly-one-of store rule. They go with Z12, not before. |
| `DeploymentNames.runnerRole()`: test-only | It pins the 64-character IAM bound behind the 3–47 name rule (CLAUDE.md, resource naming). The role itself is named in the template. |
| `StoredMeasurement.withTags`/`withBenchmarkClass`: test-only | They test the record's defensive copy and null check, which every store relies on. |
| `JobReference.resolve`'s trailing-slash strip | `jobs download` lists by prefix. `artifactKey`'s tolerance (I6) covers single keys only. |
| Two `Sleeper` interfaces (`SetupContext.Sleeper(Duration)`, `JobSession.Sleeper(long)`) | Unifying them adds a kernel type to save two lines. Each is local to its use case. |
| `JobItemConditions`, `RunnerArgs`, `JobLayout`, `BenchmarkType` in the model, and the user-data token substitution | That is the contract: a rename fails a build instead of a paid job (ADR 0009 §1). |
| Bash functions in `UserDataScriptBuilder` (`job_status`, `json_escape`, …) that the scanner flags as uncalled | They are shell, called inside the script. `job_status` must stay defined before the watchdog forks (CLAUDE.md, termination layer 1). |
| gson in the runner | It is the only JSON parser on the runner's classpath. Replacing it with jackson adds a dependency rather than removing one. |
| The deployment → jobs → {image, results} slice imports | ADR 0009 §3. Hiding them behind ports is mechanism with no second implementation. |
