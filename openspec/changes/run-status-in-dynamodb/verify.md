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
  `UserDataScriptBuilderTest.retrySettingsApplyToTheStatusCommandOnly` checks for
  `AWS_MAX_ATTEMPTS=3`, `--cli-connect-timeout 5` and `--cli-read-timeout 10`, which give 45 s
  against the 60 s floor. The other three outcomes run against the stub.
- **D4, the duration column.** The spec asked for a duration. An ended run's end time is not
  recorded, because the instance writes no timestamp by design (so that "the instance's clock never
  reaches the record" holds). The column became ELAPSED, shown for runs in flight and `—` otherwise.
  A SOURCE column was added, per explore decision 12. The spec was updated.

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
