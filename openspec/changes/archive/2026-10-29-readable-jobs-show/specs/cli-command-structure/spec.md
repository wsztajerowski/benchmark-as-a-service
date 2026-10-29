# Spec Delta

## MODIFIED Requirements

### Requirement: Next-step hints are shown to people, never to scripts
A command MAY end by suggesting at most two follow-up commands, written to standard error as one block:
a line `Next`, then one line per hint, `  <purpose>  <exact command>`, with the commands aligned and real
identifiers filled in. The block SHALL carry no timestamp, log level or logger name. A suggested command
that acts on a deployment SHALL always carry `--deployment <name>`, however many deployments are
configured, so that it stays valid when another is added. Hints SHALL appear only when the console is
interactive, by the same definition that gates colour, and SHALL never appear on standard output. There
SHALL be no option to disable them. Setup's closing report of next steps is its result, not a hint: it
is printed in both modes, and every command in it carries `--deployment <name>` too.

#### Scenario: A finished job points to its measurements
- **WHEN** `baas jobs show <id>` completes on an interactive terminal for deployment `baas-123456789012`
- **THEN** standard error ends with a `Next` line followed by
  `  measurements  baas --deployment baas-123456789012 query --job-id <id>` and
  `  files         baas --deployment baas-123456789012 jobs download <id>`, with no timestamp, level or
  logger name on any of them

#### Scenario: Scripts see no hints
- **WHEN** the same command runs with standard output redirected or without a console
- **THEN** no hint is written to either stream

#### Scenario: A hint printed with one deployment works with two
- **WHEN** a hint is printed while one deployment is configured, a second is then configured, and the hint's command is run as printed
- **THEN** it acts on the deployment it named, and is not refused as ambiguous

### Requirement: Results filters cover the supported query patterns
`baas results query` SHALL accept `--job-id`, `--benchmark-name`, `--tag <key>=<value>` (repeatable),
`--exclude-tag <key>=<value>` (repeatable), `--project`, `--all-projects`, `--best-per <tag>`,
`--show-hidden`, `--sort-by`, `--asc`, `--limit`, `--offset`, `--format` and `--watch`. `--job-id`
SHALL NOT combine with `--project` or `--all-projects`, which select a different access path, and
`--project` and `--all-projects` SHALL be mutually exclusive with each other. An invalid combination
SHALL fail with a message naming the conflicting option.

#### Scenario: Tag filter is accepted
- **WHEN** `baas results query --project p --tag jdk=25.0.4` is invoked
- **THEN** matching rows are returned

#### Scenario: Conflicting filters are rejected
- **WHEN** both `--job-id abc` and `--all-projects` are given
- **THEN** the command exits non-zero naming `--all-projects`, and issues no query

#### Scenario: A job lookup does not take a project
- **WHEN** both `--job-id abc` and `--project p` are given
- **THEN** the command exits non-zero naming `--project`, and issues no query

#### Scenario: One project and every project are exclusive
- **WHEN** both `--project p` and `--all-projects` are given
- **THEN** the command exits non-zero naming both options

#### Scenario: Limit bounds the output
- **WHEN** `--limit 5` is given and more rows match
- **THEN** at most five rows are returned

#### Scenario: Removed options are rejected
- **WHEN** `baas results query --all-jobs`, `--group-by branch`, `--living-branches` or `--all` is given
- **THEN** picocli reports an unknown option error
