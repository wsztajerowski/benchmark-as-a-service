# Spec Delta

## Purpose

Records every benchmark run as one item in the results table, so a run's status is visible to any
operator — including after the launching CLI has died — and in-flight runs can be listed and stopped.

## ADDED Requirements

### Requirement: Every run is recorded as one item in a single run partition
Each run SHALL be recorded as exactly one item in the results table at `pk = RUN` and
`sk = <createdAt>#<runId>`, where `createdAt` is the run's single clock reading in the table's fixed-width
timestamp format. The item SHALL carry `gsi1pk = <runId>` and a `gsi1sk` value that no measurement item
can carry, so the request-ID index resolves it. The item SHALL hold the run's identifier, project,
creation instant, S3 result path, instance type, status, and the tag map the CLI assembled for the run
(project, source and caller-supplied tags). It SHALL NOT hold the machine-observed tags, which belong to
measurements. Every run of every project SHALL share the one `RUN` partition.

#### Scenario: One query lists every project's runs
- **WHEN** runs from two projects have been launched
- **THEN** a single query on `pk = RUN` in descending sort order returns both, newest first

#### Scenario: A run is found by its identifier
- **WHEN** the request-ID index is queried for a run identifier
- **THEN** it returns that run's item, distinguishable from any measurement of the same run

#### Scenario: Observed tags stay off the run item
- **WHEN** a run completes on an instance whose JDK and CPU model were observed
- **THEN** the run item's tags contain the CLI-side tags only, and the measurement items carry the
  observed ones

### Requirement: The run is reserved before the instance is launched
`baas run` SHALL write the run item with status `launching` after every precondition has passed and
after the benchmark JAR is uploaded, and immediately before requesting the instance. If that write
fails, `baas run` SHALL NOT request an instance and SHALL exit non-zero. After the instance is
requested, the CLI SHALL record `launched` with the instance identifier. A refused precondition SHALL NOT
create a run item.

#### Scenario: A refused run leaves no item
- **WHEN** `baas run` fails a precondition such as a missing benchmark JAR or a missing runner image
- **THEN** no run item exists for it

#### Scenario: A failed reservation launches nothing
- **WHEN** the `launching` write is rejected
- **THEN** no instance is requested, and the command exits non-zero naming the failed write

#### Scenario: A launched run records its instance
- **WHEN** the instance request succeeds
- **THEN** the run item's status becomes `launched` and it carries the instance identifier

### Requirement: A failed launch is recorded and diagnosable
When the instance request fails, `baas run` SHALL record status `launch-failed` with the AWS error code on
the run item, and SHALL upload `launch-error.txt` to the run's S3 prefix holding the error code, message,
AWS request identifier, time, and the requested instance type, image and subnet. Both writes SHALL be
best effort: the CLI SHALL report the launch error and exit non-zero whether or not they succeed.

#### Scenario: Insufficient capacity is recorded
- **WHEN** the instance request fails with `InsufficientInstanceCapacity`
- **THEN** the run item reads `launch-failed` with that error code, the run prefix holds
  `launch-error.txt`, and `baas run` exits non-zero

#### Scenario: The recording itself fails
- **WHEN** the instance request fails and the S3 upload and the item write fail too
- **THEN** `baas run` still reports the original launch error and exits non-zero

### Requirement: The instance reports its own progress and outcome
The user-data script SHALL record `running`, with the instance identifier it observed, after the
watchdog has started. It SHALL record `completed` when the benchmark process exits zero and
`failed:<exitCode>` otherwise. The watchdog SHALL record `timed-out` before uploading the boot log and
terminating the instance. A write that is refused by the terminal-status rule SHALL be treated as
expected. A write that fails for any other reason SHALL be retried by the AWS CLI's retry policy and
then logged, and SHALL NOT stop the script before the instance terminates. There SHALL be no fallback
status written to S3.

#### Scenario: A successful run reports completion
- **WHEN** the benchmark process exits zero
- **THEN** the run item's status is `completed`

#### Scenario: A deadlocked benchmark reports a timeout
- **WHEN** the benchmark process never exits and the watchdog fires
- **THEN** the run item's status is `timed-out`, the boot log is in the run's S3 prefix, and the
  instance terminates

#### Scenario: A status write failure does not orphan the instance
- **WHEN** every attempt to record the final status fails
- **THEN** the boot log is still uploaded and the instance still terminates

### Requirement: The first terminal status wins
Every run-item write SHALL be conditional on the stored status not being terminal. The terminal statuses
are `completed`, `failed:<n>`, `timed-out`, `cancelled` and `launch-failed`. A write that would replace a
terminal status SHALL be refused. Each writer SHALL set the identity fields only when absent, so
whichever write lands first creates a complete item.

#### Scenario: Cancellation races completion
- **WHEN** the CLI records `cancelled` and the instance then attempts to record `completed`
- **THEN** the item keeps `cancelled`, and the stored measurements remain queryable

#### Scenario: The instance writes first
- **WHEN** the CLI dies after the instance was requested but before recording `launched`
- **THEN** the instance's `running` write completes the item with the instance identifier

### Requirement: A vanished run is inferred, never written
A run SHALL be reported as `vanished` when its stored status is not terminal and its instance is not
`pending` or `running`. When the item carries no instance identifier, the instance SHALL be looked up by
its `baas-request-id` tag; finding none SHALL also mean `vanished`. No command SHALL write `vanished` to
the item.

#### Scenario: A launch the CLI never confirmed
- **WHEN** an item reads `launching` with no instance identifier and an instance tagged with its run
  identifier is running
- **THEN** the run is reported as in flight

#### Scenario: Reading does not write
- **WHEN** `baas runs list` reports a run as vanished
- **THEN** the run item's stored status is unchanged

### Requirement: `baas run` reports a lost final status honestly
`baas run` SHALL poll the run item with strongly consistent reads. When the item holds no terminal status
and the instance has terminated, `baas run` SHALL check whether measurements are stored for the run. If
any are, it SHALL report that results were stored but the final status was lost, and exit non-zero. If
none are, it SHALL report the run as vanished, naming the boot log's location, and exit non-zero.

#### Scenario: Results survive a lost status
- **WHEN** the instance stored measurements but its final status write failed
- **THEN** `baas run` says results were stored and the final status was lost, and exits non-zero

#### Scenario: A run that died early
- **WHEN** the instance terminates having stored nothing and recorded no terminal status
- **THEN** `baas run` reports the run as vanished, names the boot log location, and exits non-zero

### Requirement: The CLI records cancellation before terminating
When `baas run` stops a run that is still in flight — on interrupt or on reaching its poll cap — it SHALL
record `cancelled` and then terminate the instance. `baas runs terminate` SHALL do the same.

#### Scenario: Interrupt cancels the run
- **WHEN** the operator presses Ctrl+C while the benchmark is running
- **THEN** the run item reads `cancelled` and the instance is terminated

### Requirement: `baas runs list` shows recent runs
`baas runs list` SHALL show the most recent runs of every project and status, newest first, 20 by
default and `--limit <n>` otherwise. `--in-flight` SHALL restrict the output to runs whose resolved
status is not terminal and not vanished. `--project <name>` and repeatable `--tag <key>=<value>` SHALL
filter the rows. The table SHALL show run identifier, project, status, instance, instance type, start
time and duration, plus the error code for a failed launch. `--format json` and `--format csv` SHALL
carry the same rows, including tags. Runs tagged `exclude_from_results=true` SHALL be shown like any
other. Statuses SHALL be resolved with at most one instance lookup per invocation.

#### Scenario: Default listing
- **WHEN** `baas runs list` is invoked and 30 runs exist
- **THEN** the 20 newest are shown, of every status and project

#### Scenario: In-flight only
- **WHEN** `baas runs list --in-flight` is invoked while one run is running and one has vanished
- **THEN** only the running one is shown

#### Scenario: CI runs are not hidden
- **WHEN** a CI run tagged `exclude_from_results=true` is in flight
- **THEN** `baas runs list` shows it, with its source

#### Scenario: Nothing to show
- **WHEN** `baas runs list --in-flight` is invoked and no run is in flight
- **THEN** standard output says so, and the command exits zero

### Requirement: `baas runs terminate` stops one run
`baas runs terminate <runId>` SHALL record `cancelled` on the run item and terminate its instance. On an
interactive terminal it SHALL ask for confirmation first; `--yes` SHALL skip the prompt, and without a
terminal and without `--yes` it SHALL refuse. It SHALL report a run that is already terminal without
changing it, and SHALL fail naming the identifier when no such run exists.

#### Scenario: Terminating an in-flight run
- **WHEN** `baas runs terminate <runId> --yes` names a running run
- **THEN** the item reads `cancelled` and the instance is terminated

#### Scenario: A finished run is left alone
- **WHEN** `baas runs terminate` names a run whose status is `completed`
- **THEN** the item is unchanged, no instance is terminated, and the command says the run already ended

#### Scenario: No terminal, no confirmation
- **WHEN** `baas runs terminate <runId>` runs without a terminal and without `--yes`
- **THEN** it refuses and changes nothing

### Requirement: The `runs` group is subcommands only
`baas runs` SHALL be a command group whose actions are subcommands. Invoked with no subcommand it SHALL
print its usage.

#### Scenario: Bare group prints usage
- **WHEN** `baas runs` is invoked
- **THEN** its usage, naming `list` and `terminate`, is printed

### Requirement: Run items never appear as measurements
Every reader of measurements — the project sweep, the every-project report, the project picker and the
lookup of a run's measurements by identifier — SHALL exclude run items. A run item reaching a
measurement reader SHALL fail loudly rather than be rendered as a row.

#### Scenario: A project with only failed runs is not offered
- **WHEN** a project's only items are run items for runs that stored nothing
- **THEN** the project picker does not offer it

#### Scenario: Every-project report ignores runs
- **WHEN** `baas results --all-projects` is invoked while run items exist
- **THEN** every row printed is a measurement

#### Scenario: Results by run identifier
- **WHEN** `baas results --request-id <runId>` is invoked for a run that stored measurements
- **THEN** the rows are that run's measurements, and the run item is not among them

### Requirement: Run status writes are limited to the run partition
The operator role and the runner role SHALL each be able to update items only in the `RUN` partition, and
the runner role SHALL be able to put measurement items only in `RESULT#` partitions. Neither role SHALL
gain delete access.

#### Scenario: The operator cannot write a measurement
- **WHEN** an identity holding the operator role attempts to update an item in a `RESULT#` partition
- **THEN** the request is denied

#### Scenario: The runner cannot forge a run outside its partition
- **WHEN** an instance holding the runner role attempts to put an item in the `RUN` partition
- **THEN** the request is denied
