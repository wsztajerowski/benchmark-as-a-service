# Verify: benchmark-args-pass-through

Run 2026-10-31 on branch `benchmark-args-pass-through`. Paid jobs used **8 of the 20 allowed**, all
c5.large on `baas-development`, all `hidden=true`.

## Summary

| Dimension | Status |
|---|---|
| Completeness | 37/37 tasks; 7 capabilities and 14 requirement blocks (ADDED, MODIFIED, REMOVED) implemented |
| Correctness | Every requirement has code and an automated or live check; one scenario's wiring is covered live only (W2) |
| Coherence | Design followed; deviations D1–D6 below, each recorded in design.md or explained here |

**Builds:**
- `baas-model`: 126 tests pass.
- `benchmark-runner`: 66 unit tests and 17 ITs pass, with `ASYNC_PATH` exported, so `JmhAsyncProfilerIT`
  ran.
- `baas-cli`: 1210 tests pass.
- Full reactor `mvn verify` in a clean worktree at the branch head: BUILD SUCCESS at 0e47f6b (model 126, runner 66 + 17 ITs, CLI 1211 + 56 ITs; the two tests added during verify ran in their modules afterwards).

## Requirement → code → test → gap

| Requirement | Code | Test / evidence | Gap |
|---|---|---|---|
| **benchmark-arguments** · The benchmark's arguments reach the benchmark JAR verbatim | `JmhSubcommand`/`JCStressSubcommand` `@Parameters` after `--`; `JmhSubcommandService.jmhProcess`; `JCStressSubcommandService.jcstressProcess`; `UserDataScriptBuilder` `-- "${BENCHMARK_PARAMS_ARRAY[@]}"`; `BenchmarkType` has two constants | `BenchmarkArgumentsTest.everyArgumentAfterTheSeparatorIsKeptAsGiven` (`-p`, `-v EXTRA`, a spaced `-jvmArgs`, `;` in `-prof`, `--tag type=x`); `JmhCommandLineTest.theArgumentsFollowTheResultFlagsUnchanged`; `UserDataScriptBuilderTest.theBenchmarkParametersFollowTheRunnersSeparator`; `OptionValidationTest.aRetiredTypeIsRefused`. Live: 6.3 `cliParameters` identical to the pre-change CLI's | `-p` producing one measurement per value is covered by the existing `@Param` ITs (`Summing_Array`), not by a `-p` override |
| Arguments BaaS manages are refused | `BenchmarkArgs.firstManaged`; `RunCommand.refuseManagedOrNoRun`; runner `BenchmarkArguments.refuseManagedOrNoRun` | `BenchmarkArgsTest.aManagedArgumentIsFoundInEverySpellingJmhAccepts`; `RunCommandTest.aManagedArgumentIsRefusedNamingIt`; `RunCommandLaunchTest.aManagedArgumentIsRefusedAfterTheDoubleDashBeforeAnyAwsCall` (no AWS event); `BenchmarkArgumentsTest.aManagedArgumentIsRefusedBeforeAnythingRuns` (exit 2). Live 6.2: `-rf csv`, `-pro async:dir=…` and `jcstress -r` exit 2 with nothing launched | — |
| Arguments that would measure nothing are refused with the local command | `BenchmarkArgs.firstNoRun`; `RunCommand.localCommand`/`localCommandCaveat` → `hints` | `RunCommandTest.aNoRunArgumentIsRefusedWithTheLocalCommand`, `helpCarriesNoProfilerCaveat`; `RunCommandLaunchTest.aNoRunArgumentIsRefusedAfterTheDoubleDashBeforeAnyAwsCall`; `BenchmarkArgumentsTest.aNoRunArgumentIsRefusedBeforeAnythingRuns`. Live 6.2: `-h` and `-lprof` exit 2; under a pseudo-terminal the `Next` block shows `run it here  java -jar …` and the runner-image line | The hint prints before the `ERROR` line, as every `baas run` failure hint already does (W4) |
| Refusals recognise every spelling the benchmark's parser accepts | `BenchmarkArgs.read`, `readLong`, `readShort` (exact name, unique prefix, cluster) | `BenchmarkArgsTest` (`--rf=csv`, `-oout.txt`, `-pro`, `-lr`, `-lpro`, `-hl`, ambiguous `-jvmA`, required-value skipping, optional values); `BenchmarkArgsContractIT` (option lists equal the fixture JARs' `-h`). Live 6.1: `-pro async:output=flamegraph` was injected and tagged `profilers=async,gc` | — |
| Profilers that write files are pointed at the job's artifacts | `JmhSubcommandService.managedProfilerOptions`; `BenchmarkArgs.withProfilerOptions`; `JmhRun.profilerOutput`; `ProfilerArtifacts.upload` | `JmhCommandLineTest.asyncAndJfrArePointedAtTheProfilerOutputAfterTheUsersKeys`, `asyncWithoutTheLibraryFailsBeforeTheBenchmarkStarts`; `JmhProfilerArgumentsIT` (gc+comp+cl+jfr, gc alone, a `@Param` sweep with `-pro jfr`); `JmhAsyncProfilerIT`. Live 6.1: graphs at `<prefix>/<benchmark>-Throughput/flame-cpu-{forward,reverse}.html` naming `incrementUsingSynchronized`, plus `gc.*` metrics stored | — |
| **jcstress-execution** · A JCStress job's mode is JCStress's own `-m` (ADDED) / A JCStress job can select its mode (REMOVED) | `JCStressSubcommandService.jcstressProcess`; `--mode` and the `-m` refusal deleted | `JCStressSubcommandServiceTest.theArgumentsReachJCStressAsGivenAfterTheReportDirectory`, `noModeAddsNoModeArgument`; `JCStressSubcommandTest`; `OptionValidationTest.jcstressesOwnModeFlagPasses`. Live 6.5: `Test preset mode: "sanity"`, one JCStress item, `cliParameters=-m sanity` | — ("the former runner option is not translated": `JCStressSubcommandServiceTest.theFormerModeOptionReachesJCStressUnchanged`, `RunCommandTest.theFormerModeOptionIsNotRefused`, added during verify) |
| **results-store-schema** · Tags are the queryable dimensions… | `TagKeys.PROFILERS` in `KNOWN`/`RESERVED`; `BenchmarkArgs.profilersTag`; `RunCommand.buildRunnerTags` → reservation (`JobLaunch:160`) and runner `--tag` | `TagKeysTest`; `RunCommandTest.recordsTheProfilersSortedWithoutTheirOptions`, `anUnprofiledJobCarriesNoProfilersTag`, `aCallerCannotClaimOrDenyProfiling`; `BenchmarkArgsTest.theOrderProfilersAreGivenInDoesNotChangeTheTag`. Live: `profilers=async,gc` on the job item (`jobs show`) and on the measurement; unprofiled jobs have no key; `--tag profilers=x` refused | A reserved-tag refusal exits 1 (D5) |
| **benchmark-results-query** · Output carries a measurement's tags, and the table omits the score error | `ResultsTable.print` (`profiled`, mark/padding, `PROFILED_FOOTNOTE`) | `ResultsFormatTest.aProfiledScoreIsMarkedAndTheDigitsStayAligned`, `aTableWithNoProfiledRowHasNoMarkAndNoFootnote`. Live 6.1: `63821951,491* ops/s` with the footnote; JSON score plain, `tags.profilers` present | "Machine formats carry no mark" is covered only by `printJson` being untouched plus the live JSON |
| **results-diff** · Measurements from different libraries are never compared; The payload opens with each job's instance type and benchmark type | unchanged: pairing is by `kind` | Existing `ResultsDiffTest`/`ResultsDiffCommandTest` cases with `jmh-with-prof`/`jmh-with-async` rows. Live 6.4: `type jmh` in both headers | — |
| Different profilers are warned about (ADDED) | `ResultsDiff.profilerDifference`; `ResultsDiffSubcommand` warning | `ResultsDiffTest.differentProfilersAreReportedAndAnUnprofiledSideIsNone`. Live 6.4: `The jobs ran under different profilers (profilers: none → async,gc)` beside the `cliParameters` warning | The subcommand's wiring is tested live only (W2) |
| **ci-benchmark-execution** · The mechanism is not narrowed…; The self-test covers the image-dependent benchmark type… | `e2e-cloud-test.yml`: `jmh` with `-prof async:…;output=flamegraph`, a graph-content check, `type`/`profilers` assertions, JCStress `-m sanity` | `actionlint` 1.7.7 clean (shellcheck included) | The workflow itself runs on the PR (W1) |
| **runner-image-provisioning** · The image declares the kernel tunables…; The runner installs no tooling at run time | `JmhSubcommand --async-path` default `/app/async-profiler/lib/libasyncProfiler.so`, injected as `libPath` | Live 6.1: `-prof async` on the AMI produced full graphs with no `perf_event_open` error | — |

## Comparability (task 6.3)

`Summing_Array -f 3 -wi 3 -i 5 -w 2s -r 2s`, 3 runs before (reactor build of `origin/next-release`
c7191ec, CLI and runner) and 3 after, launched together. c5.large placed them on two CPU models, so
compare within a model:

| CPU | size | before (M ops/s) | after (M ops/s) |
|---|---|---|---|
| 8124M (Skylake) | 1 | 451.8 ± 0.2, 451.1 ± 3.6 | 452.0 ± 0.2, 451.7 ± 0.5 |
| 8124M | 2 | 398.8 ± 0.1, 398.1 ± 3.0 | 398.5 ± 0.7, **423.9 ± 39.2** |
| 8275CL (Cascade Lake) | 1 | 509.0 ± 5.2 | 506.2 ± 3.2 |
| 8275CL | 2 | 458.1 ± 2.5 | 455.6 ± 2.8 |

- **The outlier was investigated, not accepted.** after-2 at `size=2`: forks 1–2 measured
  `[399,398,398,399,398]` and `[399,399,398,398,398]`, the same as every before fork; fork 3 settled
  at `[467…481]`. It is a per-fork JIT plateau. Before forks pass through the same 420–450M plateaus in
  warmup. Both sides report `VM options: <none>`.
- **Same-model diff:** `baas results diff` of before-1 against after-3 gives −0.0 % and −0.1 %, `~`.
- **No `cliParameters` warning** between those two jobs: the old and new CLIs recorded identical
  parameters.
- **The CPU split is a property of c5.large placement,** not of this change: 8275CL is about 12 %
  faster on both sides.

No measurement difference is attributable to the change.

## Deviations from design and tasks

- **D1 — The reader also follows single-letter clusters.** Found in task 1.2: `-oout2.txt` wrote
  `out2.txt`. design.md was corrected before implementation.
- **D2 — A managed profiler key is refused by its exact name only.** An abbreviated key (`di=`) or a
  dashed one (`-dir=`) slips the pre-launch check. The job still fails at profiler start: the runner
  appends the key, and JMH refuses a key given twice or an ambiguous one (exit 1, recorded in
  `option-lists.md`). This fails safe, at the cost of a paid boot for someone who goes out of their way.
- **D3 — The refusal order differs from the design.** The design ordered type, reserved tags, managed,
  no-run. The code checks type, managed, no-run, then reserved tags inside `buildRunnerTags`. All of
  them still come before any AWS call.
- **D4 — Small structural changes:**
  - The `profilers` helper lives in `BenchmarkArgs.profilersTag`, not `TagKeys` (task 2.3), because
    `TagKeys` holds names, not parsing.
  - The option-list contract test is an IT (`BenchmarkArgsContractIT`), because the fixture JARs are
    copied only in `pre-integration-test`.
- **D5 — `--tag profilers=…` exits 1.** It goes through the existing reserved-tag check, which exits 1
  for every reserved key. The spec requires a non-zero exit with nothing launched, which holds.
- **D6 — Task 6.3 used a different benchmark and settings.** It used `Summing_Array` with 2 s
  iterations on c5.large instead of `Incrementing_Synchronized`, which is multimodal on c5.large.
  Task 5.5 was checked with the pinned release plugins run locally (`@semantic-release/commit-analyzer`
  13.0.1, `release-notes-generator` 14.1.1, `conventionalcommits` 9.3.1), which resolve **major** with
  clean notes, rather than a `semantic-release --dry-run`, which needs the release token. The two
  `BREAKING CHANGE:` commits gained the `---` separator before their trailers, so `Co-Authored-By` does
  not leak into the notes.

## Warnings

- **W1 — The CI self-test of this change runs on the PR.** That covers the graph-content check against
  the real AMI, and the `type`/`profilers` assertions. Live 6.1 exercised the same path by hand.
- **W2 — One scenario's wiring is verified live only.** The `results diff` profilers warning through
  the subcommand has no automated test. `ResultsDiff.profilerDifference` is unit-tested, and the
  subcommand writes it with one `logger.warn`, seen live in 6.4. The `--mode` pass-through gap was
  closed during verify with two tests.
- **W3 — `profiler-output` is not cleared between local runs.** A local `jmh-with-async.sh` run after
  `jmh-with-profiler.sh` re-uploads the earlier `profile.jfr`. The old `async-output` behaved the same,
  and on an instance the directory is always new.
- **W4 — A no-run refusal's `Next` hint prints above its `ERROR` line.** That is the order every
  `baas run` failure hint already has: hints are written in `call()`'s `finally`, and the error is
  logged after.

## Code review (2026-10-31, `/code-review high`)

Eight findings, each checked against the code.

**Fixed:**
- **R1** — A profiler named by its class (`org.openjdk.jmh.profile.JavaFlightRecorderProfiler`) got no
  `dir=`, so its recording was never uploaded, and the async class got no library. Now
  `Profiler.canonicalName()` maps both to `async`/`jfr`, for injection, the managed-key check and the
  tag. Tests: `BenchmarkArgsTest.aClassNamedAsyncOrJfrIsManagedLikeItsShortName`,
  `JmhCommandLineTest.aClassNamedJfrIsPointedAtTheProfilerOutputToo`.
- **R2** — Rows stored under `jmh-with-async`/`jmh-with-prof` counted as unprofiled. Now
  `ResultRow.profilers()` reads the retired types as `async`/`unrecorded`, for both the `*` and the diff
  warning. Tests: `ResultsFormatTest.aRowOfARetiredProfiledTypeIsMarkedAndJcstressCellsKeepTheirEdge`,
  `ResultsDiffTest.aJobOfARetiredProfiledTypeCountsAsProfiled`.
- **R3** — The `java -jar …` hint left `*` and `;` bare. Now `RunCommand.shellWord` quotes anything
  outside `[A-Za-z0-9_./:=,@%+-]`. Test: `RunCommandTest.theLocalCommandQuotesShellMetacharacters`.
- **R4** — Profiler artifacts were uploaded only after a successful exit. The upload now precedes the
  exit check. Test: `JmhCommandLineTest.profilerArtifactsAreUploadedBeforeAFailedExitIsReported`.
- **R5, in part** — JCStress verdicts are padded like unmarked scores, so a mixed table keeps its right
  edge (same table test as R2).

The spec deltas `benchmark-arguments`, `benchmark-results-query` and `results-diff` gained the matching
text and scenarios.

**Declined:**
- **R6 — Refuse any unresolvable dash token before launch.** The spec decided `-- --mode sanity`
  reaches JCStress and fails there. The option lists are JMH 1.37's, so a newer JMH in the user's JAR
  would have real options refused. The cost is one paid failed job per mistyped flag, and the failure
  names the flag.
- **R7 — Score overflow above 1e10 with the mark.** A 15-character `%.3f` already overflowed the
  14-wide column before this change.
- **R8 — The runner deriving `profilers` for direct invocations.** Only the LocalStack scripts call the
  runner directly; every `baas run` job, CI included, is tagged by the CLI.
- **R9 — Merge `workingOutputName`/`processOutputName`.** It is cleanup that touches user-data for no
  behaviour change.

## Final assessment

No critical issues. Four warnings, none blocking; review findings R1–R5 fixed. Ready for archive once
the PR's CI is green.
