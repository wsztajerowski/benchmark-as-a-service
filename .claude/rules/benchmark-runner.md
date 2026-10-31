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
  exit 2 before any AWS call, and by the runner again: those BaaS manages (JMH `-rf`, `-rff`, `-o`;
  `dir`/`libPath` inside `-prof async:`, `dir` inside `-prof jfr:`; JCStress `-r`) and those that
  measure nothing (JMH `-h`, `-l`, `-lp`, `-lprof`, `-lrf`; JCStress `-h`, `-l`, `-p`), answered with
  the local `java -jar` command. **Both match every spelling jopt-simple accepts** — `--`, `=value`,
  unique prefixes (`-pro` is `-prof`), and single-letter clusters (`-ofile` is `-o file`, which really
  redirected JMH's output) — through `BenchmarkArgs` in `baas-model`, which also reads the `-prof`
  values the runner injects into and the `profilers` tag names. Its option lists are the fixture JARs'
  `-h`, pinned by `BenchmarkArgsContractIT`; a JMH or JCStress bump fails there until they follow. A
  user's own spelling of a managed profiler key slips the check but not the job: the runner appends
  the key, and JMH refuses a key given twice, failing the profiler at start.
- **`mvn -pl benchmark-runner verify` alone fails.** It needs the `fake-jmh-benchmarks` and
  `fake-stress-tests` shaded JARs already in the local repo (`classifier=shaded`). Run the full
  reactor first.
- **JCStress writes `jcstress-results-*.bin.gz` to the module root**, not `target/`. `mvn clean`
  removes them via an extra fileset.
- **`ASYNC_PATH` gates async coverage.** `JmhAsyncProfilerIT` is annotated
  `@EnabledIfEnvironmentVariable(named = "ASYNC_PATH", ...)`, so a plain `mvn verify` **silently
  skips** the only test exercising async-profiler end to end. Export it before trusting a green
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
