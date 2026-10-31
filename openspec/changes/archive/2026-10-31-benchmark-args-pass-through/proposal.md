# Proposal

## Why

`benchmark-runner` still carries its own copy of the JMH and JCStress command lines (`ApiJmhOptions`,
`ApiJCStressOptions`, the async-profiler options of `jmh-with-async`) from the time it was called
directly. Only `baas run` calls it now, and the copy is lossy:

- `-p` is missing, so a `@Param` cannot be overridden.
- Plain `jmh` has no `-prof`.
- Flags were renamed (`-v` → `-bvm`, JCStress `-m` → `--mode`, with a refusal in `baas run` to match).
- The help text belongs to whichever JMH was current when it was copied, not to the JMH inside the
  user's JAR, which is the one that parses the arguments.

The runner never reads what any of these options mean. It parses them into fields and writes each back
as the flag it started as. The only arguments it genuinely needs are the five that say *where output
goes*.

## What Changes

- **BREAKING** — Everything after `baas run <type> … --` reaches `java -jar benchmark.jar` verbatim. The
  runner drops every copied JMH/JCStress option and takes `<runner options> -- <benchmark arguments>`.
  The renamed and invented flags go with it:
  - `-bvm` (JMH's `-v` again)
  - `--mode` (JCStress `-m` again)
  - the JCStress positional regex (`-t`)
  - `-pr/--profiler`
  - `-ae`, `-aot`, `-ai`, `-aap`
- Arguments BaaS manages are refused with exit 2, before any AWS call, as "managed by BaaS":
  - JMH `-rf`, `-rff`, `-o`
  - `libPath` and `dir` inside `-prof async:…`
  - `dir` inside `-prof jfr:…`
  - JCStress `-r`
- Arguments that make the benchmark print and exit without measuring are refused with exit 2 as "would
  measure nothing". The `Next` hint is the `java -jar <benchmark-jar> …` command to run locally instead:
  - JMH `-h`, `-l`, `-lp`, `-lprof`, `-lrf`
  - JCStress `-h`, `-l`, `-p`

  The `-lprof` hint says that a local run lists the laptop's profilers, and that the runner image also
  has `perf` and async-profiler.
- Both refusals recognise every spelling the benchmark's parser accepts: `-` or `--`, a value attached
  with `=`, and jopt-simple's unique-prefix abbreviations (`-pro` is `-prof`, `-lr` is `-lrf`; verified
  against the fixture JARs). One parse in `baas-model` serves the CLI's refusal, the runner's own check,
  the runner's `-prof` injection and the `profilers` tag, so none of them can disagree.
- The runner injects `dir=` into every `-prof async…` and `-prof jfr…`, and `libPath=` into every
  `-prof async…`, whatever the job type. Profiling is a plain JMH argument.
- **BREAKING** — Benchmark types collapse to `jmh` and `jcstress`. `jmh-with-async` and `jmh-with-prof`
  are removed from `baas run`, the runner and `BenchmarkType`. Old jobs' process-output names
  (`jmh-with-async-output.txt`, `jmh-profiler-output.txt`) stay recognised where stored jobs are read.
- New derived, reserved tag `profilers`: the profiler names from every `-prof`, sorted alphabetically,
  de-duplicated and comma-joined, absent when nothing is profiled. `baas run` sets it, so the job item
  and every measurement carry the same value, and rejects a caller `--tag profilers=…`.
- A profiled measurement is marked in the `baas results query` table: `*` after SCORE and one footnote
  line. `baas results diff` warns when the two jobs' profilers differ.
- Removed:
  - `RunnerArgs.SET_BY_THE_CLI` and its refusal. Text after the runner's `--` can no longer reach a
    runner option.
  - The `-m` refusal.
- `e2e-cloud-test.yml` runs `baas run jmh -- … -prof async:…` with one output format, and asserts the
  flame graph holds the benchmark method, not only that an `.html` exists.

Released as `feat!` (9.0.0).

## Capabilities

### New Capabilities
- `benchmark-arguments`: what `baas run` does with the arguments after `--`. Covers verbatim forwarding
  to the benchmark JAR, the managed and no-run refusals, the spellings they recognise, and the runner's
  profiler injection.

### Modified Capabilities
- `jcstress-execution`: the mode is JCStress's own `-m`, passed through. `--mode` and the `-m` refusal
  are gone.
- `results-store-schema`: the tag vocabulary gains the derived, reserved `profilers`, and the
  `cliParameters` example moves off `jmh-with-async`.
- `benchmark-results-query`: the table marks profiled measurements.
- `results-diff`: the JMH types to compare are just `jmh`. A new warning covers differing profilers.
- `ci-benchmark-execution`: the self-test profiles a `jmh` job instead of running `jmh-with-async`, and
  asserts that the graph has content.
- `runner-image-provisioning`: the scenarios naming `jmh-with-async` and `--async-path` now describe a
  `jmh` job profiled with `-prof async`.

## Impact

- **`baas-model`**:
  - new `BenchmarkArgs`: per-tool option names, managed and no-run lists, abbreviation resolution and
    the `-prof` parse
  - `BenchmarkType` reduced to two constants, plus a legacy output-name list
  - `TagKeys.PROFILERS`
  - `RunnerArgs.SET_BY_THE_CLI` and `firstSetByTheCli` removed
- **`benchmark-runner`**:
  - `ApiJmhOptions`, `ApiJCStressOptions`, `JmhWithProfilerSubcommand`,
    `JmhWithAsyncProfilerSubcommand`, the matching services and the `options/Jmh*`/`JCStressOptions`
    builders removed or reduced
  - `JmhBenchmarkProcessBuilderFactory` replaced by "injected flags + arguments as given"
  - `--async-path` stays as a runner option (the LocalStack scripts need it)
- **`baas-cli`**:
  - `RunCommand`: the refusals, the `profilers` tag, the removed types
  - `UserDataScriptBuilder`: the runner's `--`
  - `ArtifactView`: legacy names
  - `ResultsTable`: the marker
  - `ResultsDiff`: the warning
- **Scripts and docs**:
  - `jmh-with-async.sh` and `jmh-with-profiler.sh` move to `jmh` + `-prof`
  - `README.md` and `infra/README.md`
  - `docs/diagrams/baas-ci-e2e.mmd`
  - CLAUDE.md:
    - the `-m` gotcha
    - "Text after `--` may not name a runner option"
    - "Adding a benchmark type"
    - the `type` and `cliParameters` tag rows
    - the profiler-output paragraph
    - the user-data invariant that names the JMH profiler types
- **Users**: every invocation that used a runner-dialect flag or a removed type must be rewritten.
  Stored `cliParameters` keep the old dialect; they are a record, not a recipe. Results stored before
  the change keep `type=jmh-with-async`/`jmh-with-prof` and carry no `profilers` tag.
- **Closes**: E1. CI no longer asks for `flamegraph,jfr` and fails on a graph without the benchmark
  method. The async-profiler behaviour behind it stays the user's choice, now that the profiler string
  is theirs.
- **Cost**: no AWS resource changes. The CI self-test still launches one instance per CI job.
- **Deliberately not changed**:
  - the S3 layout (profiler artifacts land at the same keys)
  - the stored item shape and keys, and `kind` (still what `results diff` compares on)
  - every termination layer
  - the AMI: async-profiler stays at `/app/async-profiler/lib/libasyncProfiler.so`, and user-data
    installs nothing
  - running from `/app`
  - `cliParameters` remains everything after `--`, verbatim
- **Deferred**:
  - a presence or membership tag filter (`--exclude-tag profilers`)
  - a command that lists the runner image's profilers (the `-lprof` hint names it once it exists)
