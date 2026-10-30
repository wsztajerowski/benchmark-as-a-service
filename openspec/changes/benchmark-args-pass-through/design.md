# Design

## Context

See proposal.md — Why. What shapes the approach:

- **The runner's copied options are a translation table.** Each `ApiJmhOptions`/`ApiJCStressOptions`
  field is parsed by picocli, copied into an options record, and written back by
  `JmhBenchmarkProcessBuilderFactory`/`JCStressSubcommandService` as the flag it came from. No service
  reads what one means. The runner's real needs are output locations:
  - `-rf json` and `-rff <path>`, because it reads the result JSON back;
  - JCStress `-r <dir>`, because it reads the report;
  - `dir=` for `async`/`jfr` (`JmhWithProfilerSubcommandService.getProfilerOutputOptionName`,
    `JmhWithAsyncProfilerSubcommandService.createAsyncCommand`);
  - `libPath=` for `async`.
- **User-data already passes the benchmark's arguments safely.** `UserDataScriptBuilder` writes
  `BENCHMARK_PARAMS_ARRAY` as a bash array literal, one single-quoted element per argument, so `;`,
  spaces and quotes reach the runner as data.
- **JMH and JCStress both parse with jopt-simple, which accepts unique-prefix abbreviations.** Checked
  on 2026-10-30 against `fake-jmh-benchmarks.jar` and `fake-stress-tests.jar`:
  - `-pr`, `-pro`, `-pro=x` and `--prof` all act as `-prof`;
  - `-lpro` acts as `-lprof`, and `-lr` as `-lrf`;
  - `-o out.txt` diverted all of JMH's output into `out.txt`;
  - exact names (`-r`, `-rf`, `-l`, `-p`) are never read as abbreviations;
  - JCStress lists `-p <result file>` as "will not run any tests".
- **`BenchmarkType` is used only to name things.** It names the `baas run` argument, the runner
  subcommand, the `type` tag, and the process-output file name in user-data and the runner.
  `ArtifactView` groups every type's output name. `kind`, the item keys, the GSI and measurement mapping
  never consult it.

## Goals / Non-Goals

**Goals:**
- One reading of the benchmark's arguments, shared by:
  - the CLI's refusal;
  - the runner's own refusal;
  - the runner's `-prof` injection;
  - the `profilers` tag.
- Unprofiled `jmh` and `jcstress` jobs give JMH/JCStress the same effective command line as today.

**Non-Goals:**
- Validating anything about the benchmark's arguments except the managed and no-run flags. JMH and
  JCStress keep validating their own, on the instance.
- A command listing the runner image's profilers. That is the next change; this one's `-lprof` hint is
  interim.
- Tag filters for presence or membership.

## Decisions

### `baas-model` owns one argument reader per tool, built from the tool's full option list

`BenchmarkArgs` (in `pl.wsztajerowski.baas.model`, no SDK, no JMH dependency) holds the following
for JMH and for JCStress, per `BenchmarkType`:
- every option name and whether it takes a required value;
- the managed names;
- the no-run names.

It resolves a token the way jopt-simple does:
1. Strip one or two leading dashes and split at the first `=`.
2. An exact name wins.
3. Otherwise, a prefix that begins exactly one name is that name.
4. Anything else is not an option of the tool, and passes through.

The token after an option that takes a required value is that option's value, never an option, so
`-jvmArgs -Xmx2g` is not inspected as a flag.

From that walk it returns:
- the first managed argument;
- the first no-run argument;
- the `-prof` values, as name plus options.

The CLI and the runner both call it.

*Alternatives:*
- **Match literal names only.** Rejected: `-pro async:dir=x` would get past the refusal, the injection
  and the tag alike. The verified abbreviations make this a real gap, not a theoretical one.
- **Put `jmh-core` in `baas-model` and ask its parser.** Rejected:
  - it adds a benchmark library to the shared contract;
  - JMH's `CommandLineOptions` parser is private;
  - it would still be the CLI's JMH, not the one in the user's JAR.
- **Treat any prefix of a guarded name as guarded.** Rejected: that refuses `-r` (iteration time) and
  `-p` (params), because exact options shadow longer names.

A contract test in `benchmark-runner` runs both fixture JARs' `-h` and asserts the embedded name lists
and value arities match. Bumping the JMH/JCStress version then fails the build until the lists follow.

### The runner takes `<subcommand> <runner options> -- <benchmark arguments>`, and injects only managed values

- `TestWrapper` keeps two subcommands, `jmh` and `jcstress`. Each declares its runner options (job
  identity, tags, store, bucket, `--benchmark-path`, plus `--async-path` on `jmh`) and one
  `@Parameters` list holding everything after `--`.
- Before starting the process, it calls `BenchmarkArgs` and fails with a parameter error on a managed
  or no-run argument.
- The process command line is `java -jar <benchmark> <injected> <arguments as given>`:
  - JMH injects `-rf json -rff <the runner's result file>`;
  - JCStress injects `-r <work>/jcstress-results`.
- Every `-prof` value naming `async` or `jfr` is rewritten in place:
  - `dir=<work>/profiler-output` is appended for both;
  - `libPath=<async path>` is appended for `async`;
  - `:` is added when the value had no options.

  Nothing else in the value changes, and every other argument is copied untouched.
- Picocli's own `--` puts the benchmark's arguments beyond reach of every runner option. That removes
  the reason `RunnerArgs.SET_BY_THE_CLI` existed, so it goes.

*Alternatives:*
- **Keep the runner's options and add the missing ones.** Rejected: the copy drifts again, and its help
  describes the wrong JMH version.
- **Set `LD_LIBRARY_PATH` instead of injecting `libPath`.** Rejected: implicit. The value appears in
  no recorded argument, and a JVM started with `-jvm` or a cleared environment loses it.

### The old async-profiler defaults are not carried over

`jmh-with-async` used to add `event=cpu`, `interval=9990`, `output=flamegraph` and `verbose=true`. Under
pass-through, `-prof async` gets JMH's async-profiler defaults, and the user writes
`-prof async:output=flamegraph` when they want a graph. A BaaS default would be a second, invisible
profiler configuration that `cliParameters` does not record. It is also the shape that led to E1, where
`output` was set by a flag the user could not see combined with another.

*Alternative:* inject the old defaults when the user gives none. Rejected for the same reason, and
because "when the user gives none" needs per-key merging logic for a convenience the user can type.

### Profiler artifacts keep their S3 keys; the local directory is renamed

Both injected `dir=` values point at `<work>/profiler-output`. Its contents are uploaded to the job's
prefix exactly as `ProfilerArtifacts` does today, so artifacts stay at
`jobs/<project>/<jobId>/<benchmark-id>/…`. The one visible difference concerns async-profiler's own
`.log` files: `JobLogs` ships them by path relative to `/app`, so they move from `logs/async-output/…`
to `logs/profiler-output/…`.

*Alternative:* keep the name `async-output`. Rejected: JFR recordings land there too, and the name would
then be wrong for half its contents.

### The CLI derives `profilers` from the same reading and reserves it

- `buildRunnerTags` adds `TagKeys.PROFILERS`: the `-prof` names as given, sorted, de-duplicated and
  comma-joined, or nothing when there are none.
- It joins `TagKeys.RESERVED` and `TagKeys.KNOWN`, so a caller `--tag profilers=…` is refused like
  `type`, and a query filtering on it raises no unknown-key warning.
- Because the reservation writes the CLI-side tags, the job item and every measurement carry the same
  value without the runner deriving anything.
- A profiler named by class (`-prof org.openjdk.jmh.profile.AsyncProfiler`) is recorded as written and
  not injected. Neither BaaS nor JMH canonicalises names, and that spelling is rare.

### Refusals are usage errors raised before any AWS call, after the type and tags are checked

`RunCommand` checks in this order:
1. the type (`jmh` or `jcstress`, exit 2);
2. reserved `--tag`s;
3. managed arguments;
4. no-run arguments.

Each is a `CommandRefusal.usage`, so it exits 2 before the project, the table, the image or any upload
is resolved. The no-run refusal's `Next` hint (`console.Hints`) is
`java -jar <benchmark-jar as given, shell-quoted> <the arguments after -->`. For `-lprof` it adds
the interim line: "lists this machine's profilers; the runner image also provides perf and
async-profiler". The `-m` refusal and the `SET_BY_THE_CLI` refusal are deleted.

### Two types remain, and the retired output names stay readable

- `BenchmarkType` keeps `JMH` and `JCSTRESS`. `JMH`'s working and uploaded output names are both
  `jmh-output.txt`.
- A `LEGACY_PROCESS_OUTPUT_NAMES` list (`jmh-with-async-output.txt`, `jmh-profiler-output.txt`) is
  joined into `ArtifactView`'s output group, so `jobs show` and `jobs download` still group an old job's
  output.
- User-data's `ship_logs` needs no legacy names, because it ships only the job it belongs to.

*Alternative:* keep the two constants, marked deprecated. Rejected: they would still be offered as
`baas run` types, as completion candidates and as runner subcommands.

### `jobs show` gets no dedicated Profilers line

`jobs show` already prints every tag except `project` and `type` on its own line, so `profilers=async,gc`
is shown with no change. The explore session's "Profilers line" turned out to be already covered.

### The table marks profiled rows in the score cell; the diff warns separately from `cliParameters`

- **Marker.** `ResultsTable` appends `*` to the score text of a row whose tags carry `profilers` and a
  space to every other non-JCStress score, so the right-aligned digits stay in one column. After the
  rows it prints `* measured under a profiler (see the profilers tag, -v)` when any printed row was
  marked.
- **Diff warning.** `ResultsDiff` writes a `profilers: A → B` warning, separate from the `cliParameters`
  one, which also fires whenever the profilers differ. That warning's message is about the error interval
  and says nothing about profiling perturbing the score.

### Comparability with stored results

For a job without a profiler the benchmark sees what it saw before:
- the same JAR, the same JMH or JCStress;
- `-rf json -rff …`, or `-r …` for JCStress;
- the user's flags, now in the user's order. Neither tool's results depend on flag order.

Measurement items, keys and `kind` are unchanged, so old and new `jmh` results sit side by side in
`results query`, `--best-per` and `results diff`.

Profiled jobs differ from old ones in two ways:
- they are tagged `type=jmh`, with profiling shown by `profilers`, where old ones carried
  `type=jmh-with-async`/`jmh-with-prof` and no `profilers`;
- their async-profiler settings are now whatever the user wrote.

A query that excludes profiled runs by `profilers` misses old profiled runs, which carry no such tag.
The release note says so.

## Risks / Trade-offs

- [A newer JMH in the user's JAR adds an option that makes one of our prefixes ambiguous, or shadows a
  managed name] → JMH itself then rejects the ambiguous prefix and the job fails loudly. The contract
  test keeps the embedded lists in step with the JMH version BaaS builds against.
- [A JCStress `-l false` is refused although it would run tests] → accepted. Nobody writes it, and the
  refusal names the flag.
- [Every user invocation using the runner dialect breaks at once] → `feat!` with a migration table in
  the release notes:

  | Old | New |
  |---|---|
  | `--mode` | `-m` |
  | `-bvm` | `-v` |
  | JCStress positional regex | `-t` |
  | `jmh-with-async -- -ae wall -aot flamegraph` | `jmh -- -prof async:event=wall;output=flamegraph` |
  | `jmh-with-prof -- -pr gc` | `jmh -- -prof gc` |

  Unknown runner-dialect flags fail at the instance in JMH's own words. The CLI does not keep a list of
  old flags to translate.
- [A refusal message shows the user's own argument in a shell-quoted hint] → the hint is printed to
  stderr's `Next` block only on a terminal, like every other hint, and is never executed.
- [`-prof async` without `output=` yields JMH's default text output, not a graph] → documented in the
  README's profiling section. The CI self-test names `output=flamegraph` explicitly.

## Migration Plan

1. Ship in one release, `feat!` → 9.0.0. The CLI pins the runner JAR to its own version
   (`releases/<version>/benchmark-runner.jar`), so a 9.x CLI always runs a 9.x runner and an 8.x CLI an
   8.x runner. No mixed pair exists.
2. No infrastructure, image or stack change, so `baas admin deployment setup` is not required by this
   change. The CLI version floor rises only when an operator runs setup with 9.x.
3. Rollback: reinstall the 8.x CLI. Jobs stored by 9.x read fine with it; they carry one more tag, and
   their output file is `jmh-output.txt`, which 8.x already knows.
