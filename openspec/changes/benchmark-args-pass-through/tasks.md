# Tasks

## 1. Verify blocking assumptions

- [ ] 1.1 Record each fixture JAR's full option list and value arity from its `-h` (`fake-jmh-benchmarks.jar`, `fake-stress-tests.jar`) into the change directory. Verify: the lists contain JMH's `prof`, `lprof`, `lrf`, `rf`, `rff`, `o`, `p`, `r` and JCStress's `r`, `l`, `p`, `m`, `t`.
- [ ] 1.2 Confirm on the fixture JMH that jopt-simple resolves an ambiguous prefix as an error, not as a pick (find two options sharing a prefix, e.g. `-w`/`-wi`/`-wbs`/`-wf`/`-wm`/`-wmb`: run `-wb 1 -l` and `-wm INDI -l`, then a prefix shared by two names). Verify: the observed rule matches design.md's resolution steps, or design.md is corrected before 2.x.
- [ ] 1.3 Confirm that a `-prof async:…;dir=<d>;libPath=<p>` value with the user's own keys first is accepted by JMH's async profiler (append order), using `-l`, plus one local run of `jmh-with-async.sh` rewritten to pass-through against LocalStack (needs `ASYNC_PATH`). Verify: the artifacts land under `<d>/<benchmark-id>/`.

## 2. `baas-model`: one reading of the benchmark's arguments

- [ ] 2.1 Add `BenchmarkArgs`: per-tool option names and arities from 1.1, plus the managed and no-run names. Resolution: one or two dashes, `=` split, exact name wins, otherwise a unique prefix; the value of a required-value option is skipped. Verify: unit tests cover every spec scenario of `benchmark-arguments` "Refusals recognise every spelling", plus `-r 1s`, `-p size=10` and `-jvmArgs -Xmx2g` passing through.
- [ ] 2.2 Add `-prof` value parsing (name, options map, original text) and the managed-key check (`dir`/`libPath` for `async`, `dir` for `jfr`). Verify: unit tests for `async`, `async:event=wall;threads=true`, `--prof=jfr`, `-pro async:dir=x` (refused), and a class-named profiler (recorded, not injected).
- [ ] 2.3 Add `TagKeys.PROFILERS` to `KNOWN` and `RESERVED`, with a `profilers(List<String> args)` helper (sorted, de-duplicated, comma-joined, empty when none). Verify: `TagKeysTest` covers ordering, duplicates and absence.
- [ ] 2.4 Reduce `BenchmarkType` to `JMH` and `JCSTRESS` and add `LEGACY_PROCESS_OUTPUT_NAMES`. Remove `RunnerArgs.SET_BY_THE_CLI` and `firstSetByTheCli`. Verify: `mvn -pl baas-model verify` is green and `ModelBoundaryTest` still passes.

## 3. `benchmark-runner`: pass-through

- [ ] 3.1 Replace the `jmh`, `jmh-with-prof` and `jmh-with-async` subcommands with one `jmh` subcommand: runner options, `--async-path`, and an `@Parameters` list after `--`. Delete `ApiJmhOptions`, `JmhWithProfilerSubcommand`, `JmhWithAsyncProfilerSubcommand`, their services and the `options/Jmh*`/`AsyncProfilerOptions` records that only they used. Verify: `TestWrapper` lists `jmh` and `jcstress` only, and `RunnerArgsContractTest` passes.
- [ ] 3.2 Build the JMH command line as `java -jar <benchmark> -rf json -rff <result file> <arguments>`, with every `async`/`jfr` `-prof` value rewritten to append `dir=<work>/profiler-output` (and `libPath=` for async). Refuse managed and no-run arguments through `BenchmarkArgs` before starting the process. Verify: a rewritten `JmhOptionForwardingTest` asserts exact argv for no profiler, `-prof gc`, `-prof async`, `-pr async:event=wall` and a quoted `-jvmArgs`.
- [ ] 3.3 Upload `<work>/profiler-output` with `ProfilerArtifacts` at the job prefix root, the same keys as today. Verify: a runner IT on LocalStack with `-prof jfr` finds the recording under `<prefix>/<benchmark-id>/`; with `ASYNC_PATH` exported, the async IT finds a flame graph.
- [ ] 3.4 Do the same for `jcstress`: delete `ApiJCStressOptions`/`JCStressOptions`, inject `-r <work>/jcstress-results`, pass the rest through. Verify: `JCStressSubcommandTest` asserts argv for `-m sanity -t Foo`, and refusal of `-r x`, `-h` and `-p f`.
- [ ] 3.5 Add the contract test from design.md: run each fixture JAR's `-h` and assert that `BenchmarkArgs`' name lists and arities match. Verify: it fails when one name is removed from the embedded list, and passes once restored.
- [ ] 3.6 Rewrite `jmh-with-async.sh` and `jmh-with-profiler.sh` to `jmh -- … -prof …`. Verify: both run against LocalStack and store measurements.

## 4. `baas-cli`

- [ ] 4.1 `RunCommand`: accept only `jmh`/`jcstress`; after the reserved-tag check, refuse managed then no-run arguments (`CommandRefusal.usage`, exit 2). The no-run `Next` hint is `java -jar <jar> <args>`, plus the `-lprof` caveat. Delete the `-m` and `SET_BY_THE_CLI` refusals. Verify: `RunCommandTest` covers every `benchmark-arguments` refusal scenario, with no AWS call made (`FakeRunAws` untouched).
- [ ] 4.2 `buildRunnerTags`: add `profilers` from `BenchmarkArgs`; a caller `--tag profilers=` is refused. Verify: tests for `-prof gc -prof async:event=alloc -prof gc` → `async,gc`, for absence, and for the reservation scenario. `JobLaunchTest` shows the tag on the reservation.
- [ ] 4.3 `UserDataScriptBuilder`: put the runner's `--` before `BENCHMARK_PARAMS_ARRAY`; process-output names come from the two remaining types. Verify: the rendered script passes `bash -n`, holds `-- "${BENCHMARK_PARAMS_ARRAY[@]}"`, and `aLargeJobStaysWellUnderTheUserDataLimit` still passes.
- [ ] 4.4 `ArtifactView`: include `LEGACY_PROCESS_OUTPUT_NAMES` in the output group. Verify: a test lists an old job holding `jmh-with-async-output.txt` under "output".
- [ ] 4.5 `ResultsTable`: `*` and a padding space on JMH scores, plus the footnote when a printed row is marked. Verify: a table test asserts aligned decimal points, the mark, the footnote, its absence with no profiled row, and unchanged JSON/CSV.
- [ ] 4.6 `ResultsDiff`: a `profilers: A → B` warning (`none` for absent), plus `type`-independent JMH pairing including former types. Verify: tests for the `results-diff` delta scenarios.
- [ ] 4.7 Fix completion and help: the type candidates are `jmh` and `jcstress`, and `run`'s help text names `-prof`. Verify: the completion test and a `baas run --help` snapshot.

## 5. CI, docs and release

- [ ] 5.1 `e2e-cloud-test.yml`: the benchmark job runs `baas run jmh -- … -prof "async:event=wall;output=flamegraph;threads=true"` and fails unless a downloaded `.html` contains the fixture method `incrementUsingSynchronized`; it asserts `type=jmh` and `profilers=async`. The JCStress job uses `-- -m sanity`. Verify: `actionlint`, and the PR's CI run is green.
- [ ] 5.2 Delete E1 from `docs/review/open-findings.md` (table row and section). Verify: `grep -n E1 docs/review/open-findings.md` finds nothing.
- [ ] 5.3 Update CLAUDE.md:
  - the `-m` gotcha;
  - "Text after `--` may not name a runner option" (replace it with the managed/no-run rule);
  - "Adding a benchmark type";
  - the `type`/`cliParameters` rows, plus a `profilers` row, in the tag table;
  - the profiler-output paragraph and `logs/profiler-output/`;
  - the user-data stop-hook invariant's "differ for the JMH profiler types".

  Verify: `grep -n 'jmh-with\|--mode\|SET_BY_THE_CLI' CLAUDE.md` finds nothing left that describes current behaviour.
- [ ] 5.4 Update `README.md`, `infra/README.md` and `docs/diagrams/baas-ci-e2e.mmd`. Render the diagram with `mmdc` into the scratchpad and look at the PNG. Verify: `grep -rn 'jmh-with' README.md infra/README.md docs/diagrams` is empty, and the render reads as intended.
- [ ] 5.5 Release notes: the commit carrying the type removal has a `BREAKING CHANGE:` footer with the migration table from design.md, so the release is 9.0.0. Verify: the semantic-release dry run reports a major bump.

## 6. End-to-end verification (manual — `baas run` path)

- [ ] 6.1 On `baas-development` with the reactor build (`baas-dev`), run `jmh -- Incrementing_Synchronized -f 1 -wi 1 -i 3 -prof gc -pro "async:output=flamegraph"`. Verify: completed; `profilers=async,gc` on the job and the measurements; `gc.*` secondary metrics stored; the flame graph under the job prefix; the table shows `*` and the footnote.
- [ ] 6.2 Refusals: `-- -rf csv`, `-- -h`, `-- -lprof`, `--tag profilers=x`. Verify: each exits 2 with no instance launched (`baas jobs list` unchanged), and `-h` prints the `java -jar` hint on a terminal.
- [ ] 6.3 Comparability: run an unprofiled `jmh -- Incrementing_Synchronized -f 3 -wi 3 -i 5` three times before (8.x CLI) and three times after (reactor build) on the same instance type. Record every score and the spread in verify.md, and investigate any difference outside the observed run-to-run spread rather than accepting it (the benchmark is multimodal on c5.large: check per-iteration plateaus first).
- [ ] 6.4 `baas results diff` between a 6.1 job and an unprofiled 6.3 job. Verify: the `profilers: none → async,gc` warning appears beside the `cliParameters` one.
- [ ] 6.5 `jcstress -- -m sanity` end to end. Verify: completed with one JCStress item, and `jcstress-output.txt` shows sanity mode.

## 7. Verify

- [ ] 7.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory: a requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation from design or tasks.
