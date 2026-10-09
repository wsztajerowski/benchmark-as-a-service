# Design

## Context

See proposal.md for the findings and why they are fixed in this PR. Every fix below was chosen with the
user, finding by finding, on 2026-10-09. The alternatives recorded here were offered at that point and
lost. The code is `next-release` at `75b6350`.

Two things shape most of the decisions:

- **Termination is spread across two processes.** The CLI (`JobSession`, `JobTermination`) and the
  instance (user-data's `job_status`, the watchdog) each record a status and each terminate. The
  defects sit at the hand-offs between them: who terminates after which status, and what happens when
  one side's terminate fails.
- **`main` → `next-release` is a wipe-and-rebuild release.** `rename-run-to-job` D3 decided no migration,
  and `jobs-command` D12 assumed a pre-change stack would pick up `Delete` policies on its next setup.
  That setup cannot succeed (R4), so D12's assumption does not hold.

## Goals / Non-Goals

**Goals:**
- No path in which a failed AWS call leaves a paid instance with nothing left to terminate it.
- No sequence of CLI commands that ends in a state the CLI cannot remove.
- A single place for each rule that the CLI and the instance currently each hold a copy of.

**Non-Goals:**
- An in-place upgrade for a deployment created by the released CLI (R4, R5).
- Guarding two concurrent `baas admin image build` runs (an accepted gap, per CLAUDE.md).
- Closing the remaining gap in `RunCommand.call()`'s success-path coverage.

## Decisions

### Setup deletes a `ROLLBACK_COMPLETE` stack and then creates it (R1)

`ROLLBACK_COMPLETE` means CloudFormation rolled back a failed create: the stack holds no resources and
cannot be updated. When `SetupCommand.deploy` sees that status, it deletes the stack, waits until the
deletion completes, and takes the create path. `CloudFormationService` gets a status read (or
`stackExists` becomes three-valued) and a `deleteStackAndWait`. `requireUpdatable` stays as the guard on
the update path, which `admin image build` also uses. Its message no longer names `teardown`: it names
`baas admin deployment setup`, which is now the recovery.

`DeletionPolicy: Retain` resources were removed from the template, so nothing outlives the deletion. The
stacks that still carry `Retain` are pre-change stacks, and those never reach `ROLLBACK_COMPLETE` through
this CLI's create.

*Rejected:*
- **Save the configuration file before `CreateStack`.** A create rejected outright, for example at
  template validation, would leave a file naming no stack, and teardown would then refuse it.
- **Let teardown run without a file when `--deployment` names one.** That needs `--region` and the
  deployer profile passed again, which brings back per-command options that `multiple-deployments`
  removed.

### Setup refuses a create whose bucket or table name is taken, before touching anything (R16)

Raised by the user after the review, and confirmed in the code:
- `CreateStack` sets no `OnFailure`, so a create defaults to rollback.
- `S3MainBucket` (`BucketName: ${ResourceNamePrefix}`) and `ResultsTable`
  (`TableName: ${ResourceNamePrefix}-results`) have fixed names. Either one already existing fails the
  resource, rolls back the whole stack, and leaves `ROLLBACK_COMPLETE`.
- Setup checks only for a bucket in *another* region (`SetupCommand.java:162`).

Without a pre-check, R1 turns that dead end into a loop: each re-run deletes the rolled-back stack,
creates it, and fails the same way.

`S3UploadService.bucketRegion` becomes a three-way probe of `HeadBucket`:
- **absent:** 404, or `NoSuchBucket`;
- **reachable, in region X:** 200, or 301/400 carrying `x-amz-bucket-region`, the answer to a
  wrong-region request for the caller's own bucket;
- **forbidden:** 403. The bucket exists, but the caller may not read it.

A redirect is probed again with a client in the region it names: from another region, another
account's bucket answers 301 too, and only a request in its own region returns the 403. Task 1.1
showed this live: `test` answered 301 from eu-central-1, then 403 from us-west-2.

The early check before the preflight acts only on *reachable* in another region, which is today's
message. It no longer acts on a 403. Today a 403 also carries the region header, so another account's
bucket was reported as "the deployment lives in X".

On the create path, before `CreateStack`, and before deleting a `ROLLBACK_COMPLETE` stack (R1), setup
checks both names. That is after the preflight, so the caller holds the deployer's `s3:List*` and
`dynamodb:Describe*` on these exact names.
- **reachable, same region:** a leftover. Refuse, naming `aws s3 rb s3://<prefix> --force` and warning
  that the bucket may hold earlier results.
- **forbidden:** the caller holds the deployer's rights on this name, so the bucket belongs to another
  account. Refuse, and say to choose another name with `--deployment`.
- **The table:** `DescribeTable` finds it. A leftover. Refuse, naming `aws dynamodb delete-table`.

A refusal changes nothing, including leaving a rolled-back stack in place. Once the leftover is removed,
a re-run takes R1's recovery. An update of an existing, updatable stack skips the check, because the
bucket and table are its own.

This partly reverses `jobs-command` D12, which deleted setup's retained-bucket and retained-table
pre-checks. D12's premise, that nothing is retained any more, holds for deployments this CLI creates.
It does not hold for a pre-change teardown (R5), a bucket created by hand, or a globally unique bucket
name another account already holds. The new check reports the leftover. It does not adopt or delete it.

No IAM change: `s3:List*` and `dynamodb:Describe*` already cover `HeadBucket` and `DescribeTable` on
these exact names.

*Rejected:*
- **Read the stack's failure events after the rollback and explain them.** The create has already been
  paid for in time, and R1's recovery would loop.
- **Check only the bucket.** The table fails the create the same way, and R5 leaves both behind.

### A missing bucket counts as already empty (R2)

`S3UploadService.deleteAllObjects` catches `NoSuchBucketException` from the version listing, logs that
the bucket is already gone, and returns. Every other failure still throws, and teardown still stops
before `deleteStack`. Stopping there is what keeps a stack out of `DELETE_FAILED` with objects still in
its bucket. This also covers a stack someone deleted by hand: `getStackParameters` already returns an
empty map for a missing stack, so after this fix teardown removes the leftover file.

*Rejected:* going back to `main`'s warn-and-continue, which brings back `DELETE_FAILED`.

### Self-termination never disarms the watchdog, and falls back to an OS shutdown (R3, R10)

Both `kill $WATCHDOG_PID` lines are deleted. Every `aws ec2 terminate-instances …` in `SCRIPT_BODY`
becomes `aws ec2 terminate-instances … || shutdown -h now`: the end of the script, the refused-`running`
path and the watchdog's own. `Ec2ProvisioningService` already launches with
`instanceInitiatedShutdownBehavior(TERMINATE)`, so the shutdown is a termination that needs no API call,
no IAM permission and no `INSTANCE_ID`.

Killing the watchdog bought nothing. When either `kill` runs, the job item is already terminal. The
watchdog's late `job_status timed-out` is therefore refused by `NOT_TERMINAL`, and its `terminate-instances`
targets an instance that is already terminating. The kill mattered only when the terminate failed, and
then it removed the last thing able to terminate the instance.

One behaviour changes. If the final `completed` write failed outright (not refused) *and* the terminate
failed *and* the shutdown failed, the watchdog later records `timed-out` for a job that finished. Today
that job would sit at `running`. A terminal status that is slightly wrong is preferred to one that is
stuck, and the measurements are stored either way.

**Comparability:** none affected. Every change is after the benchmark process has exited, or on paths
where it never starts, or inside the watchdog. Nothing the JVM sees changes, and nothing in
`environment.json` or the image.

*Rejected:*
- **Kill the watchdog only after the terminate succeeds** (the reviewer's version). It is correct, but
  keeps a branch whose only purpose is a kill that buys nothing.
- **Drop the `kill` without the shutdown fallback.** It leaves a gap when the cause of the failure
  (IAM, instance credentials) also defeats the watchdog's own terminate.

### `RunInstances` carries the job id as its client token (R7)

`.clientToken(jobId)` on the request. The job id is 28 ASCII characters (EC2 accepts up to 64) and is
minted once per job, so a retried request returns the original instance instead of starting a second
one. A token reused with different parameters raises `IdempotentParameterMismatch`, which cannot happen
because the id is never reused. The existing request-shape test pins the token.

### Only `cancelled` makes the CLI terminate a live instance (R10)

`JobStatus.isRecordedByInstance` is renamed `endsItself` and returns true for `completed`, `failed:<n>`
and `timed-out`. `timed-out` is written by exactly two parties:
- the launching CLI's poll cap, which goes through `stop()` and terminates the instance there;
- the instance's watchdog, which uploads the boot log and then terminates itself.

`finish()` handles only statuses this CLI did not write, so a `timed-out` it reads always comes from the
watchdog. `cancelled` remains the only status that someone else (`jobs terminate`) writes while promising
nothing about the instance.

The remaining gap: if the poll cap's own `TerminateInstances` failed, `jobs terminate` will now leave
that instance alone. The watchdog fires within moments, because the poll cap is
`RunCommand.watchdogBound` measured from slightly earlier, and it now has the shutdown fallback.

*Rejected:* terminating on `timed-out` after a grace period long enough for the upload. That adds timing
logic and still guesses how long the upload takes.

### `JobSession` and `jobs terminate` share one stop step (R8)

CLAUDE.md already says `baas jobs terminate` shares `JobSession.stop`. The code copied it instead, and
the copy dropped the re-read after a refused write. The fix extracts that step into one class in
`jobs/` (working name `JobStop`):

1. Write the stop status under the 5 s bound.
2. If the write was refused, read the item again with strong consistency. If the job `endsItself`,
   leave the instance alone and return that status.
3. Otherwise terminate the instance by its recorded id, or by a deployment-scoped tag lookup.

The callers differ in one way only. `jobs terminate` reports a failed termination (exit 1, naming the
instance), while the shutdown hook and the poll cap log it and carry on. That becomes a parameter, or
the step returns the termination's result and each caller decides. `JobTermination` keeps its pre-checks
(job not found, already ended with no live instance, confirmation), but they read the item through the
strongly consistent `read`, not through the index's `find`. `find` resolves the job id to its key, and
the item is then re-read with strong consistency.

*Rejected:* patching the copy in `JobTermination`. The rule would still live in two places, which is
how this bug got in.

### `jobs terminate` acts only on its own deployment's jobs (R6)

`terminateUnrecorded` is deleted. An id with no job item fails with "No job found … baas jobs list" and
terminates nothing. `Ec2ProvisioningService.findLive(jobId)` takes the deployment and adds the
`baas-deployment` filter, so every caller is scoped: `JobTermination`, `JobSession`'s lookup during a
launch, and the new shared stop step.

The branch had no legitimate case left. The released CLI tags `baas-request-id`, never `baas-job-id`.
This CLI reserves the item before `RunInstances` and launches nothing without it. So a `baas-job-id`
instance with no item can only be another deployment's, or one the index has not yet returned.

*Rejected:* scoping `findLive` but keeping the branch. It leaves an unreachable purpose, plus a path that
terminates during index lag without recording anything.

### An absent score is `NaN` from the moment it is read (R9)

`ResultRow.from` maps an absent `score` or `scoreError` to `Double.NaN` instead of `0`. The non-finite
handling that already exists then applies to stored data, which it never did, because the store writes a
`NaN` as absent:
- `ResultsGrouping.better` never picks it;
- the table de-emphasises the cell (`ResultsTable.number`);
- JSON prints `null` (`jsonNumber`).

Two places change to match:
- CSV prints an empty cell for a non-finite value, the CSV form of JSON's `null`, and still formats
  with `Locale.ROOT`.
- `--sort-by score` orders non-finite values last in both directions. `Comparator.comparingDouble` puts
  `NaN` above everything, which would put unknown rows at the top of the descending default.

JCStress rows store no score, so they now show a faint `NaN` instead of `0.000`. That is more honest:
JCStress has no score.

*Rejected:* special-casing `0` in `better()`. It cannot tell a missing score from a real zero, and the
false `0` stays in JSON and CSV.

### A job lookup has no default limit (R11)

`--limit` becomes an `Integer` with no picocli default. The effective limit is the given value; else no
limit when `--job-id` is set; else 20. The `--help` text says so. A job's measurements are a bounded set,
and `baas run` points the user at exactly this lookup.

*Rejected:* no default limit for `--format json|csv` on every access path. A project sweep in JSON can be
a whole partition, and the 20-row default exists for exactly those sweeps.

### A non-terminal job younger than five minutes is never `vanished` (R12)

`JobListing.resolve` takes the current instant. A job whose status is not terminal, whose `createdAt` is
within 5 minutes, and that has no live instance keeps its stored status. Past that, it is `vanished`.
The instant is passed in, not read inside, so tests drive it. The 5 minutes is a named constant: far
longer than `RunInstances` plus the lag before `DescribeInstances` sees the instance, and far shorter
than any real job. `createdAt` is the launching CLI's single instant (`job-identity`), so another
machine's clock skew shifts the window by seconds at most.

This closes U31.

*Rejected:*
- **`launching` is never `vanished`.** A CLI killed hard after its reservation would leave a job in
  flight forever.
- **Leave it as is.** That is U31 itself.

### A configuration file must carry its own name as its prefix (R13)

`ConfigService.readLogged`, or one helper every load path calls, compares the file's `<name>` with its
`prefix` and throws `DeploymentSelectionException` naming the file, both values, and "rename the file or
correct `prefix`". This runs before any AWS client is built, so teardown fails before its gates. An absent
`prefix` (a hand-written file) is accepted and set from the name, since nothing contradicts it.

*Rejected:* deriving `prefix` from the file name and no longer writing the key. A copied file would then
silently address a deployment whose region and profiles it may not match, and the stored format would
change from what CLAUDE.md and the spec describe.

### Every end of a `JobSession` goes through one synchronized claim (R14)

`ended` and `endStatus` stop being set directly in five places. One `synchronized` method on the session
claims the end and records the status. `stop()`, `finish()`, the instance-gone branch and the two launch
paths all use it. A caller that finds the job already ended gets the recorded `endStatus`, which is what
`stop()`'s Javadoc already promises.

`stop()` holds the lock for the whole stop step, so a second caller waits rather than issuing a second
write and a second terminate. The shutdown hook can therefore wait for a `finish()` in progress, which
is one `TerminateInstances` call.

*Rejected:* fixing only the return value. That leaves the double write, the double terminate, and a
window where `ended` is true and `endStatus` is null.

### The terminal-status guard is generated from `JobStatus` (R15)

One helper builds both the condition text and its values from `JobStatus.EXACT_TERMINAL` plus
`FAILED_PREFIX`, with one placeholder per status, generated. It lives in `DynamoDbJobRecorder`, because
`baas-model` stays storage-neutral. `NOT_TERMINAL`, `Update.request`'s value map and
`UserDataScriptBuilder.guardValues()` all read from it. The test asserts the values cover
`EXACT_TERMINAL`, not a list of literals. The expression's semantics are unchanged.

The dead code goes in the same task:
- `S3UploadService.deleteBucket` and its IT cases;
- `JobListing.SHOWN_STATUSES`;
- `RunnerImageExtension.hash`, rewritten as `RunnerJarResolver.sha256Hex(stripped bytes).substring(0, 8)`
  with identical output, pinned by the existing hash test.

### The pre-change upgrade is a release note, carried by a commit footer (R4, R5)

There is no code for this. One commit in this change, the docs commit, carries a `BREAKING CHANGE:`
footer, then a `---` separator, then the trailers. The separator stops the trailers leaking into the
published notes, the third landmine in the release-pipeline memory. Footer text, in substance:

> A deployment created by an earlier release cannot be updated in place: its results index was renamed
> and its bucket and table were retained on deletion. With no job from the earlier CLI in flight:
> `baas admin deployment teardown`, then `aws s3 rb s3://<prefix> --force` and
> `aws dynamodb delete-table --table-name <prefix>-results`, then `baas admin deployment setup` and
> `baas admin image build`.

The release is already a major (ten `!` commits on `next-release`), so the footer does not change the
version.

*Rejected:*
- **Detect a pre-change deployment and refuse with the steps.** The user chose not to carry a
  compatibility check for a release that is a deliberate wipe.
- **An in-place GSI migration in two updates.** `rename-run-to-job` D3: the renamed stored attributes
  make old rows unreadable anyway.

## Risks / Trade-offs

- **[R1] Setup deletes a stack automatically.** → Only in `ROLLBACK_COMPLETE`, which by definition holds
  no resources. Setup logs what it deletes and why. Any other state still goes down the update path.
- **[R16] A 403 on the create path is read as "another account's bucket".** → After the preflight, the
  caller holds `s3:List*` on exactly this name, so a 403 cannot come from missing rights on its own bucket.
  When the preflight could not simulate (no `iam:SimulatePrincipalPolicy`), a caller without rights is
  told the name is taken rather than that rights are missing. The message names both possibilities.
- **[R3/R10] `shutdown -h now` races the boot-log upload.** → It runs only after `terminate-instances`
  failed, and every upload comes before the terminate. Ordering is unchanged.
- **[R10] A poll cap whose terminate failed is no longer retried by `jobs terminate`.** → The watchdog
  fires within moments and now falls back to an OS shutdown.
- **[R12] A crashed CLI's job shows as `launching` for up to five minutes.** → It is bounded, and then
  becomes `vanished` as before.
- **[R14] The shutdown hook can block behind `finish()`.** → At most one terminate call. Status writes are
  bounded to 5 s already.
- **[R9] JCStress rows now print a faint `NaN`.** → It is the truth. JSON consumers that read `0` for
  JCStress now get `null`. The e2e JCStress job asserts shape, not the score; to be confirmed in the
  tasks.

## Migration Plan

Nothing to migrate in data or infrastructure. The CLI and user-data change together, because user-data is
rendered by the CLI that launches the job. A job launched before an upgrade keeps its old script, which
is no worse than today.

Verification: the local gates, then the CI e2e on the PR. The user-data change (R3, R10) is exercised by
both e2e jobs on the success path. The refused-`running` and watchdog paths are covered by the
user-data unit tests and `bash -n`, as today. No live paid run is planned beyond CI's.
