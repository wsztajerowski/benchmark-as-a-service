# Spec Delta

## Purpose

What `baas run` does with the arguments after its `--`: they reach the benchmark JAR's own JMH or
JCStress parser unchanged, except for the few that BaaS manages itself and those that would make a paid
job measure nothing, which are refused before anything is launched.

## ADDED Requirements

### Requirement: The benchmark's arguments reach the benchmark JAR verbatim
Every argument following `baas run <type> … --` SHALL be passed to the benchmark JAR's own entry point
on the instance, unchanged, in order, and as separate arguments, whatever characters they contain. The
runner SHALL NOT parse, rename, validate or drop any of them. Only `jmh` and `jcstress` SHALL be accepted
as types. The only arguments added SHALL be the managed ones the runner injects (see the next two
requirements). The runner SHALL take its own options before its own `--` separator and the benchmark's
arguments after it, so no benchmark argument can be read as a runner option.

#### Scenario: A JMH option the runner never knew is honoured
- **WHEN** `baas run jmh … -- MyBenchmark -p size=10,100 -f 1` is run
- **THEN** JMH on the instance receives `-p size=10,100`, and the stored measurements are one per value of
  `size`

#### Scenario: JMH's own spelling is used
- **WHEN** `baas run jmh … -- MyBenchmark -v EXTRA` is run
- **THEN** JMH receives `-v EXTRA`, and the job is not refused

#### Scenario: Arguments with spaces and semicolons survive
- **WHEN** `baas run jmh … -- MyBenchmark -jvmArgs "-Xmx2g -XX:+UseZGC" -prof "async:event=wall;threads=true"`
  is run
- **THEN** JMH receives `-jvmArgs` with the single value `-Xmx2g -XX:+UseZGC`, and `-prof` with
  `async:event=wall;threads=true` plus the injected managed keys

#### Scenario: A benchmark argument cannot reach a runner option
- **WHEN** `baas run jmh … -- MyBenchmark --tag type=x` is run
- **THEN** `--tag type=x` is passed to JMH, which rejects it as unknown, and the job's stored `type` tag
  is still `jmh`

#### Scenario: A removed type is refused
- **WHEN** `baas run jmh-with-async … -- MyBenchmark` is run
- **THEN** it exits 2 listing `jmh` and `jcstress` as the valid types, before any AWS call

### Requirement: Arguments BaaS manages are refused
`baas run` SHALL refuse, with exit 2 and before any AWS call, an argument after `--` that sets something
BaaS manages, naming the argument and saying it is managed by BaaS. For `jmh` these SHALL be the
result format (`-rf`), the result file (`-rff`), the output file (`-o`), and the keys `dir` and
`libPath` within an `async` profiler's options and `dir` within a `jfr` profiler's options. For
`jcstress` it SHALL be the report directory (`-r`). The runner SHALL apply the same refusal before
starting the benchmark, so a job launched without the CLI is refused alike.

#### Scenario: A result format is refused
- **WHEN** `baas run jmh … -- MyBenchmark -rf csv` is run
- **THEN** it exits 2 saying `-rf` is managed by BaaS, and nothing is uploaded or launched

#### Scenario: A profiler output directory is refused
- **WHEN** `baas run jmh … -- MyBenchmark -prof async:dir=/tmp/x` is run
- **THEN** it exits 2 saying the `dir` of the `async` profiler is managed by BaaS

#### Scenario: A JCStress report directory is refused
- **WHEN** `baas run jcstress … -- -r /tmp/report` is run
- **THEN** it exits 2 saying `-r` is managed by BaaS

#### Scenario: JMH's iteration time is not mistaken for a managed flag
- **WHEN** `baas run jmh … -- MyBenchmark -r 1s` is run
- **THEN** the job is launched, and JMH receives `-r 1s`

#### Scenario: The runner refuses alike
- **WHEN** the runner is invoked directly with `jmh … -- MyBenchmark -rff /tmp/x.json`
- **THEN** it exits non-zero naming `-rff` as managed, and runs no benchmark

### Requirement: Arguments that would measure nothing are refused with the local command
`baas run` SHALL refuse, with exit 2 and before any AWS call, an argument after `--` that makes the
benchmark print information and exit without measuring. For `jmh` these SHALL be `-h`, `-l`, `-lp`,
`-lprof` and `-lrf`; for `jcstress`, `-h`, `-l` and `-p`. The refusal SHALL say that the job would
measure nothing, and its next-step hint SHALL be the command that runs the same arguments against the
named benchmark JAR locally. For `-lprof` the hint SHALL also say that a local run lists the local
machine's profilers, and that the runner image additionally provides `perf` and async-profiler.

#### Scenario: Help is refused and the local command offered
- **WHEN** `baas run --benchmark-jar build/b.jar jmh -- -h` is run on a terminal
- **THEN** it exits 2 saying the job would measure nothing, the next-step hint is
  `java -jar build/b.jar -h`, and no AWS call is made

#### Scenario: Listing profilers carries the local caveat
- **WHEN** `baas run --benchmark-jar build/b.jar jmh -- -lprof` is run
- **THEN** it exits 2, and the hint names `java -jar build/b.jar -lprof` and says the runner image also
  provides `perf` and async-profiler

#### Scenario: JCStress's re-parse mode is refused
- **WHEN** `baas run jcstress … -- -p old-results.bin.gz` is run
- **THEN** it exits 2 saying the job would measure nothing

#### Scenario: JMH's params flag is not mistaken for a no-run flag
- **WHEN** `baas run jmh … -- MyBenchmark -p size=10` is run
- **THEN** the job is launched

### Requirement: Refusals recognise every spelling the benchmark's parser accepts
The managed and no-run refusals SHALL recognise an argument however the benchmark's own parser would
accept it:
- with one or two leading dashes;
- with its value attached after `=`;
- as any abbreviation that the parser resolves to it — a prefix that matches no option exactly and
  starts exactly one of the tool's option names.

An argument that is an exact option name of the tool SHALL be read as that option and never as an
abbreviation of a longer one. The same reading SHALL decide which profilers a job uses and where
managed profiler keys are injected, so a profiler cannot be refused, injected and recorded under three
different readings.

#### Scenario: An abbreviated profiler flag is read as `-prof`
- **WHEN** `baas run jmh … -- MyBenchmark -pro async:dir=/tmp/x` is run
- **THEN** it exits 2 saying the `dir` of the `async` profiler is managed by BaaS

#### Scenario: Double dash and attached values are recognised
- **WHEN** `baas run jmh … -- MyBenchmark --rf=csv` is run
- **THEN** it exits 2 saying `-rf` is managed by BaaS

#### Scenario: An abbreviated listing flag is recognised
- **WHEN** `baas run jmh … -- -lr` is run
- **THEN** it exits 2 saying the job would measure nothing, naming `-lrf`

#### Scenario: An abbreviated profiler is still injected and recorded
- **WHEN** `baas run jmh … -- MyBenchmark -pr async` completes
- **THEN** the async profiler ran with its library and output directory injected, its artifacts are
  under the job's prefix, and the job carries `profilers=async`

### Requirement: Profilers that write files are pointed at the job's artifacts
For every `async` profiler argument the runner SHALL add the location of the baked async-profiler library
and the profiler output directory. For every `jfr` profiler argument it SHALL add the profiler output
directory. It SHALL do so for any `jmh` job, keeping every key the user gave. Everything the profilers
write there SHALL be uploaded under the job's prefix at the same keys profiler artifacts have had: one
directory per benchmark, as JMH names it.

#### Scenario: Async profiling needs no BaaS-specific flag
- **WHEN** `baas run jmh … -- MyBenchmark -prof async:event=alloc;output=flamegraph` completes on the
  runner image
- **THEN** the flame graph is under the job's prefix, in the directory JMH named for the benchmark, and
  `baas jobs download` retrieves it

#### Scenario: Two profilers in one job
- **WHEN** `baas run jmh … -- MyBenchmark -prof gc -prof jfr` completes
- **THEN** the GC profiler's secondary metrics are stored with the measurement, and the JFR recording is
  under the job's prefix
