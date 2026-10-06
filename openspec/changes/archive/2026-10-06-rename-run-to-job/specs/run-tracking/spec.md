# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: Every run is recorded as one item in a single run partition`
- TO: `### Requirement: Every job is recorded as one item in a single job partition`

- FROM: `### Requirement: The run is reserved before the instance is launched`
- TO: `### Requirement: The job is reserved before the instance is launched`

- FROM: `### Requirement: A vanished run is inferred, never written`
- TO: `### Requirement: A vanished job is inferred, never written`

- FROM: `### Requirement: The CLI records why it stopped a run, then always terminates`
- TO: `### Requirement: The CLI records why it stopped a job, then always terminates`

- FROM: `### Requirement: `baas runs list` shows recent runs`
- TO: `### Requirement: `baas jobs list` shows recent jobs`

- FROM: `### Requirement: `baas runs terminate` stops one run`
- TO: `### Requirement: `baas jobs terminate` stops one job`

- FROM: `### Requirement: The `runs` group is subcommands only`
- TO: `### Requirement: The `jobs` group is subcommands only`

- FROM: `### Requirement: Run items never appear as measurements`
- TO: `### Requirement: Job items never appear as measurements`

- FROM: `### Requirement: Run status writes are limited to the run partition`
- TO: `### Requirement: Job status writes are limited to the job partition`

## MODIFIED Requirements

### Requirement: Every job is recorded as one item in a single job partition
Each job SHALL be recorded as exactly one item in the results table at `pk = JOB` and
`sk = <createdAt>#<jobId>`, where `createdAt` is the job's single clock reading in the table's fixed-width
timestamp format. The item SHALL carry `gsi1pk = <jobId>` and a `gsi1sk` value that no measurement item
can carry, so the job-ID index resolves it. The item SHALL hold the job's identifier, project,
creation instant, S3 result path, instance type, status, and the tag map the CLI assembled for the job
(project, source, benchmark type and caller-supplied tags). It SHALL NOT hold the tags the instance
observes (image version, JDK, CPU model and architecture), which belong to measurements. Every job of
every project SHALL share the one `JOB` partition. The identity fields and tags SHALL be written only by
the CLI's reservation.

#### Scenario: One query lists every project's runs
- **WHEN** jobs from two projects have been launched
- **THEN** a single query on `pk = JOB` in descending sort order returns both, newest first

#### Scenario: A run is found by its identifier
- **WHEN** the job-ID index is queried for a job identifier
- **THEN** it returns that job's item, distinguishable from any measurement of the same job

#### Scenario: Observed tags stay off the run item
- **WHEN** a job completes on an instance whose JDK and CPU model were observed
- **THEN** the job item's tags contain the CLI-side tags only, and the measurement items carry the
  observed ones

### Requirement: The job is reserved before the instance is launched
`baas run` SHALL write the job item with status `launching` after every precondition has passed and
after the benchmark JAR is uploaded, and immediately before requesting the instance. If that write
fails, `baas run` SHALL NOT request an instance and SHALL exit non-zero. A refused precondition SHALL NOT
create a job item. Once the instance request returns, `baas run` SHALL be able to terminate that instance
on interrupt before it writes anything further. It SHALL then record `launched` with the instance
identifier, only while the status is still `launching`. That write SHALL be best effort: if it fails,
the job proceeds, and the instance's own `running` write supplies the instance identifier. If it is
refused because the job is already terminal (cancelled elsewhere while launching), `baas run` SHALL
terminate the instance it launched and exit non-zero.

#### Scenario: A refused run leaves no item
- **WHEN** `baas run` fails a precondition such as a missing benchmark JAR or a missing runner image
- **THEN** no job item exists for it

#### Scenario: A failed reservation launches nothing
- **WHEN** the `launching` write is rejected
- **THEN** no instance is requested, and the command exits non-zero naming the failed write

#### Scenario: A launched run records its instance
- **WHEN** the instance request succeeds
- **THEN** the job item's status becomes `launched` and it carries the instance identifier

#### Scenario: Interrupt during the launched write
- **WHEN** the operator presses Ctrl+C after the instance request returned and before `launched` is
  recorded
- **THEN** the instance is terminated

#### Scenario: A run cancelled while launching does not run
- **WHEN** `baas jobs terminate` cancels a job whose item still reads `launching`, and the launching
  CLI's instance request then succeeds
- **THEN** the launching CLI's `launched` write is refused, it terminates the instance it launched,
  and it exits non-zero

#### Scenario: A late launched write does not move the status back
- **WHEN** the instance has already recorded `running` and the CLI's `launched` write arrives later
- **THEN** the item keeps `running`

### Requirement: A failed launch is recorded and diagnosable
When the instance request fails, `baas run` SHALL record status `launch-failed` with the AWS error code on
the job item, and SHALL upload `launch-error.txt` to the job's S3 prefix holding the error code, message,
AWS job identifier, time, and the requested instance type, image and subnet. Both writes SHALL be
best effort: the CLI SHALL report the launch error and exit non-zero whether or not they succeed.

#### Scenario: Insufficient capacity is recorded
- **WHEN** the instance request fails with `InsufficientInstanceCapacity`
- **THEN** the job item reads `launch-failed` with that error code, the job prefix holds
  `launch-error.txt`, and `baas run` exits non-zero

#### Scenario: The recording itself fails
- **WHEN** the instance request fails and the S3 upload and the item write fail too
- **THEN** `baas run` still reports the original launch error and exits non-zero

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
fallback status written to S3.

#### Scenario: A successful run reports completion
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

#### Scenario: A run cancelled during its launch does not run
- **WHEN** the job was recorded `cancelled` before its instance booted, and no instance was found to
  terminate
- **THEN** the instance's `running` write is refused, the benchmark does not start, the boot log is in
  the job's S3 prefix, and the instance terminates

#### Scenario: A wrong key creates nothing
- **WHEN** the instance writes a status to a key that holds no item
- **THEN** the write is refused and no item is created

### Requirement: The first terminal status wins
Every job-item write SHALL be conditional on the stored status not being terminal. The terminal statuses
are `completed`, `failed:<n>`, `timed-out`, `cancelled` and `launch-failed`. A write that would replace a
terminal status SHALL be refused.

#### Scenario: Cancellation races completion
- **WHEN** the CLI records `cancelled` and the instance then attempts to record `completed`
- **THEN** the item keeps `cancelled`, and the stored measurements remain queryable

#### Scenario: The instance completes an unconfirmed launch
- **WHEN** the CLI dies after the instance was requested but before recording `launched`
- **THEN** the instance's `running` write moves the existing `launching` item to `running` and adds
  the instance identifier

### Requirement: A vanished job is inferred, never written
A job SHALL be reported as `vanished` when its stored status is not terminal and its instance is not
`pending` or `running`. When the item carries no instance identifier, the instance SHALL be looked up by
its `baas-job-id` tag; finding none SHALL also mean `vanished`. No command SHALL write `vanished` to
the item.

#### Scenario: A launch the CLI never confirmed
- **WHEN** an item reads `launching` with no instance identifier and an instance tagged with its job
  identifier is running
- **THEN** the job is reported as in flight

#### Scenario: Reading does not write
- **WHEN** `baas jobs list` reports a job as vanished
- **THEN** the job item's stored status is unchanged

### Requirement: `baas run` reports a lost final status honestly
`baas run` SHALL poll the job item with strongly consistent reads. When the item holds no terminal status
and the instance has terminated, `baas run` SHALL check whether measurements are stored for the job. If
any are, it SHALL report that results were stored but the final status was lost, and exit non-zero. If
none are, it SHALL report the job as vanished, naming the boot log's location, and exit non-zero.

#### Scenario: Results survive a lost status
- **WHEN** the instance stored measurements but its final status write failed
- **THEN** `baas run` says results were stored and the final status was lost, and exits non-zero

#### Scenario: A run that died early
- **WHEN** the instance terminates having stored nothing and recorded no terminal status
- **THEN** `baas run` reports the job as vanished, names the boot log location, and exits non-zero

### Requirement: The CLI records why it stopped a job, then always terminates
When `baas run` stops a job that is still in flight, it SHALL record `cancelled` on interrupt, or
`timed-out` when its poll cap is reached, and then terminate the instance. `baas jobs terminate` SHALL
record `cancelled` and terminate. The status write SHALL be bounded by a short timeout, and the
instance SHALL be terminated whether or not the write succeeded. When the poll finds a `cancelled` or
`timed-out` that this CLI did not write, `baas run` SHALL still terminate the instance if it is pending or
running. A `completed` or `failed:<n>` is written by the instance itself, which terminates on its own
after uploading its boot log, so the CLI SHALL NOT terminate it — neither when the poll reads it nor
when it is the reason the CLI's own status write was refused. A poll cap reached after the instance
recorded its outcome SHALL report that outcome, not `timed-out`.

#### Scenario: Interrupt cancels the run
- **WHEN** the operator presses Ctrl+C while the benchmark is running
- **THEN** the job item reads `cancelled` and the instance is terminated

#### Scenario: The poll cap records a timeout
- **WHEN** `baas run` reaches its poll cap while the instance is still running
- **THEN** the job item reads `timed-out` and the instance is terminated

#### Scenario: A failed cancel write still terminates
- **WHEN** the operator presses Ctrl+C and the `cancelled` write fails or times out
- **THEN** the instance is terminated anyway

#### Scenario: Interrupt just after the run completed
- **WHEN** the operator presses Ctrl+C after the instance recorded `completed` and before the next poll
- **THEN** `cancelled` is refused, the item keeps `completed`, and the CLI does not terminate the instance

#### Scenario: The poll cap is reached as the run completes
- **WHEN** the poll cap is reached and the instance has already recorded `completed`
- **THEN** `baas run` reports `completed`, exits zero, and does not terminate the instance

#### Scenario: Cancelled from elsewhere
- **WHEN** another operator runs `baas jobs terminate` on a job that this `baas run` is polling
- **THEN** this `baas run` sees `cancelled`, makes sure the instance is terminated, and exits non-zero

### Requirement: `baas jobs list` shows recent jobs
`baas jobs list` SHALL show the most recent jobs of every project and status, newest first, 20 by
default and `--limit <n>` otherwise. `--in-flight` SHALL restrict the output to jobs whose resolved
status is not terminal and not vanished. `--project <name>` and repeatable `--tag <key>=<value>` SHALL
filter the rows. The table SHALL show job identifier, project, status, source, instance, instance type,
start time and, for a job in flight, the time elapsed since it started, plus the error code for a failed
launch. A finished job shows no duration: the instance records no timestamp, so none is known. `--format json` and `--format csv` SHALL
carry the same rows, including tags. Jobs tagged `exclude_from_results=true` SHALL be shown like any
other. Statuses SHALL be resolved with a single instance lookup per invocation, whose size does not grow
with the number of unresolved jobs. When filters are given, the listing SHALL keep reading older jobs
until it has the requested number of matching rows or the jobs are exhausted.

#### Scenario: Default listing
- **WHEN** `baas jobs list` is invoked and 30 jobs exist
- **THEN** the 20 newest are shown, of every status and project

#### Scenario: A filter that matches older runs
- **WHEN** `baas jobs list --project a` is invoked, and the 20 newest jobs all belong to project `b`
  while older jobs belong to `a`
- **THEN** up to 20 jobs of project `a` are shown

#### Scenario: Many vanished runs
- **WHEN** more than 200 jobs have a non-terminal status and no running instance
- **THEN** `baas jobs list --in-flight` still resolves every status, and shows only jobs whose
  instance is pending or running

#### Scenario: In-flight only
- **WHEN** `baas jobs list --in-flight` is invoked while one job is running and one has vanished
- **THEN** only the running one is shown

#### Scenario: CI runs are not hidden
- **WHEN** a CI job tagged `exclude_from_results=true` is in flight
- **THEN** `baas jobs list` shows it, with its source

#### Scenario: Nothing to show
- **WHEN** `baas jobs list --in-flight` is invoked and no job is in flight
- **THEN** standard output says so, and the command exits zero

### Requirement: `baas jobs terminate` stops one job
`baas jobs terminate <jobId>` SHALL record `cancelled` on the job item and terminate its instance. On an
interactive terminal it SHALL ask for confirmation first; `--yes` SHALL skip the prompt, and without a
terminal and without `--yes` it SHALL refuse. It SHALL leave a terminal status unchanged. It SHALL still
terminate the job's instance when that instance is pending or running, whatever the item says. It SHALL
exit non-zero when the termination request fails. A job with no item but a pending or running instance
carrying its job id — launched by a CLI from before job items — SHALL have that instance terminated, with
nothing recorded. When neither exists, it SHALL fail naming the identifier.

#### Scenario: Terminating an in-flight run
- **WHEN** `baas jobs terminate <jobId> --yes` names a running job
- **THEN** the item reads `cancelled` and the instance is terminated

#### Scenario: A run launched before run items existed
- **WHEN** `baas jobs terminate <jobId> --yes` names a job that has no item but whose instance carries
  its job id and is running
- **THEN** the instance is terminated, no item is created, and the command exits zero

#### Scenario: A finished run is left alone
- **WHEN** `baas jobs terminate` names a job whose status is `completed` and whose instance is gone
- **THEN** the item is unchanged, no instance is terminated, and the command says the job already ended

#### Scenario: A terminal item with a live instance
- **WHEN** `baas jobs terminate` names a job whose item reads `cancelled` but whose instance is still
  running, because an earlier termination failed
- **THEN** the instance is terminated and the item stays `cancelled`

#### Scenario: A failed termination is reported
- **WHEN** the termination request is rejected
- **THEN** the command exits non-zero naming the instance

#### Scenario: No terminal, no confirmation
- **WHEN** `baas jobs terminate <jobId>` runs without a terminal and without `--yes`
- **THEN** it refuses and changes nothing

### Requirement: The `jobs` group is subcommands only
`baas jobs` SHALL be a command group whose actions are subcommands. Invoked with no subcommand it SHALL
print its usage.

#### Scenario: Bare group prints usage
- **WHEN** `baas jobs` is invoked
- **THEN** its usage, naming `list` and `terminate`, is printed

### Requirement: Job items never appear as measurements
Every reader of measurements — the project sweep, the every-project report, the project picker and the
lookup of a job's measurements by identifier — SHALL exclude job items. A job item reaching a
measurement reader SHALL fail loudly rather than be rendered as a row.

#### Scenario: A project with only failed runs is not offered
- **WHEN** a project's only items are job items for jobs that stored nothing
- **THEN** the project picker does not offer it

#### Scenario: Every-project report ignores runs
- **WHEN** `baas results --all-projects` is invoked while job items exist
- **THEN** every row printed is a measurement

#### Scenario: Results by run identifier
- **WHEN** `baas results --job-id <jobId>` is invoked for a job that stored measurements
- **THEN** the rows are that job's measurements, and the job item is not among them

### Requirement: Job status writes are limited to the job partition
The operator role and the runner role SHALL each be able to update items only in the `JOB` partition, and
the runner role SHALL be able to put measurement items only in `RESULT#` partitions. Neither role SHALL
gain delete access.

#### Scenario: The operator cannot write a measurement
- **WHEN** an identity holding the operator role attempts to update an item in a `RESULT#` partition
- **THEN** the request is denied

#### Scenario: The runner cannot forge a run outside its partition
- **WHEN** an instance holding the runner role attempts to put an item in the `JOB` partition
- **THEN** the request is denied
