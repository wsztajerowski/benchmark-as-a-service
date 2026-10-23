## MODIFIED Requirements

### Requirement: The installer never reads or writes the CLI's configuration
Installation and uninstallation SHALL leave the CLI's configuration directory untouched. The
installer SHALL NOT create, modify or delete any file the CLI's own commands own.

#### Scenario: Configuration survives uninstallation
- **WHEN** a configuration file exists and the CLI is uninstalled
- **THEN** that file is still present and unmodified

#### Scenario: Installation does not seed configuration
- **WHEN** the CLI is installed on a machine with no configuration present
- **THEN** no configuration file is created

### Requirement: Uninstallation removes exactly what was installed
The installer SHALL provide an uninstall mode that removes the launcher and the installed CLI
artifact, and nothing else — except a stored installer copy that an earlier release placed beside
the CLI artifact, which it SHALL also remove.

#### Scenario: Uninstallation removes the command
- **WHEN** uninstall is invoked
- **THEN** the launcher is gone and `baas` is no longer invocable by name

#### Scenario: Uninstallation is scoped
- **WHEN** uninstall is invoked
- **THEN** no file outside the installation directories is removed

#### Scenario: An earlier release's stored installer is removed too
- **WHEN** uninstall is invoked on an installation an earlier release made, which holds a stored
  installer copy beside the CLI artifact
- **THEN** that copy is removed along with the launcher and the artifact

## ADDED Requirements

### Requirement: Updating is re-running the published installer
The installer SHALL NOT offer an update mode and SHALL NOT store a copy of itself beside the
installed CLI. Updating an installation SHALL be running the newest release's published installer,
which installs that release over the existing installation by the same atomic replacement as a
first installation.

#### Scenario: The newest installer updates an existing installation
- **WHEN** an older release is installed and the newest release's published installer is run
- **THEN** the newest release is installed in its place, and the configuration directory is untouched

#### Scenario: The update option is refused
- **WHEN** the installer is run with `--update`
- **THEN** it exits non-zero as for any unknown option, and installs nothing

#### Scenario: Nothing is stored beside the CLI
- **WHEN** the CLI is installed
- **THEN** the installation directory holds the CLI artifact and no installer copy

## REMOVED Requirements

### Requirement: Updating resolves the current release and defers to that release's installer
**Reason**: The CLI's version check now prints the one command that updates any installation —
the newest release's published installer — so a second path that needs a stored copy, a release
lookup and its own version comparison buys only a skipped download and a downgrade guard against
a case releases never produce.
**Migration**: Run `curl -fsSL https://github.com/<repo>/releases/latest/download/install.sh | sh`.
A stored installer from an earlier release still works: its `--update` hands off to the newest
release's installer, which installs in plain mode.

### Requirement: The update path remains available after a piped installation
**Reason**: The stored copy existed only to make the update mode reachable after a piped install;
with the update mode gone it has no purpose.
**Migration**: None needed. Uninstallation removes a copy an earlier release stored.
