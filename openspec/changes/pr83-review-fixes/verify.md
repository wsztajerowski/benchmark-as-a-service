# Verify — pr83-review-fixes

## 1. Blocking assumptions

| Task | Result |
|---|---|
| 1.1 | `aws s3api head-bucket` with `baas-admin` on 2026-10-09: the own bucket `baas-381492019823` from eu-central-1 → 200 with `x-amz-bucket-region: eu-central-1`. The same bucket from us-east-1 → 301 with the header (the AWS CLI then follows it to 200). `test` from eu-central-1 → **301** with `x-amz-bucket-region: us-west-2`, then 403 in us-west-2. A random name → 404. **Deviation from the design:** a foreign bucket in another region answers with a redirect, not 403, so the probe re-asks in the region the redirect names (design.md updated). |
| 1.2 | LocalStack 4.14.0: `listObjectVersionsPaginator` on a missing bucket throws `NoSuchBucketException` (`S3UploadServiceIT.emptyingABucketThatDoesNotExistSucceeds` passes only because that exception is caught). |
| 1.3 | EC2 `RunInstances` `ClientToken`: "Unique, case-sensitive identifier … Constraints: Maximum 64 ASCII characters" (EC2 API Reference, RunInstances). A job id is 28 ASCII characters (`JobId`), so it qualifies. |
| 1.4 | `Ec2ProvisioningService.runInstance` sets `.instanceInitiatedShutdownBehavior(ShutdownBehavior.TERMINATE)` (line 70). cloud-init runs user-data scripts as root (cloud-init docs, *User-data scripts*), so `shutdown -h now` is permitted. With that behaviour, an OS shutdown terminates the instance (EC2 User Guide, *Change instance initiated shutdown behavior*). |
| 1.5 | `e2e-cloud-test.yml` asserts length, `imageVersion`, `instanceType`, `benchmarkType`, `benchmarkName` and tags. It never reads `score`, so 8.4 is not needed. |

## 2. Setup

- 2.1–2.2: `BucketProbe` (absent / reachable / forbidden, a redirect asked again in the region it names) and `SetupCommand.nameConflict`. `BucketProbeTest` replays task 1.1's live answers, and `SetupCommandTest` covers the three refusals, free names, and a reachable bucket elsewhere not counting as a create conflict.
- 2.3: `CloudFormationService.stackStatus`. `deploy()` treats `ROLLBACK_COMPLETE` as a create: names are checked first, then the stack is deleted and waited out, then created. The `requireUpdatable` message now names re-running setup (`CloudFormationServiceTest`).
- **Gap W1:** the ordering inside `deploy()` (check → delete → create) has no unit test, because `deploy()` builds its clients from `AwsClientFactory`. It is covered by the live check in 12.2.

## 3. Teardown

- 3.1: `deleteAllObjects` catches `NoSuchBucketException`, logs it and returns; every other failure still throws (`S3UploadServiceIT`, 7 tests green).
- **Gap W2:** `TeardownCommand.removeDeployment` is all AWS calls, and its tests replace it whole. "A missing bucket still deletes the stack and the file" is covered by the live check in 12.2, not by a unit test.

## 4. User-data

- 4.1: both `kill $WATCHDOG_PID` lines and the then-unused `WATCHDOG_PID` are gone, and all three `terminate-instances` calls end in `|| shutdown -h now`. `UserDataScriptBuilderTest` has 62 green tests: the watchdog executed with a failing stub ends in `shutdown -h now`; the refused-`running` path executed with every call refused ends in terminate then shutdown; `bash -n`; and `aLargeJobStaysWellUnderTheUserDataLimit`.

## 5. Launch

- 5.1: `RunInstances` carries `.clientToken(jobId)` (`Ec2ProvisioningServiceTest.theLaunchIsIdempotentOnTheJobId`).

## 6. Job termination

- 6.1: `DynamoDbJobRecorder.NOT_TERMINAL_VALUES` is generated from `EXACT_TERMINAL` plus the prefix, and feeds `NOT_TERMINAL`, the CLI's writes and `UserDataScriptBuilder.guardValues()`. `UserDataScriptBuilderTest` checks the values against `EXACT_TERMINAL`. `DynamoDbJobRecorderIT` (10, LocalStack) still refuses every terminal status, and its simulated instance write uses the same map.
- 6.2: `JobStatus.endsItself` covers completed, failed:n and timed-out (`JobStatusTest`). `JobSession.finish` terminates only on `cancelled`: `theWatchdogsTimeoutExitsOne` now also asserts nothing is terminated, and `aCancellationFromElsewhereStillTerminatesALiveInstance` is unchanged.
- 6.3: `Ec2ProvisioningService(ec2, deployment)`. `findLive` adds the `baas-deployment` filter and refuses to run without one. Every caller passes `config.stackName()`: `RunCommand`, `JobsShowSubcommand`, `JobsTerminateSubcommand`.
- 6.4: `JobStop` is the one step: bounded write → on refusal a strong re-read → `LEFT_ALONE` if `endsItself`, else terminate. `JobSession.stop` and `JobTermination` both use it. `JobTermination` resolves the id through the index, then reads the item strongly. New tests: `anInstanceThatCompletesBeforeTheCancelIsLeftToTerminateItself` (the review's race) and `aJobTheWatchdogTimedOutIsLeftToTerminateItself`.
- 6.5: `terminateUnrecorded` is deleted (`anIdWithNoItemTerminatesNothing`).
- 6.6: every end in `JobSession` goes through `end()` under the session's lock, and a late `stop()` returns the recorded status (`twoStopsAtOnceRecordAndTerminateOnce`, `stopIsANoOpOnceTheJobEnded`).

## 7. Listing

- 7.1: `JobListing.VANISH_GRACE` = 5 min. `resolve` and `filter` take `now`, and `jobs list` and `jobs show` pass `Instant.now()`. Tests: `aJobYoungerThanTheGraceKeepsItsStoredStatus` (10 s old `launching`, shown and in flight) and `aJobOlderThanTheGraceWithNoInstanceVanished` (6 min). The existing tests pass `LATER`, a day after creation.

## 8. Results query

- 8.1: `ResultRow.from` maps an absent score or error to `NaN` (`aScoreTheItemDoesNotCarryNeverWinsALowerIsBetterGroup`, which builds the row through `ResultRow.from`).
- 8.2: `csvNumber` prints an empty cell for a non-finite value (`anUnknownScoreIsAnEmptyCsvCell`, with the column count kept), and JSON gives `null` (`anUnknownScoreIsJsonNull`). `--sort-by score` puts non-finite last in both directions (`anUnknownScoreSortsLastInBothDirections`).
- 8.3: `--limit` is an `Integer`; `effectiveLimit()` is the given value, else 0 under `--job-id`, else 20 (`aJobLookupReturnsEveryMeasurementByDefault` 30/30 with no cut note, `anExplicitLimitStillBoundsAJobLookup`). The project default of 20 is unchanged (`theDefaultIsTheTwentyNewestEveryMeasurement`).
- 8.4: not needed, per 1.5.

## 9. Configuration

- 9.1: `ConfigService.readDeployment` is used by `load`, `loadForSetup`, `loadForSync` and `all`. It refuses a mismatched prefix with `DeploymentSelectionException` naming the file, both values and the fix, and gives a missing prefix the file's name. Tests: `aCopiedFileIsRefusedByEveryWayIntoIt`, `aFileWithoutAPrefixTakesItsName`, and `TeardownConfigFileTest.aCopiedFileStopsTheTeardownBeforeAnythingIsDeleted` (the AWS step never runs; both files remain). `config list` also refuses such a machine, deliberately: listing a file that aims at another deployment would hide the problem.

## 10. Dead code

- 10.1: removed `S3UploadService.deleteBucket` (and its IT case `removesTheBucketItselfNotJustItsContents`; `detectsWhetherABucketNameIsTaken` now deletes through the client, and its Javadoc names `config sync` as its reader) and `JobListing.SHOWN_STATUSES`. `RunnerImageExtension.hash` now runs on `RunnerJarResolver.sha256Hex`, and a new literal pin, `theHashIsTheLeadingEightHexOfSha256` ("abc" → `ba7816bf`, computed independently with Python), shows the output is unchanged. `grep` finds no remaining reference.

## 11. Documentation

- 11.1: CLAUDE.md. *Three termination layers*: nothing kills the watchdog, the `|| shutdown -h now` fallback, `JobStop`/`endsItself`, only `cancelled` terminates, and the client token. *Job items*: the `vanished` grace. *Other rules*: setup's name pre-check and the `ROLLBACK_COMPLETE` recovery. *Each deployment is one file*: a mismatched prefix is refused. ADR 0003 gets an amendment for `timed-out` rather than an edit, since ADRs are records.
- 11.2: U31 deleted from `docs/review/open-findings.md` (its entry and its row in the index table).
- 11.3: `baas-setup`, `baas-teardown`, `baas-jobs`, `baas-run`, `baas-states-job` and `baas-states-deployment` `.mmd` updated. Each was rendered with `mmdc` into the scratchpad and looked at. The first render of `baas-jobs` failed on an extra `end` left by the edit, which was fixed and re-rendered.
- 11.4: release note carried by the docs commit's footer (below).

## 12. Verification

- 12.1: full reactor `mvn -o verify` at `cd779e2` (all code commits), `ASYNC_PATH` exported: BUILD SUCCESS. 944 tests: model 73, the fixtures 44 + 18, CLI 769, runner 40 (the async-profiler IT ran and was not skipped).
- 12.2: **manual, live, 2026-10-09 23:40–00:05 CEST**, CLI and runner from this branch's build. 10 paid jobs (J8 never launched).

  | # | Check | Result |
  |---|---|---|
  | L1 | R13: a copy of `baas-development.yaml` saved as `wiktor-copy.yaml`; `--deployment wiktor-copy admin deployment teardown --yes` and `config list` | Both refused naming the file, both prefixes and the fix; exit 1; `baas-development` stayed `CREATE_COMPLETE`; copy removed |
  | L2 | R16 leftover bucket/table refusal and R2 missing-bucket teardown on `baas-development` | **Not run.** The teardown that must come first was denied by the session's permission classifier. Left for the user (W4) |
  | J1 | Normal JMH run; `jobs list` polled during launch; instance attributes | `completed`, exit 0; listing showed `launching` (no instance) then `launched`, never `vanished` (R12); `ClientToken` = job id and shutdown behaviour `terminate` (R7); instance `terminated`, boot log uploaded (R3 normal path) |
  | J2 | 25-variant `@Param` sweep, `avgt` (scratch JAR, not committed) | `results query --job-id` → 25 rows, no cut note; `--limit 5` → 5 with "Reporting 5 of 25"; single-iteration `scoreError` an empty CSV cell (R11, R9) |
  | J3 | `jobs terminate --yes` from a second process while running | `Terminated instance …`, exit 0; the polling `baas run` reported `cancelled`, exit 1; instance `shutting-down` (R8) |
  | J4 | Job on `baas-381492019823`; `--deployment baas-development jobs terminate <its id>` | "No job found", exit 1; job stayed `running` and completed (R6, different regions, so same-region scoping rests on unit tests) |
  | J5 | Ctrl+C on a running job | Invalid: a background child of a non-interactive shell ignores SIGINT, so it ran to `completed`. Rerun as J5b |
  | J5b | SIGINT with job control on, while running | exit 130; item `cancelled`; hook "Terminated instance …" through `JobStop`; instance `shutting-down` |
  | J6 | SIGINT while `RunInstances` was in flight | Hook: "no instance to terminate" (lookup missed); the instance logged `job_status: running not recorded`, then "not starting the benchmark", and shut down; item `cancelled` |
  | J7 | JCStress sanity | `completed`; JSON `score: null`, CSV empty score cells, table a faint `NaN` (R9) |
  | J8 | Benchmark that does not exist, launched beside two others | `launch-failed` (`VcpuLimitExceeded`: us-east-1 allows 16 vCPU, i.e. two c5.2xlarge), `launch-error.txt` written. Not the intended path, but a correct one |
  | J9 | `--timeout 60` on a 5-minute benchmark | `failed:124`, exit 1, instance `terminated` |
  | J10 | R10: the operator role writes `timed-out` on a running job, as its watchdog would | `baas run` reported `timed-out`, exit 1, instance left `running`; `jobs terminate` said it ends itself, exit 0, instance still `running`; see the follow-up below |
- 12.3: CI on PR #84 (run 37994637686): **JMH on EC2 via `baas run`** pass and **JCStress on EC2 via `baas run`** pass, both with this branch's CLI and runner on `baas-381492019823`. The `build` check (run 37994637880) first failed in `MongoResultsStoreContractIT.initializationError`: `ContainerFetchException: Can't get Docker image mongo:7.0.5`, a Docker Hub pull failure in a module this change does not touch. It passed on re-run.
- 12.4: no measurement comparison. Nothing in this change reaches the benchmark process, the image or `environment.json`: user-data changed only after the benchmark exits, on paths that never start it, and inside the watchdog (design, *Comparability*). J1's and CI's runs used the unchanged image `1.3.0`.
  - J10 follow-up: the instance finished its benchmark, its `completed` write was refused over `timed-out` (`job_status: completed not recorded`), it uploaded the boot log and went to `shutting-down` by itself. Neither CLI path terminated it.

## Verification report (`/opsx:verify`, 2026-10-10)

| Dimension | Status |
|---|---|
| Completeness | 32/33 tasks. Open: 12.2 (partly blocked, W4) |
| Correctness | 16/16 delta requirements implemented; 41/46 scenarios have a test or a live check (W1, W2, W4, W6, W7) |
| Coherence | Design followed; one recorded deviation (`BucketProbe` re-asks a redirect, task 1.1) |

### Requirement → code → test

| Requirement (delta) | Code | Test / live | Gap |
|---|---|---|---|
| *setup is self-sufficient* (`ROLLBACK_COMPLETE` recovery) | `SetupCommand.deploy`, `CloudFormationService.stackStatus`, `requireUpdatable` | `CloudFormationServiceTest` (2) | W1 |
| *Bucket emptying handles object versions* (missing bucket) | `S3UploadService.deleteAllObjects` | `S3UploadServiceIT.emptyingABucketThatDoesNotExistSucceeds` | W2, W7 |
| *Teardown removes every deployment resource* (scenario text only) | — | — | — |
| *Setup checks the stack's fixed names are free* (ADDED) | `BucketProbe`, `SetupCommand.nameConflict`, `tableExists` | `BucketProbeTest` (6), `SetupCommandTest` (5) | W1, W4 |
| *A job launches at most one instance* (ADDED) | `Ec2ProvisioningService.runInstance` | `theLaunchIsIdempotentOnTheJobId`; live J1 | — |
| *`jobs terminate` stops one job of its deployment* (replaces the REMOVED one) | `JobTermination`, `JobStop` | `JobTerminationTest` (11); live J3, J4, J10 | W6 |
| *The instance reports its own progress and outcome* | `UserDataScriptBuilder` | `UserDataScriptBuilderTest` (62); live J1, J6, J9, J10 | — |
| *A vanished job is inferred, never written* | `JobListing.resolve/filter`, `VANISH_GRACE` | `JobListingTest` (12); live J1 | — |
| *The CLI records why it stopped a job, then always terminates* | `JobSession`, `JobStop`, `JobStatus.endsItself` | `JobSessionTest` (28), `JobStatusTest`; live J3, J5b, J10 | — |
| *Runner lookups are scoped to the deployment* | `Ec2ProvisioningService.findLive` | `Ec2ProvisioningServiceTest` (2 cases) | W6 |
| *Results for one job are queryable by job ID* | `ResultsQuerySubcommand.effectiveLimit` | `ResultsQueryPipelineTest` (2 new); live J2 | — |
| *Output carries a measurement's tags…* (unknown score) | `ResultRow.from`, `csvNumber`, `jsonNumber` | `ResultsFormatTest` (2 new); live J2, J7 | — |
| *The best score per group…* (missing score never wins) | `ResultRow.from`, `ResultsGrouping.better` | `ResultsGroupingTest.aScoreTheItemDoesNotCarry…` | — |
| *Listings share one ordering and paging pipeline* | `effectiveLimit`, `ResultsGrouping.sorted` | `ResultsQueryPipelineTest`, `anUnknownScoreSortsLastInBothDirections` | — |
| *Each deployment has its own configuration file* (mismatch refused) | `ConfigService.readDeployment` | `ConfigServiceTest` (2 new), `TeardownConfigFileTest`; live L1 | — |
| REMOVED *`baas jobs terminate` stops one job* | `terminateUnrecorded` deleted | `anIdWithNoItemTerminatesNothing` | — |

### Warnings

- **W1:** the order inside `SetupCommand.deploy` (check names → delete a rolled-back stack → create), and "an update does not check its own names", have no unit test, because `deploy()` builds its AWS clients itself. Nor were they run live (W4). Recommendation: give `deploy()` an injectable seam like `TeardownCommand.removeDeployment`, or run L2 below.
- **W2:** "A missing bucket does not block teardown" is proven at `deleteAllObjects` (IT), not through `TeardownCommand`, which is all AWS. Covered by L2 once it runs.
- **W3** (predates this change): `avgt` results default to `s/op`, so a nanosecond-scale score prints as `0.000000` at the fixed 6 decimals in JSON and CSV (live J2). It is a real measurement shown as zero. Recommendation: format by significant digits, or note `-tu ns` in the docs. Not this change's scope.
- **W4:** live check L2 was not run. Tearing down `baas-development` (the step before both R16 refusals and the R2 missing-bucket teardown) was denied by this session's permission classifier. To run by hand:
  1. `baas --deployment baas-development admin deployment teardown --yes`
  2. `aws s3 mb s3://baas-development --region us-east-1`, then `baas --deployment baas-development admin deployment setup`: expect the leftover-bucket refusal and no stack. Then `aws s3 rb s3://baas-development`.
  3. Repeat step 2 with `aws dynamodb create-table --table-name baas-development-results …`: expect the leftover-table refusal. Then delete the table.
  4. `setup`, then `aws s3 rb s3://baas-development` (empty), then `teardown --yes`: expect exit 0 and the stack and file gone.
  5. `setup` and `admin image build` to restore it.
- **W5** (predates this change): the CSV `mode` cell is the literal text `null` for a JCStress row (live J7); JSON has a real `null`. Recommendation: an empty cell, matching R9's treatment of the score.
- **W6:** same-region cross-deployment scoping (R6) is proven by unit tests only. Live J4 crossed regions, because no second deployment exists in either region (QUEUE's deferred two-deployments-in-one-region check covers it).
- **W7:** "An undeletable object still stops teardown" has no test that forces a `DeleteObjects` error; the path (`IllegalStateException` → exit 1 before `deleteStack`) is unchanged code. Recommendation: a fake `S3Client` returning an error entry.

### Assessment

No critical defect in the implementation. One task is still open: 12.2, blocked on a permission only the user can grant (W4). The change is ready to merge once CI is green. It should not be archived until W4's live steps have run.
