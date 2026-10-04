# Verify: run-status-in-dynamodb

Evidence collected during `/opsx:apply`, then the requirement → code → test → gap table from
`/opsx:verify` (task 14.1).

## Section 1: blocking assumptions (2026-10-03)

### 1.1 `dynamodb:LeadingKeys` per action

**Deviation:** `aws iam simulate-custom-policy` is not available. The deployer user
`baas-admin-wiktor` is denied `iam:SimulateCustomPolicy`, and widening the deployer policy for a test
was ruled out. The check moved to after task 2.2:
- `simulate-principal-policy` against the *deployed* `RunnerRole` and `OperatorRole`, which tests the
  real policies rather than candidates;
- plus the live AccessDenied checks of task 13.7.

The result is recorded under task 2.2 below.

**Result:** see *Task 2.2* below. Every case matches the design. ✓

### 1.2 AWS CLI on the runner image

The newest run's `environment.json` (`runs/benchmark-as-a-service/20261002T195517542Z-6c4ff89e/`,
image `1.2.0`, `ami-0ae60d9d990dc00df`) reports
`aws-cli/2.33.15 Python/3.9.25 Linux/6.1.177-224.371.amzn2023.x86_64`. That is v2, which supports
`AWS_RETRY_MODE`, `AWS_MAX_ATTEMPTS`, `--cli-connect-timeout`, `--cli-read-timeout` and
`update-item --condition-expression`. ✓

### 1.3 The designed condition, against LocalStack 3.8

The condition was
`attribute_exists(pk) AND (attribute_not_exists(#status) OR NOT (#status IN (:completed, :timedOut,
:cancelled, :launchFailed) OR begins_with(#status, :failedPrefix)))`.

| Case | Exit | Item after |
|---|---|---|
| `completed` → `running` | 254, `ConditionalCheckFailedException` | `completed` |
| `failed:3` → `completed` | 254, `ConditionalCheckFailedException` | `failed:3` |
| missing key → `running` | 254, `ConditionalCheckFailedException` | none (no item created) |
| `launching` → `running` | 0 | `running` |

All as designed. ✓

**Note carried into 6.1:** a missing key also surfaces as `ConditionalCheckFailedException`, so the
shell's log line for it reads "status already terminal, or no run item at this key". It does not
claim the first only. Nothing is created either way, which is the property that matters.

### 1.4 `--no-database` in workflows and scripts

`git grep -n -e '--no-database' -- .github scripts` finds nothing (exit 1). ✓

## Task 2.2: stack update, and the deployed roles (2026-10-03)

`java -jar baas-cli/target/baas-cli.jar admin setup`, built from `run-status-in-dynamodb` after
task 2.1, reported `Updating stack baas-381492019823... updated successfully` (00:19:56 → 00:20:57).
The deployer policy is unchanged.

`aws iam simulate-principal-policy` against the deployed roles, with a `dynamodb:LeadingKeys`
context entry (this replaces 1.1's `simulate-custom-policy`):

```
runner dynamodb:UpdateItem RUN => allowed
runner dynamodb:UpdateItem RESULT#x => implicitDeny
runner dynamodb:PutItem RESULT#x => allowed
runner dynamodb:PutItem RUN => implicitDeny
runner dynamodb:BatchWriteItem RESULT#x => allowed
runner dynamodb:BatchWriteItem RUN => implicitDeny
runner dynamodb:DeleteItem RUN => implicitDeny
operator dynamodb:UpdateItem RUN => allowed
operator dynamodb:UpdateItem RESULT#x => implicitDeny
operator dynamodb:PutItem RUN => implicitDeny
operator dynamodb:PutItem RESULT#x => implicitDeny
```

### 13.7 live checks, run now that the stack is updated

As `baas-operator-381492019823` against `baas-381492019823-results`, each write conditioned on
`attribute_exists(pk)` so that nothing could be written even if a check were wrongly allowed:

| Call | Result |
|---|---|
| `update-item` on `RESULT#verify-deny` | `AccessDeniedException` ✓ |
| `put-item` on `RUN` | `AccessDeniedException` ✓ |
| `update-item` on `RUN` | `ConditionalCheckFailedException`: IAM allowed it, and the condition stopped the write ✓ |

The runner's deny cases are covered by the simulation above. Exercising them live would need
credentials for `RunnerRole`, which only the instance profile holds.

## Deviations found during apply

- **D1, the request-ID index filter.** The design named `#gsi1sk <> :run`. DynamoDB rejects it:
  "Filter Expression can only contain non-primary key attributes: Primary key attribute: gsi1sk"
  (6 errors in `ResultsQueryServiceIT`). It was replaced by `attribute_exists(#kind)`, since `kind`
  is on every measurement and on no run item. `MeasurementItemMapper.KIND` became public for it.
  design.md and tasks.md were corrected.
- **D2, terminating on a terminal status the CLI did not write.** The spec said any such status. That
  would include `completed` and `failed:<n>`, which the instance writes moments before uploading its
  boot log and terminating itself, and terminating it from the CLI could cut that upload off (the
  hazard the old shutdown-hook comment recorded). It was narrowed to `cancelled` and `timed-out` in
  the spec and the design. Pinned by `RunSessionTest.aCancellationFromElsewhereStillTerminatesALiveInstance`
  and `completionExitsZero`.
- **D3, task 6.3's "hang" case.** A stub `aws` cannot exercise the AWS CLI's own connect and read
  timeouts, so the bound is asserted instead:
  `UserDataScriptBuilderTest.retrySettingsApplyToTheStatusCommandOnly` checks the attempt cap,
  `--cli-connect-timeout 5` and `--cli-read-timeout 10`. The other three outcomes run against the
  stub. *Corrected after the post-apply review (finding 6):* the original "3 × 15 s = 45 s" left out
  standard-mode backoff (up to 2 s, then 4 s), so 3 attempts cost ~51 s. And the right comparison
  is not one write against the 60 s floor: that margin has to cover everything before the JVM starts
  (boot, IMDS, the manifest, four `s3 cp`) as well. The `running` write, the only one on that path,
  now makes 2 attempts (~32 s); the terminal writes keep 3, since nothing waits on them.
- **D4, the duration column.** The spec asked for a duration. An ended run's end time is not
  recorded, because the instance writes no timestamp by design (so that "the instance's clock never
  reaches the record" holds). The column became ELAPSED, shown for runs in flight and `—` otherwise.
  A SOURCE column was added, per explore decision 12. The spec was updated.

## Post-apply review (2026-10-04)

A reviewer agent read `origin/main...HEAD` and found one High, two Medium and six Low findings, no
Blocker. All nine are fixed in this change:

| # | Finding | Fix | Pinned by |
|---|---|---|---|
| 1 High | A Ctrl+C while `RunInstances` is in flight records `cancelled`, but the tag lookup can miss an instance AWS has not created yet or `DescribeInstances` does not show yet. The instance's refused `running` write was "expected", so the cancelled run went on to run the whole benchmark, paid, and hidden from `--in-flight` | A refused `running` write makes the instance ship its boot log and terminate without starting the benchmark. Design, spec, CLAUDE.md layer 3 and `baas-run.mmd` no longer claim that the tag lookup is guaranteed | `UserDataScriptBuilderTest.aRefusedRunningWriteShipsTheLogAndTerminatesInsteadOfRunningTheBenchmark`, `aRecordedOrFailedRunningWriteLetsTheBenchmarkStart`, `RunSessionTest.anInterruptWhoseInstanceIsNotYetVisibleStillRecordsTheCancellation` |
| 2 Medium | User-data reached 14,113 bytes raw for a representative CI run, against EC2's 16,384 | Comment-only lines are stripped from the body when it is rendered. The same outsized run is now 8,247 bytes | `aLargeRunStaysWellUnderTheUserDataLimit` (under 12 KB), `commentLinesAreNotRendered` |
| 3 Medium | `baas runs terminate` failed with "No run found" on a run launched by a pre-change CLI, though teardown points at it | With no item, it falls back to the run-id tag, confirms, and terminates without recording anything | `RunTerminationTest.aRunWithNoItemIsStoppedByItsInstanceTag`, `…LeftAloneWhenTheConfirmationIsDeclined` |
| 4 Low | The poll cap checked the clock before reading the item, so it could report `timed-out` for a run that had just completed, and cut off its log upload | The cap reads the item first. If its own write is refused, it reports the status that stands | `RunSessionTest.theCapHonoursAnOutcomeRecordedDuringTheLastSleep`, `theCapReportsAnOutcomeThatBeatItsOwnWrite` |
| 5 Low | `stop` terminated even when its write was refused over `completed`/`failed:<n>` (D2's hazard, from the hook) | On a refusal, `stop` reads the item and leaves an instance that recorded its own outcome alone (`RunStatus.isRecordedByInstance`) | `RunSessionTest.anInterruptAfterTheInstanceRecordedItsOutcomeLeavesItToTerminateItself`, `anInterruptAfterACancellationFromElsewhereStillTerminates` |
| 6 Low | D3's arithmetic | See D3 above. The `running` write makes 2 attempts | `retrySettingsApplyToTheStatusCommandOnly` |
| 7 Low | A user-data comment broke off mid-sentence | Rewritten | — |
| 8 Low | `runs list --in-flight` pages the whole `RUN` partition | A note in design.md *Risks*, with the bound to add if it ever matters | — |
| 9 Low | CLAUDE.md's *Results table* opened with "one item per measurement" only | Now names both kinds of item | — |

`mvn verify` over the whole reactor: baas-model 73 unit tests; baas-cli 525 unit tests and 40 ITs;
benchmark-runner 39 unit tests and 17 ITs. 0 failures. The `ASYNC_PATH` IT is skipped as usual.

Two live runs with the new user-data (`c5.2xlarge`, image `1.2.0`, both tagged
`exclude_from_results=true`):

| Check | Run | Result |
|---|---|---|
| A normal run still completes (comment stripping, `run_status running 2`) | `20261004T092343834Z-95bfb59f` | ✓ `completed`, exit 0, 1 measurement stored |
| Finding 1, live: cancelled before the instance boots, with no CLI left to terminate it | `20261004T092538415Z-acda115b`: CLI `kill -9`'d at `Run ID`; `cancelled` written as the operator 12 s after launch, conditioned on `launching`/`launched`; the instance left alone | ✓ boot log: `run_status: running not recorded: …` then `Run already has an outcome, or has no run item: not starting the benchmark.` The instance was `shutting-down` 24 s after launch. The prefix holds `cloud-init-output.log` and `input/` only, with no `environment.json` and no JMH output. The item stays `cancelled`. Before the fix, this run would have executed all 18 iterations |

## Section 13: end-to-end, live (2026-10-03, account 381492019823, eu-central-1)

Three paid runs, plus one launch that failed before any instance existed. Image `1.2.0`,
`ami-0ae60d9d990dc00df`, `c5.2xlarge`, `fake-jmh-benchmarks` `Incrementing_Synchronized`. Every run
was tagged `exclude_from_results=true`.

| Task | Run | Result |
|---|---|---|
| 13.1 | `20261003T070025527Z-5bc8461b`: `origin/main`'s CLI (`acbbd1f`), `jmh` | ✓ completed, exit 0, 1 measurement stored. The narrowed `PutItem` (`RESULT#*`) accepts today's runners. No run item existed before it |
| 13.2 | `20261003T070251157Z-eb960685`: this branch, `jmh-with-async` | ✓ sampled `launched` (09:03:04, with the instance id) → `running` (09:03:20) → `completed` (09:03:53). `launching` lasted under the sampler's 3 s. The prefix holds `cloud-init-output.log`, `environment.json`, `jmh-result.json`, the output, the flamegraphs and the JFR, and **no `run-status`** |
| 13.3 | `20261003T070631715Z-c70ac6f5`: `kill -9` of the CLI 5 s after launch | ✓ `runs list --in-flight` shows it `running` with its instance. `runs terminate --yes` exits 0; the item reads `cancelled` and the instance `shutting-down`. A second `terminate` reports "already ended (cancelled)"; an unknown id exits 1 |
| 13.4 | — | **Not run live (W1).** See below |
| 13.5 | `20261003T070410263Z-1f314cba`: `--instance-type x9.notreal`, free | ✓ `launch-failed` with `InvalidParameterValue` on the item and in `runs list`. `baas download <runId>` fetched `launch-error.txt` plus `input/`. Also exposed a real bug, fixed in `7a99dca`: see D5 |
| 13.6 | during 13.3, deployer credentials, stdin `no` | ✓ "Aborting: 1 run is still in flight", naming the run id from the tag, the instance and its state, and `baas runs terminate <runId>`; exit 1. Gate 2 (typing the stack name) was never reached |
| 13.7 | — | ✓ recorded under task 2.2 above |
| 13.8 | `origin/main`'s CLI, after run items existed | ✓ as the proposal states. `results --all-projects --all-runs` and `download <runId>` fail with `NullPointerException: Name is null` at `MeasurementKind.valueOf`; `results --project benchmark-as-a-service --all-runs` returns 5 rows. Nuance: without `--all-runs`, the old exclusion filter hid these run items, because they carry `exclude_from_results=true`. Any non-excluded run item makes it fail |
| 13.9 | 13.2's score against recent CI runs of the same benchmark and type | ✓ 10,874,619 ops/s, against CI 11,636,619 / 13,118,215 / 10,592,628 (2026-10-02). Inside both that range and the recorded historical spread (10.0M–29.6M). No difference to investigate: the only user-data change on the measurement path happens before the JVM starts |

### W1: the watchdog and the poll cap were not exercised live

Neither path is reachable live with any existing fixture:
- The watchdog fires at `timeout + margin` after launch. The process `timeout` fires at `timeout`
  after the JVM starts, which is within about 40 s of launch. The margin is at least 60 s, so the
  process `timeout` always ends the benchmark first, and the run records `failed:124`.
- The CLI's poll cap equals the watchdog bound, so the instance's own outcome lands first.
- Reaching either needs a benchmark process that ignores SIGTERM, which no fixture provides.

Coverage instead:
- `UserDataScriptBuilderTest.theWatchdogRecordsTimedOutBeforeShippingTheLogAndTerminating`
  executes the rendered watchdog subshell under bash against a stub `aws`, and asserts the
  `timed-out` write, then the log upload, then the termination.
- `RunSessionTest.theCapRecordsATimeoutNotACancellation` covers the poll cap.

Closing W1 would take a SIGTERM-ignoring fixture benchmark. That is a separate change.

### D5: the instance type was sent as `'null'` for any type the SDK's enum does not know

`InstanceType.fromValue("x9.notreal")` becomes `UNKNOWN_TO_SDK_VERSION`, which serialises as
`'null'`, so EC2 answered "Invalid value 'null' for InstanceType". The bug predates this change, and
it affects every instance family released after the SDK was built. Fixed in `7a99dca` by passing the
string, pinned by `Ec2ProvisioningServiceTest.anInstanceTypeTheSdkDoesNotKnowIsSentAsTyped`.
Cosmetic, fixed in the same commit: `runs list`'s PROJECT column grew from 20 to 24, the width
`baas results` uses. `benchmark-as-a-service` had shifted the row.

## Task 14.1: `/opsx:verify` (2026-10-04)

Run after the post-apply review fixes (`b7577c8`, `561105a`). `openspec validate` passes. Tasks:
45/46 once this entry is recorded. 13.4 stays open, see W1.

### Requirement → code → test

`RS` = `RunSessionTest`, `RT` = `RunTerminationTest`, `UD` = `UserDataScriptBuilderTest`,
`REC` = `DynamoDbRunRecorderIT`, `RQ` = `ResultsQueryServiceIT`, `CT` = `CoreTemplateTest`.

| Spec · requirement | Code | Tests | Gap |
|---|---|---|---|
| run-tracking · one item in one `RUN` partition | `ResultKeys.runSortKey`, `RunItemMapper`, `DynamoDbRunRecorder.reserve` | `RunItemMapperTest` (6), `ResultKeysTest.runSortKey*`, `noMeasurementIndexSortKeyCanEqualTheRunItems`, `REC.theReservationCreatesACompleteItem`, `REC.listingIsNewestFirstAcrossProjects…`, `REC.aRunIsFoundByItsIdThroughTheIndex` | *Observed tags stay off the run item* holds by construction (W3) |
| run-tracking · reserved before launch | `RunCommand` (reserve, hook, `RunInstances`), `RunSession.reserve/confirmLaunched` | `RS.aFailedReservationThrows…`, `aConfirmedLaunchRecordsTheInstance`, `aFailedLaunchedWriteProceeds…`, `aRunCancelledWhileLaunching…`, `aLateLaunchedWrite…`, `REC.aSecondReservationOfTheSameRunIsRefused`, `launchedNeverMovesTheStatusBackwards`. *A refused run leaves no item*: `RunCommandTest` refusals all fail before AWS | — |
| run-tracking · failed launch recorded | `RunCommand` launch-failure branch, `RunLayout.launchErrorKey`, `RunSession.recordLaunchFailed` | `RS.aFailedLaunchIsRecordedAndEndsTheSession`, `REC.aFailedLaunchKeepsItsErrorCode`; live 13.5 | `launch-error.txt` and *the recording itself fails* have no JVM test (W2) |
| run-tracking · instance reports progress | `UserDataScriptBuilder.run_status`, watchdog, `running` block | `UD.runStatusIsDefinedBeforeTheWatchdogForks`, `recordsRunningOnlyAfterTheWatchdogStarts`, `recordsTheOutcomeWhere…`, `theWatchdogRecordsTimedOut…` (executes it), `aRefusedStatusWrite…`, `theStatusWriteWorksWithoutAnInstanceId`, `retrySettings…`, `aRefusedRunningWrite…`, `aRecordedOrFailedRunningWrite…`; live 13.2 and the review's check B | Watchdog firing live (W1) |
| run-tracking · first terminal wins | `DynamoDbRunRecorder.NOT_TERMINAL`, exported verbatim to the shell | `REC.theFirstTerminalStatusWins`, `aFailedOutcomeIsTerminalByPrefix`, `noWriteButTheReservationCreatesAnItem`, `UD.theTerminalGuardIsTheCLIsOwn`; live 1.3 | — |
| run-tracking · vanished inferred | `RunListing.resolve`, `Ec2ProvisioningService` live-runner lookup | `RunListingTest.anInFlightStatusWithNoLiveInstanceVanished`, `aLaunchingRunWithoutAnInstanceIdResolvesThroughTheTag`, `Ec2ProvisioningServiceTest.eachLiveRunnerCarriesItsRunIdFromTheTag` | — |
| run-tracking · lost final status | `RunSession.await` | `RS.aGoneInstanceWithStoredMeasurements…`, `aGoneInstanceWithNothingStoredVanished`, `aStatusWrittenJustBeforeTermination…` | — |
| run-tracking · CLI records why, then terminates | `RunSession.stop/stopWith/await/finish` | `RS.stopRecordsTheReason…`, `aThrowingStatusWriteStillTerminates`, `theCapRecordsATimeout…`, `theCapHonoursAnOutcome…`, `theCapReportsAnOutcomeThatBeatItsOwnWrite`, `anInterruptAfterTheInstanceRecordedItsOutcome…`, `aCancellationFromElsewhere…`; live 13.3 | — |
| run-tracking · `runs list` | `RunsListSubcommand`, `RunListing`, `DynamoDbRunRecorder.newestFirst` | `RunsCommandTest` (6 list cases), `RunListingTest` (7), `REC.listingIsNewestFirst…PagesUntilEnoughMatch` | — |
| run-tracking · `runs terminate` | `RunTermination`, `RunsTerminateSubcommand` | `RT` (9, including the pre-item fallback), `RunsCommandTest.terminate*` (3); live 13.3 | — |
| run-tracking · `runs` group | `RunsCommand` | `RunsCommandTest.theBareGroupPrintsUsageNamingBothSubcommands` | — |
| run-tracking · run items never measurements | `ResultsQueryService` filters, `MeasurementItemMapper.fromItem` | `RQ.everyProjectIgnoresRunItems`, `thePickerDoesNotOffer…`, `aLookupByRunIdReturnsTheMeasurementsNotTheRunItem`, `MeasurementItemMapperTest.aRunItemIsRefusedAsAMeasurement` | — |
| run-tracking · writes limited to `RUN` | `cf-template-core.yaml`, `operator-policy.json` | `CT.theRunnerPutsOnlyMeasurementsAndUpdatesOnlyRunItems`, `theOperatorUpdatesOnlyRunItems`; simulation 2.2, live 13.7 | — |
| benchmark-results-query · download | `RunReference`, `ResultsQueryService.resultPathForRun` | `RunReferenceTest` (4), `RQ.aRunThatStoredNothingResolves…`, `aRunFromBeforeRunItems…`, `anUnknownRunIdResolvesToNoPath`; live 13.5 | — |
| cli-command-structure · `run` always resolves the table, `runs` top-level | `RunCommand`, `BaasApp` | `RunCommandTest.refusesToRunWhenNoInstallationIsConfigured`, `theDiscardOptionIsUnknown`, `RunsCommandTest` | — |
| core-stack-provisioning · teardown gate | `TeardownCommand.inFlightRefusal`, `Ec2ProvisioningService` | `TeardownNoticeTest.theInFlightRefusalNamesEachRunAndHowToStopIt`, `Ec2ProvisioningServiceTest.theTeardownGateCountsBootingRunnersAsLive`; live 13.6 | — |
| core-stack-provisioning · operator, runner and diagnostics | template, user-data | `CT` (DynamoDB grants), `UD.shipsCloudInitLog…`, `watchdogShipsTheLog…`, `RS.aGoneInstance…` | — |
| results-store-schema · every run names a store | `ResultsStoreBuilder`, `ApiCommonSharedOptions` | `ResultsStoreBuilderTest` (5), `ApiCommonSharedOptionsTest.theDiscardOptionIsUnknown`, the runner's LocalStack ITs | — |
| run-artifact-layout · one prefix, CI layout | `RunLayout`, `e2e-cloud-test.yml` | `UD.writesNoRunStatusObjectToS3`, `theBenchmarkJarComesFromTheRunsOwnInputPrefix`; live 13.2 and 13.5 | — |

### Design adherence

Followed throughout. Two passages had drifted after the review fixes and were corrected in this
pass: *User-data writes status through one shell function* (attempt counts, and the refused `running`
write now acts) and *The CLI always terminates* (the finding 4 and 5 exceptions). `baas-runs.mmd`'s
terminate branch now shows the pre-item fallback. tasks.md 6.1's `AWS_MAX_ATTEMPTS=3` is left as the
historical task text; D3 records the change.

### Warnings

- **W1** (open, carried): the watchdog and the poll cap have not fired live. Task 13.4 is closed
  as *won't implement in this change* (2026-10-04) and tracked under *Deferred checks* in
  `openspec/changes/QUEUE.md`. A larger benchmark is not enough on its own: the process `timeout`
  fires `margin` seconds before the watchdog does, so the benchmark must also outlive SIGTERM.
- **W2** (new): `RunCommand`'s launch-failure branch — `launch-error.txt`'s content and upload,
  and *the recording itself fails* — runs in no JVM test. Live 13.5 covered the success case of both
  writes. The branch sits inside `call()`, which CLAUDE.md already lists as executed by no JVM test.
  Covering it means extracting the report and the two best-effort writes from `call()`.
- **W3** (new, suggestion): *Observed tags stay off the run item* holds by construction. The run
  item is built from `buildRunnerTags`, which rejects every observed key
  (`RunCommandTest.rejectsACallerTagThatCollidesWithAMachineObservedKey`), but no test asserts on a
  stored run item's tags after a run.
