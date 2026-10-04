## Why

A run whose CLI dies without running its shutdown hook (SIGKILL, laptop sleep, lost network) is
invisible. No `baas` command lists in-flight runs or stops one, and teardown's only advice is
"terminate them manually" (review finding **U3**, `docs/review/baas-cli-findings.md`). The watchdog
caps the cost at `timeout + margin`, but nothing shows the run exists.

Run status lives in a per-run S3 object that only the launching CLI ever polls. Listing runs from
it would mean walking the bucket, about 70 s for 10,000 runs. Moving status into the results table
makes listing a single Query. The bucket then returns to being write-once storage that nothing
polls.

## What Changes

- **New: each run is recorded as an item in the results table**, in a single partition (`pk = RUN`,
  `sk = <createdAt>#<runId>`, `gsi1pk = <runId>`). The item holds the run's identity fields,
  instance, result path, CLI-side tags and status.
- **New run states**, recorded at different points by two writers:
  - The CLI records `launching` before `RunInstances` and `launched` after it. It records
    `launch-failed` when the launch fails, and `cancelled` from its shutdown hook and from
    `baas runs terminate`.
  - The instance records `running`, `completed`, `failed:<n>`, and `timed-out` (from the watchdog).
    It writes only the status and its instance id, and only to an item that already exists. Every
    identity field, the tags included, is written once, by the CLI's reservation, so no caller tag
    value is rendered into user-data.
  - The CLI records `timed-out` too, when its poll cap fires on a run still in flight.
  - Every write is a conditional `UpdateItem`, and the first terminal status wins.
  - `vanished` is never written. It is inferred when reading: a non-terminal status whose instance
    is gone.
- **New: a failed reservation stops the launch.** If the `launching` write fails, `RunInstances` is
  never called.
- **New: failed launches are recorded.** The AWS error code goes on the item, and the details go to
  `launch-error.txt` in the run's prefix, so `baas download <runId>` works for a run that never
  started.
- **New: `baas runs list`** shows the 20 most recent runs of any status, across every project,
  newest first. It accepts `--limit`, `--in-flight`, `--project`, `--tag` and
  `--format table|json|csv`.
- **New: `baas runs terminate <runId>`** records `cancelled` and terminates the instance. It asks for
  confirmation on an interactive terminal; `--yes` skips the prompt.
- **New: an honest report when the final status write is lost.** If the poll sees no terminal
  status and the instance gone, `baas run` checks for stored measurements. It then reports
  "results stored, final status lost" and exits non-zero, rather than reporting a plain vanished
  run.
- **Changed: the `run-status` S3 sentinel is no longer written or polled.** Existing sentinel
  objects stay in S3 as artifacts.
- **Changed: teardown's in-flight refusal** names each run id, read from the instance's
  `baas-request-id` tag, and points to `baas runs terminate`. The gate itself is still the EC2
  instance listing.
- **Changed IAM:**
  - `OperatorRole` gains `dynamodb:UpdateItem` restricted to `dynamodb:LeadingKeys = RUN`. It is
    the role's first write right on the table, and it is deliberate.
  - `RunnerRole` gains the same grant, and its `PutItem`/`BatchWriteItem` narrow to `RESULT#*`
    partitions.
- **BREAKING:** `--no-database` is removed from `baas run` and from `benchmark-runner`, and
  `NoOpResultsStore` is deleted. Every run names a store: the installation's table, or a LocalStack
  table for local runs. The runner's MongoDB adapter is untouched.
- **BREAKING: every CLI pointed at the installation must be upgraded.** An older CLI reads run
  items as measurements. Once the first run item exists, its `results --all-projects`, project
  picker, `results --request-id`, `download <runId>` and `env diff <runId>` fail, while
  `results --project <name>` still works. The first run item appears when the implementation PR's
  CI first runs, well before the release. Until then, use a CLI built from the branch for those
  commands. Accepted on 2026-10-02 instead of releasing a reader fix ahead of the batch.

## Capabilities

### New Capabilities
- `run-tracking`: the run item, its states and writers, the conditional-write rule, read-time
  inference, the failed-launch record, the lost-terminal-write report, keeping run items apart from
  measurements, and the `baas runs` command group.

### Modified Capabilities
- `core-stack-provisioning`: the teardown gate's message; the operator's and runner's DynamoDB
  grants; the poll loop's dead-instance rule and the boot-log scenario, which no longer refer to a
  `run-status` sentinel.
- `results-store-schema`: the no-op opt-in is replaced by a mandatory store; the store-failure
  scenario reads the run item instead of an S3 sentinel.
- `cli-command-structure`: `--no-database` is removed from the run path; `runs` joins the top-level
  daily-use commands.
- `run-artifact-layout`: the run prefix no longer holds `run-status` and may hold
  `launch-error.txt`.
- `benchmark-results-query`: `baas download <runId>` resolves through the run item, so a run that
  stored no measurement is retrievable.

## Impact

- **Code:**
  - `baas-model`: the run-item keys in `ResultKeys`, plus a run-item mapper.
    `MeasurementItemMapper.fromItem` rejects any item that is not a measurement.
  - `baas-cli`: a new `runs` command group; `RunCommand` (reservation, launch-failure record, poll
    against the item, shutdown hook, `--no-database` removed); `UserDataScriptBuilder` (a
    `run_status` function, a `timed-out` write in the watchdog, the S3 sentinel removed, the table
    name always passed); `TeardownCommand` message; `ResultsQueryService` (both Scans restricted to
    `RESULT#`, run-item lookup by id); `Ec2ProvisioningService` (instances looked up by run id).
  - `benchmark-runner`: `ResultsStoreBuilder` and `ApiCommonSharedOptions` drop `--no-database`;
    `NoOpResultsStore` is deleted.
- **Infra:** `infra/cf-template-core.yaml` and `infra/operator-policy.json` (the two DynamoDB grants).
  The deployer policy does not change. The stack update ships ahead of the code: it is harmless to
  today's CLIs and runners, and CI on the implementation PR needs it.
- **CI:** `.github/workflows/e2e-cloud-test.yml` asserts a downloaded `run-status` file and says
  failed runs cannot be downloaded by id. Both change.
- **Docs:** CLAUDE.md (S3 layout, results table, termination layers, absent-store paragraph,
  *Adding a benchmark type*), README, `docs/diagrams/baas-run.mmd`, `c4-3-component-runner.mmd`, a
  new `baas runs` sequence diagram, the ADR, and `docs/review/baas-cli-findings.md` (U3 marked
  Fixed, S7 tightened). The `jmh-with-*.sh` comments that mention `--no-database` are removed.
- **Cost:** no new resource and no standing cost. On-demand reads to poll a 2-hour run cost about
  $0.00015, against about $0.0002 of S3 GETs today. Writes add about five `UpdateItem` calls per run.
- **Deliberately not changed:**
  - The three termination layers. The watchdog still starts immediately after `INSTANCE_ID`, and
    user-data still has no `set -e`.
  - The boot log and environment manifest still go to S3 on every path.
  - The deployer policy and its size budget.
  - Teardown's EC2-based gate.
  - The measurement key encoding and `requestId-index`.
  - One table, retained.
  - The table name still goes into user-data, which is still deliberate.
  - The MongoDB adapter.
  - `baas run`'s exit codes: `failed:<n>` still exits 1.
  - "The instance's clock never reaches the record": the instance writes no timestamp.
  - No backfill of past runs.
- **Closes:** U3. **Tightens:** S7, since the runner can no longer write outside `RESULT#*` and
  `RUN`.
