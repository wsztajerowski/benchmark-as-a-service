# Tasks

## 1. Verify blocking assumptions

- [ ] 1.1 Confirm `dynamodb:LeadingKeys` evaluates as designed for each action, using
  `aws iam simulate-custom-policy` with the rendered `RunnerRole` and `OperatorRole` statements and
  context entries for `dynamodb:LeadingKeys`. Expected results:
  - `UpdateItem` is allowed for `RUN` and denied for `RESULT#x`;
  - `PutItem` and `BatchWriteItem` are allowed for `RESULT#x` and denied for `RUN`.

  Record the output in `verify.md`. If `ForAllValues` behaves differently for `BatchWriteItem`,
  stop and revise design.md.
- [ ] 1.2 Read the `awsCliVersion` field of the current runner image's `environment.json` (any run
  since `ami-060b449bdd8cd463e`). Confirm it is AWS CLI v2, which honours `AWS_RETRY_MODE` and
  `AWS_MAX_ATTEMPTS` and supports `dynamodb update-item --condition-expression`.
- [ ] 1.3 Against LocalStack, run `aws dynamodb update-item` with the designed condition on an item
  already at `completed`. Confirm the exit code is non-zero and stderr names
  `ConditionalCheckFailedException`. Confirm that `begins_with(#status, :failedPrefix)` refuses an
  overwrite of `failed:3`.
- [ ] 1.4 `git grep -n -- '--no-database'` across `.github/` and `scripts/`, and confirm no workflow
  or script passes it. Record the result.

## 2. Model: run-item keys and mapper (`baas-model`)

- [ ] 2.1 Add `RUN` partition, run sort-key (`<createdAt>#<runId>`, using the fixed-width timestamp)
  and `gsi1sk = RUN` constructors to `ResultKeys`. Verify with unit tests: the sort key is fixed
  width and orders chronologically, and `RUN` cannot equal any measurement's index sort key.
- [ ] 2.2 Add a run-item mapper (to item and from item) carrying the run's identity fields, status,
  instance id, error code and tag map, plus the status vocabulary and its terminal set (`failed:`
  matched by prefix). Verify with round-trip unit tests and a test that each terminal status is
  classified as terminal.
- [ ] 2.3 Make `MeasurementItemMapper.fromItem` reject an item whose `pk` is not `RESULT#…`. Verify
  with a unit test that a run item makes it throw, naming the item.

## 3. Readers exclude run items (`baas-cli`)

- [ ] 3.1 Restrict `scanAllProjects` and `listVisibleProjects` to `begins_with(pk, RESULT#)`, and
  exclude `gsi1sk = RUN` from `queryByRequestId`. Verify with LocalStack integration tests seeded
  with run items:
  - `--all-projects` returns only measurements;
  - the picker omits a project that has only run items;
  - `--request-id` returns only measurements.
- [ ] 3.2 Make `resultPathForRun` read the run item (`gsi1pk = <runId>`, `gsi1sk = RUN`) first and
  fall back to a measurement. Verify with integration tests:
  - a run with only a run item resolves;
  - a run from before this change, with only measurements, resolves;
  - an unknown id still fails, naming the id.

## 4. Run-item writes from the CLI

- [ ] 4.1 Add a run-status service in `baas-cli` with `reserve` (`launching`), `launched`,
  `launchFailed` and `cancel`. Each is one conditional `UpdateItem` that sets the identity fields
  with `if_not_exists`. Verify with LocalStack integration tests:
  - each transition succeeds from a non-terminal status;
  - `cancel` after `completed` leaves `completed`;
  - a write arriving first creates a complete item.
- [ ] 4.2 In `RunCommand`, write `launching` after networking resolves and immediately before
  `RunInstances`, and exit non-zero without launching if it fails. Verify with a `RunCommandTest`
  case where the reservation throws and the fake EC2 records no `RunInstances` call.
- [ ] 4.3 On `RunInstances` failure, write `launch-failed` with the AWS error code and upload
  `launch-error.txt` (key built by `RunLayout`), both best effort, then report the original error.
  Verify with tests: the item and object are written, and a test where both writes also fail still
  shows the original error and a non-zero exit.
- [ ] 4.4 On success, write `launched` with the instance id. Move the shutdown hook's termination
  into one shared "record `cancelled`, then terminate" method. Verify with a unit test that the hook
  path writes `cancelled` before calling terminate.

## 5. User-data writes from the instance

- [ ] 5.1 Add a `run_status` shell function to `UserDataScriptBuilder`. It captures the values into
  variables, uses only `${VAR}` references in its JSON, sets `AWS_RETRY_MODE=standard` and the
  chosen `AWS_MAX_ATTEMPTS`, treats `ConditionalCheckFailedException` as success, and logs other
  failures without aborting. Verify with `UserDataScriptBuilderTest` assertions on the rendered
  function, and with a bash test that runs it against a stub `aws` returning each outcome.
- [ ] 5.2 Call `run_status running` immediately after the watchdog starts, replace the S3
  `run-status` write with `run_status completed|failed:<n>`, and add `run_status timed-out` to the
  watchdog subshell before its boot-log upload. Verify with tests:
  - the rendered script has no `run-status` S3 write;
  - the watchdog still starts immediately after `INSTANCE_ID`, with nothing before it;
  - there is no `set -e`;
  - `timed-out` precedes the watchdog's log upload.
- [ ] 5.3 Always render the results table into user-data, and remove the `NO_DATABASE` branch. Verify
  that `UserDataScriptBuilderTest` asserts that `STORE_ARGS` is always `--results-table`.

## 6. Polling and the lost-status report

- [ ] 6.1 Replace the S3 `GetObject` poll with a strongly consistent `GetItem` on the run item, keeping
  the single re-read after the instance is seen terminated. Verify with `RunCommandTest` cases:
  `completed` maps to 0, `failed:3` to 3, and `timed-out` and `cancelled` to non-zero.
- [ ] 6.2 When no terminal status exists and the instance is gone, query the run's measurements and
  report either "results stored, final status lost" or vanished with the boot-log path. Exit
  non-zero in both cases. Verify with a unit test for each branch.

## 7. `--no-database` removal

- [ ] 7.1 Remove `--no-database` from `RunCommand`. `resolveResultsTable` resolves unconditionally,
  and the "Or discard the results" hint goes. Verify with a `RunCommandTest` case: the option is
  unknown, and nothing is uploaded or launched.
- [ ] 7.2 Remove `--no-database` from `ApiCommonSharedOptions`, shrink `ResultsStoreBuilder`'s check
  to exactly one of `--results-table` / `--mongo-connection-string`, and delete `NoOpResultsStore`
  and its test. Verify with `ResultsStoreBuilderTest` (neither or both fails, either alone succeeds)
  and `ApiCommonSharedOptionsTest` (the option is unknown).
- [ ] 7.3 Remove the "swap for `--no-database`" comments from `jmh-with-profiler.sh` and
  `jmh-with-async.sh`. Verify with `git grep -- '--no-database'`, which should find no code, script
  or main doc.

## 8. `baas runs` command group

- [ ] 8.1 Add the `runs` group with no action of its own, registered at the root next to `run`. Verify
  with a test that `baas runs` prints usage naming `list` and `terminate`.
- [ ] 8.2 Implement `baas runs list` with these options: `--limit` (default 20), `--in-flight`,
  `--project`, repeatable `--tag`, and `--format table|json|csv`. It issues one `Query` on `RUN`,
  newest first, and resolves non-terminal rows with one tag-filtered `DescribeInstances`. Payload
  goes through `Console`, and JSON/CSV use `Locale.ROOT`. Verify with tests:
  - the default shows 20 rows;
  - `--in-flight` drops vanished and terminal runs;
  - a `launching` item without an instance id resolves through the tag;
  - `exclude_from_results=true` runs are shown;
  - listing writes nothing;
  - an empty `--in-flight` prints its message and exits 0.
- [ ] 8.3 Implement `baas runs terminate <runId> [--yes]` on the shared cancel method: prompt when
  interactive, refuse without a terminal and without `--yes`, report a run that already ended, and
  fail on an unknown id. Verify with a test for each case.

## 9. Teardown message

- [ ] 9.1 Return each in-flight instance's id, state and `baas-request-id` tag from
  `listRunningBenchmarkInstances`. Rewrite teardown's refusal to name run ids and
  `baas runs terminate <runId>`. Verify with a `TeardownCommand` test asserting the message, and
  that no DynamoDB client is created on that path.

## 10. Infrastructure

- [ ] 10.1 In `cf-template-core.yaml`, add `UpdateItem` with `LeadingKeys = RUN` to `RunnerRole` and
  `OperatorRole`, and narrow `RunnerRole`'s `PutItem`/`BatchWriteItem` to `LeadingKeys = RESULT#*`.
  Update `infra/operator-policy.json` to match. Verify with `CoreTemplateTest` cases pinning both
  conditions and the absence of any delete action, the existing reference-copy sync test, and a
  check that the deployer policy is unchanged.

## 11. Documentation

- [ ] 11.1 Update CLAUDE.md:
  - the S3 layout table (`run-status` row replaced, `launch-error.txt` added);
  - the *Results table* section (`RUN` items, keys and readers);
  - *Three termination layers* (the watchdog records `timed-out`);
  - *What isn't there* (the absent-store paragraph without `--no-database`);
  - *Adding a benchmark type* (exactly one of two stores).

  Verify with `git grep -n 'run-status\|no-database' CLAUDE.md`, which should return only
  intentional mentions.
- [ ] 11.2 Update README (`baas runs`, `--no-database` removed), `docs/diagrams/baas-run.mmd`,
  `c4-3-component-runner.mmd`, and add `docs/diagrams/baas-runs.mmd`. Verify that each diagram
  renders with `mmdc`.
- [ ] 11.3 Update `docs/analysis/cli-usage-analysis.md` (U3 done, the §2.3 run state graph) and
  `docs/adr/0001-self-contained-baas-cli.md` where they mention `run-status` or `--no-database`.
  Verify with grep.
- [ ] 11.4 Mark U3 **Fixed** and note S7's tightening in `docs/review/baas-cli-findings.md`'s status
  table. Remove `run-status-in-dynamodb` from `openspec/changes/QUEUE.md`.

## 12. End-to-end verification (manual: no automated test covers the `baas run` path)

- [ ] 12.1 Run `baas admin setup` from the new CLI against the account installation. Verify the stack
  update succeeds and an old CLI build can still complete a run against it.
- [ ] 12.2 Run `baas run jmh` against `fake-jmh-benchmarks`. Verify the item passes through
  `launching` → `launched` → `running` → `completed`, the prefix holds no `run-status` object, and
  `baas runs list` shows the run.
- [ ] 12.3 Start a run and `kill -9` the CLI. Verify `baas runs list --in-flight` shows it, then
  `baas runs terminate <runId>` cancels it, the instance terminates, and the item reads `cancelled`.
- [ ] 12.4 Run with a 60-second timeout, a benchmark that outlives it and the minimum margin. Verify
  the watchdog path records `timed-out` and uploads the boot log.
- [ ] 12.5 Force a launch failure, for example with an instance type the account cannot launch.
  Verify `launch-failed` with the error code, `launch-error.txt` in the prefix, and that
  `baas download <runId>` fetches it.
- [ ] 12.6 With a run in flight, run `baas admin teardown` and verify the refusal names the run id
  and `baas runs terminate`. Abort before confirming.
- [ ] 12.7 Compare 12.2's `fake-jmh-benchmarks` score with the most recent pre-change e2e runs in
  `baas results`. Record the spread, and investigate any difference outside the recorded CI range
  rather than accepting it. The only change to user-data timing comes before the JVM starts.

## 13. Verify

- [ ] 13.1 Run `/opsx:verify` and record the result in `verify.md` in this change directory: a
  requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation
  from design or tasks.
