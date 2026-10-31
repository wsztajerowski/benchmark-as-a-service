# Verify — list-runner-profilers

## Section 1: blocking assumptions (2026-10-31)

Fixture: `fake-jmh-benchmarks` shaded JAR (JMH 1.37), benchmark
`pl.wsztajerowski.fake.Incrementing_Synchronized.incrementUsingSynchronized`, `-f 1 -wi 0 -i 1 -r 1s`.

### 1.1 Linux (`amazoncorretto:25` container, async-profiler 4.6 linux-x64, sha256 checked against `infra/runner-image.yaml`)

| Run | Result |
|---|---|
| `LD_LIBRARY_PATH` + `JAVA_LIBRARY_PATH`, `-lprof` | `async: async-profiler profiler provider.` under *Supported profilers* |
| Both, forked `-prof async:event=itimer;output=text;dir=/tmp/pA` | `# Preparing profilers: AsyncProfiler`, `<benchmark>-Throughput/summary-itimer.txt` written |
| `JAVA_LIBRARY_PATH` alone | `java.library.path = /usr/java/packages/lib:/usr/lib64…` — **Linux's JVM does not read it**; `-lprof` lists `async` as unsupported |
| No variable, forked `-prof async` (control) | `Unable to load async-profiler. Ensure asyncProfiler library is on LD_LIBRARY_PATH (Linux)…` |

`event=itimer` because a container has no `perf_events`; the run checks loading, not the event.

### 1.2 macOS (Darwin 24.6, x86_64, JDK 25 from SDKMAN and `/usr/bin/java`, local async-profiler)

Both variables set, no `libPath`:

| Run | Result |
|---|---|
| `java` (SDKMAN), `-lprof` | `async` supported |
| `/usr/bin/java` (SIP stub), `-lprof` | `async` supported |
| `/usr/bin/java`, forked `-prof async:output=text;dir=…` | `summary-cpu.txt` written |

Earlier the same day, per variable: `LD_LIBRARY_PATH` is ignored on macOS; `DYLD_LIBRARY_PATH` enters
`java.library.path` through SDKMAN's `java` but is stripped by `/usr/bin/java`; `JAVA_LIBRARY_PATH`
enters it through both. With no variable, the forked run fails "Unable to load async-profiler".

### 1.3 Profiler-key prefixes (Linux container)

| Run | Result |
|---|---|
| `-prof async:libP=/nonexistent/lib.so` | `Can't load library: /nonexistent/lib.so` — the unique prefix resolves to `libPath` |
| `-prof async:di=/tmp/x` | refused by JMH (`d is not a recognized option`) — ambiguous with `direction` |

So the design's rationale holds: without a refusal, `libP=` would load a user library.

### 1.4 `-lprof` and result files (Linux container, working directory `/tmp`)

| Run | Result |
|---|---|
| `-lprof` | exit 0, no `jmh-result.json` |
| `-lprof -rf json -rff x.json` | exit 0, no `x.json` |

A listing writes no result file even when asked to, so the runner must not read one.

## 6.2 Refusals with the reactor CLI (2026-10-31, `--deployment baas-development`, no AWS call)

| Arguments after `--` | Result |
|---|---|
| `-lprof MyBenchmark` | exit 2, "-lprof lists JMH's profilers on the runner and exits, ignoring everything else, so it must be the only argument after --. Nothing was launched." |
| `MyBenchmark -prof async:libP=/x` | exit 2, "libPath in -prof async is managed by BaaS and cannot be set after --: … Nothing was launched." |

The managed message said "the runner sets it", untrue for `libPath` now; it also names loading the
image's async-profiler.
