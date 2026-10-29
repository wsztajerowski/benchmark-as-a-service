# Spec Delta

## ADDED Requirements

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

## MODIFIED Requirements

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
