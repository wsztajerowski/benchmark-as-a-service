# Spec Delta

## ADDED Requirements

### Requirement: Listing profilers runs as a job on the runner
`baas run jmh … -- -lprof` SHALL launch an ordinary job, and the runner SHALL run the benchmark JAR with
`-lprof` on the runner image, so the listing describes that image, the job's instance type and the JMH
version inside `--benchmark-jar`. `-lprof` SHALL be accepted only as the sole argument after `--`, in any
spelling the parser accepts; with any other argument after `--` it SHALL be refused with exit 2, before
any AWS call, saying `-lprof` must stand alone. The runner SHALL apply the same refusal. A listing job
SHALL store no measurements and no result JSON; its listing SHALL be the job's process output, and it
SHALL end `completed` when JMH exits 0 and `failed:<n>` otherwise. async-profiler SHALL be reported as
the runner image provides it.

In text mode `baas run` SHALL print the listing job's process output on standard output, byte for byte
as stored, in place of the measurement table, and its next-step hint SHALL name only
`baas jobs download <jobId>`. Under `--format json` it SHALL print the summary object unchanged and no
listing. A detached listing job SHALL be reported like any detached job.

#### Scenario: The runner's profilers are listed
- **WHEN** `baas run --benchmark-jar build/b.jar jmh -- -lprof` completes on the runner image
- **THEN** standard output holds JMH's `Supported profilers` and `Unsupported profilers` lists, `async`
  and `perf` are under `Supported profilers`, the job's status is `completed`, and
  `baas results query --job-id <jobId>` finds no measurement

#### Scenario: The listing is the stored process output, verbatim
- **WHEN** a listing job's output begins with JVM `WARNING:` lines
- **THEN** `baas run` prints them too, and its standard output equals the job's `jmh-output.txt`
  fetched with `baas jobs download <jobId>`

#### Scenario: Only the download hint follows a listing
- **WHEN** a listing job completes on a terminal
- **THEN** the next-step hint names `baas jobs download <jobId>` and no `results query` command

#### Scenario: JSON mode keeps its one payload
- **WHEN** `baas run --format json … jmh -- -lprof` completes
- **THEN** standard output is the summary object alone, and it parses as JSON

#### Scenario: Anything beside `-lprof` is refused
- **WHEN** `baas run jmh … -- -lprof MyBenchmark` or `baas run jmh … -- -lprof -prof gc` is run
- **THEN** it exits 2 saying `-lprof` must be the only argument after `--`, and nothing is uploaded or
  launched

#### Scenario: Another spelling of `-lprof` alone is a listing
- **WHEN** `baas run jmh … -- --lprof` or `baas run jmh … -- -lpr` is run
- **THEN** a listing job is launched

#### Scenario: The runner refuses a mixed listing alike
- **WHEN** the runner is invoked directly with `jmh … -- -lprof -f 1`
- **THEN** it exits non-zero saying `-lprof` must stand alone, and runs no benchmark

### Requirement: Listings read from the benchmark JAR are refused with the local command
`baas run` SHALL refuse, with exit 2 and before any AWS call, an argument after `--` that makes the
benchmark print information read from the benchmark JAR alone and exit without measuring. For `jmh` these
SHALL be `-h`, `-l`, `-lp` and `-lrf`; for `jcstress`, `-h`, `-l` and `-p`. The refusal SHALL say that the
job would measure nothing, and its next-step hint SHALL be the command that runs the same arguments
against the named benchmark JAR locally. `-lprof` is not among them: its answer depends on the host, and
it runs as a listing job.

#### Scenario: Help is refused and the local command offered
- **WHEN** `baas run --benchmark-jar build/b.jar jmh -- -h` is run on a terminal
- **THEN** it exits 2 saying the job would measure nothing, the next-step hint is
  `java -jar build/b.jar -h`, and no AWS call is made

#### Scenario: Listing benchmarks is refused and the local command offered
- **WHEN** `baas run --benchmark-jar build/b.jar jmh -- -l` is run
- **THEN** it exits 2, and the hint names `java -jar build/b.jar -l` with no caveat about the runner image

#### Scenario: JCStress's re-parse mode is refused
- **WHEN** `baas run jcstress … -- -p old-results.bin.gz` is run
- **THEN** it exits 2 saying the job would measure nothing

#### Scenario: JMH's params flag is not mistaken for a no-run flag
- **WHEN** `baas run jmh … -- MyBenchmark -p size=10` is run
- **THEN** the job is launched

## MODIFIED Requirements

### Requirement: Refusals recognise every spelling the benchmark's parser accepts
The managed and no-run refusals SHALL recognise an argument however the benchmark's own parser would
accept it:
- with one or two leading dashes;
- with its value attached after `=`;
- as any abbreviation that the parser resolves to it — a prefix that matches no option exactly and
  starts exactly one of the tool's option names.

An argument that is an exact option name of the tool SHALL be read as that option and never as an
abbreviation of a longer one. The same reading SHALL decide which profilers a job uses, where
managed profiler keys are injected and whether `-lprof` stands alone, so a profiler cannot be refused,
injected and recorded under three different readings.

Within a profiler's options, a key SHALL be refused as managed when it is a managed key of that profiler
or any prefix of one (`l`, `li`, … `libPath`; `d`, `di`, `dir`), whether or not the profiler would
resolve that prefix uniquely. A key that merely starts with a managed key (`direction`) SHALL NOT be
refused.

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
- **THEN** the async profiler ran with the baked library and its output directory injected, its
  artifacts are under the job's prefix, and the job carries `profilers=async`

#### Scenario: An abbreviated library path is refused
- **WHEN** `baas run jmh … -- MyBenchmark -prof async:libP=/tmp/lib.so` is run
- **THEN** it exits 2 saying the `libPath` of the `async` profiler is managed by BaaS, and the runner
  refuses it alike

#### Scenario: An abbreviated output directory is refused
- **WHEN** `baas run jmh … -- MyBenchmark -prof jfr:di=/tmp/x` is run
- **THEN** it exits 2 saying the `dir` of the `jfr` profiler is managed by BaaS

#### Scenario: A key that only starts with a managed key is passed through
- **WHEN** `baas run jmh … -- MyBenchmark -prof async:output=flamegraph;direction=forward` is run
- **THEN** the job is launched, and JMH receives `direction=forward`

### Requirement: Profilers that write files are pointed at the job's artifacts
The runner SHALL make the baked async-profiler library loadable by every JMH process it starts, the
listing included, through the process environment, and SHALL NOT add a library location to any profiler
argument. For every `async` and every `jfr` profiler argument it SHALL add the profiler output directory.
A profiler named by the class JMH resolves it to (`org.openjdk.jmh.profile.AsyncProfiler`,
`org.openjdk.jmh.profile.JavaFlightRecorderProfiler`) SHALL be treated as `async` or `jfr` — injected,
checked for managed keys and recorded in the `profilers` tag under the short name. It SHALL do so for any
`jmh` job, keeping every key the user gave. Everything the profilers write there SHALL be uploaded under
the job's prefix at the same keys profiler artifacts have had: one directory per benchmark, as JMH names
it — also when the benchmark process exits non-zero.

#### Scenario: Async profiling needs no BaaS-specific flag
- **WHEN** `baas run jmh … -- MyBenchmark -prof async:event=alloc;output=flamegraph` completes on the
  runner image
- **THEN** the flame graph is under the job's prefix, in the directory JMH named for the benchmark, and
  `baas jobs download` retrieves it

#### Scenario: No library location is added to the profiler argument
- **WHEN** `baas run jmh … -- MyBenchmark -prof async` is run
- **THEN** JMH receives `-prof` with `async:` and the output directory only, and async-profiler still
  loads the baked library

#### Scenario: The library is found the same way on Linux and macOS
- **WHEN** the runner runs `jmh … -- MyBenchmark -prof async` on Linux, and on macOS with `java`
  resolving to `/usr/bin/java`
- **THEN** async-profiler loads in the forked benchmark JVM on both, with no library location in the
  profiler argument

#### Scenario: A profiler named by its class is pointed at the job's artifacts
- **WHEN** `baas run jmh … -- MyBenchmark -prof org.openjdk.jmh.profile.JavaFlightRecorderProfiler` completes
- **THEN** the JFR recording is under the job's prefix, and the job carries `profilers=jfr`

#### Scenario: Two profilers in one job
- **WHEN** `baas run jmh … -- MyBenchmark -prof gc -prof jfr` completes
- **THEN** the GC profiler's secondary metrics are stored with the measurement, and the JFR recording is
  under the job's prefix

## REMOVED Requirements

### Requirement: Arguments that would measure nothing are refused with the local command
**Reason**: `-lprof` no longer measures nothing: alone, it runs as a listing job on the runner
("Listing profilers runs as a job on the runner"). The refusal of the remaining options, which read only
the benchmark JAR, continues as "Listings read from the benchmark JAR are refused with the local
command", without the `-lprof` caveat.
**Migration**: `baas run jmh … -- -lprof` now lists the runner's profilers; `java -jar <jar> -lprof`
still lists the local machine's.
