## ADDED Requirements

### Requirement: Setup refuses a CLI older than the version recorded on the stack
When the names step finds the deployment's stack present, setup SHALL read the stack's recorded CLI
version and, when the running CLI is a released build whose version is numerically lower — patch
level included — SHALL stop with exit 1 before any stack operation, interactive or not, writing the
recorded version, its own version and the command that installs the newest release. It SHALL NOT
offer to wait: the running process is the CLI that has to be replaced. A stack that is absent, rolled
back, or records no version SHALL NOT stop setup. An unreleased build SHALL proceed with the
unreleased-build line on standard error.

#### Scenario: An older CLI cannot revert the stack
- **WHEN** the stack records `8.2.0` and setup from `8.1.0` is run
- **THEN** setup exits 1 naming both versions and the installer command, and the stack is not updated

#### Scenario: A new deployment is not checked
- **WHEN** setup creates a deployment that has no stack yet
- **THEN** no version comparison is made

### Requirement: Setup records its version and announces a raise
The stack step SHALL submit the running CLI's version as the recorded version when it is a released
`x.y.z` build, on create and on update. An unreleased build SHALL submit no value, so a create
records none and an update carries the deployed value forward. After an update that changed the
recorded version, setup SHALL write one line to standard error saying CLIs older than the new
version now refuse the deployment, naming the version it replaced, or saying none was recorded. It
SHALL ask nothing. A create, and an update that left the version unchanged, SHALL NOT print it.

#### Scenario: A raise is announced
- **WHEN** the stack records `8.1.0` and setup from `8.2.0` updates it
- **THEN** after the update setup states that CLIs older than `8.2.0` now refuse the deployment, and
  that it was `8.1.0`

#### Scenario: A re-run with the same CLI announces nothing
- **WHEN** the stack records `8.2.0` and setup from `8.2.0` runs again
- **THEN** no version line is printed

#### Scenario: A reactor build leaves the recorded version as it was
- **WHEN** the stack records `8.2.0` and setup from a reactor build updates it
- **THEN** the stack still records `8.2.0`, and no raise is announced
