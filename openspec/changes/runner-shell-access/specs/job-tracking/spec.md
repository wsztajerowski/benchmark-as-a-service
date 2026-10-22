## ADDED Requirements

### Requirement: The reservation records the job's watchdog bound
The `launching` reservation SHALL record the job's watchdog bound — the benchmark timeout plus the
watchdog margin, in seconds, the same value the user-data watchdog sleeps for and `baas run` polls
for. It SHALL be written by the CLI only, in the reservation, and no later write SHALL change it. The
instance SHALL NOT write it.

#### Scenario: A new job records its bound
- **WHEN** `baas run --timeout 600 --watchdog-margin 300 ...` reserves a job
- **THEN** the job item records a watchdog bound of 900 seconds

#### Scenario: The instance's writes leave it untouched
- **WHEN** the instance records `running` and then `completed`
- **THEN** the job item's watchdog bound is the value the reservation wrote

## MODIFIED Requirements

### Requirement: The `jobs` group is subcommands only
`baas jobs` SHALL be a command group whose actions are the subcommands `run`, `list`, `show`, `diff`,
`download`, `terminate` and `connect`. Invoked with no subcommand it SHALL print its usage.

#### Scenario: Bare group prints usage
- **WHEN** `baas jobs` is invoked
- **THEN** its usage, naming `run`, `list`, `show`, `diff`, `download`, `terminate` and `connect`, is
  printed

### Requirement: `baas jobs show` reports one job's execution
`baas jobs show <job>` SHALL take a job identifier and print three sections: **Job** — the job item's
identifier, project, type, stored status and the resolved status (`vanished` computed as `jobs list`
does), creation time, result path, instance id and type, tags, the error code when there is one, and,
while the instance is `pending` or `running` and the job records its watchdog bound, the time by which
the watchdog will terminate it; **Environment** — the job's `environment.json` by group; and
**Artifacts** — one listing of the job's S3 prefix, summarised by folder. It SHALL show no
measurements. A part that does not exist SHALL be stated rather than fail the command — no environment
when the instance never booted — while a job id with no job item SHALL fail naming the id, before any
S3 read. `--format json` SHALL print one object with `job`, `environment` and `artifacts` members, the
environment as stored and the `job` member carrying the watchdog bound when recorded. It SHALL run
under operator credentials.

#### Scenario: A completed job is shown in full
- **WHEN** `baas jobs show <id>` names a completed job
- **THEN** the Job, Environment and Artifacts sections are printed, and no measurement row appears

#### Scenario: A job that never booted has no environment
- **WHEN** `baas jobs show <id>` names a `launch-failed` job
- **THEN** the Job section shows the error code, the Environment section states there is none, and the
  command exits zero

#### Scenario: A vanished job is resolved
- **WHEN** the named job's stored status is `running` and its instance no longer exists
- **THEN** the Job section shows the stored status and the resolved status `vanished`

#### Scenario: An unknown id fails cleanly
- **WHEN** `baas jobs show` names an id with no job item
- **THEN** the command exits non-zero naming the id, and reads nothing from S3

#### Scenario: Machine-readable output
- **WHEN** `baas jobs show <id> --format json` is run
- **THEN** standard output is one JSON object with `job`, `environment` and `artifacts` members

#### Scenario: A running job shows its termination deadline
- **WHEN** `baas jobs show <id>` names a job whose instance is running and which records its watchdog
  bound
- **THEN** the Job section shows the time by which the watchdog will terminate the instance

#### Scenario: A finished job shows no deadline
- **WHEN** `baas jobs show <id>` names a job whose instance has terminated
- **THEN** the Job section shows no termination deadline
