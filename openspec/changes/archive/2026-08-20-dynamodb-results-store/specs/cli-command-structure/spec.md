## ADDED Requirements

### Requirement: The results table is resolved from configuration, not a secret store
`~/.baas/config.yaml` SHALL carry the results table name, populated from the core stack output by
`baas admin setup` and `baas config sync`. `baas run` SHALL pass it into user-data directly, since the
table name is not sensitive.

#### Scenario: Setup records the table name
- **WHEN** `baas admin setup` completes
- **THEN** `config.yaml` holds the results table name from the stack output

#### Scenario: Config sync populates the table name
- **WHEN** `baas config sync --core-stack-name <name>` runs on a machine with no prior config
- **THEN** the results table name is populated from that stack's outputs

#### Scenario: Table name is carried in user-data
- **WHEN** the user-data script is rendered
- **THEN** it contains the table name and performs no parameter-store lookup for database configuration

### Requirement: User tags are passed through to the runner
`baas run` SHALL forward every `--tag key=value` option into the user-data script as a runner argument,
in addition to applying the EC2 instance tags it already applies. A tag applied only to the instance
SHALL NOT be considered forwarded.

#### Scenario: User tags appear in rendered user-data
- **WHEN** `baas run --tag branch=main --tag experiment=gc jmh -- MyBenchmark` renders user-data
- **THEN** the runner invocation carries `--tag branch=main` and `--tag experiment=gc`

#### Scenario: Environment tags are still forwarded
- **WHEN** user-data is rendered
- **THEN** it still forwards `imageVersion` and `instanceType` observed on the instance

### Requirement: The project is derived, with an override
`baas run` SHALL derive the `project` value from the current git repository's name and SHALL accept
`--project <name>` to override it. When neither is available the command SHALL fail before provisioning.

#### Scenario: Derived project is used
- **WHEN** `baas run` is invoked inside a git repository with no `--project`
- **THEN** the repository name is forwarded as the `project` tag

#### Scenario: Unresolvable project fails before provisioning
- **WHEN** `baas run` is invoked outside a git repository with no `--project`
- **THEN** the command exits non-zero and no EC2 instance is launched

### Requirement: Discarding results requires an explicit flag on the run path
`baas run` SHALL expose a `--no-database` pass-through that selects the no-op store on the runner.
Without it, an unresolvable table name SHALL fail before any instance is launched.

#### Scenario: Unresolvable table fails before provisioning
- **WHEN** `baas run jmh -- MyBenchmark` is invoked with no table name in config and no `--no-database`
- **THEN** the command exits non-zero and no EC2 instance is launched

#### Scenario: Explicit opt-in is honoured
- **WHEN** `baas run --no-database jmh -- MyBenchmark` is invoked
- **THEN** the run proceeds and the runner performs no database write

### Requirement: Results filters cover the supported query patterns
`baas results` SHALL accept `--request-id`, `--benchmark-name`, `--tag <key>=<value>` (repeatable),
`--living-branches`, `--project` and `--limit`. `--request-id` SHALL be mutually exclusive with the other
filters, and an invalid combination SHALL fail with a message naming the supported forms.

#### Scenario: Tag filter is accepted
- **WHEN** `baas results --tag jdk=25.0.4` is invoked
- **THEN** matching rows are returned

#### Scenario: Conflicting filters are rejected
- **WHEN** both `--tag branch=main` and `--request-id abc` are given
- **THEN** the command exits non-zero explaining that `--request-id` cannot be combined with other filters

#### Scenario: Limit bounds the output
- **WHEN** `--limit 5` is given and more rows match
- **THEN** at most five rows are returned

### Requirement: A command downloads a run's S3 artifacts
The CLI SHALL provide a command taking a request ID and a destination directory, downloading every S3
object under that run's prefix.

#### Scenario: Artifacts land locally
- **WHEN** the command is invoked with a request ID and a destination
- **THEN** the destination contains the run's result JSON, `environment.json`, process output and logs

#### Scenario: Destination is reported
- **WHEN** the download completes
- **THEN** the command prints the destination path and exits 0

### Requirement: Teardown reports what it retains
`baas admin teardown` SHALL state, before the confirmation prompt, that the results table and the working
bucket are retained by default, so an operator is not left believing that history was deleted.

#### Scenario: Retention is stated before confirmation
- **WHEN** `baas admin teardown` prompts for confirmation
- **THEN** the prompt text names both the retained bucket and the retained results table

## REMOVED Requirements

### Requirement: Mongo URI is supplied and validated by the CLI
**Reason**: BaaS no longer uses MongoDB. `--mongo-uri` on `baas admin setup` and `baas config set`, the
SecureString write, and the `validateMongoUri` helper duplicated across three classes (finding A5) all
become dead code. The MongoDB adapter retained in `benchmark-runner` is configured on that JAR's own
command line and never through `baas`.

**Migration**: Drop `--mongo-uri` from any scripted invocation of `baas admin setup` or
`baas config set`. Existing `config.yaml` files keep working; the Mongo key is ignored and the table name
is populated by `baas admin setup` or `baas config sync`. Run `scripts/migrate-atlas-to-dynamodb` before
decommissioning the Atlas cluster.
