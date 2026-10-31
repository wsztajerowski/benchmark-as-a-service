# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 On Linux with async-profiler unpacked (CI's `ci-pr-build.yml` recipe, or a Docker
      `amazoncorretto:25` container), run the fixture JAR with both `LD_LIBRARY_PATH` and
      `JAVA_LIBRARY_PATH` set to the async lib dir and no `libPath`: `-lprof` lists `async` under
      *Supported*, and `-prof async` on one benchmark (`-f 1 -wi 0 -i 1`) loads it in the fork and
      writes its output. Repeat with `JAVA_LIBRARY_PATH` alone to record whether Linux reads it (either
      answer is fine). Record the outputs in `verify.md`; if the first run fails, stop and revisit the
      decision
- [x] 1.2 Copy the macOS evidence from design.md *Context* into `verify.md` (both variables, `-lprof`
      and a forked `-prof async`, `/usr/bin/java` stripping `DYLD_LIBRARY_PATH`), rerunning it once with
      the fixture JAR to confirm it still holds
- [x] 1.3 Confirm JMH 1.37 resolves a unique profiler-key prefix (`-prof async:libP=/nonexistent`
      fails loading `/nonexistent`, not as an unknown option) and rejects an ambiguous one
      (`-prof async:di=/tmp`). Record the output; if prefixes do not resolve, the prefix rule is still
      kept (it fails safe) but the design's rationale is corrected
- [x] 1.4 Confirm `-lprof` with the fixture JAR exits 0 and writes no file at the `-rff` path when
      none is given, and that `-lprof` with `-rf json -rff x.json` also writes no `x.json`

## 2. Argument reading (`baas-model`)

- [x] 2.1 Remove `lprof` from JMH's no-run set and add a listing predicate to `BenchmarkArgs`
      (exactly one occurrence, resolving to `lprof`, nothing else after `--`) plus a refusal reason
      for `-lprof` with other arguments; unit tests cover `-lprof`, `--lprof`, `-lpr`, `-lprof X`,
      `-lprof -prof gc`, and that `-l`, `-lp`, `-lrf`, `-h` are still no-run
- [x] 2.2 Make the managed-profiler-key check refuse every key that is a prefix of a managed key
      (`l`…`libPath`, `d`…`dir`), for `async` and `jfr`, also when the profiler is named by its class;
      unit tests cover `libP=`, `li=`, `di=` (jfr), exact keys, and that `direction=` passes
- [x] 2.3 Update the `MANAGED_PROFILER_KEYS` comment: `libPath` is refused, no longer appended; the
      "any spelling gives JMH the key twice" backstop now holds for `dir` only. `BenchmarkArgsContractIT`
      still passes

## 3. Runner (`benchmark-runner`)

- [x] 3.1 Let `BenchmarkProcessBuilder` carry environment variables, and have `JmhSubcommandService`
      set both `LD_LIBRARY_PATH` and `JAVA_LIBRARY_PATH` to `asyncPath().getParent()` (each prepended to
      any existing value) on every JMH process, with no OS check; a unit test asserts both variables and
      that no `libPath=` appears in the command line
- [x] 3.2 Drop the `libPath` append from `managedProfilerOptions` (`async` and `jfr` both get `dir`
      only); keep the missing-library check for `-prof async`; update the existing command-line tests
- [x] 3.3 Refuse `-lprof` beside other arguments in `BenchmarkArguments`, and add a listing branch to
      `JmhSubcommandService.executeCommand`: no `-rf`/`-rff`, upload process output and logs, no
      result JSON, no store write, non-zero exit fails as today; an IT on LocalStack runs the fixture
      JAR with `-lprof` and asserts `jmh-output.txt` is uploaded and no measurement is written
- [x] 3.4 Run `JmhAsyncProfilerIT` with `ASYNC_PATH` exported on macOS (locally) and on Linux (CI's PR
      build) and confirm it ran, not skipped, and passed on both

## 4. CLI (`baas-cli`)

- [x] 4.1 `RunCommand.refuseManagedOrNoRun`: refuse a mixed `-lprof` with exit 2 before any AWS call;
      remove `localCommandCaveat`; unit tests cover the refusal and that `-lprof` alone passes
- [x] 4.2 After a completed listing job in text mode, fetch `jmh-output.txt` (key from `JobLayout` and
      `BenchmarkType.JMH.processOutputName()`) and write it verbatim to `Console`, in place of the
      table, under a log line naming the job id; JSON summary unchanged; unit test with a fake source
      asserts byte-for-byte output and that no table is printed
- [x] 4.3 Hints after a listing job: only `artifacts  baas jobs download <jobId>`; detached unchanged;
      unit test on `hints`
- [x] 4.4 Update `run`'s help text: `-- -lprof` lists the runner's profilers, and stands alone

## 5. Docs

- [x] 5.1 `.claude/rules/benchmark-runner.md`: no-run list without `-lprof`; `-lprof` alone is a listing
      job; async-profiler reaches JMH through `LD_LIBRARY_PATH` and `JAVA_LIBRARY_PATH` (why both, and
      why not `DYLD_LIBRARY_PATH`), `libPath` refused; delete the "a user's own spelling of a managed
      profiler key slips the check" sentence
- [x] 5.2 `.claude/rules/results-store.md`: a listing job has a job item and `jmh-output.txt` but no
      measurements and no `jmh-result.json`; check `CLAUDE.md` needs no change (no invariant in it moves)
- [x] 5.3 Update `docs/diagrams/` only if a sequence diagram shows `libPath` injection or the no-run
      refusal (grep); render any edited `.mmd` with `mmdc` and look at the PNG before committing

## 6. End-to-end verification (manual — no automated test covers the `baas run` path)

- [x] 6.1 With the reactor CLI (`baas-dev` alias, `--runner-jar`), run `baas run --benchmark-jar
      <fixture> jmh -- -lprof`: listing printed verbatim on stdout, `async` and `perf` under *Supported*,
      status `completed`, only the `jobs download` hint, `results query --job-id` finds nothing. Repeat
      with `--format json | jq .` and confirm one object. Record the outputs in `verify.md`
- [x] 6.2 Run `-- -lprof MyBenchmark` and `-- MyBenchmark -prof async:libP=/x`: both exit 2 and launch
      nothing
- [x] 6.3 Run one fixture benchmark with `-prof async:output=flamegraph` and confirm the flame graph is
      under the job's prefix (`jobs download`)
- [x] 6.4 Comparability: run the same fixture benchmark without profilers before (installed release) and
      after (reactor build), same instance type, at least three jobs each; record every score and the
      spread in `verify.md`, and investigate any difference outside the recorded spread rather than
      accepting it

## 7. Verify

- [ ] 7.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory — a
      requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation
      from design or tasks
