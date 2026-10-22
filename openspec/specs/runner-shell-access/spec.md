# runner-shell-access Specification

## Purpose
Lets an operator open an interactive shell on a running job's instance — to inspect a hung or slow
benchmark while it still runs — through Session Manager, with no inbound port and no key pair.

## Requirements

### Requirement: `baas jobs connect` opens a shell on a job's live instance
`baas jobs connect <jobId>` SHALL resolve the job item by its identifier, find the job's pending or
running instance within the deployment — by the job-identifier tag the instance carries from launch, the
lookup `baas jobs show` uses, not by the instance identifier the item may record — and open an
interactive shell on that instance through Session Manager, using the deployment's own session
document. It SHALL run under operator credentials and SHALL return the
session's exit status when the shell ends. A job id with no job item SHALL fail naming the id, before
any session is started.

#### Scenario: Connecting to a running job
- **WHEN** `baas jobs connect <id>` names a job whose instance is running and whose agent is online
- **THEN** an interactive shell opens on that instance, as `ssm-user`, in `/app` when it exists

#### Scenario: An unknown id fails cleanly
- **WHEN** `baas jobs connect` names an id with no job item
- **THEN** the command exits non-zero naming the id, and no session is started

### Requirement: Connecting is decided by the instance, not by the stored status
`baas jobs connect` SHALL allow a session whenever the job's instance is `pending` or `running`,
whatever status the job item holds, and whether or not the item records the instance yet. When the job
has no pending or running instance, it SHALL refuse, naming the job's stored status. When the instance is live but its agent
has not yet registered, it SHALL refuse with a message saying the instance is not reachable yet and
to retry shortly, and SHALL NOT start a session.

#### Scenario: A job stuck before running can be reached
- **WHEN** a job's item still reads `launched` and its instance is running with an online agent
- **THEN** `baas jobs connect` opens a shell on it

#### Scenario: A finished job is refused
- **WHEN** a job's instance is `terminated`
- **THEN** `baas jobs connect` exits non-zero naming the stored status, and starts no session

#### Scenario: A job still reserving has nothing to connect to
- **WHEN** a job's item reads `launching`, records no instance, and no instance carries its tag
- **THEN** `baas jobs connect` exits non-zero naming the status `launching`

#### Scenario: A job whose instance the item never recorded can be reached
- **WHEN** the CLI's `launched` write failed and the instance has not yet recorded `running`, but it is
  running with an online agent
- **THEN** `baas jobs connect` finds it by its tag and opens a shell on it

#### Scenario: An instance still booting
- **WHEN** a job's instance is running but its agent is not yet registered
- **THEN** `baas jobs connect` exits non-zero saying the instance is not reachable yet, and starts no
  session

### Requirement: Any job of the deployment can be connected to, whoever launched it
`baas jobs connect` SHALL treat every job of the addressed deployment alike, regardless of whether it
was launched from an operator's machine, by CI, attached or detached. It SHALL need nothing but the
job identifier and the deployment's operator role.

#### Scenario: A CI job is reached from a laptop
- **WHEN** a job launched by a CI workflow is still running and an operator runs
  `baas jobs connect <id>` on their own machine for the same deployment
- **THEN** a shell opens on that job's instance

### Requirement: A missing session plugin is refused before any session starts
`baas jobs connect` SHALL require `session-manager-plugin` on the operator's `PATH`. When it is absent,
the command SHALL exit non-zero before starting a session, naming the plugin and the command that
installs it on the operator's platform. No other `baas` command SHALL require the plugin.

#### Scenario: Plugin absent
- **WHEN** `baas jobs connect <id>` is run on a machine without `session-manager-plugin`
- **THEN** the command exits non-zero naming the plugin and its install command, and no session is
  started on AWS

#### Scenario: Other commands do not need it
- **WHEN** `baas run`, `baas jobs list` or `baas results query` is run on a machine without the plugin
- **THEN** each behaves as before

### Requirement: The AWS CLI is not required
`baas jobs connect` SHALL start the session itself under the operator role and SHALL NOT require the
AWS CLI to be installed. The operator role's credentials SHALL NOT be passed to the plugin or to any
child process.

#### Scenario: No AWS CLI installed
- **WHEN** `baas jobs connect <id>` is run on a machine with `session-manager-plugin` but no `aws`
- **THEN** the shell opens

### Requirement: Connecting states when the instance will end, and never extends it
Before the shell opens, `baas jobs connect` SHALL state that the instance terminates itself when the
job finishes, and when the job records its watchdog bound, the time by which the watchdog will
terminate it. A session SHALL NOT delay, extend or prevent any of the job's termination paths; when the
instance terminates, the session ends.

#### Scenario: The deadline is shown
- **WHEN** `baas jobs connect <id>` connects to a job that records its watchdog bound
- **THEN** the time the watchdog will terminate the instance is printed before the shell opens

#### Scenario: A job from before the bound was recorded
- **WHEN** the job item records no watchdog bound
- **THEN** a warning that the instance terminates when the job finishes or its watchdog fires is
  printed, without a time, and the shell opens

#### Scenario: The watchdog fires during a session
- **WHEN** the watchdog's deadline passes while a session is open
- **THEN** the instance is terminated and the session ends

### Requirement: Sessions leave no record in the job's results
A session SHALL NOT be recorded in the job's measurements, tags, job item or S3 prefix: measurements
of a job someone connected to SHALL be stored exactly as any other job's. No transcript of a session
SHALL be stored.

#### Scenario: A connected job's results are unmarked
- **WHEN** an operator connects to a job while it runs and the job then completes
- **THEN** its measurement items and job item carry no attribute or tag that differs from those of a
  job nobody connected to, and its S3 prefix holds no session transcript
