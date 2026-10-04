## ADDED Requirements

### Requirement: `baas run` always resolves the results table
`baas run` SHALL resolve the installation's results table on every invocation, before the runner-image
lookup and before any upload, and SHALL pass it to the runner. An unresolvable table SHALL fail before any
instance is launched. `baas run` SHALL NOT offer an option that discards measurements.

#### Scenario: Unresolvable table fails before provisioning
- **WHEN** `baas run jmh -- MyBenchmark` is invoked with no installation configured
- **THEN** the command exits non-zero and no EC2 instance is launched

#### Scenario: The discard option is gone
- **WHEN** `baas run --no-database jmh -- MyBenchmark` is invoked
- **THEN** picocli reports an unknown option and nothing is uploaded or launched

## MODIFIED Requirements

### Requirement: Daily-use commands remain top-level
`run`, `runs` (with its `list`/`terminate` subcommands), `results`, and `config` (with its `set`/`show` subcommands) SHALL remain directly reachable from the `baas` root command, unaffected by the `admin` grouping.

#### Scenario: Top-level commands unchanged
- **WHEN** a user runs `baas run jmh -- MyBenchmark -f 1`, `baas runs list`, `baas results`, or `baas config show`
- **THEN** each resolves to its command implementation with no `admin` prefix required

## REMOVED Requirements

### Requirement: Discarding results requires an explicit flag on the run path
**Reason**: `--no-database` is removed. Every run records its status in the results table, so a run
without the table has no status, and the flag had no remaining use.
**Migration**: None needed for cloud runs, which always use the installation's table. Replaced by
"`baas run` always resolves the results table".
