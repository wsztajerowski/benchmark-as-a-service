# Spec Delta

## MODIFIED Requirements

### Requirement: Poll loop detects a dead instance
`baas run` SHALL check the runner instance's state while polling and SHALL abort with a non-zero exit as soon as the instance reaches `terminated` or `shutting-down` without a `run-status` sentinel, rather than waiting for the poll cap of `timeout + watchdog margin`.

#### Scenario: Boot failure fails fast
- **WHEN** the runner instance terminates before writing `run-status`
- **THEN** `baas run` reports the instance state and exits non-zero well before `timeout + watchdog margin` elapses
