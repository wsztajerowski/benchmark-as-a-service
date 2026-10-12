## ADDED Requirements

### Requirement: A job launches at most one instance
The launch request for a job SHALL carry an idempotency token derived from the job identifier, so that a
request retried after its response was lost returns the instance the first attempt started rather than
starting another.

#### Scenario: A retried launch does not start a second instance
- **WHEN** the first launch request for a job starts an instance but its response is lost, and the request is retried
- **THEN** the retry reports the same instance, and exactly one instance carries the job's identifier

### Requirement: `baas jobs terminate` stops one job of its deployment
`baas jobs terminate <jobId>` SHALL record `cancelled` on the job item and terminate its instance. On an
interactive terminal it SHALL ask for confirmation first; `--yes` SHALL skip the prompt, and without a
terminal and without `--yes` it SHALL refuse. It SHALL leave a terminal status unchanged. It SHALL still
terminate the job's instance when that instance is pending or running and the item reads `cancelled` or
no terminal status. When the item reads a status after which the instance ends itself — `completed`,
`failed:<n>` or `timed-out` — whether read before the `cancelled` write or as the reason that write was
refused, it SHALL leave the instance alone and say the job already ended. It SHALL exit non-zero when the
termination request fails. A job identifier with no item in the addressed deployment SHALL fail naming the
identifier, and SHALL terminate nothing.

#### Scenario: Terminating an in-flight job
- **WHEN** `baas jobs terminate <jobId> --yes` names a running job
- **THEN** the item reads `cancelled` and the instance is terminated

#### Scenario: A finished job is left alone
- **WHEN** `baas jobs terminate` names a job whose status is `completed` and whose instance is gone
- **THEN** the item is unchanged, no instance is terminated, and the command says the job already ended

#### Scenario: A terminal item with a live instance
- **WHEN** `baas jobs terminate` names a job whose item reads `cancelled` but whose instance is still
  running, because an earlier termination failed
- **THEN** the instance is terminated and the item stays `cancelled`

#### Scenario: Terminating as the job completes
- **WHEN** `baas jobs terminate` reads the job as `running`, and the instance records `completed` before the `cancelled` write
- **THEN** the `cancelled` write is refused, the item keeps `completed`, the instance is not terminated, and the command exits zero saying the job already ended

#### Scenario: A job the watchdog timed out
- **WHEN** `baas jobs terminate` names a job whose item reads `timed-out` and whose instance is still running
- **THEN** the instance is not terminated, and the command says the job already ended

#### Scenario: An identifier from another deployment
- **WHEN** `baas --deployment a jobs terminate <jobId> --yes` names a job of deployment `b`, whose instance is running in the same region
- **THEN** the command fails naming the identifier, and no instance is terminated

#### Scenario: A failed termination is reported
- **WHEN** the termination request is rejected
- **THEN** the command exits non-zero naming the instance

#### Scenario: No terminal, no confirmation
- **WHEN** `baas jobs terminate <jobId>` runs without a terminal and without `--yes`
- **THEN** it refuses and changes nothing

## MODIFIED Requirements

### Requirement: The instance reports its own progress and outcome
The user-data script SHALL record `running`, with the instance identifier it observed, after the
watchdog has started. It SHALL record `completed` when the benchmark process exits zero and
`failed:<exitCode>` otherwise. The watchdog SHALL record `timed-out` before uploading the boot log and
terminating the instance. The instance SHALL write only the status, and the instance identifier when the
item lacks one. It SHALL write no timestamp. It SHALL write only to an item that already exists, so a
key the instance gets wrong is refused rather than creating a second item. A write that is refused by
the terminal-status rule SHALL be treated as expected; when it is the `running` write, the instance SHALL
NOT start the benchmark, and SHALL upload the boot log and terminate. A write that fails for any other reason SHALL be
retried by the AWS CLI's retry policy and then logged, and SHALL NOT stop the script before the instance
terminates. Each status write, retries included, SHALL give up within a bound shorter than the minimum
watchdog margin, and its retry settings SHALL NOT reach the benchmark process. There SHALL be no
fallback status written to S3. The watchdog SHALL NOT be stopped before the instance terminates. Every
self-termination — on success, on failure, on a refused `running` write, and the watchdog's — SHALL fall
back to shutting the operating system down when the termination request fails; the instance is launched
so that an operating-system shutdown terminates it.

#### Scenario: A successful job reports completion
- **WHEN** the benchmark process exits zero
- **THEN** the job item's status is `completed`

#### Scenario: A deadlocked benchmark reports a timeout
- **WHEN** the benchmark process never exits and the watchdog fires
- **THEN** the job item's status is `timed-out`, the boot log is in the job's S3 prefix, and the
  instance terminates

#### Scenario: A status write failure does not orphan the instance
- **WHEN** every attempt to record the final status fails
- **THEN** the boot log is still uploaded and the instance still terminates

#### Scenario: An unreachable table does not hold up the benchmark
- **WHEN** the `running` write cannot reach DynamoDB at all
- **THEN** it gives up within its bound and the benchmark starts

#### Scenario: A job cancelled during its launch does not run
- **WHEN** the job was recorded `cancelled` before its instance booted, and no instance was found to
  terminate
- **THEN** the instance's `running` write is refused, the benchmark does not start, the boot log is in
  the job's S3 prefix, and the instance terminates

#### Scenario: A wrong key creates nothing
- **WHEN** the instance writes a status to a key that holds no item
- **THEN** the write is refused and no item is created

#### Scenario: A failed termination request does not orphan the instance
- **WHEN** the instance's own termination request fails, on any path that ends the script
- **THEN** the instance shuts its operating system down and so terminates, and the watchdog was not stopped beforehand

### Requirement: A vanished job is inferred, never written
A job SHALL be reported as `vanished` when its stored status is not terminal, its instance is not
`pending` or `running`, and it was created at least five minutes ago. When the item carries no instance
identifier, the instance SHALL be looked up by its `baas-job-id` tag; finding none SHALL also mean
`vanished` once those five minutes have passed. A job younger than five minutes SHALL be reported with its
stored status, because its instance may not have been requested or be visible yet. No command SHALL write
`vanished` to the item.

#### Scenario: A launch the CLI never confirmed
- **WHEN** an item reads `launching` with no instance identifier and an instance tagged with its job
  identifier is running
- **THEN** the job is reported as in flight

#### Scenario: Reading does not write
- **WHEN** `baas jobs list` reports a job as vanished
- **THEN** the job item's stored status is unchanged

#### Scenario: A job still launching is not vanished
- **WHEN** a job was reserved a few seconds ago, reads `launching`, and no instance carrying its identifier is visible yet
- **THEN** `baas jobs list` reports it as `launching`, and `--in-flight` shows it

#### Scenario: A launch abandoned long ago is vanished
- **WHEN** a job reads `launching`, was created more than five minutes ago, and no instance carrying its identifier exists
- **THEN** `baas jobs list` reports it as `vanished`

### Requirement: The CLI records why it stopped a job, then always terminates
When `baas run` stops a job that is still in flight, it SHALL record `cancelled` on interrupt, or
`timed-out` when its poll cap is reached, and then terminate the instance. `baas jobs terminate` SHALL
record `cancelled` and terminate. The status write SHALL be bounded by a short timeout, and the
instance SHALL be terminated whether or not the write succeeded. When the poll finds a `cancelled` that
this CLI did not write, `baas run` SHALL still terminate the instance if it is pending or running.
`completed`, `failed:<n>` and `timed-out` are statuses after which the instance ends itself: the first
two are written by the instance, which terminates after uploading its boot log, and a `timed-out` that
this CLI did not write was written by the instance's watchdog, which does the same. The CLI SHALL NOT
terminate the instance for any of them — neither when the poll reads one nor when it is the reason the
CLI's own status write was refused. A poll cap reached after the instance recorded its outcome SHALL
report that outcome, not `timed-out`. When a job is stopped by two paths at once — an interrupt while
the poll cap is being handled — it SHALL be recorded and terminated once, and both SHALL report the
status the job ended with.

#### Scenario: Interrupt cancels the job
- **WHEN** the operator presses Ctrl+C while the benchmark is running
- **THEN** the job item reads `cancelled` and the instance is terminated

#### Scenario: The poll cap records a timeout
- **WHEN** `baas run` reaches its poll cap while the instance is still running
- **THEN** the job item reads `timed-out` and the instance is terminated

#### Scenario: A failed cancel write still terminates
- **WHEN** the operator presses Ctrl+C and the `cancelled` write fails or times out
- **THEN** the instance is terminated anyway

#### Scenario: Interrupt just after the job completed
- **WHEN** the operator presses Ctrl+C after the instance recorded `completed` and before the next poll
- **THEN** `cancelled` is refused, the item keeps `completed`, and the CLI does not terminate the instance

#### Scenario: The poll cap is reached as the job completes
- **WHEN** the poll cap is reached and the instance has already recorded `completed`
- **THEN** `baas run` reports `completed`, exits zero, and does not terminate the instance

#### Scenario: Cancelled from elsewhere
- **WHEN** another operator runs `baas jobs terminate` on a job that this `baas run` is polling
- **THEN** this `baas run` sees `cancelled`, makes sure the instance is terminated, and exits non-zero

#### Scenario: The watchdog fired first
- **WHEN** `baas run` polls after the instance's watchdog recorded `timed-out` and while that instance is still uploading its boot log
- **THEN** `baas run` reports `timed-out`, exits non-zero, and does not terminate the instance

#### Scenario: Interrupt during the poll cap
- **WHEN** the operator presses Ctrl+C while `baas run` is recording its poll-cap timeout
- **THEN** one status is recorded, the instance is terminated once, and the reported status equals the stored one

### Requirement: Runner lookups are scoped to the deployment
Every query the CLI makes for live runner instances SHALL filter on `baas-deployment=<prefix>` of the
deployment it addresses, in addition to `baas-role=benchmark-runner`. This SHALL include a lookup of
one job's instance by its `baas-job-id` tag. Two deployments in one region SHALL NOT see or act on each
other's runners. An instance launched before this tag existed carries no `baas-deployment` and SHALL NOT
be found by a scoped query.

#### Scenario: `jobs list` ignores another deployment's runners
- **WHEN** `baas --deployment wiktor-dev jobs list` runs while a runner of `baas-123456789012` is running in the same region
- **THEN** that runner is neither reported nor used to resolve any listed job's liveness

#### Scenario: An untagged runner from an older CLI
- **WHEN** a runner launched by a CLI from before this change is running
- **THEN** a scoped query does not find it, and the shell watchdog still terminates it within its timeout plus margin

#### Scenario: A job's instance is looked up within the deployment
- **WHEN** the CLI looks up the instance of one job by its identifier
- **THEN** the lookup filters on the addressed deployment, and an instance of another deployment carrying that identifier is not found

## REMOVED Requirements

### Requirement: `baas jobs terminate` stops one job
**Reason**: It terminated an instance found by its job-id tag alone when the job had no item, for jobs
"launched by a CLI from before job items". No such instance exists: the released CLI tags
`baas-request-id`, and this CLI writes the item before it launches. What the branch could actually reach
was another deployment's runner, or a job whose item the index had not yet returned. It also terminated
an instance that had just recorded its own outcome.
**Migration**: Replaced by *`baas jobs terminate` stops one job of its deployment*. An identifier with no
item fails with "No job found". An instance launched by the released CLI is stopped by its own
watchdog, or terminated by hand.
