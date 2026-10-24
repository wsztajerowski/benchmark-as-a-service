# Verify: persist-logs-on-termination

2026-10-21. Branch `persist-logs-on-termination` from `origin/next-release` (`0308fb8`).

## Summary

| Dimension | Status |
|---|---|
| Completeness | 12/14 tasks; 6.2 and 6.3 deferred (W1) |
| Correctness | 1/1 requirement, 4/4 scenarios covered (two by unit test only, W3) |
| Coherence | Design followed; one placement deviation, below |

`mvn -B clean verify` green: 894 unit tests and 42 ITs in `baas-cli`, and
`JmhWithAsyncProfilerSubcommandServiceIT` ran (not skipped, 1 test) against the async-profiler 4.6
macOS library (`ASYNC_PATH`).

## Requirement → code → test → gap

| Requirement / scenario | Code | Test | Gap |
|---|---|---|---|
| A stopped job's logs reach its prefix | `UserDataScriptBuilder`: `ship_logs`, `ship_logs_hook`, `baas-ship-logs.service` | `shipLogsUploadsTheBootLogTheProcessOutputAndEveryLogAtTheRunnersKeys`, `aFailedUploadDoesNotStopTheOthers`, `theShutdownHookShipsTheLogsWithNothingButItsOwnFile`, `theShutdownHookRunsOnStopWhileTheNetworkIsUpAndIsInstalledBeforeTheBenchmark`, `everyExitOfTheScriptShipsTheLogsBeforeTerminating` | — |
| Scenario: a cancelled job keeps its logs | systemd `ExecStop` | Live, job `20261021T092853764Z-fdd6b761` (below) | — |
| Scenario: a benchmark that hit its timeout keeps its process output | `ship_logs` at the script's normal end | `everyExitOfTheScriptShipsTheLogsBeforeTerminating`, `recordsTheOutcomeWhereTheSentinelUsedToBe` | Not run live (W3) |
| Scenario: a watchdog kill keeps the logs | `ship_logs` in the watchdog | `theWatchdogRecordsTimedOutBeforeShippingTheLogAndTerminating`, `watchdogShipsTheLogBeforeHardKillingTheInstance` | Not run live (W3) |
| Scenario: a completed job's keys are unchanged | same keys as `JmhSubcommandService`/`JobLogs` | `expectedUploads` pins them; live job `20261021T090544847Z-daec77d6` completed and its prefix holds one object per key | — |

## Live runs (task 6.1), `baas-development`, reactor CLI + `--runner-jar`

- **`20261021T092853764Z-fdd6b761`**: `jmh` `Incrementing_Synchronized -i 60 -r 10s` on c5.2xlarge
  (image 1.3.1). `running` at 09:29:41Z; `baas jobs terminate --yes` at 09:32:55Z. At 09:32:57Z the
  prefix gained `cloud-init-output.log` (12.7 KB) and `jmh-output.txt` (2 KB: 18 complete iterations,
  cut off at `Iteration  19:`). The job item says `cancelled`; there is no `jmh-result.json`. The
  script never reached its own end (the boot log stops at `Running JMH`), so these uploads came from
  the shutdown hook. Before this change the prefix would have held only `environment.json` and
  `packages.txt`.
- **`20261021T090544847Z-daec77d6`**: the same job, terminated at 09:16:35Z, two seconds before JMH
  finished by itself. Recorded `cancelled` (the CLI's write came first; the instance's `completed` was
  refused), with the measurement stored and every artifact present. It does not show which upload
  path ran, so it is not counted as evidence for the hook. It does show that a terminate racing
  completion loses nothing.

- **`20261021T111142914Z-93ba31d9`**, after the rebase onto the slice layout and the output-name fix:
  `jmh-with-async`, same benchmark. `running` at 11:13:13Z; `baas jobs terminate --yes` at 11:15:59Z.
  At 11:16:02Z the prefix gained `cloud-init-output.log` and `jmh-with-async-output.txt` (14 complete
  iterations, cut off at `Iteration  15:`), the name the runner uploads it as, and no `jmh-output.txt`
  beside it. Status `cancelled`.

## Deviations

- **Rebased onto `slice-architecture` (2026-10-21), and a naming bug fixed on the way.** The first
  version uploaded `/app/*-output.txt` under its local name. Every JMH type writes `jmh-output.txt`,
  but the runner uploads it per type (`jmh-with-async-output.txt`, `jmh-profiler-output.txt`), so a
  stopped profiler job would have left its output beside the expected key, not at it. The live run
  was a plain `jmh` job, which is why it did not show. Both names now come from `BenchmarkType`
  (`workingOutputName`, `processOutputName`), which the runner's default also reads; pinned by
  `aStoppedProfilerJobsOutputLandsAtTheNameTheRunnerUploadsItAs` and `BenchmarkTypeTest`. The boot
  log and `logs/` names come from `JobLayout` through the refactor's `@NAME@` placeholders.

- Task 2.3 said "right after the watchdog starts". The hook is installed after the `running` write
  instead: that write must stay first after the watchdog, because everything before the JVM has to
  fit in the watchdog margin, and the refused-`running` exit calls `ship_logs` itself anyway. Pinned
  by `theShutdownHookRunsOnStopWhileTheNetworkIsUpAndIsInstalledBeforeTheBenchmark`.

## Warnings

- **W1**: tasks 6.2 (image build of 1.4.0 on a second deployment) and 6.3 (score comparison) were
  deferred. `baas-development` holds a 1.3.1 image that another session built from the unmerged
  `slice-architecture` branch on 2026-10-21, and a build from this branch would replace it under
  that session. async-profiler 4.6 is verified only through the local IT on macOS and the tarball
  listing, not on a baked AMI. Run 6.2/6.3 when `baas-development` is free.
- ~~**W2**~~: closed by the rebase onto `slice-architecture`: `infra/runner-image.yaml` is 1.4.0 with
  async-profiler 4.6 and its `sha256`.
- **W3**: the benchmark-timeout and watchdog scenarios are covered by stub-`aws` unit tests, not
  by a live run.
