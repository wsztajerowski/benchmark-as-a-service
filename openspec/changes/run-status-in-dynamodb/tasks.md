# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 Confirm `dynamodb:LeadingKeys` evaluates as designed for each action, using
  `aws iam simulate-custom-policy` with the rendered `RunnerRole` and `OperatorRole` statements and
  context entries for `dynamodb:LeadingKeys`. Expected results:
  - `UpdateItem` is allowed for `RUN` and denied for `RESULT#x`;
  - `PutItem` and `BatchWriteItem` are allowed for `RESULT#x` and denied for `RUN`.

  Record the output in `verify.md`. If `ForAllValues` behaves differently for `BatchWriteItem`,
  stop and revise design.md. This checks the policy logic only; task 13.7 checks the live deny.
- [x] 1.2 Read the `awsCliVersion` field of the current runner image's `environment.json` (any run
  since `ami-060b449bdd8cd463e`). Confirm it is AWS CLI v2, which honours `AWS_RETRY_MODE`,
  `AWS_MAX_ATTEMPTS`, `--cli-connect-timeout` and `--cli-read-timeout`, and supports
  `dynamodb update-item --condition-expression`.
- [x] 1.3 Against LocalStack, check the designed `update-item` with its condition. Expected results:
  - on an item at `completed`, the exit code is non-zero and stderr names
    `ConditionalCheckFailedException`;
  - `begins_with(#status, :failedPrefix)` refuses an overwrite of `failed:3`;
  - `attribute_exists(pk)` refuses a write to a missing key and creates nothing.
- [x] 1.4 `git grep -n -- '--no-database'` across `.github/` and `scripts/`, and confirm no workflow
  or script passes it. Record the result.

## 2. Infrastructure first (it ships before any code is pushed)

- [x] 2.1 In `cf-template-core.yaml`, add `UpdateItem` with `LeadingKeys = RUN` to `RunnerRole` and
  `OperatorRole`, and narrow `RunnerRole`'s `PutItem`/`BatchWriteItem` to `LeadingKeys = RESULT#*`.
  Update `infra/operator-policy.json` to match. Verify with `CoreTemplateTest` cases pinning both
  conditions and the absence of any delete action, the existing reference-copy sync test, and a
  check that the deployer policy is unchanged.
- [x] 2.2 Build the CLI from the branch, then **stop and ask the user** to run
  `java -jar baas-cli/target/baas-cli.jar admin setup`. Record the stack update's result. Do not push
  any commit from section 3 onward before this is done: CI on the PR runs the PR's CLI against the
  shared installation and needs these grants. Today's CLIs and runners are unaffected by the update.

## 3. Model: run-item keys and mapper (`baas-model`)

- [x] 3.1 Add the run-item key constructors to `ResultKeys`:
  - the `RUN` partition;
  - the run sort key, `<createdAt>#<runId>` with the fixed-width timestamp;
  - `gsi1sk = RUN`.

  Verify with unit tests: the sort key is fixed width and orders chronologically, a `createdAt`
  whose `Instant.toString()` has six fractional digits or no fractional digits still yields three,
  and `RUN` cannot equal any measurement's index sort key.
- [x] 3.2 Add a run-item mapper (to item and from item) carrying the run's identity fields, status,
  instance id, error code and tag map (project, source, `type`, caller tags). Add the status
  vocabulary and its terminal set (`failed:` matched by prefix). Verify with round-trip unit tests
  and a test that each terminal status is classified as terminal.
- [x] 3.3 Make `MeasurementItemMapper.fromItem` reject an item whose `pk` is not `RESULT#…`. Verify
  with a unit test that a run item makes it throw, naming the item.

## 4. Readers exclude run items (`baas-cli`)

- [x] 4.1 Restrict `scanAllProjects` and `listVisibleProjects` to `begins_with(pk, RESULT#)`, and add
  the filter expression `attribute_exists(#kind)` to `queryByRequestId` (a filter on `gsi1sk` is refused: it is a key attribute). Verify with LocalStack integration
  tests seeded with run items:
  - `--all-projects` returns only measurements;
  - the picker omits a project that has only run items;
  - `--request-id` returns only measurements.
- [x] 4.2 Make `resultPathForRun` read the run item (`gsi1pk = <runId>`, `gsi1sk = RUN`) first and
  fall back to a measurement. Update `RunReference`'s "a failed run has no index entry" wording and
  `noSuchRun`. Verify with integration tests:
  - a run with only a run item resolves;
  - a run from before this change, with only measurements, resolves;
  - an unknown id still fails, naming the id.

## 5. Run-item writes from the CLI

- [x] 5.1 Add a run-status service in `baas-cli` with these writes:
  - `reserve` (`launching`) sets every identity field and creates the item;
  - `launched` is conditioned on `status = launching` and `attribute_exists(pk)`;
  - `launchFailed`;
  - `stop(reason)` writes `cancelled` or `timed-out` with a short API-call timeout.

  Every write after `reserve` requires `attribute_exists(pk)` and the terminal guard. Verify with
  LocalStack integration tests:
  - each transition succeeds from a non-terminal status;
  - `stop` after `completed` leaves `completed`;
  - `launched` after `running` leaves `running`;
  - a write to a missing key creates nothing.
- [x] 5.2 In `RunCommand`, keep networking resolved before the run is named. Write `launching` after
  the upload and immediately before `RunInstances`, and exit non-zero without launching if it fails.
  Verify with a `RunCommandTest` case where the reservation throws and the fake EC2 records no
  `RunInstances` call.
- [x] 5.3 On `RunInstances` failure, write `launch-failed` with the AWS error code and upload
  `launch-error.txt` (key built by `RunLayout`), both best effort, then report the original error.
  Verify with tests: the item and object are written, and a test where both writes also fail still
  shows the original error and a non-zero exit.
- [x] 5.4 Register the shutdown hook as soon as `RunInstances` returns, then write `launched`, best
  effort. When `launched` is refused because the run is already terminal, terminate the instance
  just launched and exit non-zero. Verify with tests:
  - an interrupt raised before `launched` is written still terminates;
  - a failed `launched` write proceeds to the poll;
  - a refused `launched` write terminates the instance.
- [x] 5.5 Put the shutdown hook, the poll cap and `baas runs terminate` (section 9) on one stop
  method: write the reason (`cancelled` on interrupt, `timed-out` on the poll cap) with a short
  timeout, then terminate whatever the write did. Verify with unit tests:
  - the hook writes `cancelled` before terminating;
  - the cap writes `timed-out`;
  - a throwing or timing-out status write still terminates.

## 6. User-data writes from the instance

- [x] 6.1 Have the CLI export `RUN_SORT_KEY`, built by `ResultKeys`, into user-data. Define the
  `run_status` function **before** `INSTANCE_ID` and the watchdog. It writes `status`, plus
  `if_not_exists(instanceId)` when it has one, under `attribute_exists(pk)` and the terminal guard.
  It writes no timestamp and no tag. It passes `AWS_RETRY_MODE=standard`, `AWS_MAX_ATTEMPTS=3`,
  `--cli-connect-timeout 5` and `--cli-read-timeout 10` inline on that one command and exports
  none of them. It treats `ConditionalCheckFailedException` as success, and logs other failures
  without aborting. Verify with `UserDataScriptBuilderTest` assertions:
  - the function is defined before the watchdog's `( … ) &`;
  - its JSON holds only `${VAR}` references;
  - no `AWS_MAX_ATTEMPTS` or `AWS_RETRY_MODE` is exported.
- [x] 6.2 Call `run_status running` immediately after the watchdog starts, replace the S3 `run-status`
  write with `run_status completed|failed:<n>`, and add `run_status timed-out` to the watchdog
  subshell before its boot-log upload. Verify with tests:
  - the rendered script has no `run-status` S3 write;
  - the watchdog still starts immediately after `INSTANCE_ID`, with only the function definitions
    before it;
  - there is no `set -e`;
  - `timed-out` precedes the watchdog's log upload.
- [x] 6.3 Add a bash test that executes the rendered watchdog body and the `run_status` function
  against a stub `aws` that records its arguments. Run it once for each stub outcome: success,
  `ConditionalCheckFailedException`, a generic failure, and a hang beyond the timeouts. Verify that
  `timed-out` is sent from the subshell, that the script continues after every outcome, and that
  the hang gives up within the 60 s margin floor.
- [x] 6.4 Always render the results table into user-data, and remove the `NO_DATABASE` branch. Verify
  that `UserDataScriptBuilderTest` asserts that `STORE_ARGS` is always `--results-table`.

## 7. Polling and the lost-status report

- [x] 7.1 Replace the S3 `GetObject` poll with a strongly consistent `GetItem` on the run item, keeping
  the single re-read after the instance is seen terminated. Keep exit codes as today: `completed`
  exits 0, and `failed:<n>`, `timed-out` and `cancelled` exit 1. Verify with `RunCommandTest` cases
  for each status and a `--format json` summary test showing `exitCode` unchanged.
- [x] 7.2 When the poll reads a terminal status this CLI didn't write and the instance is pending or
  running, terminate it before exiting. Verify with a unit test for a foreign `cancelled` on a
  running instance.
- [x] 7.3 When no terminal status exists and the instance is gone, query the run's measurements and
  report either "results stored, final status lost" or vanished with the boot-log path. Exit
  non-zero in both cases. Verify with a unit test for each branch.

## 8. `--no-database` removal

- [x] 8.1 Remove `--no-database` from `RunCommand`. `resolveResultsTable` resolves unconditionally,
  and the "Or discard the results" hint goes. Verify with a `RunCommandTest` case: the option is
  unknown, and nothing is uploaded or launched.
- [x] 8.2 Remove `--no-database` from `ApiCommonSharedOptions`, shrink `ResultsStoreBuilder`'s check
  to exactly one of `--results-table` / `--mongo-connection-string`, and delete `NoOpResultsStore`
  and its test. Verify with `ResultsStoreBuilderTest` (neither or both fails, either alone succeeds)
  and `ApiCommonSharedOptionsTest` (the option is unknown).
- [x] 8.3 Remove the "swap for `--no-database`" comments from `jmh-with-profiler.sh` and
  `jmh-with-async.sh`. Verify with `git grep -- '--no-database'`, which should find no code, script
  or main doc.

## 9. `baas runs` command group

- [x] 9.1 Add the `runs` group with no action of its own, registered at the root next to `run`. Verify
  with a test that `baas runs` prints usage naming `list` and `terminate`.
- [x] 9.2 Implement `baas runs list` with these options: `--limit` (default 20), `--in-flight`,
  `--project`, repeatable `--tag`, and `--format table|json|csv`. It pages the `RUN` partition newest
  first until `--limit` rows match. It resolves statuses with one `DescribeInstances` on
  `tag:baas-role=benchmark-runner` and `instance-state-name=pending,running`, joined on
  `baas-request-id`. Payload goes through `Console`, and JSON/CSV use `Locale.ROOT`. Verify with
  tests:
  - the default shows 20 rows;
  - `--project a` fills 20 rows when the 20 newest belong to `b`;
  - `--in-flight` with more than 200 vanished items shows only live runs;
  - a `launching` item without an instance id resolves through the join;
  - `exclude_from_results=true` runs are shown;
  - listing writes nothing;
  - an empty `--in-flight` prints its message and exits 0.
- [x] 9.3 Implement `baas runs terminate <runId> [--yes]` on the stop method, with a termination
  call that throws instead of `Ec2ProvisioningService.terminateInstance`, which swallows errors. It
  prompts when interactive, refuses without a terminal and without `--yes`, and exits non-zero when
  the termination fails. It terminates a pending or running instance even when the item is already
  terminal, and reports "already ended" only when no live instance exists. Verify with a test for
  each case.

## 10. Teardown message

- [x] 10.1 Return each in-flight instance's id, state and `baas-request-id` tag from
  `listRunningBenchmarkInstances`. Rewrite teardown's refusal to name run ids and
  `baas runs terminate <runId>`. Verify with a `TeardownCommand` test asserting the message, and
  that no DynamoDB client is created on that path.

## 11. CI workflow

- [x] 11.1 In `.github/workflows/e2e-cloud-test.yml`, replace `test -s ./artifacts/run-status` with
  a check of the run item's status through `baas runs list --format json`. Rewrite the comment
  claiming failed runs cannot be downloaded by id. Verify the workflow passes on the PR, which needs
  task 2.2 to have been done first.

## 12. Documentation

- [ ] 12.1 Update CLAUDE.md:
  - the S3 layout table (`run-status` row replaced, `launch-error.txt` added);
  - the *Results table* section (`RUN` items, keys and readers);
  - *Three termination layers* (the watchdog and the poll cap record `timed-out`);
  - *What isn't there* (the absent-store paragraph without `--no-database`);
  - *Adding a benchmark type* (exactly one of two stores);
  - the `e2e-cloud-test.yml` paragraph.

  Verify with `git grep -n 'run-status\|no-database' CLAUDE.md`, which should return only
  intentional mentions.
- [ ] 12.2 Update the README (`baas runs`, `--no-database` removed, and the run-status passages at
  lines 311–313 and 421). Update `docs/diagrams/baas-run.mmd`, `baas-ci-e2e.mmd`,
  `baas-lifecycle.mmd`, `c4-2-container.mmd` and `c4-3-component-runner.mmd`, and add
  `docs/diagrams/baas-runs.mmd`. Verify that each diagram renders with `mmdc`.
- [ ] 12.3 Update the stale code text: the `RunCommand` help footer (lines 68–71), the
  `BaasConfig.java:156` comment, and `S3UploadServiceIT:142`. Update
  `docs/analysis/cli-usage-analysis.md` (U3 done, the §2.3 run state graph) and
  `docs/adr/0001-self-contained-baas-cli.md`. Verify that `git grep -n 'run-status'` returns only
  historical or intentional mentions.
- [ ] 12.4 Mark U3 **Fixed** and note S7's tightening in `docs/review/baas-cli-findings.md`'s status
  table. Remove `run-status-in-dynamodb` from `openspec/changes/QUEUE.md`.
- [ ] 12.5 Commit the `--no-database` removal and the old-CLI incompatibility as `feat(cli)!` with a
  `BREAKING CHANGE:` footer naming both. Verify with `git log` that semantic-release will read it
  as a major bump, and that no prose line in the body parses as a second footer.

## 13. End-to-end verification (manual: no automated test covers the `baas run` path)

- [ ] 13.1 Confirm that 2.2's stack update is live and that an old CLI build can still launch and
  complete a run against it, before any run item exists.
- [ ] 13.2 Run `baas run jmh` against `fake-jmh-benchmarks`. Verify the item passes through
  `launching` → `launched` → `running` → `completed`, the prefix holds no `run-status` object, and
  `baas runs list` shows the run.
- [ ] 13.3 Start a run and `kill -9` the CLI. Verify `baas runs list --in-flight` shows it, then
  `baas runs terminate <runId>` cancels it, the instance terminates, and the item reads `cancelled`.
- [ ] 13.4 Exercise the watchdog path: detach the CLI (`kill -9` after launch), with a benchmark
  process that ignores SIGTERM so the process `timeout` cannot end it, and the minimum margin.
  Verify the item reads `timed-out` and the boot log is uploaded. Separately, let an attached CLI
  reach its poll cap, and verify it records `timed-out`, not `cancelled`.
- [ ] 13.5 Force a launch failure, for example with an instance type the account cannot launch.
  Verify `launch-failed` with the error code, `launch-error.txt` in the prefix, and that
  `baas download <runId>` fetches it.
- [ ] 13.6 With a run in flight, run `baas admin teardown` and verify the refusal names the run id
  and `baas runs terminate`. Abort before confirming.
- [x] 13.7 As the operator, try `aws dynamodb update-item` on a `RESULT#` key and `put-item` on a
  `RUN` key. Verify both return AccessDenied. LocalStack does not enforce IAM, so this live check is
  the only one for the deny scenarios.
- [ ] 13.8 Confirm that an older CLI fails as the proposal states (`results --all-projects`) and that
  `results --project <name>` still works, so the BREAKING note is accurate.
- [ ] 13.9 Compare 13.2's `fake-jmh-benchmarks` score with the most recent pre-change e2e runs in
  `baas results`. Record the spread, and investigate any difference outside the recorded CI range
  rather than accepting it. The only change to user-data timing comes before the JVM starts.

## 14. Verify

- [ ] 14.1 Run `/opsx:verify` and record the result in `verify.md` in this change directory: a
  requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation
  from design or tasks.
