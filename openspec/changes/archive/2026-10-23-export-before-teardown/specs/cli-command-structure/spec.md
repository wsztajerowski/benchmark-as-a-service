# Spec Delta

## MODIFIED Requirements

### Requirement: Deployer-privileged commands are grouped under `admin`
The `baas` command tree SHALL group every command that needs deployer credentials under a nested `admin`
subcommand, as two nouns: `admin deployment` with the verbs `setup`, `teardown`, `export` and
`import`, and `admin image` with the verbs `build` and `show`. None of these SHALL be reachable as top-level commands, and
`admin deployer-policy`, `admin setup`, `admin teardown`, `admin build-image` and `admin image` without
a verb SHALL NOT exist as commands.

#### Scenario: Admin commands are nested
- **WHEN** a user runs `baas admin deployment setup --help`
- **THEN** picocli shows the setup command's options
- **WHEN** a user runs `baas setup` (without the `admin` prefix) or `baas admin setup`
- **THEN** picocli reports an unknown command error

#### Scenario: The image is a noun with two verbs
- **WHEN** a user runs `baas admin image`
- **THEN** its usage, naming `build` and `show`, is printed and no AWS call is made

#### Scenario: The deployment noun lists its four verbs
- **WHEN** a user runs `baas admin deployment`
- **THEN** its usage, naming `setup`, `teardown`, `export` and `import`, is printed and no AWS call is made

#### Scenario: Export and import have no other form
- **WHEN** a user runs `baas export`, `baas import` or `baas jobs export`
- **THEN** picocli reports an unknown command error

