# Spec Delta

## ADDED Requirements

### Requirement: A detached job is launched, then left to end itself
`baas run --detach` (and `baas jobs run --detach`) SHALL reserve the job, launch its instance and
record the launch exactly as an attached run does, and SHALL then exit without polling. From a
confirmed launch onward the CLI SHALL record nothing on the job item and terminate nothing: the job
SHALL reach its terminal status through the instance alone — `completed` or `failed:<n>` from the
runner, `timed-out` from the instance's watchdog — and an operator SHALL stop it with
`baas jobs terminate`. Until the launch is confirmed, an interrupt SHALL cancel and terminate the
job as it does for an attached run. A detached launch that fails, or that finds the job cancelled
while launching, SHALL exit non-zero. A detached job's item SHALL be indistinguishable from an
attached job's: no status or field records the launch mode.

#### Scenario: A detached job outlives the CLI
- **WHEN** `baas run --detach jmh -- MyBenchmark` returns and the benchmark later finishes
- **THEN** the CLI exited zero right after the launch, the job item reads `completed`, and the
  instance terminated itself

#### Scenario: The CLI's exit does not cancel a detached job
- **WHEN** `baas run --detach` exits after a confirmed launch
- **THEN** the job item is not `cancelled` and the instance is not terminated by the CLI

#### Scenario: Interrupt during a detached launch
- **WHEN** the operator presses Ctrl+C while a detached `baas run` is still launching
- **THEN** the job item reads `cancelled`, the instance is terminated or refuses to start the
  benchmark, and the command exits non-zero

#### Scenario: A detached job that hangs is ended by its watchdog
- **WHEN** a detached job's benchmark runs past its timeout and margin
- **THEN** the job item reads `timed-out` and the instance is terminated, with no CLI running

#### Scenario: An operator stops a detached job
- **WHEN** `baas jobs terminate <jobId> --yes` is run on a detached job that is still running
- **THEN** the job item reads `cancelled` and the instance is terminated

#### Scenario: A detached job is followed by its identifier
- **WHEN** `baas jobs show <jobId>` is run for a detached job moments after its launch
- **THEN** it reports the job as `launched` or `running`, never `vanished`, and later its terminal
  status

#### Scenario: A failed detached launch
- **WHEN** `RunInstances` fails for a detached job
- **THEN** the job item reads `launch-failed` and the command exits non-zero
