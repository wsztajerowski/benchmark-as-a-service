---
paths:
  - "benchmark-runner/**"
  - "fake-jmh-benchmarks/**"
  - "fake-stress-tests/**"
  - "baas-model/src/*/java/pl/wsztajerowski/baas/model/BenchmarkArgs*.java"
  - "baas-model/src/*/java/pl/wsztajerowski/baas/model/BenchmarkType*.java"
---

# Benchmark runner and benchmark arguments

The runner module, what reaches the benchmark JAR, and how a library is added.

- **Everything after `--` reaches the benchmark JAR verbatim, as JMH's or JCStress's own arguments**
  (`benchmark-args-pass-through`). The runner has no JMH or JCStress options of its own — not `-bvm`,
  not `--mode`, not `-ae`: a JCStress mode is `-- -m sanity`, a profiler `-- -prof gc` — and takes them
  after its own `--`, so no benchmark argument can reach a runner option. Two kinds are refused with
  exit 2 before any AWS call, and by the runner again: those BaaS manages (JMH `-rf`, `-rff`, `-o`,
  `-jvm`; `dir`/`libPath` inside `-prof async:`, `dir` inside `-prof jfr:`; JCStress `-r`) and those that
  measure nothing (JMH `-h`, `-l`, `-lp`, `-lrf`; JCStress `-h`, `-l`, `-p`), answered with the local
  `java -jar` command, because they read only the JAR. **Both match every spelling jopt-simple
  accepts** — `--`, `=value`, unique prefixes (`-pro` is `-prof`), and single-letter clusters
  (`-ofile` is `-o file`, which really redirected JMH's output) — through `BenchmarkArgs` in
  `baas-model`, which also reads the `-prof` values the runner injects into and the `profilers` tag
  names. Its option lists are the fixture JARs' `-h`, pinned by `BenchmarkArgsContractIT`; a JMH or
  JCStress bump fails there until they follow. A managed profiler key is refused in **every prefix**
  (`l`…`libPath`, `d`…`dir`), ambiguous ones included: JMH parses profiler options with jopt-simple
  too, and `libP=/x` really loaded `/x` (`list-runner-profilers`).
- **The JDK is chosen with `baas run --jdk <id>`, never with JMH's `-jvm`** (`multiple-jdks`). The
  runner takes `--jdk` and starts the benchmark as `/opt/jdks/<id>/bin/java -jar …` instead of `java
  -jar …`; JMH's forks (and `-f 0`) and JCStress's follow the started JVM's `java.home`, so one
  executable decides every measuring JVM. The runner itself stays on `java` from `PATH`. A user `-jvm`
  would send the forks to a JVM the tags do not name, so it is managed and its refusal names `--jdk`;
  JCStress has no such option. Without `--jdk` (an image from before JDK selection) the benchmark runs
  on `java` from `PATH`. The runner refuses an id with no `bin/java` under `--jdks-root` (hidden,
  `/opt/jdks`, moved only by `JmhSelectedJdkIT`) before anything runs, listing what is there.
- **Every other JMH and JCStress option passes through, each checked against "would a user value
  break BaaS".** The known gap: perfasm's `savePerfTo`, `savePerfBinTo`, `saveLogTo` and their
  `…ToFile` forms write outside the uploaded tree, so their files are lost. Not managed, because no
  allowed instance type exposes the hardware counters perfasm needs and the injection could not be
  tested (PT3). `-foe` passes through: `-foe true` fails the job with no result file, `-foe
  false` exits 0 with only the variants that succeeded (PT2).
- **`-lprof` alone is a listing job, not a refusal** (`list-runner-profilers`): its answer depends
  on the host — perf, the baked async-profiler, the instance type — and on the JMH version inside the
  JAR, so only the runner can give it. Anything beside it is refused, since JMH lists and exits,
  ignoring the rest. The runner passes no `-rf`/`-rff` (a listing writes no result file even when
  asked), uploads the process output and stores nothing; `baas run` prints that output verbatim. It
  takes `--jdk` like any job: what a profiler needs (`jcmd`, a HotSpot VM) belongs to the JDK.
- **async-profiler reaches JMH through the environment, never a `libPath` key.** The runner puts the
  library's directory first in both `LD_LIBRARY_PATH` (read by Linux's JVM) and `JAVA_LIBRARY_PATH`
  (read by macOS's) on every JMH process, and JMH's `AsyncProfiler` falls back to `loadLibrary` in the
  fork. Both, on every OS, so nothing depends on which JVM reads which, and the runner works the same
  on the image and on a developer's Mac. Not `DYLD_LIBRARY_PATH`: SIP strips it when `java` is
  `/usr/bin/java`. A user `-jvmArgs -Djava.library.path=…` replaces that path in the fork and breaks
  `-prof async` loudly. `libPath` stays managed because a user value would win over the environment.
- **`mvn -pl benchmark-runner verify` alone fails.** It needs the `fake-jmh-benchmarks` and
  `fake-stress-tests` shaded JARs already in the local repo (`classifier=shaded`). Run the full
  reactor first.
- **JCStress writes `jcstress-results-*.bin.gz` to the module root**, not `target/`. `mvn clean`
  removes them via an extra fileset.
- **`ASYNC_PATH` gates async coverage.** `JmhAsyncProfilerIT` is annotated
  `@EnabledIfEnvironmentVariable(named = "ASYNC_PATH", ...)`, so a plain `mvn verify` **silently
  skips** the only test exercising async-profiler end to end — on Linux and macOS alike (a `.so` or
  `.dylib`). Export it before trusting a green
  build on profiler changes.

## Adding a benchmark type

One type per library, not per way of running it — profiling is a JMH argument, which is why the
profiled types were retired. A `BenchmarkType` constant in `baas-model` (its name is the runner
subcommand, the `baas run` argument and an entry in `run`'s help, and it names the process-output
file) with the library's option lists, managed and no-run names in `BenchmarkArgs`, then on the
runner a subcommand class in `commands/` named by that constant that takes the arguments after `--`,
a service in `services/`, a run record in `options/`, and registration in `TestWrapper`'s
`subcommands` list. `RunnerArgsContractTest` fails until the runner and the model agree, and
`BenchmarkArgsContractIT` until the option lists match the library's `-h`.

`--s3-bucket` and `--results-table` are both required on every subcommand. `AWS_ENDPOINT_URL_S3` / `--s3-service-endpoint` and
`AWS_ENDPOINT_URL_DYNAMODB` / `--dynamodb-endpoint` redirect to LocalStack.
