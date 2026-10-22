# Verify — runner-shell-access

Run 2026-10-22 on branch `runner-shell-access` (last code commit `45f2b27`, `baas jobs connect`; `mvn test` for
`baas-model` + `baas-cli`: 102 + 1001 green, `DynamoDbJobRecorderIT` green on LocalStack;
`openspec validate --strict` valid).

## Summary

| Dimension | Status |
|---|---|
| Completeness | 27/27 tasks (this one included); 21 requirements across 5 capabilities, all implemented |
| Correctness | 21/21 requirements covered by code; 19 by automated tests, all 21 by the live checks below; 1 divergence (W1), resolved by a spec amendment |
| Coherence | Design followed, with four recorded deviations (D1–D4); 3 warnings, 2 suggestions |

No critical issues. Two open warnings (W2, W3); W1 resolved. Ready for archive with the noted improvements.

## Requirement → code → test → gap

`C` = `baas-cli/src/main/java/pl/wsztajerowski/baas`, `T` = its `src/test` twin.

| Requirement | Code | Tests | Gap |
|---|---|---|---|
| **runner-shell-access** | | | |
| `jobs connect` opens a shell on a job's live instance | `C/jobs/JobsConnectSubcommand.java:110-139`, `AwsConnectSources.java` | `T/jobs/JobsConnectCommandTest` (`aRunningJob…`, `anUnknownId…`) | Shell as `ssm-user` in `/app`: live only. Lookup by job tag (W1, resolved) |
| Connecting is decided by the instance, not the status | `JobsConnectSubcommand.java:124-130` | `aJobStillLaunched…`, `aFinishedJob…`, `aJobStillReserving…`, `anInstanceWhoseAgent…` | — |
| Any job of the deployment, whoever launched it | nothing distinguishes launchers | none specific | A CI-launched job was not connected to live; detached jobs from a separate process were — **S1** |
| Missing plugin refused before any session | `SessionPlugin.java`, `JobsConnectSubcommand.java:117` | `aMissingPluginIsRefusedBeforeAnyAwsCall`, `SessionPluginTest` | "Other commands do not need it": by construction (`SessionPlugin` used only by `connect`), no test |
| The AWS CLI is not required | `SessionLauncher.java` | `thePluginGetsTheAwsCliArgumentsAndNoCredential` | Never run on a machine without `aws` |
| States when the instance ends, never extends it | `WatchdogDeadline.java`, `JobsConnectSubcommand.java:132,159-190` | `theDeadlineIsPrinted…`, `aJobWithoutARecordedBound…`, `anInstanceThatTerminatesEndsTheSession`, `aPluginThatDoesNotLeave…` | — (found live as a bug; the fix is folded into `45f2b27`) |
| Sessions leave no record in the job's results | nothing writes one | none | Live: connected jobs' tags equal untouched jobs' (score table below) |
| **job-tracking** | | | |
| The reservation records the watchdog bound | `baas-model/…/JobItemMapper.java:29,57,94`, `C/jobs/JobLaunch.java:160` | `JobItemMapperTest`, `RunCommandLaunchTest.theReservationRecordsTheWatchdogBound`, `JobRecorderContract.theWatchdogBoundSurvivesEveryLaterWrite` (fakes + LocalStack), `UserDataScriptBuilderTest.theInstanceNeverWritesTheWatchdogBound` | — |
| The `jobs` group is subcommands only (+ `connect`) | `C/jobs/JobsCommand.java` | `CommandTreeTest`, `HelpAndHintsTest` | `JobsCommandTest`'s usage assertion names only `list`, `terminate` |
| `jobs show` reports one job (+ deadline) | `C/jobs/JobsShowSubcommand.java:131-147,204` | `JobsShowCommandTest` (4 new), `Ec2ProvisioningServiceTest.aJobsLiveInstanceCarriesItsLaunchTime` | — |
| **cli-command-structure** | | | |
| Nouns with verbs, aliases only for one-noun verbs | `JobsCommand.java` | `CommandTreeTest` (root has no `connect`) | — |
| **core-stack-provisioning** | | | |
| The stack owns the runner session document | `infra/cf-template-core.yaml:432` | `CoreTemplateTest.theSessionDocumentIsTheStacksOwnAndLogsNothing` | Created/deleted/recreated live (6.1); an in-place content update never exercised — **W2** |
| Every runner registers with Session Manager | `cf-template-core.yaml:401` | `theRunnerRegistersForSessionsAndNothingMore`, `theRunnerSecurityGroupStillHasNoIngress` | — (agent Online ~24 s after launch, live) |
| Operator role permissions (+ sessions) | `cf-template-core.yaml:800`, `infra/operator-policy.json` | `theOperatorStartsSessionsOnlyOnItsOwnRunnersWithItsOwnDocument`, `OperatorPolicyDriftTest` | `OpenDataChannel` resource unexplained — **W3** |
| Deployer policy covers the full setup path (+ document) | `infra/deployer-policy.json:76` | `DeployerPolicyTest.managesOnlyItsOwnSessionDocument`, budget test, `DeployerPreflightTest` | — (setup create/update/teardown live) |
| **runner-image-provisioning** | | | |
| The contract fails a bake that breaks a BaaS workflow (+ agent) | `C/image/RunnerImageRenderer.java:233` | `RunnerImageRendererTest.theContractChecksEverything…` | Passing bake live (1.4.0, twice); a failing bake not exercised — **S2** |

## Warnings

- ~~**W1**~~ — *resolved 2026-10-22 by amending the spec, not the code.* `connect` finds the instance by
  its `baas-job-id` tag within the deployment (`JobsConnectSubcommand.java:124`), while the spec said
  it took the item's recorded `instanceId`. The two differ when the CLI's best-effort `launched` write
  failed and the instance has not yet written `running`: by tag the job is reachable, by item it is not
  — and if the instance hangs before `running`, never. The owner chose the tag lookup; the requirement,
  its scenarios (one added) and the design now say so.
- **W2 — the session document's in-place update is pinned, not exercised.** `UpdateMethod: NewVersion`
  is pinned by `CoreTemplateTest` and is what the schema says lets a named document change content, but
  no deployment has yet taken a content change. *Recommendation:* the next change that edits the
  document checks its setup update live; nothing to do now.
- **W3 — the `OpenDataChannel` statement works, but not visibly because of its resource.** The resource
  `session/${aws:userid}-*` looks as if it cannot match a session id `baas-cli-<random>`, yet sessions
  work. Either AWS authorises the data channel against something else, or this statement grants nothing
  and another path allows it. *Recommendation:* leave it as designed (no wider than documented); if it
  matters later, test by removing the statement on `baas-development` and opening a session.

## Suggestions

- **S1** — connect to a CI-launched job from a laptop once, when `e2e-cloud-test.yml` next runs with
  this CLI, to see the documented use case end to end.
- **S2** — a failing contract (an extension that disables `amazon-ssm-agent`) was not baked; the check
  is one line beside checks whose failure path is already exercised by design. Worth trying only if the
  contract changes shape.

## Deviations from design and tasks

- **D1** — the reservation stores `watchdogSeconds`, not a `terminatesBy` timestamp; the deadline is
  `LaunchTime + watchdogSeconds` (design, *The reservation stores the watchdog bound*).
- **D2** — no shared contract suite for `connect`'s AWS port: LocalStack does not emulate Session
  Manager; `AwsConnectSources` is exercised only live (tasks 4.2 note). SDK types moved out of the
  command by `SliceBoundaryTest`.
- **D3** — `connect` watches the instance while the plugin runs (15 s) and ends the session when it
  goes — added after a live hang; the spec already required it.
- **D4** — task 1.2 ran on the branch's deployed stack instead of temporary policies; the setup
  preflight now makes 15 `SimulatePrincipalPolicy` calls (CLAUDE.md and `baas-setup.mmd` updated).

## Live checks on `baas-development` (2026-10-22, this branch's JAR and stack)

- Setup (update) with the branch's template: the deployer preflight first refused the missing
  `SsmSessionDocument` statement and created nothing; after the user published the new policy version,
  setup passed the preflight and updated the stack in about a minute.
- Job `20261022T125053111Z-7fcf8587` (c5.large): agent `Online` 24 s after launch.
  `StartSession` with `baas-development-session-runner` allowed; own `TerminateSession` allowed;
  no document, `SSM-SessionManagerRunShell` and `AWS-StartPortForwardingSession` denied. `jobs connect`
  after the job completed: refused, naming `completed`.
- Job `20261022T130353548Z-c4baa457` (c5.large): `jobs connect` while booting → "not reachable yet";
  ~20 s later a shell as `ssm-user` in `/app`, `exit` → CLI exit 0; deadline line printed
  ("about 13:49 UTC").
- Agent cost, same job, 8.5 min uptime / 7.5 min benchmarking, one session attached:
  `amazon-ssm-agent` and `ssm-agent-worker` under 1 s CPU each; the forked benchmark JVM 899 s.
  Under about 0.1% of the benchmark's CPU. One job only.
- `ps` showed a session `baas-cli-25vqp7s7liiyndc5x5cjkku7qa` (started ~13:07 UTC): the user's own,
  testing what happens when the instance terminates under a session. It **hung** on the dead prompt
  after the instance terminated itself at 13:15:42 UTC — Session Manager does not close a session
  whose target is gone, and the plugin has no escape key. Ended server-side with `TerminateSession`.
  This broke the requirement "when the instance terminates, the session ends"; fixed in `connect`
  (instance check every 15 s while the plugin runs).
- After the fix, job `20261022T132452435Z-20d8c421`: a session held open with no input; when the job
  finished and the instance terminated, `connect` printed "Instance i-050368d9e84ee25df has terminated;
  ending the session.", the plugin left on its own ("Exiting session…") and the CLI exited 0.

- **Task 6.1, second deployment cycle** (2026-10-22, 15:40–16:19 local): `admin image build` built
  and published 1.4.0 (`ami-093dffb26a7a142c6`) — the contract's test phase passed, the new
  `amazon-ssm-agent` check included; a job on it (`20261022T135014998Z-f1242a48`) was connected to
  (`/etc/baas-image-version` = 1.4.0, `ssm-user`, `/app`, exit 0); `admin deployment teardown --yes`
  emptied the bucket, deleted the stack and retired the image, and `describe-document` then answered
  *does not exist*; `admin deployment setup --build-image` (same region and GitHub federation)
  recreated the stack — the deployer preflight passing — and image 1.4.0 (`ami-098da03cae0f78b8e`),
  with `baas-development-session-runner` back, `Active`, version 1.

## Score comparison (task 6.3) — data, saved before the 6.1 teardown deleted the table

`fake-jmh-benchmarks` `Incrementing_Synchronized`, c5.large, thrpt, `-f 1 -r 10s`, all on
`baas-development`. Unregistered = arm A of the 2026-10-21 spike (the agent as before this change);
registered = arm B and every job since this change's grants were deployed.

| Job | Agent | Image | CPU | Iters | Mean ops/s | Within-job shape |
|---|---|---|---|---|---|---|
| 0b7e3f4c | unregistered | 1.3.1 | 8124M | 10 | 63.04M | flat (cv 1.3%) |
| a3ec6845 | unregistered | 1.3.1 | 8124M | 10 | 61.16M | |
| 6e5c1e37 | unregistered | 1.3.1 | 8124M | 10 | 62.13M | |
| a67884d3 | unregistered | 1.3.1 | 8124M | 10 | 60.70M | |
| 2ca66034 | unregistered | 1.3.1 | 8275CL | 10 | 65.48M | |
| cdba0f3a | unregistered | 1.3.1 | 8275CL | 10 | 58.49M | |
| c2a7f558 | registered | 1.3.1 | 8124M | 10 | 63.07M | |
| 055d670e | registered | 1.3.1 | 8124M | 10 | 46.45M | flat but low (cv 0.2%) |
| 0ec22d3d | registered | 1.3.1 | 8124M | 10 | 59.05M | |
| 84b27bec | registered | 1.3.1 | 8124M | 10 | 62.37M | |
| d8bb525e | registered | 1.3.1 | 8124M | 10 | 61.79M | |
| 1fc3facb | registered | 1.3.1 | 8223CL | 10 | 61.74M | |
| 7fcf8587 | registered, probes | 1.3.1 | 8124M | 60 | 57.02M | **two levels**: ~47.5M (iters 1–5, 12–29) / ~62–63M |
| c4baa457 | registered, 2 sessions | 1.3.1 | 8275CL | 60 | 64.50M | flat (cv 1.1%) |
| 20d8c421 | registered, idle session | 1.3.1 | 8124M | 6 | 61.81M | flat |
| f1242a48 | registered, session at start | 1.4.0 | 8223CL | 12 | 57.14M | **step**: ~63M (1–5) then 53.3M flat (6–12) |
| a5022e56 | unregistered (2026-10-15) | 1.3.0 | 8124M | 1 | 56.79M | one iteration |

Reading: the *fast* level is the same with and without registration (~61–63M on 8124M). What differs is
a **slow plateau** — a steady 15–25% drop lasting minutes, then sometimes back — seen in 3 of 10
registered jobs and 0 of 6 unregistered ones. The unregistered jobs ran only 100 s each (600 s in
all) against ~1900 s registered, so the counts alone cannot separate "the agent" from "rare and
longer runs see it more". In `7fcf8587` the first slow block (12:51:38–12:52:28 UTC) overlaps the
agent's first minute after registering and the operator's `StartSession` probes; the second
(12:53:28–12:56:28) overlaps nothing known. A drop of ~24% held steady is the signature of the core's
sibling hyperthread being busy (a c5.large is one core, two threads); the agent's own measured CPU
(under 1 s per process over 8.5 min in `c4baa457`) argues against the agent, but that job never hit
the slow level.

### Investigation of the slow plateau (2026-10-22, 14:24–14:36 UTC)

Four c5.large jobs, 60 iterations each, launched together; in each, one early session started
`top -b -d 5` writing to `/app/top-sampler.log` (shipped by the runner with the job's logs). B1/B2 kept
the agent; S1/S2 then ran `systemctl stop amazon-ssm-agent`. (B1's and B2's sampler did not start —
their second command reached the shell too early — so only S1's log is usable.)

| Arm | CPU | Mean | Slow plateaus |
|---|---|---|---|
| B1, agent | 8223CL | 62.0M | none |
| S1, agent stopped 14:26:43 | 8124M | 57.0M | **49.6M, iters 12–23 and 30–41** — both after the stop |
| B2, agent | 8124M | 53.8M | 36.9M (iters 3–5), then 54.2–54.8M levels |
| S2, agent stopped 14:27:39 | 8124M | 60.8M | 51.8M (iters 6–11), 59.1M (54–59) |

S1 settles it: no SSM process appears in any of its 110 samples after the stop, yet it spent 24 of 60
iterations on a flat 49.6M plateau. Through fast and slow iterations alike, `top` shows one process
above 3% CPU — the benchmark JVM at 200% — with 0% steal. The cause is the benchmark:
`Incrementing_Synchronized` runs `@GroupThreads(2)`, two threads contending for one `synchronized`
lock on the two hyperthreads of one core, and a contended lock's throughput settles into distinct steady
modes (how the lock is handed over — spinning or parking, and which thread wins) and switches between
them. The spike's six unregistered runs (100 s each) simply landed in the fast mode.

**Conclusion for 6.3:** no score difference attributable to this change. The fast mode is the same
with and without a registered agent (~61–63M on 8124M), and the slow modes occur with the agent
stopped. This benchmark is a poor probe for small environment effects: a comparison that must see a few
percent needs an uncontended benchmark or several forks.

### Further 6.2 checks

- `ssm:SendCommand` (`AWS-RunShellScript`) as the operator: denied.
- Deadline printed for `c4baa457` ("about 13:49 UTC") equals its launch (~13:04:00) plus
  `--timeout 2400` plus the 300 s margin.
- Every connected job was launched `--detach` and reached by id from a separate process.

