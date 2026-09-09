## MODIFIED Requirements

### Requirement: The project is derived, with an override
`baas run` SHALL derive the `project` value from the current git repository's name and SHALL accept
`--project <name>` to override it. Derivation SHALL resolve the main repository, so that a run launched
from a linked worktree is attributed to the repository rather than to the worktree directory. When
neither is available the command SHALL fail before provisioning.

#### Scenario: Derived project is used
- **WHEN** `baas run` is invoked inside a git repository with no `--project`
- **THEN** the repository name is forwarded as the `project` tag

#### Scenario: A linked worktree resolves to its repository
- **WHEN** `baas run` is invoked from a linked worktree with no `--project`
- **THEN** the repository's name is forwarded, not the worktree directory's name

#### Scenario: Unresolvable project fails before provisioning
- **WHEN** `baas run` is invoked outside a git repository with no `--project`
- **THEN** the command exits non-zero and no EC2 instance is launched

### Requirement: A command downloads a run's S3 artifacts
The CLI SHALL provide a command taking either a run identifier or a literal S3 result path, plus a
destination directory, downloading every S3 object under that run's prefix. A run identifier SHALL be
resolved to the run's stored result path rather than reconstructed from its other attributes.

#### Scenario: Artifacts land locally
- **WHEN** the command is invoked with a run identifier and a destination
- **THEN** the destination contains the run's result JSON, `environment.json`, process output and logs

#### Scenario: A literal result path is accepted
- **WHEN** the command is invoked with a result path rather than an identifier
- **THEN** that prefix is downloaded

#### Scenario: Destination is reported
- **WHEN** the download completes
- **THEN** the command prints the destination path and exits 0
