## ADDED Requirements

### Requirement: A CLI older than the deployment's recorded version is refused
Every command that makes an AWS call, under `baas admin` or not, SHALL first read the deployment's
recorded CLI version (the core stack's `BaasCliVersion`) and compare it numerically, field by field,
with its own. When its own version is lower — patch level included — the command SHALL make no
further AWS call, write to standard error the recorded version, its own version and the command
`curl -fsSL https://github.com/<repository>/releases/latest/download/install.sh | sh`, where
`<repository>` is the one the CLI was released from, and exit 1. When the stack does not exist or
records no version, the command SHALL proceed. `baas jobs terminate` SHALL NOT perform this check.
Commands that make no AWS call — `config list`, `config show`, `config set`, `--help`, `--version`
— SHALL NOT perform it. `baas admin deployment setup` performs it as its own step (see
*guided-setup*).

#### Scenario: An older CLI is refused before it acts
- **WHEN** the deployment records `8.1.0` and `baas results query` from `8.0.3` is run
- **THEN** it writes the two versions and the installer command to standard error, queries nothing,
  and exits 1

#### Scenario: A patch-older CLI is refused
- **WHEN** the deployment records `8.1.1` and a command from `8.1.0` is run
- **THEN** it is refused as above

#### Scenario: Versions compare numerically
- **WHEN** the deployment records `8.9.0` and a command from `8.10.0` is run
- **THEN** it is not refused

#### Scenario: Terminating is never refused
- **WHEN** the deployment records `8.1.0` and `baas jobs terminate <jobId>` from `8.0.3` is run
- **THEN** it terminates the job as it would for a current CLI

#### Scenario: No recorded version means no refusal
- **WHEN** the deployment's stack predates the recorded version, or records none
- **THEN** every command proceeds without a version message

#### Scenario: An admin command is refused too
- **WHEN** the deployment records `8.1.0` and `baas admin deployment teardown` from `8.0.3` is run
- **THEN** it is refused before deleting or exporting anything

### Requirement: A CLI newer than the recorded version says what to run
When a checked command's own version is higher than the recorded one, it SHALL proceed and SHALL
write one line to standard error naming both versions. Under `baas admin` the line SHALL name the
command that records the newer version (`baas --deployment <name> admin deployment setup`);
elsewhere it SHALL tell the user to ask the deployment's admin to run that setup with the newer
release. The line SHALL NOT appear on standard output.

#### Scenario: An operator ahead of the deployment is told whom to ask
- **WHEN** the deployment records `8.1.0` and `baas jobs list --format json` from `8.2.0` is run
- **THEN** the list is printed on standard output unchanged, and standard error carries one line
  naming `8.1.0`, `8.2.0` and asking the deployment's admin to run setup

#### Scenario: An admin ahead of the deployment is told to run setup
- **WHEN** the deployment records `8.1.0` and `baas admin image show` from `8.2.0` is run
- **THEN** standard error carries one line naming both versions and
  `baas --deployment <name> admin deployment setup`

### Requirement: An unreleased build is not checked, and says so
A CLI whose version is the unreleased placeholder `0.0.0-semantically-released`, or is not a numeric
`x.y.z`, SHALL NOT compare versions and SHALL NOT read the recorded version for that purpose. Each
command that would otherwise check SHALL write one line to standard error saying the build is
unreleased and was not checked against the deployment, and SHALL proceed.

#### Scenario: CI's reactor build runs unchecked
- **WHEN** a reactor-built CLI runs `baas run` against a deployment recording `8.1.0`
- **THEN** the job launches as it would from a current release, and standard error carries the
  unreleased-build line
