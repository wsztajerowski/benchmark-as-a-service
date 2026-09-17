## ADDED Requirements

### Requirement: A released CLI is installed onto the user's PATH
An installer SHALL place a released `baas` JAR and an executable launcher on the user's machine such
that `baas` is invocable by name from any working directory. The launcher SHALL resolve the Java
runtime through `PATH`, SHALL accept an explicit override for it, and SHALL NOT spawn a process
merely to validate the runtime on each invocation. The installer SHALL report when the launcher's
directory is absent from `PATH`, and SHALL NOT modify the user's shell configuration files.

#### Scenario: Installed CLI is invocable by name
- **WHEN** the installer completes and a new shell is started
- **THEN** `baas --version` runs from any directory and reports the installed release version

#### Scenario: A missing Java runtime is named, not stack-traced
- **WHEN** `baas` is invoked and no Java runtime is resolvable
- **THEN** the launcher exits non-zero with a message naming Java and the override variable

#### Scenario: PATH is reported, never edited
- **WHEN** the launcher's directory is not on `PATH`
- **THEN** the installer reports it and prints the line the user may add, and no shell configuration
  file is modified

### Requirement: The installer installs the version it was published with
The installer SHALL carry the version of the release that published it, and SHALL install that
version when no version is requested. It SHALL accept an explicitly requested version and install
that instead. Artifacts SHALL be fetched by explicit version; the installer SHALL NOT resolve a
floating "latest" reference when fetching them.

#### Scenario: The published installer installs its own release
- **WHEN** the installer from release X is run with no version requested
- **THEN** it installs the CLI artifact from release X

#### Scenario: An explicit version wins
- **WHEN** a specific version is requested
- **THEN** that version is installed regardless of the installer's own

#### Scenario: An unpublished installer refuses
- **WHEN** the installer is run from a source checkout, where it carries the unreleased placeholder,
  with no version requested
- **THEN** it exits non-zero naming the option that resolves it, and installs nothing

#### Scenario: A version request without a value is rejected informatively
- **WHEN** the version option is given with no value
- **THEN** the installer exits non-zero, states that a version is required, and reports the version
  it would otherwise install

### Requirement: Nothing is installed unverified
The installer SHALL retrieve a published SHA-256 checksum for the CLI artifact and SHALL verify the
downloaded bytes against it before writing anything to its destination. A mismatch SHALL be a hard
failure leaving the existing installation untouched. When no tool capable of computing SHA-256 is
available, the installer SHALL fail rather than proceed unverified.

#### Scenario: A tampered download is rejected
- **WHEN** the downloaded artifact does not match its published checksum
- **THEN** the installer exits non-zero, and any previously installed CLI remains as it was

#### Scenario: Verification precedes installation
- **WHEN** an artifact is fetched
- **THEN** it is verified before any file is written to the installation directory

#### Scenario: A missing checksum is a failure, not a skip
- **WHEN** the published checksum for the requested version cannot be retrieved
- **THEN** the installer exits non-zero rather than installing unverified

#### Scenario: A missing checksum tool is a failure, not a skip
- **WHEN** no SHA-256 utility is available on the machine
- **THEN** the installer exits non-zero naming the requirement, and installs nothing

### Requirement: Installation replaces files atomically
The installer SHALL write each downloaded file to a temporary location on the same filesystem as its
destination and SHALL move it into place, so that a replacement is atomic. It SHALL NOT write in
place over a file that may be open.

#### Scenario: An in-flight command is unaffected by a concurrent install
- **WHEN** the installer replaces the CLI artifact while a `baas` command is already executing
- **THEN** the executing command continues on the artifact it started with and completes normally

#### Scenario: A failed download leaves the previous installation intact
- **WHEN** a download or verification fails part-way
- **THEN** the previously installed CLI is still present and runnable

### Requirement: The installer reports prerequisites and fails only on the required runtime
The installer SHALL verify that a Java runtime of the version the CLI targets is present and SHALL
fail when it is not. It SHALL report, without failing, any optional tool whose absence limits some
commands but not others, and SHALL state what each is needed for.

#### Scenario: An absent or too-old Java runtime blocks installation
- **WHEN** no Java runtime of the required major version is present
- **THEN** the installer exits non-zero naming the required version, and installs nothing

#### Scenario: An optional tool is reported, not enforced
- **WHEN** a tool needed by only some commands is absent
- **THEN** the installer completes, and reports which commands are affected

### Requirement: The installer never reads or writes the CLI's configuration
Installation, update and uninstallation SHALL leave the CLI's configuration directory untouched. The
installer SHALL NOT create, modify or delete any file the CLI's own commands own.

#### Scenario: Configuration survives uninstallation
- **WHEN** a configuration file exists and the CLI is uninstalled
- **THEN** that file is still present and unmodified

#### Scenario: Installation does not seed configuration
- **WHEN** the CLI is installed on a machine with no configuration present
- **THEN** no configuration file is created

### Requirement: Updating resolves the current release and defers to that release's installer
The installer SHALL provide an update mode that determines the newest published release, compares it
against the installed version, and SHALL delegate the installation to the installer published with
the newer release rather than installing the newer artifact itself. When the installed version is
already the newest, it SHALL report so and download no artifact. When the installed version is newer
than the newest published release, it SHALL report both versions and change nothing.

#### Scenario: Already current does no work
- **WHEN** update is invoked and the installed version equals the newest published release
- **THEN** it reports that the installation is current and downloads no artifact

#### Scenario: A newer release is installed by its own installer
- **WHEN** update is invoked and a newer release exists
- **THEN** the installer published with that newer release performs the installation

#### Scenario: Update never downgrades
- **WHEN** the installed version is newer than the newest published release
- **THEN** update reports both versions and leaves the installation unchanged

#### Scenario: Version ordering is numeric, not lexical
- **WHEN** the installed version is `3.9.0` and the newest published release is `3.10.0`
- **THEN** the newer release is recognised as newer

#### Scenario: An unreadable installed version stops the update
- **WHEN** update is invoked and the installed version cannot be determined
- **THEN** it reports that and changes nothing, rather than assuming a version

### Requirement: The update path remains available after a piped installation
The installer SHALL store, alongside the installed CLI, the installer for the version it installed,
so that update is reachable without re-obtaining the installer. The stored copy SHALL be the
published installer for that version.

#### Scenario: Update is reachable after installing through a pipe
- **WHEN** the CLI is installed by piping the installer into a shell
- **THEN** an installer for the installed version is present alongside the CLI and can be run with
  the update option

### Requirement: Uninstallation removes exactly what was installed
The installer SHALL provide an uninstall mode that removes the launcher, the installed CLI artifact
and the stored installer, and nothing else.

#### Scenario: Uninstallation removes the command
- **WHEN** uninstall is invoked
- **THEN** the launcher is gone and `baas` is no longer invocable by name

#### Scenario: Uninstallation is scoped
- **WHEN** uninstall is invoked
- **THEN** no file outside the installation directories is removed

### Requirement: The installer runs on macOS and Linux
The installer SHALL work on both macOS and Linux without per-platform variants, relying only on
utilities present on both, and SHALL detect at run time which of the available SHA-256 utilities to
use.

#### Scenario: Installation succeeds on a stock macOS machine
- **WHEN** the installer runs on macOS, where the GNU checksum utility is absent
- **THEN** it verifies the download using the utility that is present and installs successfully

#### Scenario: Installation succeeds on a stock Linux machine
- **WHEN** the installer runs on Linux
- **THEN** it verifies the download and installs successfully
