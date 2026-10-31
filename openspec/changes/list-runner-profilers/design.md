# Design

## Context

See proposal.md — Why. The facts the approach rests on, each checked during exploration
(2026-10-31):

- `-lprof` on the laptop (JMH 1.37, JDK 25, macOS) lists `async` under *Unsupported* — "Unable to
  load async-profiler. Ensure asyncProfiler library is on LD_LIBRARY_PATH … or point to explicit
  library location with -prof async:libPath=<path>" — and every `perf*` profiler as missing.
- JMH 1.35–1.37's `AsyncProfiler$JavaApi` calls `System.load(libPath)` when `libPath` is given and
  `System.loadLibrary("asyncProfiler")` otherwise (bytecode of the JARs in `~/.m2`). JMH's own
  `-prof async:help` says the same: without `libPath` "the library must be made available to the forked
  JVM in an entry of -Djava.library.path, LD_LIBRARY_PATH (Linux)". On Linux a JVM's default
  `java.library.path` is built from `LD_LIBRARY_PATH`.
- `async` is an internal profiler: it loads inside the **forked** benchmark JVM, which inherits the
  JMH host process's environment. `-lprof` evaluates the profilers in the host JVM.
- On macOS (JDK 25 from SDKMAN, JMH 1.37 fixture, local async-profiler) `LD_LIBRARY_PATH` is ignored;
  `DYLD_LIBRARY_PATH` and `JAVA_LIBRARY_PATH` both enter `java.library.path`, but `/usr/bin/java` — the
  SIP-protected stub a Mac without SDKMAN resolves `java` to — strips `DYLD_LIBRARY_PATH` and keeps
  `JAVA_LIBRARY_PATH`. With either variable and no `libPath`, `-lprof` lists `async` as supported and a
  forked `-prof async` run writes `summary-cpu.txt`; with neither, it fails "Unable to load
  async-profiler". The runner starts JMH as plain `java` from `PATH` (`BenchmarkProcessBuilder`).
- The developer machine runs `JmhAsyncProfilerIT` on macOS: `ASYNC_PATH` points at a local
  `libasyncProfiler.dylib`.
- Today the runner always passes `-rf json -rff <file>`, then uploads and maps that file
  (`JmhSubcommandService.executeCommand`); `-lprof` writes none, so an unrefused listing would end
  `failed`. It appends `dir=…;libPath=…` to every `-prof async` and `dir=…` to every `-prof jfr`.
- The managed-profiler-key check before launch matches the exact key only. Its backstop for other
  spellings was the runner's own append: JMH rejects a key given twice (`BenchmarkArgs`,
  `MANAGED_PROFILER_KEYS` comment).
- JMH 1.37's profiler option names — `async`: output, direction, libPath, event, alloc, lock, dir,
  interval, jstackdepth, framebuf, filter, threads, simple, sig, ann, include, exclude, rawCommand,
  and more; `jfr`: configName, debugNonSafePoints, dir, stackDepth, verbose. Profiler options are
  parsed by jopt-simple too, so unique prefixes resolve.

## Goals / Non-Goals

**Goals:**
- The listing answers for the image, the instance type and the user's JMH version at once.
- One mechanism for where async-profiler lives, used by listing and measuring jobs alike.
- No spelling of a managed profiler key reaches JMH.

**Non-Goals:**
- A listing that needs no instance (a bake-time probe, a cached list).
- JCStress: none of its listings depends on the host.
- Hiding JVM warnings from the listing.
- Recording the profiler list on every job.

## Decisions

### The listing is an ordinary job, not a bake-time probe

A job runs the user's JAR on the image and instance type the benchmark will use, so it is right on all
three things the list depends on. A probe baked into the image fixes one JMH version: newer JARs would
see profilers the list lacks, older ones would be shown profilers they do not have — and it knows only
the build instance's type, while hardware counters can vary by instance family and size.

*Rejected:* a contract-phase probe writing to `image-builds/` (wrong JMH version; adds a pinned JMH
download to the bake); recording `-lprof` on every job beside `environment.json` (answers only after a
run); a `baas harnesses|tools list|show` noun to display either (with no stored list, `show` would have
only static argument rules left, which belong in `run --help`). The contract assertion that JMH can use
`async` and `perf` was dropped with the probe: the contract already checks perf against the booted
kernel, and the runner refuses `-prof async` when the library is missing.

### `-lprof` must stand alone

JMH lists and exits, ignoring a benchmark regex, `-prof` or `-f` given beside it. Accepting them would
make the command line say something the job did not do — the trap the no-run refusal exists for. The
predicate lives in `BenchmarkArgs`, read through the same `read()` as every other check: exactly one
occurrence, it resolves to `lprof`, and nothing else is in the list. The CLI and the runner both apply it.

### The other no-run options keep the local command

`-h`, `-l`, `-lp`, `-lrf` and JCStress's `-h`, `-l`, `-p` read the JAR only; the laptop answers them
for free and correctly. Only `-lprof` leaves the set, and with it the `localCommandCaveat` in
`RunCommand`.

### The async library reaches JMH through `LD_LIBRARY_PATH` and `JAVA_LIBRARY_PATH` on every JMH process

The runner sets both variables to the directory of `--async-path` (default `/app/async-profiler/lib`)
in the environment of the JMH process it starts, on every OS, and stops appending `libPath=`. A listing
has no `-prof` value to append to, so the environment is the only way it can see the library; setting
it on every JMH process instead of only listings leaves one mechanism for one fact. Linux's JVM reads
`LD_LIBRARY_PATH`, macOS's `JAVA_LIBRARY_PATH`; each ignores, or reads to the same directory, the other,
so no OS is detected and nothing depends on which JVM reads which. Set on the process the runner
starts, not exported by user-data, so it reaches JMH and its forks and nothing else on the box, and the
LocalStack ITs (`ASYNC_PATH`) exercise the same code on Linux and macOS. An existing value of either
variable is kept, with the async directory first.

The missing-library check stays as it is: only when `-prof async` is requested, before JMH starts.

*Rejected:* the environment for listings only, with `libPath=` kept for measuring jobs — two mechanisms
for where async-profiler lives, chosen by job kind; keeping `libPath=` and the environment on every job —
the same duplication, always; `LD_LIBRARY_PATH` alone — macOS ignores it, breaking the developer's
`JmhAsyncProfilerIT`; one variable chosen by OS — an OS branch that buys nothing (Linux's JVM does ignore
`JAVA_LIBRARY_PATH`, verify.md 1.1); `DYLD_LIBRARY_PATH` on macOS — stripped by `/usr/bin/java`;
`-Djava.library.path` on the host and forks — changes every job's command line, is dropped from forks
by a user `-jvmArgs`, and collides with a user's own; symlinking the library into a default library
directory in the image — an image rebuild, and a local Mac would still need its own setup; the IT
setting the variable itself — it would no longer test the code a job runs.

### `libPath` stays managed

BaaS owns the async-profiler version and location. A user `libPath` would win over the environment,
since JMH tries `System.load(libPath)` first, so it stays in `MANAGED_PROFILER_KEYS` and is refused.

### Managed profiler keys are refused in every prefix spelling

Without the appended `libPath`, nothing behind the exact-match check stopped `-prof async:libP=/x`: no
duplicate key, and JMH resolves the prefix to `libPath`. The check becomes "the key is a prefix of a
managed key" — `managedKey.startsWith(key)` — for every managed key, so `dir` follows the same rule
though its append still backstops it. This over-refuses only prefixes that are ambiguous (`l` is also
`lock`; `d`, `di` are also `direction` for async; `d` is also `debugNonSafePoints` for jfr), which JMH
rejects anyway. It needs no list of each profiler's options and no new contract test. The reverse
direction is not refused: `direction` starts with `dir` but is its own option.

### A listing job stores no result and `baas run` prints its process output

The runner's JMH path branches on the listing predicate: no `-rf`/`-rff`, then upload the process output
and logs as every job does, and stop — no result JSON, no `JmhRunResults.map`, no store write. A non-zero
exit fails the job as today. User-data, the environment manifest, the job item and its statuses are
untouched, so a listing job has the same `environment.json` and `cloud-init-output.log` as any job.

On `completed`, `RunCommand` (text mode) fetches the job's `jmh-output.txt` — key from `JobLayout` and
`BenchmarkType.JMH.processOutputName()` — and writes it to `Console` verbatim, under a log line naming
the job id. The JVM's `WARNING:` lines stay: filtering would match JVM wording that changes between
releases, and adding `--enable-native-access` would change every job's command line. The hint is
`artifacts  baas jobs download <jobId>` only. `--format json` and `--detach` are unchanged.

## Risks / Trade-offs

- **A user `-Djava.library.path` disables the library path** → `-jvmArgs -Djava.library.path=/x`
  replaces the default path in the fork, so `-prof async` fails at start with JMH's "Unable to load
  async-profiler". Loud, rare (needs native libraries of its own and async profiling), and the user
  can add the async directory to their path. Today's `libPath=` survived this case.
- **Every JMH job's process environment changes** → no benchmark command line changes for unprofiled
  jobs, and the directory holds only async-profiler's library. Comparability: no score should move;
  the change date is the boundary, since `environment.json` records no environment variables. A
  score comparison against a pre-change run is a task.
- **A variable unused on one OS** → every Linux job carries `JAVA_LIBRARY_PATH` its JVM may ignore, and
  every macOS run `LD_LIBRARY_PATH`. Both name the same directory, so whichever is read, the result is
  the same.
- **A listing job is a paid boot for a two-second answer** → accepted: it is the price of trying one
  profiler today, and answers for all of them. It shows in `jobs list` as a `completed` job with no
  measurements, which is what happened.
- **Over-refusing an ambiguous prefix** → only prefixes JMH would reject as ambiguous; the refusal names
  the managed key, which says what to spell out.
- **The listing reports `perf` as supported where hardware counters are limited** → JMH checks that
  `perf` runs, not which events the instance exposes; a `perfnorm` job may still report missing
  counters. Unchanged by this change.

## Migration Plan

None: no stored data, image or IAM changes. A CLI from before this change still refuses `-lprof`; a
listing launched by a new CLI needs the runner of the same release, which the version pin guarantees.

## Resolved Questions

- *Should `-lprof` be accepted beside other arguments?* No, refused (user, 2026-10-31).
- *`LD_LIBRARY_PATH` for listings only, or every JMH process?* Every JMH process, replacing the
  appended `libPath` (user, 2026-10-31).
- *How does the async IT keep working on macOS?* Both variables on every OS (user, 2026-10-31), after
  the macOS tests recorded under Context showed `JAVA_LIBRARY_PATH` loads the library in the fork,
  also through `/usr/bin/java`.
- *Should `libPath` become user-settable once it is no longer appended?* No, it stays managed (user,
  2026-10-31).
- *Exact or prefix matching for managed profiler keys?* Every prefix spelling, for `dir` and `libPath`
  (user, 2026-10-31).
- *Filter the listing's JVM warnings?* No, verbatim; JSON summary unchanged (user, 2026-10-31).
- *Keep a bake-time contract assertion?* Dropped (user, 2026-10-31).
