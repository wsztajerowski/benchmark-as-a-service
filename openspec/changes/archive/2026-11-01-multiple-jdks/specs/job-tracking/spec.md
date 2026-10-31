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
  memory in GiB, the JDK as its vendor version and vendor, with the `--jdk` id it was selected by, tool versions as their version numbers,
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
  `jdk.vendorVersion` `Corretto-25.0.4.7.1`, `jdk.id` `corretto-25`, `tools.asyncProfiler` `Async-profiler 4.0 built on Apr  8 2025`,
  `tunables.transparentHugepages` `[always] madvise never` and an empty `cpu.maxMhz` is shown
- **THEN** memory is shown as `15.3 GiB` with no swap, the JDK as Corretto 25.0.4.7.1 from its vendor and
  `corretto-25`,
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
