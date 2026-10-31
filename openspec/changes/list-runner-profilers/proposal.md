# Proposal

## Why

JMH's `-lprof` lists the profilers *of the host it runs on*, and which ones exist depends on the JMH
version inside the benchmark JAR. BaaS refuses `-lprof` and points at `java -jar … -lprof` on the
laptop, so the only answer anyone can get describes the wrong machine: no `perf`, no baked
async-profiler, a different JDK and kernel. The one honest answer is to run the listing on a runner,
with the user's own JAR.

## What Changes

- `baas run jmh … -- -lprof` runs as an ordinary job on the runner image and prints JMH's profiler
  listing for that image, that instance type and the JMH version in `--benchmark-jar`. The job ends
  `completed` and stores no measurements and no result JSON; its listing is the job's
  `jmh-output.txt`.
- `-lprof` is accepted **only on its own** after `--`. Anything else with it — a benchmark regex, `-f`,
  `-prof` — is refused with exit 2 before any AWS call, and again by the runner, because JMH would
  list and exit, silently ignoring the rest.
- `-lprof` leaves the no-run set. The other no-run options (`-h`, `-l`, `-lp`, `-lrf`; JCStress `-h`,
  `-l`, `-p`) keep their local-command refusal: they read only the JAR, so the laptop's answer is
  the right one. The `-lprof` caveat on that hint ("lists this machine's profilers") goes away.
- In text mode `baas run` prints the listing job's `jmh-output.txt` **verbatim** on stdout — JVM
  warnings included — instead of the measurement table, and its next-step hint is only
  `jobs download <jobId>`. `--format json` prints the summary object, unchanged.
- The runner puts both `LD_LIBRARY_PATH` and `JAVA_LIBRARY_PATH`, set to the async-profiler library
  directory, on **every** JMH process, on every OS, and no longer appends `libPath=` to `-prof async`.
  Linux's JVM reads the first, macOS's the second (which, unlike `DYLD_LIBRARY_PATH`, survives SIP's
  `/usr/bin/java`), so the runner works the same on the image and on a developer's Mac. That is how
  JMH finds the library in the listing — which has no `-prof` value to append to — and it leaves one
  mechanism, not two, for where async-profiler lives. `dir=` is still appended for `async` and `jfr`.
- `libPath` stays managed and refused: BaaS owns the async-profiler version and location, and a user
  `libPath` would override the environment variable.
- The managed-profiler-key check refuses **every prefix spelling** of a managed key (`libP=`, `di=`,
  `l=`), not only the exact key. Without the appended `libPath`, nothing behind the exact-match check
  stopped `-prof async:libP=/x` from loading another library; `dir` follows the same rule.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `benchmark-arguments`: `-lprof` alone becomes a listing job instead of a no-run refusal; the
  async library reaches JMH through the process environment instead of an injected `libPath`;
  managed profiler keys are refused in every prefix spelling.

## Impact

- **Code:** `baas-model` `BenchmarkArgs` (listing predicate, no-run set, prefix rule for managed
  profiler keys); `benchmark-runner` `JmhSubcommandService` (environment variable, listing branch,
  no `libPath` append), `BenchmarkArguments`, `BenchmarkProcessBuilder` (environment); `baas-cli`
  `RunCommand` (refusal, listing output, hints).
- **Docs:** `.claude/rules/benchmark-runner.md` (no-run list, the "spelling slips the check"
  caveat), `run`'s help text.
- **Comparability:** every JMH job's process environment gains `LD_LIBRARY_PATH` and
  `JAVA_LIBRARY_PATH`, both pointing at a directory holding only async-profiler's library. No benchmark command line changes for unprofiled
  jobs; profiled `async` jobs lose the appended `libPath=` but load the same library. Not recorded
  in `environment.json`; the change date is the boundary, as for the session-agent change.
- **Cost:** no AWS resource changes and no standing cost. A listing job costs one ordinary job's
  boot (1–2 minutes of the chosen instance type).
- **Deliberately not changed:** the runner image and its contract (no probe at bake time, no
  JMH download), IAM, the bucket layout, the results table, the job item, the command tree (no new
  noun), and JCStress. `cliParameters` and the `profilers` tag never recorded the injected keys, so
  no stored value changes meaning. The managed list itself is unchanged.
- **Findings:** closes none in `docs/review/open-findings.md`.
