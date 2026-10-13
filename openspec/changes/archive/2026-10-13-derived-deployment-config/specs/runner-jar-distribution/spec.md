# Spec Delta

## ADDED Requirements

### Requirement: The runner source repository is a property of the release build
The repository the CLI fetches release assets from SHALL be fixed when the CLI is built, defaulting to
the upstream repository and set by the release to the repository that published it, so that a fork's
release fetches its own runner without editing code. It SHALL NOT be read from any configuration file
or option. A CLI without a released version never fetches a release asset, and so never uses it.

#### Scenario: A fork's release fetches its own runner
- **WHEN** a CLI released from repository `someone/baas-fork` seeds a missing runner slot
- **THEN** it fetches the release asset from `someone/baas-fork`

#### Scenario: A retrieval failure names what it tried
- **WHEN** the release asset cannot be retrieved
- **THEN** the error names the repository and version it attempted, and names no configuration
  setting to change

## REMOVED Requirements

### Requirement: The runner source repository is configuration, not a hardcoded literal
**Reason**: The repository is where this CLI binary was released from, not a choice per deployment or
per user. As configuration, a fork's CLI reading an old file could seed the upstream runner, pairing
one build's CLI with another build's runner — the drift the version pin exists to prevent.
**Migration**: A fork sets the repository at build time; its release workflow passes its own
repository. Existing configuration files are discarded with the deployments they belong to.
