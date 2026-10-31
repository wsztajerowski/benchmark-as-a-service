# Spec Delta

## MODIFIED Requirements

### Requirement: Arguments BaaS manages are refused
`baas run` SHALL refuse, with exit 2 and before any AWS call, an argument after `--` that sets something
BaaS manages, naming the argument and saying it is managed by BaaS. For `jmh` these SHALL be the
result format (`-rf`), the result file (`-rff`), the output file (`-o`), and the keys `dir` and
`libPath` within an `async` profiler's options and `dir` within a `jfr` profiler's options, and the JVM
the benchmark forks run on (`-jvm`), which `--jdk` chooses instead. For
`jcstress` it SHALL be the report directory (`-r`). The runner SHALL apply the same refusal before
starting the benchmark, so a job launched without the CLI is refused alike.

#### Scenario: A result format is refused
- **WHEN** `baas run jmh … -- MyBenchmark -rf csv` is run
- **THEN** it exits 2 saying `-rf` is managed by BaaS, and nothing is uploaded or launched

#### Scenario: A profiler output directory is refused
- **WHEN** `baas run jmh … -- MyBenchmark -prof async:dir=/tmp/x` is run
- **THEN** it exits 2 saying the `dir` of the `async` profiler is managed by BaaS

#### Scenario: A fork JVM is refused in favour of --jdk
- **WHEN** `baas run jmh … -- MyBenchmark -jvm /opt/jdks/temurin-26/bin/java` or `… -- MyBenchmark --jvm=/x` is run
- **THEN** it exits 2 saying `-jvm` is managed by BaaS and naming `--jdk`, and nothing is uploaded or launched

#### Scenario: JVM arguments still pass through
- **WHEN** `baas run jmh … -- MyBenchmark -jvmArgsAppend -Xmx2g` is run
- **THEN** the job is launched, and JMH receives `-jvmArgsAppend -Xmx2g`

#### Scenario: A JCStress report directory is refused
- **WHEN** `baas run jcstress … -- -r /tmp/report` is run
- **THEN** it exits 2 saying `-r` is managed by BaaS

#### Scenario: JMH's iteration time is not mistaken for a managed flag
- **WHEN** `baas run jmh … -- MyBenchmark -r 1s` is run
- **THEN** the job is launched, and JMH receives `-r 1s`

#### Scenario: The runner refuses alike
- **WHEN** the runner is invoked directly with `jmh … -- MyBenchmark -rff /tmp/x.json`
- **THEN** it exits non-zero naming `-rff` as managed, and runs no benchmark

### Requirement: Listing profilers runs as a job on the runner
`baas run jmh … -- -lprof` SHALL launch an ordinary job, and the runner SHALL run the benchmark JAR with
`-lprof` on the runner image, so the listing describes that image, the job's instance type and the JMH
version inside `--benchmark-jar`. `-lprof` SHALL be accepted only as the sole argument after `--`, in any
spelling the parser accepts; with any other argument after `--` it SHALL be refused with exit 2, before
any AWS call, saying `-lprof` must stand alone. The runner SHALL apply the same refusal. A listing job
SHALL store no measurements and no result JSON; its listing SHALL be the job's process output, and it
SHALL end `completed` when JMH exits 0 and `failed:<n>` otherwise. async-profiler SHALL be reported as
the runner image provides it. The listing SHALL run on the JDK the job selects with `--jdk`, like any job,
so it lists the profilers available on that JDK.

In text mode `baas run` SHALL print the listing job's process output on standard output, byte for byte
as stored, in place of the measurement table, and its next-step hint SHALL name only
`baas jobs download <jobId>`. Under `--format json` it SHALL print the summary object unchanged and no
listing. A detached listing job SHALL be reported like any detached job.

#### Scenario: The runner's profilers are listed
- **WHEN** `baas run --benchmark-jar build/b.jar jmh -- -lprof` completes on the runner image
- **THEN** standard output holds JMH's `Supported profilers` and `Unsupported profilers` lists, `async`
  and `perf` are under `Supported profilers`, the job's status is `completed`, and
  `baas results query --job-id <jobId>` finds no measurement

#### Scenario: A listing runs on the selected JDK
- **WHEN** `baas run --jdk temurin-26 jmh -- -lprof` completes on an image holding `temurin-26`
- **THEN** the listing comes from JMH running on `/opt/jdks/temurin-26/bin/java`, and the job carries
  `jdkId=temurin-26`

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
