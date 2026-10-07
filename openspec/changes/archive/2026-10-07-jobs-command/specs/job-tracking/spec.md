# Spec Delta

## MODIFIED Requirements

### Requirement: Job items never appear as measurements
Every reader of measurements — the project sweep, the every-project report, the project picker and the
lookup of a job's measurements by identifier — SHALL exclude job items. A job item reaching a
measurement reader SHALL fail loudly rather than be rendered as a row.

#### Scenario: A project with only failed jobs is not offered
- **WHEN** a project's only items are job items for jobs that stored nothing
- **THEN** the project picker does not offer it

#### Scenario: Every-project report ignores job items
- **WHEN** `baas results query --all-projects` is invoked while job items exist
- **THEN** every row printed is a measurement

#### Scenario: Results by job identifier
- **WHEN** `baas results query --job-id <jobId>` is invoked for a job that stored measurements
- **THEN** the rows are that job's measurements, and the job item is not among them
### Requirement: `baas jobs list` shows recent jobs
`baas jobs list` SHALL show jobs of every project and status through the shared ordering and paging
pipeline: newest first and 20 by default, `--limit <n>` (0 for no limit), `--offset <m>`,
`--sort-by created|status|project` and `--asc`. `--in-flight` SHALL restrict the output to jobs whose
resolved status is not terminal and not vanished. `--project <name>`, repeatable `--tag <key>=<value>`
and repeatable `--exclude-tag <key>=<value>` SHALL filter the rows. Without `--project` every project
is listed. The table SHALL show job identifier, project, status, source, instance, instance type, start
time and, for a job in flight, the time elapsed since it started, plus the error code for a failed
launch. A finished job shows no duration: the instance records no timestamp, so none is known.
`--format json` and `--format csv` SHALL carry the same rows, including tags. Jobs tagged
`exclude_from_results=true` SHALL be shown like any other. Statuses SHALL be resolved with a single
instance lookup per invocation, whose size does not grow with the number of unresolved jobs. With the
default ordering, the listing SHALL keep reading older jobs until it has the requested number of
matching rows or the jobs are exhausted; any other ordering reads every job first. `--watch` SHALL
redraw the listing in place every 30 seconds on an interactive terminal, SHALL be refused without one,
and SHALL be refused with any `--format` other than `table`.

#### Scenario: Default listing
- **WHEN** `baas jobs list` is invoked and 30 jobs exist
- **THEN** the 20 newest are shown, of every status and project, and standard error reports 20 of 30

#### Scenario: A filter that matches older jobs
- **WHEN** `baas jobs list --project a` is invoked, and the 20 newest jobs all belong to project `b`
  while older jobs belong to `a`
- **THEN** up to 20 jobs of project `a` are shown

#### Scenario: Many vanished jobs
- **WHEN** more than 200 jobs have a non-terminal status and no running instance
- **THEN** `baas jobs list --in-flight` still resolves every status, and shows only jobs whose
  instance is pending or running

#### Scenario: In-flight only
- **WHEN** `baas jobs list --in-flight` is invoked while one job is running and one has vanished
- **THEN** only the running one is shown

#### Scenario: CI jobs are not hidden
- **WHEN** a CI job tagged `exclude_from_results=true` is in flight
- **THEN** `baas jobs list` shows it, with its source

#### Scenario: Nothing to show
- **WHEN** `baas jobs list --in-flight` is invoked and no job is in flight
- **THEN** standard output says so, and the command exits zero

#### Scenario: Watching jobs in flight
- **WHEN** `baas jobs list --in-flight --watch` runs on an interactive terminal
- **THEN** the table is redrawn in place every 30 seconds until interrupted

#### Scenario: Watching without a terminal is refused
- **WHEN** `baas jobs list --watch` runs with standard output redirected
- **THEN** the command exits non-zero and lists nothing
### Requirement: The `jobs` group is subcommands only
`baas jobs` SHALL be a command group whose actions are the subcommands `run`, `list`, `show`, `diff`,
`download` and `terminate`. Invoked with no subcommand it SHALL print its usage.

#### Scenario: Bare group prints usage
- **WHEN** `baas jobs` is invoked
- **THEN** its usage, naming `run`, `list`, `show`, `diff`, `download` and `terminate`, is printed

## ADDED Requirements

### Requirement: `baas jobs run` launches a job, and `baas run` is its alias
`baas jobs run <type> [options] -- <benchmark args>` SHALL be the canonical command that provisions a
runner and executes a benchmark job, with every option and behaviour `baas run` has. `baas run` SHALL
remain as its alias, identical in arguments, output, exit code and summary.

#### Scenario: Both spellings launch the same job
- **WHEN** `baas jobs run jmh --benchmark-jar b.jar --project p -- MyBenchmark -f 1` and the same line
  with `baas run` are compared
- **THEN** both parse to the same options and launch the same kind of job

### Requirement: `baas jobs show` reports one job's execution
`baas jobs show <job>` SHALL take a job identifier and print three sections: **Job** — the job item's
identifier, project, type, stored status and the resolved status (`vanished` computed as `jobs list`
does), creation time, result path, instance id and type, tags, and the error code when there is one;
**Environment** — the job's `environment.json` by group; and **Artifacts** — one listing of the job's
S3 prefix, summarised by folder. It SHALL show no measurements. A part that does not exist SHALL be
stated rather than fail the command — no environment when the instance never booted — while a job id
with no job item SHALL fail naming the id, before any S3 read. `--format json` SHALL print one object
with `job`, `environment` and `artifacts` members, the environment as stored. It SHALL run under
operator credentials.

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

### Requirement: `baas jobs download` fetches one job's artifacts
`baas jobs download <job> [-o <dir>]` SHALL take a job identifier, resolve it to the job's stored result
path through the job-ID index — reading the job item, so a job that stored no measurement is
retrievable — and download every S3 object under that prefix, inputs included, to a local directory.
It SHALL NOT accept a literal result path. An unknown job SHALL fail naming it and create no
directory.

#### Scenario: Whole job is retrieved
- **WHEN** `baas jobs download <id>` names a completed job
- **THEN** the destination contains the job's result JSON, `environment.json`, process output, logs and
  any profiling artifacts, and the destination path is reported

#### Scenario: A failed job is retrievable by identifier
- **WHEN** it names a job that failed before storing any measurement
- **THEN** the boot log and environment manifest are downloaded

#### Scenario: Data absent from the item is recoverable
- **WHEN** a measurement's `rawData` is needed
- **THEN** it is available in the downloaded result JSON

#### Scenario: A path is not an identifier
- **WHEN** `baas jobs download jobs/p/20261006T165241024Z-47516b65` is invoked
- **THEN** the command exits non-zero stating that a job id is required, and downloads nothing

#### Scenario: Unknown job reports clearly
- **WHEN** it names a job with no job item
- **THEN** the command exits non-zero naming the job, and creates no directory
