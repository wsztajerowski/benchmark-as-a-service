# Test complexity and quality review (branch `codebase-review`, 2026-10-13)

Re-checked against slice-architecture (post-refactor) on 2026-10-15.

Re-verified against 7d9660a on 2026-10-14. This pass reads the final tree at `a8be1a0` (`git log --oneline 0308fb8..a8be1a0`, ADR 0009; the last commit applies the simplification review, deleting `SsmService`, `StorageServiceBuilder` and the runner's `*ServiceBuilder` classes). Every file:line below is at `a8be1a0`. Test counts come from the run measured on that commit, not from a new build: baas-model 82 unit; benchmark-runner 49 unit + 18 IT; baas-cli 932 unit + 49 IT; all green.

## Summary

1. The unit suite is still in good shape. It is fast and offline, has no Mockito, uses short behaviour-named methods (the longest is 44 lines), and tests the riskiest artefacts (user-data, CloudFormation template, IAM policies) by what they *do*.
2. **The main gap of the original review is closed for `baas run`.** The refactor moved `RunCommand.execute`'s AWS sequence into `JobLaunch` over one port, `RunAws`, with `AwsRunAws` as the real adapter. `JobLaunchTest` drives a whole launch against the recording `FakeRunAws` and pins the order: image → stack outputs → upload → pinned runner → reserve → hook → `runInstance`. It also pins every early-exit branch. `RunCommandLaunchTest` drives `RunCommand.call()` through picocli to a completed, detached and refused launch, without registering real JVM hooks (`RunCommand.shutdownHooks`). T1 is fixed.
3. **The `JobRecorder` fakes now share a contract.** `JobRecorderContract` (7 cases) runs against `DynamoDbJobRecorder` (Docker-gated IT), `FakeRunAws`'s recorder and `JobSessionTest.FakeRecorder`. One in-memory recorder is still unbound: `RunCommandDetachTest.Recorder`, which breaks two of the contract's rules. The `SetupAws` and now `RunAws` adapters (`AwsSetupAws`, `AwsRunAws`) still have no test. T4 is narrowed, not closed.
4. **Teardown and image build are unchanged.** `TeardownCommand.removeDeployment` and `RunnerImageBuild.run` still build their clients from a concrete `AwsClientFactory` (`DeployerSession.factory`). Their ordering invariants still run in no JVM test (T2). The refactor did make the fix cheaper: `RunnerImageBuild` now sits in `image` beside `FakeEc2`/`FakeImageBuilder`/`FakeSsm`.
5. **The cross-runtime contract gained real tests.** `RunnerArgsContractTest` parses user-data's argv for every `BenchmarkType` through the real `TestWrapper`. `SliceBoundaryTest`, `ModelBoundaryTest` and `RunnerLayeringTest` pin package edges. `PayloadWritersTest` pins JSON/CSV under `pl-PL`. The runner's JMH option mapping and its JCStress HTML parser are still untested (T3).
6. Every other finding (T5–T13) is still open. Most were only moved, because the slice refactor relocated nearly every test file. T13 still writes `baas-cli/policy.json`, and the file is still committed.
7. For the next refactor: the new port tests (`JobLaunchTest`, `JobRecorderContract`, `RunnerArgsContractTest`, the boundary tests) join the **protect** side. `RunCommandTest`'s statics, the seven `IFactory` harnesses and the field-poking `RunCommand*Test`s are still on the **obstruct** side. `RunCommandLaunchTest` adds four more field writes.

## Index

| ID | Title | Necessity | Effort | Status after slice-architecture (`a8be1a0`) |
|---|---|---|---|---|
| T1 | `RunCommand.execute` has no injectable AWS seam, so its step order is pinned only by the paid e2e | Should | M | **Fixed by the refactor** |
| T2 | Admin command sequencing (teardown, image build) is untested because `AwsClientFactory` is built inline | Should | S | Open, relocated |
| T3 | Runner `commands` layer is nearly untested; JMH option forwarding and the JCStress HTML parser have no unit test | Should | S | Open, changed: argv contract now tested |
| T4 | Hand-written stateful fakes re-implement the real rules with no contract test | Should | S | Open, changed: two of three in-memory `JobRecorder`s now bound; detach `Recorder`, `AwsSetupAws`, `AwsRunAws` not |
| T5 | No common command-test harness: 5 seam idioms, `IFactory` boilerplate ×7 | Could | S | Open, relocated |
| T6 | Positional records and builders rebuilt by private helpers in every test class | Could | S | Open, relocated |
| T7 | `Thread.sleep(100)` followed by an assertion on thread state in `JobSessionTest` | Could | XS | Open, relocated |
| T8 | `DeployerPreflightTest` pins the exact call count (14) to today's policy shape | Could | XS | Open, relocated |
| T9 | Global-state mutation (System.err, Locale, TimeZone) without `@ResourceLock` | Could | XS | Open, relocated (one new site) |
| T10 | Test isolation from `~/.baas`/`~/.aws` exists only in the surefire config | Could | XS | Open, relocated |
| T11 | LocalStack pin duplicated ×6, one container per IT class, stale `java.wonderland` label | Could | XS | Open, relocated |
| T12 | Every IT is `disabledWithoutDocker`: a Docker-less `mvn verify` silently skips all store/S3 tests | Could | XS | Open, changed: now also skips the contract's only real binding |
| T13 | A setup test writes `policy.json` into `baas-cli/`, and it was committed | Should | XS | Open, relocated |

No finding is withdrawn.

## Metrics

All figures are at `a8be1a0`. Figures in brackets are from 7d9660a.

| Metric | Value | Source |
|---|---|---|
| baas-cli main / test lines | 12 821 / 15 737 (1.23), 101 / 93 files [12 379 / 14 730, 1.19] | `wc -l` |
| baas-model main / test | 1 068 / 1 074 (1.01), 16 / 13 files [897 / 954]. The model grew by the contract types moved into it | |
| benchmark-runner main / test | 2 849 / 2 050 (0.72), 48 / 28 files [3 068 / 1 920]. The drop is the five deleted builders | |
| Per-package ratio, baas-cli (main/test, package only) | `jobs` 4137/5284 (1.28), `deployment` 709/2161, `deployment/setup` 1923/1436 (0.75, of which 339 are `FakeSetupAws`+`SetupRun`), `image` 1702/1942 (1.14), `results` 1102/1412, `session` 1045/980, `aws` 728/481 (0.66), `console` 607/439, `config` 460/515, `cli` 182/29, root 247/880, `testsupport` –/178. The old `commands`/`infra` split no longer exists | |
| Runner `commands` package | 663 / 210 (0.32) [0.16]. The increase is `RunnerArgsContractTest` (75 lines) | |
| Test methods | 1 033 `@Test` + 14 `@ParameterizedTest` [978 + 14]: baas-cli 888 + 14, baas-model 82, runner 63 | grep |
| Executed tests | baas-model 82; runner 49 unit + 18 IT; baas-cli 932 unit + 49 IT, all green. Measured at `a8be1a0` by the caller; Maven was not re-run for this pass. The executed count exceeds the declared count because contract suites run once per binding | given |
| Slowest classes | Not re-measured (no build in this pass). The 7d9660a profile still applies structurally: `UserDataScriptBuilderTest` runs bash, and `DeployerPreflight.java:147` holds the real 250 ms backoff sleep. `JobLaunchTest` passes a no-op sleeper and a zero interval, so the new launch tests do not wait | |
| Largest test classes | `UserDataScriptBuilderTest` 1133, `CoreTemplateTest` 784, `SetupStepsTest` 760, `RunCommandTest` 597, `ImageBuilderServiceTest` 590, `JobSessionTest` 504, `DeployerPolicyTest` 451 | `wc -l` |
| Longest test methods | `ResultsProjectSelectionTest.java:151` 44 lines, `UserDataScriptBuilderTest.java:946` 41, `JobsDiffCommandTest.java:61` 36 (almost all `IFactory` setup), `JobSessionTest.java:309` 32 | awk |
| Mockito | **none** (no `org.mockito` import) | grep |
| Hand-rolled SDK fakes | 8: `FakeEc2`, `FakeImageBuilder`, `FakeSsm` (now package `image`, beside their consumers), `CapturingEc2` (`jobs`), `FakeCloudFormation` + `DeployedStack` (`aws/CloudFormationServiceTest`), `FakeIam` (`deployment/DeployerPreflightTest`), `RecordingDynamoDbClient` (runner) | |
| Port fakes | `RunAws` ×1 (`FakeRunAws`, new). `JobRecorder` ×3: `FakeRunAws`'s and `JobSessionTest.FakeRecorder` are contract-bound, `RunCommandDetachTest.Recorder` is not. `JobSession.Instances` ×3 (`FakeRunAws`, `JobSessionTest.FakeInstances`, `RunCommandDetachTest.Instances`). `OperatorSession.Aws` ×2. `JobsShowSubcommand.Sources` ×1. `SetupAws` ×2 (`FakeSetupAws`, `SetupCommandTest.DeniedAws`) | |
| Contract suites | `JobRecorderContract` (new, 7 cases × 3 bindings, one Docker-gated); runner `ResultsStoreContractTest` (2 bindings, both Docker-gated) | |
| Structural tests | `SliceBoundaryTest` (161 lines, 6 rules), `ModelBoundaryTest` (41), `RunnerLayeringTest` (68). All three are new. The implementation review (I2, I3) covers what they do not see | |
| `IFactory` harnesses | 7 files, unchanged in number | grep |
| Direct field assignments into commands | 70 [66]: `RunCommandSummaryTest` 20, `RunCommandDetachTest` 14, `RunCommandTest` 9, `JobsConsoleOutputTest` 8, `ResultsProjectSelectionTest` 4, `RunCommandLaunchTest` 4 (new), `ResultsConsoleOutputTest` 3, `SetupCommandTest` 3, … | grep |
| String/message assertions | 298 `.contains("…")` [286], 76 `hasMessageContaining`; heaviest `UserDataScriptBuilderTest` 60, `SetupStepsTest` 34 (plus 17 `asked("…")`) | |
| Tests capturing logger output via `System.setErr` | 6 classes (list in T9) | |
| Tests writing outside their `@TempDir` | 1: `SetupCommandTest#withoutTheOptionATerminalIsAsked` writes `baas-cli/policy.json` (T13) | |
| Production classes named in no test | baas-cli: `AwsRunAws` (113, new adapter), `AwsSetupAws` (220), `RunnerImageBuild.run` (the class is now named, by `ImageCommandsTest.java:86-94` for `olderBaseRefusal`, which moved into it), `Watch`, `JobStop` (covered through `JobSession`/`JobTermination`), `ConfigListSubcommand` (driven by argv), `DeployerSession`. `StatusLine` is now named (`JobLaunchTest`). Runner: `ApiJmhOptions` (204), `JCStressHtmlResultParser` (128), `HtmlParser`, `JmhBenchmarkProcessBuilderFactory`. `SsmService` and `StorageServiceBuilder` no longer exist. `TestWrapper` and the four subcommands are now exercised by `RunnerArgsContractTest` (parse only) | grep -w |

## Findings

### T1: `RunCommand.execute` has no injectable AWS seam · Should · M · **Fixed by the refactor**
- **Was:** every AWS step in `RunCommand.execute` went through `operatorSession().factory()`. As a result, three invariants had no in-JVM test: the precondition order, "a reservation that cannot be written launches nothing", and "hook before RunInstances". CLAUDE.md recorded that `call()`'s success path ran in no JVM test.
- **Now:**
  - `RunCommand.execute` (`jobs/RunCommand.java:241-303`) resolves options and hands a `JobLaunch.Request` to `new JobLaunch(awsFactory.apply(operatorSession().factory()), config.names(), this)` (`:302`). `awsFactory` (`:91`) and `shutdownHooks` (`:88`, used at `:529`) are the test seams.
  - Every AWS call is on `RunAws` (`jobs/RunAws.java:16-53`). `AwsRunAws` is the real adapter.
  - `JobLaunchTest` (`jobs/JobLaunchTest.java`, 11 tests) runs against `FakeRunAws`, which logs each call:
    - Step order, each step strictly after the previous: `aCompletedJobRunsEveryStepInOrderAndShowsItsMeasurements` (`:101-119`).
    - No image, nothing uploaded or reserved: `:145-152`.
    - Missing networking fails before any upload: `:154-162`.
    - A failed reservation launches nothing and arms no hook: `:164-171`.
    - Refused launch, detach, ended-before-confirm: `:173-202`.
    - Status line and polling: `:204-227`.
  - `RunCommandLaunchTest` (`jobs/RunCommandLaunchTest.java:58-105`) runs `call()` through picocli for three cases: completed (exit 0, one JSON summary, hook armed once), detached and refused. It also covers a reserved tag that is refused before any AWS call (`aws.events` empty).
- **Residual, tracked elsewhere:**
  - `AwsRunAws` is untested pure delegation (see T4).
  - Of the precondition order before AWS, only the reserved-tag case is driven through `call()`. The other checks (format, type, unreleased build, project, table) are unit-tested as statics in `RunCommandTest`, which keeps them on the obstruct list.

### T2: Admin command sequencing is untested · Should · S · Open, relocated
- **Location:**
  - `deployment/TeardownCommand.java:78-150` (`removeDeployment`). It saves the extension at `:99-111`, empties the bucket at `:113-124`, deletes the stack at `:127-129` and retires the image at `:134-138`. Clients are built from `DeployerSession.factory(config)` at `:79`.
  - `image/ImageBuildSubcommand.java:75-79` builds the factory (now `DeployerSession.factory(config)`) and delegates to `image/RunnerImageBuild.java:32-91`, whose `run(AwsClientFactory, …)` signature is unchanged. Setup reaches the same method through `deployment/setup/AwsSetupAws.java:207`.
  - `deployment/TeardownConfigFileTest.java:56-67` still replaces `removeDeployment` wholesale (override at `:63`).
- **Evidence:** The refactor moved and renamed these classes but did not change their seams. `a8be1a0` moved `olderBaseRefusal` into `RunnerImageBuild` (`:100`) and its test follows it, but `run` itself is still reached only through a real factory.
  - Teardown's order is not pinned. "A teardown that cannot save the extension deletes nothing" and "retire the image only after the stack is gone" can still be reordered without a red build.
  - The build sequence in `RunnerImageBuild.run` is not pinned either: stack exists → older-base refusal → `requireCurrent` → parent resolve → `preflightVersion` (`:73`) before `updateStackParameters` (`:78`) → pipeline output → `publish`.
  - `FakeSetupAws.buildImage` still stands in for all of it as one call.
- **What changed that helps:**
  - `RunnerImageBuild` and the three image SDK fakes now share the package `image`, so a package-private test can use the fakes directly.
  - `RunAws`/`FakeRunAws`/`JobLaunchTest` is a second in-repo example (after `SetupAws`) of the port-plus-recording-fake shape this finding asks for.
- **Recommendation:** Unchanged in substance. Give teardown a port, in the shape of `RunAws`, with a recording fake and one ordering test per invariant. Change `RunnerImageBuild.run` to take a small port or the four clients rather than `AwsClientFactory`. Then test it in `image` against `FakeImageBuilder`/`FakeEc2`/`FakeSsm` and a CloudFormation fake, asserting "preflight before update, update before publish".

### T3: Runner command layer is nearly untested · Should · S · Open, changed
- **Location:** `benchmark-runner/src/main/java/pl/wsztajerowski/commands/ApiJmhOptions.java` (204 lines, picocli → JMH options), `JmhWithAsyncProfilerSubcommand.java` (82), `JmhWithProfilerSubcommand.java` (40), `JmhSubcommand.java` (29); since `a8be1a0` the subcommands construct their services directly, with no builders, `infra/JCStressHtmlResultParser.java` (128), `infra/HtmlParser.java` (72).
- **What changed:** `commands/RunnerArgsContractTest.java` (new, 75 lines) builds user-data's argv from `baas-model`'s `RunnerArgs`/`TagKeys` constants for every `BenchmarkType`, parses it through the real `TestWrapper`, and asserts the common options (`:47-65`). It also asserts that the runner's subcommands are exactly `BenchmarkType.commandNames()` (`:67-74`). A renamed common option or subcommand now fails a build. The `commands` test/code ratio rose from 0.16 to 0.32.
- **Still open:** Nothing asserts what a JMH flag *becomes*. `-wi`, `-f`, `-prof`, `-bm` passed through `ApiJmhOptions` to JMH `Options` are untested, so a dropped mapping still passes every unit test and surfaces only in a paid job's numbers. `JCStressHtmlResultParser` and `HtmlParser` still run only inside the Docker+Mongo `JCStressSubcommandServiceIT`.
- **Recommendation:** Narrower. (a) Add per-subcommand table tests from parsed args to the resulting JMH `Options`/argv; `RunnerArgsContractTest` already supplies the parsing half. (b) Add a checked-in JCStress `index.html` fixture for the parser.

### T4: Hand-written stateful fakes with no contract · Should · S · Open, changed
- **What the refactor fixed:**
  - `jobs/JobRecorderContract.java:18-89` states the conditional-write rules in 7 cases. It covers an unreserved job taking no write and reading empty, a second reservation being refused, `launched` only over `launching`, and the first terminal status winning.
  - It is bound to `DynamoDbJobRecorder` (`DynamoDbJobRecorderIT.java:213-220`, nested `Contract`), `FakeRunAws`'s recorder (`FakeRunAwsRecorderTest`) and `JobSessionTest.FakeRecorder` (`JobSessionFakeRecorderTest`).
  - `FakeRunAws`'s recorder (`FakeRunAws.java:104-153`) now refuses a second reservation and every write before one, so it matches the table on these rules.
  - This resolves the original core of T4 and implementation-review I4.
- **Still open:**
  1. **`RunCommandDetachTest.Recorder`** (`jobs/RunCommandDetachTest.java:39-86`) is a third in-memory `JobRecorder`, not bound to the contract. It would fail three cases:
     - `launchFailed` (`:59-62`) returns `WRITTEN` over a terminal status.
     - `reserve` (`:45-47`) never refuses.
     - `read` (`:72-75`) is never empty.
     The same file also has a third `JobSession.Instances` (`:89-106`).
  2. **`AwsSetupAws`** (`deployment/setup/AwsSetupAws.java`, 220 lines) against `FakeSetupAws` (237 lines). This is unchanged. The CloudFormation state transitions that `SetupStepsTest`'s 64 tests rely on are asserted only against the fake.
  3. **`AwsRunAws`** (`jobs/AwsRunAws.java`, 113 lines, new) against `FakeRunAws`. This is low risk: every method is a one-line delegation to a tested service (`ImageBuilderService.currentImage`, `CloudFormationService.getStackOutputs`, `RunnerJarResolver`, `DynamoDbJobRecorder`, `Ec2ProvisioningService`), and its recorder is the contract-bound one. It is listed for completeness, not as a gap worth a test.
  4. The contract's only real binding is Docker-gated (see T12).
- **Recommendation:**
  - Bind `RunCommandDetachTest.Recorder` to `JobRecorderContract` and fix it until it passes. Better still, delete it and its `Instances` in favour of `JobSessionTest.FakeRecorder`/`FakeInstances`, which are package-visible in `jobs` (the same package).
  - For `SetupAws`, the 7d9660a recommendation stands: a short `AwsSetupAwsTest` over the SDK fakes for the status and "no changes" mappings.

### T5: No common command-test harness · Could · S · Open, relocated
- **Location:** `IFactory` plus an anonymous override or field replacement, in 7 files:
  - `results/ResultsQueryPipelineTest.java:61`
  - `results/ResultsProjectSelectionTest.java:158`
  - `jobs/JobsShowCommandTest.java:44`
  - `jobs/JobsDiffCommandTest.java:83`
  - `config/ConfigSyncSubcommandTest.java:192`
  - `deployment/TeardownConfigFileTest.java:56`
  - `deployment/SetupCommandTest.java:161`

  `new ResultsQueryService(null, …) {…}` subclassing appears at `results/ResultsProjectSelectionTest.java:30,168` and `results/ResultsQueryPipelineTest.java:71`. `JobsDiffCommandTest` no longer does this.
- **Evidence:** The seven-step recipe from 7d9660a still holds for every noun except `run`.
  - `RunCommandLaunchTest` avoids `IFactory`, because `RunCommand` now exposes two function seams (`awsFactory`, `shutdownHooks`). It still sets four fields directly (`RunCommandLaunchTest.java:42-45`), so it is the field-poke idiom with a better port behind it.
  - `SetupRun` is still package-private to `deployment.setup`. `SetupCommandTest`, now in `deployment`, still hand-writes `DeniedAws` (`SetupCommandTest.java:133-156`) because it cannot reach `FakeSetupAws`.
  - `InfraFixtures` became public in `testsupport`, which shows that sharing test fixtures across slices is acceptable, but no command harness moved there.
- **Recommendation:** Unchanged. Add a `TestCli` in `testsupport`, modelled on `SetupRun`, and adopt one convention: one port hook per command, as `RunCommand.awsFactory` now is. Make `FakeSetupAws` reachable from `SetupCommandTest` so `DeniedAws` can go.

### T6: Positional constructors rebuilt in every test · Could · S · Open, relocated
- **Location:**
  - Private `ResultRow` builders: `results/ResultsGroupingTest.java:12,18,23`, `results/ResultsFiltersTest.java:12`, `results/ResultsConsoleOutputTest.java:42` (formerly `ConsoleOutputTest.java:47`), `console/TableTest.java:119` and `results/ResultsFormatTest.java:38`. They target the 9/10/11-argument constructors of `results/ResultRow.java:14,35,46`.
  - `new JobItem(` with 10 positional arguments: 13 test sites in 9 files. These include the new `FakeRunAws` and `JobRecorderContract.java:22`, plus `JobLaunch.java:160` in main.
  - `UserDataScriptBuilder.build` with 16 positional arguments: `jobs/UserDataScriptBuilder.java:298-303`, called from `JobLaunch.java:162-167` and 4 times in `UserDataScriptBuilderTest` (`:39`, `:173`, `:421`, …).
- **Evidence:** As before. `JobLaunch.Request` (13 positional components, built in `JobLaunchTest.java:86-92` and `RunCommand.execute`) is a new instance of the same shape.
- **Recommendation:** Unchanged: shared builders (`TestRows`, `TestJobs`) and a parameter record for `UserDataScriptBuilder.build`.

### T7: Sleep-based concurrency assertion · Could · XS · Open, relocated
- **Location:** `jobs/JobSessionTest.java:329-330` (`Thread.sleep(100)`, then `getState()` must be BLOCKED or WAITING).
- **Evidence and recommendation:** unchanged. Poll the state with a deadline, or count down a latch in the fake.

### T8: Exact call count pinned to the policy's shape · Could · XS · Open, relocated
- **Location:** `deployment/DeployerPreflightTest.java:98-106` (`hasSize(14)` at `:105`). The probed-statement list is at `:40-43`.
- **Evidence and recommendation:** unchanged. Assert `≤ 2 × statements.size()` derived from the rendered policy, and derive the statement list from the unconditioned statements.

### T9: Global JVM state without resource locks · Could · XS · Open, relocated
- **Location:**
  - `System.setErr`: `DeploymentOptionTest.java:36,71`, `OptionValidationTest.java:37`, `jobs/RunCommandSummaryTest.java:41`, `image/RunnerImageExtensionCommandsTest.java:78`, `results/ResultsConsoleOutputTest.java:34,90` (formerly `ConsoleOutputTest`) and `console/StatusLineTest.java:33`.
  - `Locale.setDefault`: `results/ResultsFormatTest.java:61,107,176`, `console/ConsoleTest.java:83`, and new, `console/PayloadWritersTest.java:37`.
  - `TimeZone.setDefault`: `baas-model/.../ResultKeysTest.java:160`.
  - `System.clearProperty`: `BaasAppTest.java:109,115`.
- **Evidence:** Still no `junit-platform.properties` and no `@ResourceLock` anywhere. Every site restores the value in `finally`, so serial execution is safe. The refactor added one site (`PayloadWritersTest`) and removed none.
- **Recommendation:** unchanged.

### T10: Home and credential isolation exists only in surefire · Could · XS · Open, relocated
- **Location:**
  - `baas-cli/pom.xml:141-167` (`user.home` at `:146`, `excludedEnvironmentVariables` at `:152-162`, still without `AWS_CONFIG_FILE`/`AWS_SHARED_CREDENTIALS_FILE`).
  - `new BaasApp()` → `ConfigService.defaultRoot()` (`BaasApp.java:93-95`). It *executes* in `jobs/RunCommandSummaryTest.java:45` and `results/ResultsConsoleOutputTest.java:35`. It only parses in `jobs/RunCommandDetachTest.java:133,143`, `jobs/JobsDiffCommandTest.java:14,25,51`, `image/ImageCommandsTest.java`, `BaasAppTest`, `HelpAndHintsTest`, `AdminProfileOptionTest` and `config/ConfigSetOptionsTest`.
- **Evidence and recommendation:** unchanged. The new `RunCommandLaunchTest` does it right: it adopts a session from an in-memory `BaasConfig` and never reads `~/.baas`.

### T11: LocalStack setup duplicated · Could · XS · Open, relocated
- **Location:**
  - The literal `"localstack/localstack:4.14.0"` appears in `jobs/RunnerJarResolverIT.java:30`, `jobs/S3DownloadIT.java:32`, `aws/S3UploadServiceIT.java:26`, `results/ResultsQueryServiceIT.java:47` and `jobs/DynamoDbJobRecorderIT.java:48`. In the runner it is `benchmark-runner/.../TestcontainersWithS3BaseIT.java:28,31`, with the stale `java.wonderland` label at `:32`.
  - The results-table schema is hand-built in `DynamoDbJobRecorderIT.java:56-75`, `ResultsQueryServiceIT` and `TestcontainersWithDynamoDbBaseIT`. These copies are still safe, because they use the `baas-model` constants.
- **Recommendation:** unchanged. A shared `LocalStackFixture` would now fit naturally in `testsupport`.

### T12: ITs skip silently without Docker · Could · XS · Open, changed
- **Location:** `@Testcontainers(disabledWithoutDocker = true)` at `results/ResultsQueryServiceIT.java:42`, `aws/S3UploadServiceIT.java:21`, `jobs/RunnerJarResolverIT.java:23`, `jobs/S3DownloadIT.java:27`, `jobs/DynamoDbJobRecorderIT.java:43` and `benchmark-runner/.../TestcontainersWithS3BaseIT.java:25`. CI runs `mvn -B clean verify` with Docker (`ci-pr-build.yml:49`).
- **What changed:** The stakes are higher. `DynamoDbJobRecorderIT.Contract` is the only binding that ties `JobRecorderContract` to the real condition expressions. Without Docker the contract still runs green against the two in-memory recorders, which *look* verified. A rule changed in `DynamoDbJobRecorder.NOT_TERMINAL` and mirrored wrongly in the fakes would pass locally. CLAUDE.md still documents only the `ASYNC_PATH` gate.
- **Recommendation:** unchanged. Fail rather than skip when `CI` is set, or document the gate beside `ASYNC_PATH`.

### T13: A setup test writes `policy.json` into `baas-cli/`, and it was committed · Should · XS · Open, relocated
- **Location:**
  - The test is `baas-cli/src/test/java/pl/wsztajerowski/baas/deployment/SetupCommandTest.java:196-201` (`withoutTheOptionATerminalIsAsked`). Its harness `onATerminal` is at `:158-181`, and `answerReader` at `:169` always returns `null`.
  - The write happens in `deployment/setup/PolicyHandOver.java`. `DEFAULT_FILE = "policy.json"` is at `:18`, the prompt at `:32-33`, and `Path.of(answer)` at `:38`.
  - The file is `baas-cli/policy.json`.
- **Evidence:**
  - **The file is still tracked:** `git ls-files` lists it at `a8be1a0`, and only `daeaea9` added it.
  - **No ignore rule covers it:** `git check-ignore` finds no match.
  - **Every test run still rewrites it.** The worktree copy's mtime is from today's in-progress build, yet `git diff` is empty, because the test renders identical bytes each time.
  - The mechanism is unchanged, only moved: a closed input answers the `[policy.json]` prompt with the default, which resolves against surefire's working directory.
- **Recommendation:** unchanged. Have `onATerminal` answer the prompt with a path under `root` (or `-`), or resolve relative answers against an injectable base. `git rm` the file and add it to `.gitignore`.

## Refactor safety: which suites protect and which obstruct

A "boundary refactor" here means moving rules between classes or packages without changing behaviour. The slice-architecture refactor was one. It confirmed this section's predictions: the suites listed as protecting survived it with only package moves, while `RunCommandDetachTest`, `RunCommandSummaryTest` and the `IFactory` harnesses had to be edited alongside the code.

**Protect** these. They assert outcomes at stable boundaries:
- **baas-model, all of it.** This now includes `ModelBoundaryTest`, which keeps the model free of the runtimes it serves.
- **Artefact suites:** `CoreTemplateTest`, `CoreStackTemplateTest`, `DeployerPolicyTest`, `OperatorPolicyDriftTest`, `DeploymentNamesTemplateTest`, `RunnerImageRendererTest`. They assert on rendered YAML/JSON and on what a policy grants (`testsupport/InfraFixtures`, now public and shared across slices).
- **`UserDataScriptBuilderTest`** (bash execution) and **`RunnerArgsContractTest`** (new) together pin the CLI-to-runner argv contract from both ends, through the same `baas-model` constants. The only coupling is the 16-argument `build(...)` (T6).
- **`JobLaunchTest`** (new) is the model for a use case behind a port. It asserts on the call log and the listener, so steps can move inside `JobLaunch` without breaking it. It is coupled to the event-string vocabulary of `FakeRunAws` (`"reserve"`, `"hook armed"`, …), which is an intended cost.
- **`JobRecorderContract`** and its three bindings, plus `JobSessionTest`, `JobTerminationTest` and `JobListingTest` (ports: `JobRecorder`, `Instances`, `Sleeper`).
- **`SetupStepsTest`, `SetupWalkthroughTest` and `PrompterTest`.** The three limits from 7d9660a still apply: about 25 package-private static calls on step classes, 34 `.contains` plus 17 `asked(…)` wording checks, and `SetupRun` building `SetupContext` directly.
- **`ImageBuilderServiceTest`, `RunnerImageResolutionTest` and `ParentImageResolverTest`** (stateful SDK fakes, now co-located in `image`).
- **`CommandTreeTest`, `BaasAppTest` and `HelpAndHintsTest`** (the command-tree shape).
- **`SliceBoundaryTest`, `ModelBoundaryTest` and `RunnerLayeringTest`** (new) pin the package edges ADR 0009 sets, so a later move cannot silently reintroduce a slice cycle. Their known blind spots (import lines only; the runner's middle packages) are implementation-review I2/I3.
- **`PayloadWritersTest`** (new) pins JSON/CSV formatting under `pl-PL` on the shared writers, independently of which command prints.
- **`SetupCommandTest`** (option validation), except its `IFactory` harness. **`ResultsGroupingTest`, `ResultsFiltersTest`, `EnvironmentManifestTest`, `PackagesDiffTest`, `BaasConfigYamlTest`** (pure logic).

**Obstruct** these. They address code by its location:
- **`RunCommandTest`** (51 tests) still calls package-private statics on the command: `RunCommand.requireValidProject` (`:65,72,92`), `resolveResultsTable` (`:111,122,130`), `RESERVED_TAG_KEYS` (`:98`), `watchdogBound` (`:455-467`), and it sets `command.project` (`:82,406`). Its `JobLaunch.noImageMessage` call (`:592`) already had to follow the code into `JobLaunch`. The `projectFromToplevel` tests now call `GitProject` directly (`:20-54`), so that part was converted.
- **The seven `IFactory` + override harnesses** (T5) depend on package-private hook names or fields and on subclassing `ResultsQueryService` with a `null` client.
- **Field-poking `RunCommand` tests:**
  - `RunCommandSummaryTest` (20 writes), `RunCommandDetachTest` (14), `JobsConsoleOutputTest` (8) and `RunCommandLaunchTest` (4) write `RunCommand`'s fields directly.
  - `RunCommandDetachTest` also builds `new JobLaunch(null, …)` (`:113`) to reach `afterLaunch` as a listener, which couples it to `JobLaunch`'s constructor and to the `RunCommand`-as-`Listener` wiring.
- **`TeardownConfigFileTest`** replaces `removeDeployment` wholesale (`:63`), so it obstructs extracting the method and protects nothing inside it (T2).
- **The six logger-capturing classes** (T9) break when wording moves between the logger and `Console`.

**Suggested order for the next refactor:**
1. Write T2's teardown and image-build ports first. `RunAws` and `JobLaunchTest` show the shape, and the image fakes are already in the right package.
2. Land T6's builders and T5's `TestCli` in `testsupport`.
3. Move `RunCommandTest`'s statics onto a request or plan class under green tests before deleting them from `RunCommand`.

## Fixed by the slice-architecture refactor

- **T1.** `baas run`'s AWS sequence moved into `JobLaunch` over `RunAws`.
  - `JobLaunchTest` pins the order (image → outputs → upload → reserve → hook → `runInstance`) and every early exit.
  - `RunCommandLaunchTest` drives `RunCommand.call()` end to end against `FakeRunAws`, with `RunCommand.shutdownHooks` replacing real JVM hooks.
  - The stale CLAUDE.md sentence ("`call()`'s success path is executed by no JVM test") no longer describes the code.

Partly fixed, so kept open as **Open, changed**:
- **T3:** `RunnerArgsContractTest` covers the argv contract and the subcommand set, but not JMH option mapping or the HTML parser.
- **T4:** `JobRecorderContract` binds DynamoDB and two of the three in-memory recorders. `RunCommandDetachTest.Recorder`, `AwsSetupAws` and `AwsRunAws` are not bound.

## Withdrawn

None. Every remaining finding was re-located at `a8be1a0`. Classes the refactor moved are cited at their new paths, and counts that changed are refreshed in place: field writes 66 → 70, `JobItem` sites 13 in 9 files, `.contains` 286 → 298.
