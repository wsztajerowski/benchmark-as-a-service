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

## Section 6 on `baas-development` (2026-10-31, reactor CLI and runner, image 1.4.0, `c5.2xlarge`)

The first batch launched eight jobs at once; five failed at `RunInstances` with `VcpuLimitExceeded`
(account limit 33 vCPUs, 8 vCPUs each) — no instance, no cost — and were rerun two at a time.

### 6.1 Listing job

`baas run … jmh -- -lprof`, job `20261031T173108268Z-c4259484`: status `completed`; stdout is the listing
and `cmp` finds it byte-identical to the job's downloaded `jmh-output.txt`, JVM `WARNING:` lines first;
`results query --job-id` prints "No results found."; the job prefix holds no `jmh-result.json`. The hint
block was not shown because stderr was a file (hints are interactive only); `RunCommandDetachTest`
covers its content. `--format json` (job `20261031T173400873Z-cfdb3f74`): stdout is one object and
`jq` parses it.

What the runner image reports on `c5.2xlarge` with JMH 1.37:

| Supported | Unsupported (reason) |
|---|---|
| async, cl, comp, gc, jfr, mempool, pauses, perf, safepoints, stack | perfasm (`Unsupported event: cycles`), perfnorm (`No supported events.`), perfc2c (`memory events not supported`), dtraceasm, xperfasm |

`async` is supported with no `libPath`, through the environment. The hardware-counter profilers are not,
on this instance type — the host-dependent answer the laptop could never give, and the design's
"hardware counters can vary by instance family and size" risk observed for real.

### 6.2 Refusals

See above.

### 6.3 Async profiling on the image

Job `20261031T173108339Z-3eaa3335`, `-- Summing_Array -f 1 -wi 1 -i 1 -prof 'async:output=flamegraph'`:
`completed`, and `jobs download` retrieves `flame-cpu-forward.html` and `flame-cpu-reverse.html` under
`pl.wsztajerowski.fake.Summing_Array.sum-Throughput-size-1/` and `…-size-2/`.

### 6.4 Comparability

`Summing_Array.sum -f 1 -wi 2 -w 2s -i 5 -r 2s` (not `Incrementing_Synchronized`, which is multimodal),
three jobs on the pre-change base (`95fc283`, its own CLI and runner) and three on this branch.

| Job | CPU | size=1 (ops/s) | size=2 (ops/s) |
|---|---|---|---|
| before-1 `…9dcb025c` | Xeon 8275CL | 513,116,258 ± 185,144 | 448,703,570 ± 1,183,542 |
| before-2 `…4e0761c2` | **Xeon 8124M** | 467,896,229 ± 456,617 | 406,934,422 ± 256,718 |
| before-3 `…354be7f3` | Xeon 8275CL | 513,376,142 ± 672,930 | 449,140,799 ± 462,846 |
| after-1 `…51849864` | Xeon 8275CL | 513,266,888 ± 214,125 | 449,107,475 ± 531,594 |
| after-2 `…d01ac6b4` | Xeon 8275CL | 513,022,910 ± 944,351 | 448,772,475 ± 1,059,578 |
| after-3 `…d5075c93` | Xeon 8275CL | 513,238,504 ± 518,615 | 449,060,295 ± 484,289 |

On Xeon 8275CL the spread is 513.02–513.38M (size=1) and 448.70–449.14M (size=2), before and after alike
(< 0.1%). The one outlier, before-2 (−9% on both params), ran the pre-change code on a Skylake host
(Xeon 8124M) where the rest got Cascade Lake: `c5.2xlarge` spans both, and `cpuModel` tells them apart.
Not caused by this change; no score moved with it.
