# Design

## Context

User-data runs the runner under `timeout`, uploads the boot log, and terminates. The runner saves
`<type>-output.txt` and `logs/**` itself, after the benchmark exits (`JobLogs`). The benchmark's
output is redirected straight into `/app/<type>-output.txt` as it runs, so the bytes exist on disk
throughout; they are lost only because nothing uploads them when the instance is stopped early.

Termination today, by who starts it:

| Path | What reaches S3 |
|---|---|
| Normal end, `completed`/`failed:<n>` | runner's own uploads + boot log |
| Benchmark `timeout` (`failed:124`) | boot log only — the runner was killed before its uploads |
| Watchdog | boot log only |
| Refused `running` | boot log (nothing else exists yet) |
| CLI `JobStop` (Ctrl+C, `jobs terminate`, poll cap), EC2 console | nothing |

## Goals / Non-Goals

**Goals:** every row above ends with the boot log, the process output so far and the `*.log` files
in S3, with no change to the CLI, IAM, or the stored statuses.

**Non-Goals:** partial `jmh-result.json` or profiler artifacts (JMH writes the result JSON only at
the end, and a half-written flame graph is not readable); a `--skip-os-shutdown` termination, which
nothing in BaaS issues.

## Decisions

### An OS shutdown hook ships the logs, so every external termination is covered by one mechanism

User-data writes `baas-ship-logs.service` — `Type=oneshot`, `RemainAfterExit=yes`,
`ExecStart=/bin/true`, `ExecStop=` the upload, `After=`/`Wants=network-online.target` — and starts
it. systemd stops units in reverse start order on shutdown, so the upload runs while the network
and IMDS credentials are still up. EC2's default `TerminateInstances` attempts a graceful OS
shutdown (the API gained `--skip-os-shutdown` precisely to opt out of it), and billing stops at
`shutting-down`, so the time the upload takes is not paid for. `TimeoutStopSec=90` bounds a stalled
upload; after it systemd carries on shutting down.

Rejected: **the CLI asks the instance to stop itself** (instance polls its job item for
`cancelled`, uploads, terminates; the CLI waits and then terminates as a fallback). Needs a poll
loop, a `GetItem` grant for the runner and a CLI wait in the Ctrl+C path, and still misses a console
termination. **The CLI uploads the logs** — it cannot reach the instance's disk. **A CloudWatch
agent streaming logs** — a new tool in the image and a new service, for one feature.

### The hook is generated from the same function the script calls, with no shebang

`ship_logs` is defined once, before the watchdog forks (the watchdog calls it). The hook file is
`declare -f ship_logs` plus the four variables it reads, printed with `%q`, and run as
`ExecStop=/bin/bash <file>` — because a `#!` line would be stripped from `SCRIPT_BODY` like any
comment. One definition, so the explicit and the shutdown-time uploads cannot drift.

### The script's own exits keep shipping explicitly, and the double upload is accepted

The hook is best effort (it can fail to install, a shutdown can be forced), so the watchdog, the
refused-`running` exit and the normal end still call `ship_logs` before they terminate. The hook
then runs again at shutdown and overwrites the same keys with the same or longer content. No marker
file: an extra few `PutObject`s cost nothing, and the second copy of the boot log is the more
complete one.

### Keys match the runner's own, named from `baas-model`

The process output goes up as the runner uploads it: written locally to the type's
`BenchmarkType.workingOutputName()` (`jmh-output.txt` for every JMH type, `jcstress-output.txt`) and
uploaded as its `processOutputName()` (`jmh-with-async-output.txt`, …), both exported into the script
from the enum, and the runner's `--process-output` default reads the same constant. The boot log and
`logs/` come from `JobLayout` through the script's `@NAME@` placeholders. So a completed job gets
overwrites rather than a second copy. `aws s3 cp --recursive --exclude '*' --include '*.log'` from
`/app` gives `logs/<path below /app>` without listing the bucket, which `RunnerRole` cannot. Unlike
`JobLogs` it has no depth bound; `/app` is a directory the job owns, not `/`.

### async-profiler 4.6, base 1.4.0

The newest release. The breaking changes between 4.0 and 4.6 (`check` command, `safemode`,
`cstack=lbr`, the HTTP server, `filter` arguments, the `otlp` format) touch nothing the runner
passes (`libPath`, `output`, `dir`, `event`, `interval`). The tarball layout is unchanged
(`async-profiler-4.6-linux-x64/lib/libasyncProfiler.so`). Minor bump: a tool upgrade, nothing
removed — and above `slice-architecture`'s 1.3.1, which added the tarball's `sha256`. Rebased onto
it, the digest is 4.6's: `1e79ed54d8952889a2e74f993d22ee925e3322460d4f37a2ed95bfb88585da36`
(`linux-x64` tarball, computed 2026-10-21).

## Risks / Trade-offs

- [EC2 force-terminates before the hook finishes] → The upload is a few small files; the explicit
  calls still cover the script's own exits. A miss leaves today's behaviour, never worse.
- [A graceful shutdown with a hung benchmark JVM] → The hook does not wait for the JVM; it uploads
  whatever is on disk.
- [User-data size] → ~1 KB more; `aLargeJobStaysWellUnderTheUserDataLimit` still holds 12 KB.
- [Comparability] → async-profiler results from 1.4.0 images are labelled `imageVersion=1.4.0`;
  plain JMH jobs measure on the same JDK, kernel and tunables as 1.3.0.

## Migration Plan

Merge, then `baas admin image build` on each deployment for 4.6. The shutdown hook needs no image
build: it ships in user-data, so it applies to the next job from the new CLI on any AMI.
