# Verify — jcstress-e2e

Run 2026-10-05 against branch `jcstress-e2e` at `453a951` (PR #79 into `next-release`).

## Summary

| Dimension | Status |
|---|---|
| Completeness | 11/12 tasks before this one, now 12/12. 2 capabilities, 3 requirements (1 modified, 2 added) |
| Correctness | 3/3 requirements implemented. 9/9 scenarios covered: 4 by unit tests, 3 live or by CI, 2 by construction (W1, W2) |
| Coherence | The design is followed; one small addition is recorded under *Deviations* |

No CRITICAL issues. Two warnings, both about live-only coverage. The change is ready for archive
once PR #79 is merged.

## Requirement → code → test

### `jcstress-execution` (new)

**A JCStress run can select its mode**

Code:
- `ApiJCStressOptions.java:39`: `--mode`, long form only;
- `JCStressOptions` gains a `mode` field;
- `JCStressSubcommandService.java:55`: `addArgumentIfValueIsNotNull("-m", mode)` inside
  `jcstressProcess()` (l.41).

| Scenario | Test / evidence | Gap |
|---|---|---|
| Sanity mode is requested | `JCStressSubcommandServiceTest.aModeReachesJCStressAsItsShortOption` (l.45). Live run `20261005T213453907Z-daa28d7b`: its `jcstress-output.txt` reads `Test preset mode: "sanity"`, and it completed with one JCStress item. CI run `20261005T213752486Z-f34c851f` completed | — |
| No mode is requested | `JCStressSubcommandServiceTest.noModeAddsNoModeArgument` (l.53). It is the only change to the command line, so a run without `--mode` is unchanged | — |
| An unknown mode fails the run visibly | JCStress 0.16, run by hand: `-m bogus` exits 1, prints usage and writes no `index.html` (tasks 1.1). The runner then fails on the missing report (`JCStressSubcommandService.java:85`), which `aProcessThatWroteNoReportFailsNamingItsExitCode` (l.25) pins in general | **W1** |
| The short option keeps its meaning | `JCStressSubcommandTest.theShortMStaysTheMongoConnectionStringBesideMode` (l.15) | — |

### `ci-benchmark-execution` (modified)

**Continuous integration drives the CLI, not its own orchestrator** (MODIFIED: "One job, one run")

Code: `e2e-cloud-test.yml`, jobs `benchmark` and `jcstress` (l.224). Each job makes exactly one
`baas run` call. The new job has no provisioning, tagging or key-building steps; its shell calls
only `baas`, `jq` and `test`. Evidence: PR #79, workflow run 37376994441, both jobs green and in
parallel. Gap: —

**The self-test also covers JCStress, in sanity mode** (ADDED)

Code: `e2e-cloud-test.yml` l.224–325:
- `-- --mode sanity` (l.277) and `exclude_from_results=true`;
- `runStatus` asserted (l.294);
- one row, `benchmarkType`, a `(jcstress)` name and the four tags (l.299–309);
- `environment.json` and `jcstress-output.txt` (l.313–315);
- path filter `fake-stress-tests/**` (l.28).

| Scenario | Test / evidence | Gap |
|---|---|---|
| A JCStress regression fails CI | The job runs the runner's JCStress path end to end and asserts its outputs; it is green on PR #79. No regression was injected to see it go red | **W2** |
| The JCStress run is asserted by shape | The workflow assertions listed above. The same checks were run by hand against the live run (tasks 5.1) | — |
| A racing fixture does not make CI flaky | No assertion reads pass/fail counts, by construction. In the local JCStress run the forbidden outcome fired (`1, 1`) and the run still completed | W2 covers whether CI's own run hit the race |

## Design adherence

| Decision | Followed? |
|---|---|
| The mode is a long-only `--mode`, forwarded verbatim as `-m` | Yes. No validation and no passthrough |
| The JCStress leg is a second job, not a matrix | Yes. The setup steps are repeated, with no composite action |
| The JCStress job asserts shape and runs the whole fixture | Yes. No `-t` |
| Both jobs share the workflow's concurrency group | Yes. The group is unchanged, and the jobs ran in parallel in one workflow run |
| Comparability: no `--mode` means an unchanged command line | Yes. Pinned by `noModeAddsNoModeArgument` |

## Deviations from design or tasks

- **`BenchmarkProcessBuilder.commands()`** (l.31, a read-only copy) and **extracting
  `jcstressProcess()`** from `executeCommand()` were not in the design. They exist only so the unit
  tests can pin the JCStress command line without starting a process. Behaviour is unchanged: the
  same arguments, in the same order, plus `-m`.
- Task 2.1 says "`JCStressOptions` and its builder". The only builder is the test helper
  `JCStressOptionsBuilder`, which gained `withMode`; production code builds the record in
  `ApiJCStressOptions.getValues()`.
- **Added beyond the tasks:**
  - a CLAUDE.md gotcha ("A JCStress run's mode is `--mode`, not `-m`");
  - a header comment in `e2e-cloud-test.yml` describing the two jobs.

## Warnings

- **W1 — an unknown mode is verified only by hand.** The chain "JCStress rejects the mode → no
  report → the runner fails the run" is pinned in two halves: a manual JCStress 0.16 check, and the
  runner's generic no-report test. No live `baas run` with a bogus mode was made, since it would
  cost an instance to show a failure. If the JCStress version is bumped, repeat the manual check.
- **W2 — CI was not seen to go red.** The job proves the JCStress path works, not that it catches a
  break. Nor is it known whether CI run `20261005T213752486Z-f34c851f` hit the forbidden outcome.
  Two green runs exist (one local, one CI), and the local one did hit it. Whether the shape-only
  assertions tolerate both outcomes rests on there being no count assertion, which is true by
  construction.

## Suggestions

- **The `jmh-with-async` job's comment** (l.44–49) still says the other types "re-test
  orchestration that is identical across types". That is still true of `jmh`, but it now reads
  oddly beside a JCStress job. Reword it when that job is next touched (`detached-run`).
