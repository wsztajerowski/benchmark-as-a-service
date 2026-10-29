# Spec Delta

## MODIFIED Requirements

### Requirement: `baas jobs show` reports one job's execution
`baas jobs show <job>` SHALL take a job identifier and print three sections: **Job** — the job item's
identifier, project, type, stored status and the resolved status (`vanished` computed as `jobs list`
does), creation time, result path, instance id and type, tags, the error code when there is one, and,
while the instance is `pending` or `running` and the job records its watchdog bound, the time by which
the watchdog will terminate it; **Environment** — the job's `environment.json` by group; and
**Artifacts** — one listing of the job's S3 prefix. It SHALL show no measurements. A part that does not
exist SHALL be stated rather than fail the command — no environment when the instance never booted, and
an unreadable environment when the manifest is not one JSON object — while a job id with no job item
SHALL fail naming the id, before any S3 read. It SHALL run under operator credentials.

In the human-readable view:
- The tags SHALL be printed one per line, each `key=value`, except `project` and `type`, which the Job
  section already shows. `hidden=true` SHALL be shown as the marker `hidden` beside the status instead of
  as a tag.
- Each environment field SHALL be shown in a form made for reading when the field is one the CLI knows —
  memory in GiB, the JVM as its vendor version and vendor, tool versions as their version numbers,
  `transparentHugepages` as its selected mode — and as recorded otherwise. A field with an empty value
  SHALL be omitted, and `machine.instanceType` SHALL NOT be repeated in the Environment section.
- The artifacts SHALL be printed one per line with their sizes, grouped as process output and logs,
  environment, results, reports and inputs. A folder SHALL be printed as one line with its number of
  files and their total size.
- Every value written by a runner — tags, environment fields, the error code — SHALL be printed with
  its control characters removed.

`--format json` SHALL print one object with `job`, `environment` and `artifacts` members: the
`environment` member is the manifest's fields as stored, re-serialised from the parsed manifest and
never the stored text, or `null` when there is no readable manifest; the `job` member carries the
watchdog bound when recorded.

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

#### Scenario: Tags already shown are not repeated
- **WHEN** a job tagged `project`, `type`, `branch`, `commit` and `hidden=true` is shown
- **THEN** `branch` and `commit` are each printed on a line of their own, `project` and `type` appear only
  as the Project and Type fields, and the status line carries the marker `hidden`

#### Scenario: Environment values are shown for reading
- **WHEN** a job whose manifest records `memory.totalMib` 15622, `memory.swapTotalMib` 0,
  `jvm.vendorVersion` `Corretto-25.0.4.7.1`, `tools.asyncProfiler` `Async-profiler 4.0 built on Apr  8 2025`,
  `tunables.transparentHugepages` `[always] madvise never` and an empty `cpu.maxMhz` is shown
- **THEN** memory is shown as `15.3 GiB` with no swap, the JVM as Corretto 25.0.4.7.1 from its vendor,
  async-profiler as `4.0`, transparent huge pages as `always`, and `maxMhz` does not appear

#### Scenario: An unknown field is shown as recorded
- **WHEN** a manifest carries a field the CLI has no form for
- **THEN** it is shown under its group with its value as recorded

#### Scenario: Artifacts are listed one per line with sizes
- **WHEN** a JCStress job's prefix holds its boot log, process output, manifest, package list, two report
  pages and two inputs
- **THEN** each file is printed on its own line with its size under its group, and `input/` is one line
  with 2 files and their total size

#### Scenario: Control characters written by a runner are not printed
- **WHEN** a tag or environment value contains an escape sequence
- **THEN** the human-readable view prints the value without its control characters

#### Scenario: A manifest with trailing content does not reach the JSON output
- **WHEN** a job's `environment.json` holds a JSON object followed by further text
- **THEN** `--format json` prints `environment` as `null`, the Environment section states that the
  manifest is unreadable, and the command exits zero

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
`hidden=true` SHALL be shown like any other: the tag hides measurements, not jobs. Statuses SHALL be resolved with a single
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
- **WHEN** a CI job tagged `hidden=true` is in flight
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
