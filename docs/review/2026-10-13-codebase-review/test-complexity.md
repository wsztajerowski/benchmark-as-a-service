# Test complexity and quality review (branch `codebase-review`, 2026-10-13)

## Summary

1. The unit suite is in good shape: fast (892 baas-model + baas-cli tests, 0 skipped, 28 s wall including compile, offline), no Mockito anywhere, short behaviour-named methods (longest is 44 lines), and the riskiest artefacts (user-data, CloudFormation template, IAM policies) are tested by what they *do*. The user-data tests run the script in bash against a fake `aws`. They do not grep for spellings.
2. The gap is at the **command orchestration layer**. `RunCommand.execute` (~200 lines), `SetupCommand.call` (~110), `TeardownCommand.removeDeployment` (~84) and `ImageBuildSubcommand.call` (~68) run in no JVM test. That is because `AwsClientFactory` is a concrete class that builds real SDK clients, and the admin commands call `new` on it inline. The ordering invariants CLAUDE.md lists (reserve before launch, hook before RunInstances, save the extension, then empty the bucket, then delete the stack, then retire the image) are proven only by the paid e2e or not at all.
3. With no shared seam, each command test invents one. There are five idioms: a picocli `IFactory` plus an anonymous subclass overriding a package-private hook, field pokes (65), static helpers on the command, SDK-interface fakes (8), and ports (`JobRecorder`, `JobSession.Instances`, `OperatorSession.Aws`, `JobsShowSubcommand.Sources`). About 20 lines of `IFactory` boilerplate are copy-pasted into 6 files.
4. Fixtures are duplicated: 2 in-memory `JobRecorder`s with no contract test binding them to the DynamoDB one, 6 private `ResultRow` builders over a 9–11-argument positional record, `JobItem`'s 10-argument constructor at 11 sites, and the LocalStack pin written 6 times. Global-state tests (System.err swap, Locale and TimeZone defaults) are safe only because nothing runs in parallel.
5. For a boundary refactor: baas-model, the template and policy suites, `JobSessionTest`/`JobTerminationTest`, `UserDataScriptBuilderTest` and `CommandTreeTest` would **protect** it. `RunCommandTest`'s static-helper tests, every `IFactory`+override harness, and the field-poking `RunCommand*Test`s would **obstruct** it. They break on a pure move because they address code by its location (class, package-private method name, field), not by behaviour.

## Index

| ID | Title | Necessity | Effort |
|---|---|---|---|
| T1 | `RunCommand.execute` has no injectable AWS seam, so its step order is pinned only by the paid e2e | Should | M |
| T2 | Admin command sequencing (teardown, setup, image build) is untested because `AwsClientFactory` is built inline | Should | S |
| T3 | Benchmark-runner `commands` package has 0.16 test/code; JMH option forwarding and the JCStress HTML parser have no unit test | Should | S |
| T4 | Two in-memory `JobRecorder` fakes re-implement the conditional-write rules, with no contract test against `DynamoDbJobRecorder` | Should | S |
| T5 | No common command-test harness: 5 seam idioms, `IFactory` boilerplate ×6 | Could | S |
| T6 | Positional records and builders rebuilt by private helpers in every test class | Could | S |
| T7 | `Thread.sleep(100)` followed by an assertion on thread state in `JobSessionTest` | Could | XS |
| T8 | `DeployerPreflightTest` pins the exact call count (13) to today's policy shape | Could | XS |
| T9 | Global-state mutation (System.err, Locale, TimeZone) without `@ResourceLock` | Could | XS |
| T10 | Test isolation from `~/.baas`/`~/.aws` exists only in the surefire config | Could | XS |
| T11 | LocalStack pin duplicated ×6, one container per IT class, stale `java.wonderland` label | Could | XS |
| T12 | Every IT is `disabledWithoutDocker`: a Docker-less `mvn verify` silently skips all store/S3 tests | Could | XS |

## Metrics

| Metric | Value | Source |
|---|---|---|
| baas-cli main / test lines | 10 762 / 13 465 (1.25) | `wc -l` |
| baas-model main / test | 897 / 954 (1.06) | |
| benchmark-runner main / test | 3 068 / 1 920 (0.63) | |
| Per-package ratio, baas-cli | `infra` 3154/5908 (1.87), `commands` 3118/3192 (1.02), `commands/admin` 1318/973 (0.74), `jobs` 844/990, `results` 861/915, `config` 642/594, `console` 484/373 | |
| Per-package ratio, runner | `commands` 862/135 (**0.16**), `services/options` 417/126 (0.30), `process` 119/30, `services` 781/754, `infra` 450/397, `entities*` 217/0 (Mongo, going with C4) | |
| Test methods | 914 `@Test` + 14 `@ParameterizedTest` | grep |
| Unit run (baas-model + baas-cli) | 892 tests, 0 skipped, 0 failed, 28.5 s wall (62 s CPU) | `mvn -q -o -pl baas-model,baas-cli -am test` |
| Slowest classes | `UserDataScriptBuilderTest` 5.1 s (bash subprocesses), `DeploymentOptionTest` 0.89 s, `CoreTemplateTest` 0.60 s, `DeployerPreflightTest` 0.57 s (a real 250 ms backoff sleep, `DeployerPreflight.java:151`) | surefire XML |
| Largest test classes | `UserDataScriptBuilderTest` 1098, `CoreTemplateTest` 767, `RunCommandTest` 587, `JobSessionTest` 503, `ImageBuilderServiceTest` 474, `SetupCommandTest` 448, `DeployerPolicyTest` 438 | |
| Longest test methods | `ResultsProjectSelectionTest.java:152` 44 lines, `UserDataScriptBuilderTest.java:911` 41, `JobsDiffCommandTest.java:61` 36 (almost all `IFactory` setup) | awk |
| Mean size | ~14.7 test lines per test method, helpers included | |
| Mockito | **none** (0 `mock(`, 0 `when(`; the 5 `verify(` are `RunnerJarResolver.verify`) | grep |
| Hand-rolled SDK fakes | 8: `FakeEc2`, `FakeImageBuilder`, `FakeSsm`, `CapturingEc2`, `FakeCloudFormation` + `DeployedStack`, `FakeIam`, `RecordingDynamoDbClient` (runner) | |
| Port fakes | `JobRecorder` ×2, `JobSession.Instances` ×2, `OperatorSession.Aws` ×2, `JobsShowSubcommand.Sources` ×1 | |
| Anonymous command subclasses via `IFactory` | 6 files | |
| Direct field assignments into commands | 65 (RunCommandSummaryTest 20, RunCommandDetachTest 11, ConsoleOutputTest 11, RunCommandTest 9 …) | |
| String/message assertions | 258 `.contains("…")`, 77 `hasMessageContaining`; heaviest `UserDataScriptBuilderTest` (56), `SetupCommandTest` (25) | |
| Tests capturing logger output via `System.setErr` | 7 classes | |
| Assertions on captured SDK request objects | 18, in 4 files (`OperatorSessionTest` 8, `Ec2ProvisioningServiceTest` 6, `DeployerPreflightTest` 3, `ParentImageResolverTest` 1) | |
| Production classes not named in any test | baas-cli: `StatusLine`, `Watch`, `JobStop` (covered indirectly through `JobSession`/`JobTermination`), `SsmService`, `ConfigListSubcommand` (driven by argv); runner: `ApiJmhOptions` (203), `JCStressHtmlResultParser` (128), `HtmlParser`, `JmhWithAsyncProfilerSubcommand`, `JmhWithProfilerSubcommand`, `JmhSubcommand`, `TestWrapper`, `JmhBenchmarkProcessBuilderFactory`, `StorageServiceBuilder` | grep -w |
| Naming | consistent behavioural sentences (`aRefusedRunningWriteShipsTheLogAndTerminatesInsteadOfRunningTheBenchmark`), with rationale in Javadoc and finding IDs cited | |

## Findings

### T1: `RunCommand.execute` has no injectable AWS seam · Should · M
- **Location:** `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java:286-490` (execute), `:573-640` (poll and results), `:94-98` (`operatorSession()` → `OperatorSession.of`), `infra/AwsClientFactory.java:28-80` (concrete, `build(XClient.builder())`).
- **Evidence:** CLAUDE.md already says the success path of `call()` is uncovered. The cause is structural. Every AWS step is reached through `operatorSession().factory().<client>()`, and `AwsClientFactory` builds real SDK clients, so no test can substitute one. The tests therefore cover only the pieces around the launch. `RunCommandDetachTest.java:25-28` says so: "The launch itself needs AWS, so these drive the steps around it … directly". Of the CLAUDE.md invariants that live in this method, three have no in-JVM test:
  - the precondition order: format, type, runner JAR, project, table, tags, then JAR existence before any AWS call (`:290-342`);
  - "a reservation that cannot be written launches nothing" (`:458-466`);
  - "hook registered *before* RunInstances" (`:472`).
- **Recommendation:** extract the AWS-touching steps behind one port, the way `JobsShowSubcommand.Sources` (`JobsShowSubcommand.java:78`) and `JobSession.Instances` already work: `resolveImage`, `networking`, `upload`, `recorder`, `instances`, `launch`. Then one test can assert the sequence of calls and the stop-at-first-failure behaviour, and N3 becomes testable. Don't fake `AwsClientFactory`'s SDK clients for this: the SDK fakes would pin which API calls are made rather than what the step decides.

### T2: Admin command sequencing is untested · Should · S
- **Location:** `commands/admin/TeardownCommand.java:79-162` (`removeDeployment`: save extension → empty bucket → delete stack → retire image, each stopping the rest), `SetupCommand.java:102-211`, `ImageBuildSubcommand.java:78-145`. Each builds its own client factory inline (`TeardownCommand.java:80`, `SetupCommand.java:134`, `ImageBuildSubcommand.java:109`).
- **Evidence:** The services underneath are tested well. `ImageBuilderServiceTest.java:42,72,92,392-437` pins repoint-before-retire and the retire leftovers. But the order in which the *command* calls them is not. `TeardownConfigFileTest.java:55-66` replaces `removeDeployment` wholesale with a constant exit code. Under these tests, "teardown retires the image only after the stack is gone" and "a teardown that cannot save the extension deletes nothing" could be reordered without a red build. `SetupCommandTest` (448 lines) tests only static and pure helpers (`refusalForMissingRights`, `federationParameters`, …, lines 25-408).
- **Recommendation:** pass in a client source the existing fakes can satisfy. One option is an `AwsClients` interface implemented by `AwsClientFactory`, plus a test implementation that returns `FakeEc2`/`FakeImageBuilder`/`FakeSsm` and a fake CloudFormation client. Then write one ordering test per invariant. This is cheaper than T1 because the fakes already exist (`infra/FakeEc2.java`, `FakeImageBuilder.java`, `FakeSsm.java`).

### T3: Runner command layer is nearly untested · Should · S
- **Location:** `benchmark-runner/src/main/java/pl/wsztajerowski/commands/ApiJmhOptions.java` (203 lines, picocli → JMH options), `JmhWithAsyncProfilerSubcommand.java` (81), `JmhWithProfilerSubcommand.java`, `JmhSubcommand.java`, `TestWrapper.java`, `JCStressHtmlResultParser.java` (128) and `HtmlParser.java` (72).
- **Evidence:** `commands` has a 0.16 test/code ratio. Its only tests are `ApiCommonSharedOptionsTest` (113 lines) and `JCStressSubcommandTest.java:15-21` (one parse test). The HTML parser runs only inside `JCStressSubcommandServiceIT`, which needs Docker and Mongo and runs real JCStress. A dropped or renamed JMH flag (`-wi`, `-f`, `-prof`) would pass every unit test and surface only in a paid job's numbers. That is the false-green class the Necessity scale calls *Must*, held down to *Should* only because the e2e runs `jmh-with-async`.
- **Recommendation:** (a) table tests that `parseArgs` each subcommand and assert the resulting JMH `Options`/argv; (b) a checked-in JCStress `index.html` fixture for `JCStressHtmlResultParser`. Neither needs Docker. Keep these separate from C4 (`retire-mongodb`), which moves the ITs but adds no unit coverage here.

### T4: Duplicated `JobRecorder` fakes with no contract · Should · S
- **Location:** `baas-cli/src/test/java/pl/wsztajerowski/baas/jobs/JobSessionTest.java:26` (`FakeRecorder`, reused by `JobTerminationTest.java:10`), `commands/RunCommandDetachTest.java:39-86` (`Recorder`), `:89-106` (`Instances`, a second copy of `JobSessionTest.java:95`).
- **Evidence:** Both fakes re-implement "first outcome wins / refuse over a terminal status" by hand (`RunCommandDetachTest.java:50-69`). The real rule is a DynamoDB condition expression (`DynamoDbJobRecorder.NOT_TERMINAL`), checked only by the Docker-gated `DynamoDbJobRecorderIT`. If the real expression changes, every fake-backed test stays green. The runner already solves this for its store: `ResultsStoreContractTest` runs against both adapters.
- **Recommendation:** add one shared `InMemoryJobRecorder` in the test sources and an abstract `JobRecorderContract`, extended by a unit test (in-memory) and by `DynamoDbJobRecorderIT`. Delete the second `Recorder`/`Instances`.

### T5: No common command-test harness · Could · S
- **Location:** `IFactory` + anonymous override in `ResultsQueryPipelineTest.java:55-96`, `ResultsProjectSelectionTest.java:152-195`, `JobsShowCommandTest.java:40-72`, `JobsDiffCommandTest.java:61-96`, `ConfigSyncSubcommandTest.java:164-226`, `admin/TeardownConfigFileTest.java:52-77`.
- **Evidence:** Testing a new `jobs <verb>` today takes these steps:
  1. Write a deployment file (`TestDeployments.write`).
  2. Hand-write a ~20-line `CommandLine.IFactory` that intercepts one class.
  3. Subclass the command anonymously, overriding a package-private hook. The command must *have* one, and each picks its own: `sources`, `openResults`, `jobLookup`, `removeDeployment`, `aws`/`stackOutputs`.
  4. Return either a port fake or an anonymous subclass of a concrete service built with a `null` client. `new ResultsQueryService(null, table) {…}` appears at `ResultsQueryPipelineTest.java:73`, `ResultsProjectSelectionTest.java:31,169` and `JobsDiffCommandTest.java:67`.
  5. Set `command.console`.
  6. Wire `setOut`/`setErr`.
  7. Swap `System.err` to see log lines.

  That is 40+ lines before the first assertion. The longest test methods in the suite consist almost entirely of this setup.
- **Recommendation:** add a `TestCli` helper, e.g. `TestCli.in(dir).with(JobsShowSubcommand.class, cmd).run("jobs","show",id) → Run(exit,out,err)`, plus one convention: each command exposes a single `Ports ports(BaasConfig)` hook. Subclassing concrete services with a `null` client breaks as soon as a constructor validates its argument.

### T6: Positional constructors rebuilt in every test · Could · S
- **Location:** private `ResultRow` builders in `results/ResultsGroupingTest.java:12,18,23`, `results/ResultsFiltersTest.java:12`, `commands/ConsoleOutputTest.java:47`, `console/TableTest.java:119` and `commands/ResultsFormatTest.java:40`, against the 9/10/11-argument constructors of `ResultRow` (`results/ResultRow.java:14`). `new JobItem(` takes 10 positional arguments and appears at 11 sites in 7 files. `UserDataScriptBuilder.build` takes 16 positional arguments (`UserDataScriptBuilderTest.java:38-46`, `RunCommand.java:426-431`).
- **Evidence:** When `params` was added to `ResultRow`, overloads were added to keep these helpers compiling. A positional `String, String, String` list (region, bucket, type, jobId, path, createdAt, key) can be transposed silently.
- **Recommendation:** shared test-data builders (`TestRows.row().benchmark(…).mode(…)`, `TestJobs.job().status(…)`) and a parameter record for `UserDataScriptBuilder.build`. This is also what lets the T1 refactor land without editing 30 call sites.

### T7: Sleep-based concurrency assertion · Could · XS
- **Location:** `jobs/JobSessionTest.java:328-329`.
- **Evidence:** The test runs `second.start(); Thread.sleep(100);` and then asserts that `second.getState()` is BLOCKED or WAITING. On a loaded CI runner the thread may still be RUNNABLE after 100 ms, which gives a false red. Every other wait in the class uses latches.
- **Recommendation:** poll the state until it is BLOCKED or WAITING, with a deadline, or have the fake count down a latch when it enters the lock.

### T8: Exact call count pinned to the policy's shape · Could · XS
- **Location:** `infra/DeployerPreflightTest.java:97-105` (`assertThat(iam.requests).hasSize(13)`).
- **Evidence:** The test guards the 56→13-call speed-up (`1c97606`), but it couples to the rendered policy. Adding any statement to `deployer-policy.json` breaks a test whose intent is "at most two calls per statement", as its own name says.
- **Recommendation:** assert `≤ 2 × statements.size()`, derived from the rendered policy.

### T9: Global JVM state without resource locks · Could · XS
- **Location:** `System.setErr` in `DeploymentOptionTest.java:36,71`, `OptionValidationTest.java:39`, `RunCommandSummaryTest.java:42`, `admin/RunnerImageExtensionCommandsTest.java:79`, `ConsoleOutputTest.java:39,181` and `console/StatusLineTest.java:33`. `Locale.setDefault` in `ResultsFormatTest.java:63,109,178` and `console/ConsoleTest.java:83`. `TimeZone.setDefault` in `baas-model/.../ResultKeysTest.java:160`. `System.clearProperty` in `BaasAppTest.java:107`.
- **Evidence:** These are correct today only because no `junit-platform.properties` exists, so execution is sequential. Turning on parallel execution, the usual fix once the suite grows, would turn them into intermittent failures. SimpleLogger has no writer to inject (`RunCommandSummaryTest.java:33-35`), so swapping System.err is unavoidable.
- **Recommendation:** add `@ResourceLock(Resources.SYSTEM_ERR / LOCALE / TIME_ZONE / SYSTEM_PROPERTIES)` to these classes. It costs nothing now and records the constraint.

### T10: Home and credential isolation exists only in surefire · Could · XS
- **Location:** `baas-cli/pom.xml:146-166`. `new BaasApp()` (real `ConfigService.defaultRoot()`, `BaasApp.java:89-91`) is used in `RunCommandSummaryTest.java:46`, `RunCommandDetachTest.java:128,138`, `BaasAppTest`, `HelpAndHintsTest` and `ConfigSetOptionsTest`.
- **Evidence:** Under surefire, `user.home` and the AWS variables are redirected or removed. Run from an IDE, these tests see the developer's real `~/.baas` and `~/.aws`. `AWS_CONFIG_FILE` and `AWS_SHARED_CREDENTIALS_FILE` are not in the exclusion list either, so a developer who sets them gives the no-deployment path a real credential source. That path is exercised by `DeploymentOptionTest#onlyARealFailurePointsAtTheStackTrace` (0.65 s, the second-slowest test).
- **Recommendation:** add both variables to `excludedEnvironmentVariables`, and use `new BaasApp(tempDir)` everywhere the test executes, not only parses.

### T11: LocalStack setup duplicated · Could · XS
- **Location:** `"localstack/localstack:4.14.0"` appears literally in `RunnerJarResolverIT.java:30`, `S3DownloadIT.java:31`, `S3UploadServiceIT.java:26`, `ResultsQueryServiceIT.java:48` and `DynamoDbJobRecorderIT.java:47`, plus `benchmark-runner/.../TestcontainersWithS3BaseIT.java:28`. That base class also carries a stale `withLabel("java.wonderland.testcontainer", …)` at `:32`. The results-table schema is hand-built in three places: `DynamoDbJobRecorderIT.java:63-74`, `ResultsQueryServiceIT` and `TestcontainersWithDynamoDbBaseIT`. Each uses the `ResultKeys`/`MeasurementItemMapper` constants, and `CoreTemplateTest.java:444-475` pins the template to them, so the schema copies are safe.
- **Evidence:** CLAUDE.md's LocalStack pin has to be edited in six places, and each baas-cli IT class starts its own container (5 starts).
- **Recommendation:** a shared `LocalStackFixture` holding the image constant, one singleton container and a `createResultsTable(client)` helper.

### T12: ITs skip silently without Docker · Could · XS
- **Location:** `@Testcontainers(disabledWithoutDocker = true)` on all 5 baas-cli ITs and `benchmark-runner/.../TestcontainersWithS3BaseIT.java:25`.
- **Evidence:** This is the same failure mode as the documented `ASYNC_PATH` gate. A `mvn verify` on a machine with Docker stopped (the Docker-disk-full case in memory) reports green with every DynamoDB condition-expression test and every S3 test skipped. CI (`ci-pr-build.yml:49`) has Docker, so this is a local false green only.
- **Recommendation:** fail instead of skip when `CI` is set, or document the gate next to `ASYNC_PATH` in CLAUDE.md.

## Refactor safety: which suites protect and which obstruct

A "boundary refactor" here means moving rules out of picocli command classes into domain or service classes behind ports, without changing behaviour.

**Protect** these. They assert outcomes at stable boundaries and survive a move unchanged:
- **baas-model, all of it** (`ResultKeysTest`, `MeasurementItemMapperTest`, `JobItemMapperTest`, `JobLayoutTest`, `JobIdTest`, `TagKeysTest`, …). These are pure functions on the shared contract.
- **Artefact suites:** `CoreTemplateTest`, `DeployerPolicyTest`, `OperatorPolicyDriftTest`, `CiTemplateTest`, `DeploymentNamesTemplateTest`, `RunnerImageRendererTest`. They assert on the rendered YAML and JSON and on what a policy *grants* (`InfraFixtures.grants`, wildcard-aware), never on Java structure.
- **`UserDataScriptBuilderTest`** asserts on the generated script and executes it in bash. The only coupling is the 16-argument `build(...)` signature at 4 call sites (T6).
- **`JobSessionTest`, `JobTerminationTest` and `JobListingTest`** test through ports (`JobRecorder`, `Instances`, `Sleeper`). This is the model to copy.
- **`ImageBuilderServiceTest`, `RunnerImageResolutionTest` and `ParentImageResolverTest`** use stateful in-memory SDK fakes (`FakeImageBuilder` keeps components, images and pipelines). They protect a move of the service, though not a change of which AWS API it calls. That coupling is intended: the `byName` preflight invariant is about the API call.
- **`CommandTreeTest`, `BaasAppTest` and `HelpAndHintsTest`** pin the `baas [--deployment] [admin] <noun> <verb>` shape, which the refactor must keep.
- **`ResultsGroupingTest`, `ResultsFiltersTest`, `EnvironmentManifestTest`, `PackagesDiffTest` and `BaasConfigYamlTest`** test pure logic.

**Obstruct** these. They address code by its *location*, so a pure move turns them red without any behaviour change:
- **`RunCommandTest`** (most of its ~45 tests) calls package-private statics on the command: `RunCommand.requireValidProject` `:63,71,91`, `resolveResultsTable` `:110-129`, `projectFromToplevel` `:19-31`, `RESERVED_TAG_KEYS` `:97`, `watchdogBound` `:452-470`, and sets `command.project` `:81`. These are exactly the rules a refactor would move into a `JobRequest`/`RunPlan` class. The fix is mechanical, but the tests give no safety while it happens.
- **The `IFactory` + override harnesses** (T5 list) depend on package-private hook names (`openResults`, `sources`, `jobLookup`, `removeDeployment`, `aws`, `stackOutputs`) and on subclassing concrete `ResultsQueryService` with a `null` client. Rename, extract or finalise any of those and the harness breaks.
- **`RunCommandSummaryTest` (20 field writes), `RunCommandDetachTest` (11) and `ConsoleOutputTest` (11)** write `command.detach`, `.format`, `.session`, `.console`, `summaryJobId`… directly (`RunCommandDetachTest.java:111-118`). This pins RunCommand's field layout.
- **`SetupCommandTest`** calls helper methods on `SetupCommand` instances (`refusalForMissingRights`, `federationParameters`, `writePolicyFile`), which pins where those helpers live.
- **Seven classes capture SimpleLogger output** (T9) and assert on log wording. They survive a move but break when a log line is reworded or a message moves between logger and `Console`, which a boundary refactor often does.

**Suggested order for a refactor:**
1. Land T6's builders and T5's `TestCli`.
2. Convert the `RunCommandTest` statics into tests on the target class *before* moving the code, so the move runs under green tests.
3. Introduce the T1/T2 ports. The first test written against them is the call-order test that the current suite lacks.
