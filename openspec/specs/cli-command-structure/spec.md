# cli-command-structure Specification

## Purpose

The shape of the `baas` picocli command tree: which commands exist, how they nest, and which
privilege tier each grouping implies.

## Requirements

### Requirement: Deployer-privileged commands are grouped under `admin`
The `baas` command tree SHALL group `setup` and `teardown` under a nested `admin` subcommand (`baas admin setup`, `baas admin teardown`). These SHALL NOT be reachable as top-level commands (e.g. `baas setup` directly SHALL NOT exist).

#### Scenario: Admin commands are nested
- **WHEN** a user runs `baas admin setup --help`
- **THEN** picocli shows the setup command's options
- **WHEN** a user runs `baas setup` (without the `admin` prefix)
- **THEN** picocli reports an unknown command error

### Requirement: Daily-use commands remain top-level
`run`, `results`, and `config` (with its `set`/`show` subcommands) SHALL remain directly reachable from the `baas` root command, unaffected by the `admin` grouping.

#### Scenario: Top-level commands unchanged
- **WHEN** a user runs `baas run jmh -- MyBenchmark -f 1`, `baas results`, or `baas config show`
- **THEN** each resolves to the same command implementation as before this change, with no `admin` prefix required
