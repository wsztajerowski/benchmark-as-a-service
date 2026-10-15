# Implementation review — `slice-architecture` (0308fb8..81df090 + uncommitted CLAUDE.md / ADR 0009)

**Verdict.**
- The refactor is behaviour-preserving where it matters. `baas run`'s order is unchanged: preconditions, then image, then networking, then naming, then uploads, then reservation, then hook, then `RunInstances`, then confirm/detach, then poll and report. So are exit codes, JSON/CSV payloads (apart from the claimed escaping), user-data bytes, runner options, artifact keys and DynamoDB conditions.
- One behaviour change is not in the claim, and it is benign. Artifact keys now collapse a trailing slash on the result path (I6).
- Nothing is Must. The one Should (I1) is a documentation overclaim: CLAUDE.md says `SliceBoundaryTest` enforces "command classes … talk to AWS through their use case", but nine command classes still build SDK clients. The test only looks at import lines.
- The new tests are meaningful. The weak spot is the fakes: the `JobRecorder` contract binds the least faithful of the three in-memory recorders (I4), and `RunCommandLaunchTest` registers real JVM shutdown hooks (I5).
- The rest is leftovers from the move: stale comments, diagrams that still show `infra`/`RunCommand`-does-everything, import residue, and a runner "acyclic" claim that is not true (I3).

## Index

| ID | Title | Severity | Location |
|---|---|---|---|
| I1 | CLAUDE.md says commands talk to AWS only through use cases, and that the boundary test enforces it — neither holds | Should | `CLAUDE.md:49-51`; `SliceBoundaryTest.java:150-156` |
| I2 | `SliceBoundaryTest` only reads import lines: wildcard, fully qualified and `import static picocli` uses pass | Could | `SliceBoundaryTest.java:39-40`, `:143`, `:153` |
| I3 | The runner is still cyclic (services ↔ infra/process/results); `RunnerLayeringTest` and ADR 0009 say otherwise | Could | `RunnerLayeringTest.java:14-20`; ADR 0009 `:6` |
| I4 | `JobRecorderContract` binds `FakeRunAws`, the fake that breaks two table rules the contract never checks; the faithful fake stays unbound | Could | `FakeRunAws.java:104-153`; `JobRecorderContract.java:17-71`; `DynamoDbJobRecorderIT.java:37-42` |
| I5 | `RunCommandLaunchTest` registers real JVM shutdown hooks in the surefire JVM | Could | `RunCommand.java:541-545`; `RunCommandLaunchTest.java:37-46` |
| I6 | Unlisted behaviour change: `JobLayout.artifactKey` strips a trailing slash, so `jobs diff`/`show` keys can differ from before | Could | `JobLayout.java:76-80`; `JobsDiffSubcommand.java:129-130`, `:203` |
| I7 | The status-line branch of `JobLaunch.poll` and `RunCommand`'s own poll path have no test; one test name promises an assertion it does not make | Could | `JobLaunch.java:313-336`; `JobLaunchTest.java:202-210`; `RunCommandLaunchTest.java:59` |
| I8 | New `FileUtils` javadoc says the log walk is anchored on the JAR directory; it walks the process cwd | Could | `FileUtils.java:7-11`; `JobLogs.java:38-40` |
| I9 | Stale comments and javadoc that describe the old structure | Could | see entry |
| I10 | Diagrams still show `infra` and `RunCommand` doing the whole run | Could | `docs/diagrams/c4-3-component-cli.mmd:9,15`; `baas-run.mmd:3` |
| I11 | Two spellings of the existence guard; `RunnerArgs` javadoc covers an option user-data never passes; the template check names no token | Could | `DynamoDbJobRecorder.java:65,74,80`; `JobItemConditions.java:36`; `RunnerArgs.java:4,18`; `UserDataScriptBuilder.java:378-379` |
| I12 | Small mechanisms that do not pay for themselves | Could | `JobLaunch.java:42-50`; `AwsRunAws.java:116-120`; `ResultsFilters.java:48-51`; `TagKeys.java:53-62`; `RunCommand.java:363-366` |
| I13 | Residue from the move: same-package and unused imports, needless FQNs, an orphaned javadoc | Could | see entry |
| I14 | `BenchmarkType` is not the only list of types: `run`'s help text still spells them out | Could | `RunCommand.java:125-126`; `CLAUDE.md` "Adding a benchmark type" |

---

## Findings

### I1 — CLAUDE.md says commands talk to AWS only through use cases, and that the boundary test enforces it · Should

- **Location:** `CLAUDE.md:49-51` (uncommitted): "Command classes parse and print, and talk to AWS through their use case … `SliceBoundaryTest` enforces all of it". `SliceBoundaryTest.java:150-156`.
- **Evidence:** `commandClassesDoNotTouchTheAwsSdk` only fails a command class that has a line starting `import software.amazon.`. Nine command classes still build and drive SDK clients through `var`, so they import nothing:
  - `TeardownCommand.java:90,109,126,135,143` (`factory.ec2()`, `.cloudFormation()`, `.s3()`, `.imageBuilder()`/`.ssm()`)
  - `JobsDownloadSubcommand.java:92,111`
  - `JobsListSubcommand.java:181,190`
  - `JobsShowSubcommand`, `JobsDiffSubcommand`, `JobsTerminateSubcommand`, `ResultsQuerySubcommand`, `ImageShowSubcommand`, `ConfigSyncSubcommand` (`grep -E "factory\(?\)?\.(ec2|s3|dynamoDb|cloudFormation|ssm|imageBuilder)\("`).

  ADR 0009's Consequences admit this for teardown and image. The CLAUDE.md paragraph does not, and it states the rule as enforced.
- **Failure scenario:** A maintainer or agent reads CLAUDE.md, the file it is told to trust, and concludes that a green `SliceBoundaryTest` means no command orchestrates AWS. It then adds AWS sequencing to a `*Subcommand` (the shape B1 removed from `run`). The test stays green, and the sequence ends up beyond any JVM test again.
- **Recommendation:** Reword CLAUDE.md to what is true: "command classes import no AWS SDK type; `run` and setup reach AWS through `RunAws`/`SetupAws`; the other commands still build clients inline (ADR 0009, T2)". Rename the test to `commandClassesImportNoAwsSdkType`. Do not try to make the test stronger: a stronger rule here would be extra mechanism.

### I2 — `SliceBoundaryTest` only reads import lines · Could

- **Location:** `SliceBoundaryTest.java:39-40` (the `IMPORT` regex), `:143`, `:153`.
- **Evidence:**
  - The regex needs a capitalised class name after the package path, so `import pl.wsztajerowski.baas.jobs.*;` matches nothing and its crossing is never counted. One wildcard import already exists, although of the SDK: `jobs/Ec2ProvisioningService.java:7`.
  - A fully qualified use in code crosses unseen. Today's instances are allowed (`JobLaunch.java:341` → `console`) or are test code: `ResultsQueryServiceIT.java:330-331` (results test → `jobs.JobPaths`, the direction the main rule forbids).
  - `picocliBelongsToCommandsTheRootAndCli` matches `"\nimport picocli."`, so `import static picocli…` passes.
  - Test sources are not scanned at all.
- **Failure scenario:** An IDE "optimize imports" folds five imports from `jobs` into `jobs.*` inside `results`. The jobs ↔ results cycle the refactor removed comes back, and the test stays green.
- **Recommendation:** Two lines: let the regex also accept `\*` as the final segment, and match `import (static )?picocli`. Leave FQN detection out. That would be a parser, and the cost is not worth it.

### I3 — The runner is still cyclic; the test and the ADR say otherwise · Could

- **Location:**
  - `RunnerLayeringTest.java:14-20`: "services, results, process and infra below it … This is what keeps the package graph acyclic".
  - ADR 0009 `:6`: "Closes … B11 (cycle part)".
  - The commit `81df090` says "break the runner's package cycle".
- **Evidence:** `services` imports `infra` (20), `process` (6) and `results` (4). Each of those imports back into `services.options`:
  - `infra/StorageServiceBuilder.java:5` (`S3Options`)
  - `process/JmhBenchmarkProcessBuilderFactory.java:3` (`JmhOptions`)
  - `results/JmhRunResults.java:10` (`CommonSharedOptions`)

  The test checks only two rules: nothing below `commands` imports it, and the root helpers import nothing. It says nothing about the four middle packages.
- **Failure scenario:** The next runner change (`retire-mongodb`, B11's remainder) trusts the javadoc, believes the graph is acyclic, and starts from a wrong map.
- **Recommendation:** Change the javadoc and the ADR line to "breaks the commands ↔ root/services cycle; `services.options` is still shared downward (services ↔ infra/process/results)". Either leave fixing it to B11's remainder, or move the three options records to a leaf `options` package. That is a pure move.

### I4 — The `JobRecorder` contract binds the wrong fake · Could

- **Location:** `FakeRunAws.java:104-153`; `FakeRunAwsRecorderTest.java:4-12`; `JobRecorderContract.java:17-71`; `DynamoDbJobRecorderIT.java:37-42`.
- **Evidence:** `FakeRunAws`'s class comment says its recorder "follows the conditional-write rules the real table enforces". It breaks two of them:
  - `reserve` (`:106-110`) accepts a second reservation. The table refuses one: `DynamoDbJobRecorder.reserve`, `attribute_not_exists(#pk)`.
  - `read` (`:133-136`) returns an item with a `null` status before any reservation. The table returns empty. Its `stop`/`launchFailed` also write without a reservation, where the table's `attribute_exists(#pk)` refuses.

  The contract has no case for either rule, so the fake passes it.

  Meanwhile `JobSessionTest.FakeRecorder` (`JobSessionTest.java:26-80`, refuses when `status == null`, `read` is empty) is the faithful one. The IT's javadoc still names it as "the fake this keeps honest", yet it is not bound to the contract. `RunCommandDetachTest.Recorder` (`:42`) is not bound either, and its `launchFailed` (`:62-65`) returns `WRITTEN` over a terminal status.

  The review's step 3 (T4) asked for the contract to run "against both in-memory fakes and the DynamoDB adapter".
- **Failure scenario:** Someone reorders `JobLaunch` so a write lands before `reserve`. A `FakeRunAws`-based test can still pass on a rule the table does not have. The ordering assertions in `JobLaunchTest` catch the obvious version, so the residual risk is small.
- **Recommendation:** Add two contract cases: "a second reservation is refused" and "no write lands without a reservation (and read is empty)". Fix `FakeRunAws` to pass them. Bind `JobSessionTest.FakeRecorder` as well. Update the IT's javadoc. No new mechanism is needed.

### I5 — `RunCommandLaunchTest` registers real JVM shutdown hooks · Could

- **Location:** `RunCommand.java:541-545` (`reserved` → `Runtime.getRuntime().addShutdownHook`); `RunCommandLaunchTest.java:37-46`.
- **Evidence:** `run()` drives a real `RunCommand` through `call()`. Three tests reach the reservation (completed, detached, refused), so the surefire fork exits with three hooks registered. Each one runs `onShutdown` against `FakeRunAws` and a `JobSession` that holds the test's objects. Today every one of those sessions has ended or detached, so the hooks only log at debug level.
- **Failure scenario:** A future test leaves a session un-ended, for example one that throws mid-poll. Its hook then runs `stop(CANCELLED)` during JVM shutdown, against a fake that the next test has since mutated, and writes to a swapped `System.err`. The result is order-dependent noise or a hang at fork exit, which nobody connects to the test.
- **Recommendation:** Keep the production code as it is. In the test, set a package-private `Consumer<Thread> hooks = Runtime.getRuntime()::addShutdownHook` that the test can replace, or remove the hook in an `@AfterEach`. One field is enough.

### I6 — `artifactKey` collapses a trailing slash; not in the claimed changes · Could

- **Location:** `JobLayout.java:76-80`; callers `JobsDiffSubcommand.java:129-130`, `:203`, `JobsShowSubcommand.java:108`, `JobLaunch.java:315`.
- **Evidence:** Before, every caller built the key as `path + "/environment.json"` (or `/packages.txt`, `/cloud-init-output.log`). `artifactKey` first strips one trailing `/`.
  - `jobs show` already trimmed (`JobsShowSubcommand.java:145`), so nothing changes there.
  - `baas run` gets a path without a trailing slash from `JobLayout.jobPrefix`, so nothing changes there either.
  - `jobs diff` passes on whatever `JobReference.resolve` returns: a user-typed path, or an old measurement's `resultPath`. A path ending in `/` used to produce `…//environment.json` (no such object, so "has no environment.json"). It now finds the manifest.
- **Failure scenario:** None harmful. This is a fix, but the branch claims only two behaviour changes. A reviewer comparing live output would see `jobs diff` succeed where it used to fail and wonder whether anything else moved.
- **Recommendation:** Add it to the ADR's Consequences, or to the PR body, as the third behaviour change.

### I7 — The status-line poll and `RunCommand`'s own poll are untested · Could

- **Location:** `JobLaunch.java:313-336`; `JobLaunchTest.java:70-73`, `:202-210`; `RunCommandLaunchTest.java:59`.
- **Evidence:**
  - Every `JobLaunchTest` listener returns `null` from `openStatusLine`, so `line.update(...)` and the `finally` close never run.
  - `RunCommand` builds `JobLaunch` with the public constructor: 15 s interval, `Thread::sleep`. So `RunCommandLaunchTest` uses `COMPLETED_BEFORE_CONFIRM` to avoid polling at all. Its "a completed job" test therefore skips the poll loop that most real runs go through.
  - `progressIsLoggedWhenNoStatusLineIsShown` asserts only the sleeps, not the logging its name promises.
- **Failure scenario:** A regression in the status line's close-on-end ("a line still saying running beside its results") reaches users unseen. That is the reason for the `finally`.
- **Recommendation:** Add one `JobLaunchTest` case with a recording `StatusLine` (update called, then closed once). Rename the progress test to `pollsUntilTheJobEnds`. Leave `RunCommand`'s poll alone: `JobLaunch` owns it.

### I8 — `FileUtils` javadoc misstates the log walk's anchor · Could

- **Location:** `FileUtils.java:7-11`, "The directory holding the runner's JAR — on the instance `/app`, never `/`, since the log walk below it must not reach the root filesystem".
- **Evidence:** `JobLogs.upload` walks `Paths.get("")`, the process cwd (`JobLogs.java:38-40`), not `getWorkingDirectory()`. The two agree on the instance only because user-data both puts the JAR in `/app` and `cd`s there. The anchor change itself, from `TestWrapper.class` to `FileUtils.class`, is behaviour-preserving: same shaded JAR, and the same `target/classes` in tests.
- **Failure scenario:** Someone "fixes" the `/app` invariant by moving the JAR, trusting this comment, and the walk still starts wherever cloud-init left the cwd. That is the case CLAUDE.md's `/app` invariant guards.
- **Recommendation:** "The directory holding the runner's JAR, where `LocalStorageService` writes and the default JAR paths resolve. Not the log walk's root: that is the cwd (`JobLogs`)."

### I9 — Stale comments and javadoc · Could

Each one still describes the pre-refactor structure:

- `RunCommandSummaryTest.java:22-26` says "CLAUDE.md records that `RunCommand.call()` is executed by no test". `RunCommandLaunchTest` now executes it, and CLAUDE.md no longer says so.
- `RunnerImageResolutionTest.java:15-17` says "that it runs first is visible in `RunCommand` as the step numbering, and provable only by…". `JobLaunchTest.withNoRunnerImageNothingIsUploadedOrReserved` now proves it.
- `ResultsQueryServiceIT.java:156` names `RunCommand.showResults`, which is now `JobLaunch.showResults`. `ResultsQueryService.java:160` `{@link …jobs.RunCommand}`'s "own post-job summary" now lives in `JobLaunch`.
- `DynamoDbJobRecorderIT.java:37-42` names the `JobSessionTest` fake as the one it keeps honest (see I4).
- `RunCommand.java:363-366`: `projectFromToplevel` says "Shared with `baas results query`". Results calls `GitProject` directly (`ResultsQuerySubcommand.java:358`), so only `RunCommandTest` calls this method.
- `DeploymentNames.java:36`: the `stack()` javadoc ("The core stack. Identical to the prefix…") was orphaned when `defaultFor` was inserted below it. It now sits on top of `defaultFor`'s javadoc, and `stack()` (`:49`) has none.
- `RunnerArgs.java:4`, "the options user-data passes", also covers `PROJECT` (`:18`), which user-data never passes (see I11).

**Recommendation:** Fix them in one sweep. They are text-only.

### I10 — Diagrams still show the old structure · Could

- **Location:** `docs/diagrams/c4-3-component-cli.mmd:9` (`RunCommand`: "Preconditions, job naming, upload, launch, poll, JSON summary, shutdown hook"), `:15` (`infra services`); `docs/diagrams/baas-run.mmd:3` (a single `RC as RunCommand` participant for the whole flow).
- **Evidence:** Both drifted before (B12). This branch is the package-layout change, and it makes both diagrams describe packages and responsibilities that no longer exist. CLAUDE.md requires the `.mmd` to be updated when a command changes.
- **Recommendation:** In `baas-run.mmd`, split the participant into `RunCommand` and `JobLaunch` (plus `RunAws`). Regenerate `c4-3` from the slices, using the review's `target-packages.mmd` as the start. Render each with `mmdc` and look at it, per CLAUDE.md. This could go in a follow-up, but it should be named in the PR.

### I11 — Contract loose ends · Could

- **Location:**
  - `DynamoDbJobRecorder.java:65,74,80`
  - `JobItemConditions.java:36`
  - `RunnerArgs.java:4,18`
  - `UserDataScriptBuilder.java:378-379`
- **Evidence:**
  - `JobItemConditions.EXISTS` (`attribute_exists(pk)`) is used only by user-data. The CLI's recorder still writes its own `"attribute_exists(#pk) AND "` three times. The text is equivalent, but the claim that there is "one guard both runtimes evaluate" covers only the `NOT_TERMINAL` half.
  - `RunnerArgs.PROJECT` is documented as an option user-data passes. It is not: the project reaches the runner as `--tag project=…`, which `RunnerArgsContractTest` relies on.
  - The class-load check throws "user-data template holds a name with no value" without naming the token. The failure is an `ExceptionInInitializerError` that makes every `baas run` fail, so the message is the only clue.
- **Recommendation:** Either use `JobItemConditions.EXISTS`'s name via `#pk`, or drop the claim. Reword `RunnerArgs`'s javadoc to "the runner options the CLI and user-data share". Include the first unmatched token in the exception message, which is one `Matcher.find()`.

### I12 — Small mechanisms that do not pay for themselves · Could

These run against the maintainer's "no extra mechanism" rule:

- `JobLaunch.Request.resultsTable` (`JobLaunch.java:42-45`) duplicates `DeploymentNames.resultsTable()`, which `JobLaunch` already holds. `RunCommand` fills it from `config.resultsTable()`, which is `names().resultsTable()` (`BaasConfig.java:74`). That gives one fact two inputs, the shape CLAUDE.md's "`--project` … two inputs split one job" warns about.
- `AwsRunAws.withS3` (`AwsRunAws.java:116-120`) is a generic `Function<S3UploadService,T>` whose two callers both return `null`. A `Consumer` would do.
- `ResultsFilters.pair` (`ResultsFilters.java:48-51`) is now a public one-line pass-through to `TagKeys.parsePair`, and has no caller outside its class.
- `TagKeys.parsePair` (`TagKeys.java:53-62`) puts a CLI usage error ("Expected key=value") in the three-runtime contract. The target allowed this, so it is acceptable. Note that it exists only to break `jobs` → `results`, and that `jobs` → `results` is now an allowed edge anyway (`SliceBoundaryTest.java:53`).
- `RunCommand.projectFromToplevel` (`RunCommand.java:363-366`) is dead in main code.
- **Recommendation:** Drop `Request.resultsTable` and read `deployment.resultsTable()`. Make `withS3` a `Consumer`. Inline `ResultsFilters.pair`. Delete `projectFromToplevel`, pointing its test at `GitProject.fromToplevel`. Keep `parsePair` where it is.

### I13 — Residue from the move · Could

- Same-package imports:
  - `RunCommand.java:36-39`
  - `JobLaunch.java:4`
  - `AwsRunAws.java:6,9`
  - `DeployerSession.java:5`
  - `OperatorSession.java:7-10`
  - `BaasApp.java:19` (`import pl.wsztajerowski.baas.AdminCommand`)
  - `RunCommandLaunchTest.java:11`
- Unused: `RunCommand.java:7` (`console.Elapsed`).
- Fully qualified names where an import was possible:
  - `JobsTerminateSubcommand.java:68` (same package, `pl.wsztajerowski.baas.jobs.JobSession.STOP_WRITE_TIMEOUT`)
  - `JobLaunch.java:340-341`
  - `UserDataScriptBuilderTest.java:270-283`
  - `JobLayout.java:5` (`{@link pl.wsztajerowski.baas.model.ResultKeys}`, same package)
- Imports glued to the `package` line: `JobItemMapper.java:2`, `MeasurementItemMapper.java:2`, `BaasApp.java:2`.
- Stray blank lines: `ResultKeys.java:2-3`, `RunCommand.java:230-231, 353-355`, and the 5–7 empty lines at the top of every file that rename detection showed as rewrites, for example `TeardownCommand.java`.
- **Failure scenario:** None at runtime. The cost is that `git diff -M` fails to detect most moves as renames: `RunCommand`, `UserDataScriptBuilder`, `TeardownCommand` and others show as delete-plus-add, which makes this branch and its `git blame` harder to review.
- **Recommendation:** Run one "optimize imports" pass, in its own commit, before merging.

### I14 — `BenchmarkType` is not the only list of types · Could

- **Location:** `RunCommand.java:125-126`, `description = "Benchmark type: jmh, jmh-with-async, jmh-with-prof, jcstress."`; CLAUDE.md "Adding a benchmark type" (uncommitted).
- **Evidence:** Validation now reads `BenchmarkType.commandNames()` (`:84`), and `RunnerArgsContractTest` pins the runner to it. The help text stays a literal, and CLAUDE.md's recipe does not mention it.
- **Failure scenario:** A new type is accepted and executed, but `baas run --help` does not list it.
- **Recommendation:** Use picocli's `${COMPLETION-CANDIDATES}` with a `completionCandidates` class over `BenchmarkType.commandNames()`, or add "and `run`'s `<type>` help text" to the CLAUDE.md recipe. The second is cheaper.

---

## Checked and fine

**`baas run` ordering.** Old `RunCommand.execute` and new `RunCommand.execute` + `JobLaunch.run` were compared line by line:
- The `--format` and type checks (exit 2), the unreleased-build refusal (exit 1), project and table resolution, timings, and the reserved and `project` tag rejection all run before any AWS call. `RunCommandLaunchTest.aReservedTagIsRefusedBeforeAnyAwsCall` checks the tag rejection.
- The JAR-exists check comes before the image lookup. `OperatorSession.factory()` is deferred, so building it after the JAR check makes no call and changes nothing.
- Image lookup comes before networking, then naming (one `Instant.now()`), then uploads, then user-data, then the recorders and EC2 adapter. These are never closed (`AwsRunAws.recorder/stopRecorder/instances`), matching the old comment.
- Then `reserve`, then `listener.reserved`, which sets the volatile `session` and arms the hook as the old code did, then `RunInstances`.
- A launch failure records `launch-failed` and `launch-error.txt`. Then come `confirmLaunched`, `afterLaunch` (cancelled-while-launching returns 1; detach sets `summaryJobStatus` before the volatile `detached = true`), poll and report.
- Every log message and the `noImageMessage`/networking texts are byte-identical, apart from the logger name, which is claim (b) and confirmed. `simplelogger.properties` has `showShortLogName=true` and no per-class level, and no workflow or script greps for `RunCommand`.

**Summary, hints and status line.**
- `summaryJobId` and `summaryResultPath` are set by `named` at the same point as before. `summaryDeployment` is now set earlier, but `hints` returns early while `summaryJobId` is null, so output is unchanged.
- `printJobSummary` has the same field order and the same `status` verdict. `jobStatus` keeps the detached value.
- The status line is opened only for non-JSON output and closed in `finally`. The old code closed it twice and the new code closes it once; the hook still closes the volatile reference first.

**Shutdown hook and concurrency.** `onShutdown` is unchanged. `session`, `detached` and `statusLine` are still volatile. The `Listener` callbacks run on the main thread. The hook reads only volatile fields, and `JobSession`'s `ended`/`endStatus`/`instanceId` are volatile and synchronized as before.

**User-data.**
- I diffed the old and new `SCRIPT_BODY` by hand. Each of the 22 tokens substitutes a value equal to the literal it replaced: `pk`, `sk`, `JOB`, `status`, `instanceId`, `attribute_exists(pk)`, the three artifact names, the seven flags, and the six tag keys.
- Substitution runs before comment stripping, and the leftover-token check covers the whole body. The `[@]` array expansions cannot match `@[A-Z_]+@`, and no token is a substring of another.
- `NOT_TERMINAL` and its values come from an identical generator moved into the model, with the same `LinkedHashMap` order.
- The rendered script is therefore byte-identical. Literal-name tests still pin `cloud-init-output.log` and the `pk`/`JOB` key.

**Contract values.** Each value matches the literal it replaced in the runner, the user-data and the CLI:
- The `RunnerArgs` values match every replaced `@Option` name.
- The `BenchmarkType` names and output files match the four replaced literals.
- `JobLayout.RESULT_JSON_NAME`, `ENVIRONMENT_NAME`, `PACKAGES_NAME`, `BOOT_LOG_NAME` and `LOGS_SEGMENT` match.
- The `CoreStack` constants match the template (`CoreStackTemplateTest`).
- `DeploymentNames.defaultFor` keeps the 12-digit check.
- `PlanStep`, `NamesStep` and `TeardownCommand` names are unchanged.

**Payloads.** `Json.string`, `Json.object`, `Json.number`, `Csv.*` and `Elapsed.format` are the old `ResultsQuerySubcommand`/`RunCommand` bodies, moved:
- `results query`'s quoted fields keep `"null"` for an absent value (W5), via `String.valueOf`.
- `imageVersion`/`instanceType` keep JSON `null`.
- `jobs list/show` and `config list` keep `null` → `null`.
- The CSV is unchanged.
- The only difference is the escaping, which is claim (a), and for ordinary values the output is byte-identical.

**Sessions and configuration.**
- `Invocation`/`Root` reproduce `BaasApp.deployment`/`configService` exactly: strip, blank means none, default root outside a tree.
- `LoggingMixin` is semantically identical; it routes through `Root.rootLoggingMixin()`. The argv pre-scan is untouched, so `-v` survives.
- The `DeployerSession.factory` call sites build the same region and profile as the four hand-built factories they replaced. `ImageBuildSubcommand` keeps its own `region` argument.

**Moved helpers.**
- `AwsErrors.isAccessDenied` and `Checksums.sha256Hex` are verbatim moves.
- The `JobPaths` lookup order is unchanged: job item first (`gsi1sk = JOB`), then the first measurement. It closes the same client.
- `JobListing`/`ResultsFilters` reject the same inputs through `TagKeys.parsePair`.

**Runner.** The `FileUtils` anchor (`FileUtils.class` vs `TestWrapper.class`) resolves to the same JAR or the same `target/classes`. `HtmlParser` and `JCStressHtmlResultParser` are pure moves.

**Boundary tests do not pass trivially.** All three resolve module-relative `src/main/java/…`, and surefire's default working directory is `${basedir}`. A wrong directory makes `Files.walk`/`Files.list` throw `NoSuchFileException` and fail rather than pass empty. The regexes match the real import shapes, including nested `deployment.setup` and static imports. The gaps are I2.

**New tests.**
- `JobLaunchTest` pins the order that matters, each step strictly after the one before it: preconditions, then upload, then reserve, then hook, then `runInstance`. It also pins the no-image, no-networking, reservation-failure, launch-failure, detach and ended-before-confirm branches.
- `RunnerArgsContractTest` parses the real argv shape through `TestWrapper`, including last-wins observed tags.
- `UserDataScriptBuilderTest`'s new cases tie the key to `JobItemMapper.key`.
- `PayloadWritersTest` restores `Locale` in `finally`. The suite runs serially (no `junit-platform.properties`), so that is safe.
- `DynamoDbJobRecorderIT.Contract` gets a fresh table per test from the outer `@BeforeEach`.
- The old `ConsoleOutputTest` cases all survive in the per-slice tests, `ConsoleTest` or `PayloadWritersTest`.
