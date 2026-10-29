# cli-distribution Specification

## Purpose

How a released `baas` reaches a user's machine and stays current: version resolution and
pinning, checksum verification, the on-disk layout, the launcher shim, prerequisite reporting,
update and uninstall.

## Requirements

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
Installation and uninstallation SHALL leave the CLI's configuration directory untouched. The
installer SHALL NOT create, modify or delete any file the CLI's own commands own.

#### Scenario: Configuration survives uninstallation
- **WHEN** a configuration file exists and the CLI is uninstalled
- **THEN** that file is still present and unmodified

#### Scenario: Installation does not seed configuration
- **WHEN** the CLI is installed on a machine with no configuration present
- **THEN** no configuration file is created

### Requirement: Uninstallation removes exactly what was installed
The installer SHALL provide an uninstall mode that removes the launcher, the installed CLI artifact
and the completion script, and nothing else — except a stored installer copy that an earlier release
placed beside the CLI artifact, which it SHALL also remove.

#### Scenario: Uninstallation removes the command
- **WHEN** uninstall is invoked
- **THEN** the launcher is gone and `baas` is no longer invocable by name

#### Scenario: Uninstallation removes the completion script
- **WHEN** uninstall is invoked on an installation that holds a completion script
- **THEN** the completion script is gone along with the launcher and the artifact

#### Scenario: Uninstallation is scoped
- **WHEN** uninstall is invoked
- **THEN** no file outside the installation directories is removed

#### Scenario: An earlier release's stored installer is removed too
- **WHEN** uninstall is invoked on an installation an earlier release made, which holds a stored
  installer copy beside the CLI artifact
- **THEN** that copy is removed along with the launcher and the artifact

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

### Requirement: The installer provides shell completion for the version it installs
On every installation the installer SHALL generate a bash- and zsh-compatible completion script
from the CLI artifact it has just installed, and SHALL place it beside that artifact by atomic
replacement. The installer SHALL print the lines that load the script in bash and in zsh, and SHALL
NOT modify any shell configuration file. If the script cannot be generated, the installer SHALL warn,
SHALL leave no completion script from an earlier installation in place, and SHALL complete the
installation. The CLI SHALL NOT need to be started to load the script.

#### Scenario: Subcommands complete after installation
- **WHEN** the CLI is installed, the printed line is used to load the completion script in bash or
  zsh, and `baas jobs ` is completed
- **THEN** the candidates offered are the `jobs` verbs of the installed version

#### Scenario: An update replaces the completion script
- **WHEN** a newer release is installed over an existing installation
- **THEN** the completion script describes the newer release's command tree

#### Scenario: Loading instructions are printed, never written
- **WHEN** the installer completes
- **THEN** it prints how to load the completion script in bash and in zsh, and no shell
  configuration file is modified

#### Scenario: A failed generation does not fail the installation
- **WHEN** the completion script cannot be generated from the installed artifact
- **THEN** the installer warns, completes the installation, and leaves no completion script in the
  installation directory
