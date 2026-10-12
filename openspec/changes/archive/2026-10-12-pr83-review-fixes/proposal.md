# Proposal

## Why

A `/code-review xhigh` of PR #83 (`next-release` → `main`) found 15 defects. Every one was checked
against the code on `next-release` and its fix was chosen with the user on 2026-10-09. The review
findings are cited as `R1`–`R15` in this change. Four of them can leave a deployment that the CLI
cannot remove, or a paid instance that nothing terminates (R1, R2, R3, R7). This PR is the one merge
into `main` for the release, so they are fixed here, before it ships.

## What Changes

- **R1. Setup recovers from a failed first create.** A stack in `ROLLBACK_COMPLETE` holds no
  resources. Setup deletes it, waits for the deletion, and then creates the stack. The advice to run
  `teardown` is removed. Teardown cannot follow it, because no configuration file was ever written.
- **R16. Setup checks that the stack's fixed names are free before creating it.** The bucket
  `<prefix>` and the table `<prefix>-results` have fixed names. If either already exists, the whole
  create rolls back to `ROLLBACK_COMPLETE`, and with R1 a re-run would fail the same way every time.
  Before any create, setup refuses with the cause and the fix in three cases:
  - a bucket of that name in this region with no live stack: a leftover, named with `aws s3 rb`;
  - a bucket owned by another account: choose another name with `--deployment`;
  - a table of that name: a leftover, named with `aws dynamodb delete-table`.

  The existing bucket-region check no longer reads another account's bucket as "the deployment lives
  in that region". This was raised by the user on 2026-10-09, after the review.
- **R2. A missing bucket counts as already empty.** Teardown carries on to delete the stack.
  Any other failure to empty the bucket still stops teardown before the stack is deleted.
- **R3, R10. A failed self-termination falls back to shutting the instance down.** User-data no longer
  kills the watchdog. Each `terminate-instances` call, the watchdog's included, is followed by
  `|| shutdown -h now`. The instance is launched with `ShutdownBehavior=TERMINATE`, so a shutdown
  from the OS terminates it.
- **R6. `baas jobs terminate` reaches only its own deployment.** The branch for a job with no item
  is removed: no reachable instance has a `baas-job-id` tag without a job item. A lookup of one
  job's instance filters on `baas-deployment` like every other runner query. **BREAKING (spec):** the
  scenario *A job launched before job items existed* is removed.
- **R7. `RunInstances` is idempotent.** The job id is passed as the client token, so an automatic
  SDK retry after a lost response cannot launch a second instance.
- **R8, R10. The CLI terminates a live instance only for `cancelled`.** `completed`, `failed:<n>` and
  `timed-out` mean the instance is ending itself and may be uploading its boot log, or that the CLI
  which wrote the status has already terminated it. `baas run` and `baas jobs terminate` share one
  stop step, so a refused `cancelled` write is re-read and respected in both.
- **R9. A missing score is not a zero.** A score or score error absent from the item is read as
  unknown. `--best-per` never picks it, JSON shows `null`, CSV leaves the cell empty, and
  `--sort-by score` puts it last in either direction.
- **R11. `baas results query --job-id` returns the whole job.** The default limit of 20 applies only
  to project and every-project queries. An explicit `--limit` still applies to a job lookup.
- **R12. A new job is not reported as `vanished`.** A job that has not reached a terminal status and
  is less than 5 minutes old keeps its stored status, even when no instance is visible yet. This
  closes U31.
- **R13. A configuration file must name its own deployment.** `deployments/<name>.yaml` whose `prefix`
  is not `<name>` is refused on read. The refusal names the file, both values and the fix.
- **R14.** Every way a `baas run` session can end goes through one synchronized step. A second
  caller gets the status the job actually ended with.
- **R15.** The terminal-status guard that the CLI and the instance both evaluate is built from
  `JobStatus.EXACT_TERMINAL`, not typed out three times. Dead code is removed:
  `S3UploadService.deleteBucket`, `JobListing.SHOWN_STATUSES`, and a second SHA-256 helper.
- **R4, R5. Release note, no code.** A deployment created by the released CLI cannot be updated in
  place. Its GSI rename is a delete plus an add in one update, which CloudFormation refuses, and its
  bucket and table still carry `Retain`. The note gives the steps: stop old-CLI jobs, run teardown,
  `aws s3 rb s3://<prefix> --force`, `aws dynamodb delete-table --table-name <prefix>-results`, then
  setup.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `core-stack-provisioning`:
  - setup recovers a `ROLLBACK_COMPLETE` stack (R1);
  - emptying a missing bucket succeeds (R2);
  - setup checks that the bucket and table names are free before a create; a later setup after a
    teardown no longer asserts that no such pre-check exists (R16).
- `job-tracking`:
  - the instance's shutdown fallback (R3, R10);
  - the CLI terminates only for `cancelled`, and a stop by two paths at once is recorded once (R8, R10, R14);
  - the grace period before `vanished` (R12);
  - `jobs terminate` without the branch for a job with no item (R6, R8). The requirement is replaced
    under a new name, because OpenSpec refuses a MODIFIED block that drops a scenario;
  - every runner lookup scoped to the deployment (R6);
  - one launch request starts at most one instance (R7).
- `benchmark-results-query`:
  - `--job-id` returns every measurement unless `--limit` is given (R11);
  - an unknown score never wins and is printed as unknown (R9).
- `cli-command-structure`:
  - the paging default does not apply to a job lookup, and unknown scores sort last (R9, R11);
  - a configuration file whose prefix differs from its name is refused (R13).

## Impact

- **Code:** `baas-cli` only. `SetupCommand`, `CloudFormationService`, `TeardownCommand`,
  `S3UploadService`, `UserDataScriptBuilder`, `Ec2ProvisioningService`, `JobSession`,
  `JobTermination`, `JobListing`, `DynamoDbJobRecorder`, `ResultRow`, `ResultsGrouping`,
  `ResultsQuerySubcommand`, `ConfigService` and `RunnerImageExtension`; setup also gains a `DescribeTable` call, covered by the deployer's existing `dynamodb:Describe*`. `baas-model`'s `JobStatus`
  gets the renamed predicate.
- **Cost:** none. No AWS resource, template, IAM policy or tag changes. `shutdown -h now` and the
  client token use what the launch already declares.
- **Deliberately not changed:**
  - No `set -e` in user-data. The watchdog still starts right after `INSTANCE_ID`.
  - All three termination layers stay. R3 and R10 make the watchdog *more* durable: it is no longer
    killed, and it gains the shutdown fallback.
  - The first-terminal-status-wins guard and its exact semantics are unchanged; only how the guard's
    text is produced changes.
  - The deployer policy, the operator policy and `cf-template-core.yaml` are untouched. Recovering a
    `ROLLBACK_COMPLETE` stack uses the `DeleteStack` permission teardown already holds.
  - Nothing is written to the job item that is not written today. `vanished` stays inferred.
  - The deployment-selection rules (*Absent `--deployment` means "the only one"*) are unchanged. R13
    adds one refusal and selects nothing new.
  - R4 and R5 add no migration code, keeping `rename-run-to-job` D3: wipe and rebuild.
- **Closes:** U31 in `docs/review/open-findings.md`, plus review findings R1–R3 and R6–R16. R4 and R5
  are closed by the release note.
