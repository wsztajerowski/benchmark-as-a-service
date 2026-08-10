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

### Requirement: Discarding results requires an explicit flag on the run path
`baas run` SHALL expose a `--no-database` pass-through that selects the no-op store on the runner. Without
it, an unresolvable table name SHALL fail before any instance is launched.

#### Scenario: Unresolvable table fails before provisioning
- **WHEN** `baas run jmh -- MyBenchmark` is invoked with no table name in config and no `--no-database`
- **THEN** the command exits non-zero and no EC2 instance is launched

#### Scenario: Explicit opt-in is honoured
- **WHEN** `baas run --no-database jmh -- MyBenchmark` is invoked
- **THEN** the run proceeds and the runner performs no database write

### Requirement: Results filters cover the supported query patterns
`baas results` SHALL accept `--request-id`, `--branch`, `--benchmark`, and `--tag <key>=<value>`, and SHALL
accept a `--limit` bounding the rows returned. Filters SHALL be mutually exclusive, and an invalid
combination SHALL fail with a message naming the supported forms.

#### Scenario: Tag filter is accepted
- **WHEN** `baas results --tag project=lynx-journal` is invoked
- **THEN** matching rows are returned

#### Scenario: Conflicting filters are rejected
- **WHEN** both `--branch main` and `--request-id abc` are given
- **THEN** the command exits non-zero explaining that filters are mutually exclusive

#### Scenario: Limit bounds the output
- **WHEN** `--limit 5` is given and more rows match
- **THEN** at most five rows are returned

### Requirement: Teardown reports what it retains
`baas admin teardown` SHALL state, before the confirmation prompt, that the results table and the working
bucket are retained by default, so an operator is not left believing that history was deleted.

#### Scenario: Retention is stated before confirmation
- **WHEN** `baas admin teardown` prompts for confirmation
- **THEN** the prompt text names both the retained bucket and the retained results table

## REMOVED Requirements

### Requirement: Mongo URI is supplied and validated by the CLI
**Reason**: The Mongo connection string no longer exists. `--mongo-uri` on `baas admin setup` and
`baas config set`, the SecureString write, and the `validateMongoUri` helper duplicated across three
classes (finding A5) all become dead code.

**Migration**: Drop `--mongo-uri` from any scripted invocation of `baas admin setup` or
`baas config set`. Existing `config.yaml` files keep working; the Mongo key is ignored and the table name
is populated by `baas admin setup` or `baas config sync`. Run
`scripts/migrate-atlas-to-dynamodb` before decommissioning the Atlas cluster.

### Requirement: Benchmark name filtering accepts a regular expression
**Reason**: DynamoDB cannot evaluate a regular expression against a key, and doing so client-side would
require reading the whole table. Matching becomes exact class name with an optional method prefix.

**Migration**: Replace regular-expression invocations with an exact class name, optionally plus a method
prefix. A pattern spanning multiple classes now requires one invocation per class, or `--tag` on a tag
shared by the benchmarks of interest.
