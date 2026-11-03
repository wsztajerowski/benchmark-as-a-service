# Spec Delta

## MODIFIED Requirements

### Requirement: Deployer-privileged commands are grouped under `admin`
The `baas` command tree SHALL group every command that needs deployer credentials under a nested `admin`
subcommand, as two nouns: `admin deployment` with the verbs `setup`, `teardown`, `show`, `export` and
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
- **THEN** its usage, naming `setup`, `teardown`, `show`, `export` and `import`, is printed and no AWS
  call is made

#### Scenario: Export and import have no other form
- **WHEN** a user runs `baas export`, `baas import` or `baas jobs export`
- **THEN** picocli reports an unknown command error

## ADDED Requirements

### Requirement: `baas admin deployment show` reports the deployment's server-side state
`baas admin deployment show` SHALL read, under deployer credentials and without changing anything, and
report:
- the deployment's name and region;
- the core stack's status and the time it was last created or updated;
- the CLI version that last set it up;
- every stack output;
- the GitHub organisation and repositories the stack trusts to run benchmarks, or that it trusts none;
- the published runner image's version and AMI, or that none is published.

`--format json` SHALL print the same facts as one JSON object on standard output, and nothing else
there. When the stack does not exist, the command SHALL exit 1, naming `baas admin deployment setup`.

#### Scenario: A set-up deployment is reported
- **WHEN** `baas --deployment baas-development admin deployment show` is run against a deployment with a
  published image and GitHub trust
- **THEN** it prints the stack's status and last update, the recorded CLI version, the operator role
  ARN among the outputs, the trusted organisation and repositories, and the image version and AMI, and
  exits 0

#### Scenario: The machine-readable form
- **WHEN** `baas admin deployment show --format json` is run
- **THEN** standard output is one JSON object holding the deployment, region, stack status, last
  update, CLI version, outputs, GitHub trust (`null` when none) and image (`null` when none)

#### Scenario: A deployment without GitHub trust or image
- **WHEN** the stack trusts no GitHub repository and no image is published
- **THEN** the report says so for each, and the command exits 0

#### Scenario: A missing stack
- **WHEN** the deployment's stack does not exist
- **THEN** the command exits 1 naming `baas admin deployment setup`

#### Scenario: Nothing is written
- **WHEN** the command runs
- **THEN** it makes only read calls to AWS and writes no local file
