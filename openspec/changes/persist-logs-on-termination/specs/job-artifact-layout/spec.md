# Spec Delta

## ADDED Requirements

### Requirement: A stopped job's logs reach its prefix
Whenever a job's instance ends — on its own, by its watchdog, by its benchmark timeout, or by a
termination requested from outside (an interrupted `baas run`, `baas jobs terminate`, the CLI's poll
cap, or the EC2 console) — the instance SHALL upload, before it is gone, the logs it had produced
so far to the job's prefix: the boot log as `cloud-init-output.log`, the benchmark process output as
`<type>-output.txt`, and every `*.log` below the job's working directory under `logs/`, keyed by its
relative path. A termination that bypasses the operating system's shutdown is the only exception.

#### Scenario: A cancelled job keeps its logs
- **WHEN** a running job is cancelled with Ctrl+C or `baas jobs terminate`
- **THEN** its prefix contains `cloud-init-output.log` and the process output written up to the
  cancellation, and the job item records `cancelled`

#### Scenario: A benchmark that hit its timeout keeps its process output
- **WHEN** the benchmark process is killed by its timeout
- **THEN** its prefix contains the process output written up to that point, and the job item records
  the failure

#### Scenario: A watchdog kill keeps the logs
- **WHEN** the watchdog terminates the instance
- **THEN** its prefix contains `cloud-init-output.log`, the process output and the `*.log` files
  produced so far

#### Scenario: A completed job's keys are unchanged
- **WHEN** a job completes normally
- **THEN** its logs are at the same keys the runner itself writes, so a re-upload overwrites rather
  than duplicates them
