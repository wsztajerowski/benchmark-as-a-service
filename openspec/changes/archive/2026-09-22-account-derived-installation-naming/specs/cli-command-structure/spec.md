# Spec Delta

## ADDED Requirements

### Requirement: Resource names the CLI needs are derived or resolved, not cached
`~/.baas/config.yaml` SHALL store only what cannot be obtained from the installation itself: the
credential settings, the region, the installation prefix, and the operator's own preferences. Names
the composition rule determines — the working bucket, the results table and the runner instance
profile — SHALL be derived from the prefix at use time. Identifiers AWS assigns, specifically the
runner subnet and security group, SHALL be resolved from the installation's stack outputs at use
time rather than cached, so that a replaced resource cannot leave a stale identifier behind.

#### Scenario: A replaced security group does not strand the configuration
- **WHEN** the runner security group is replaced by a stack update and its identifier changes, and `baas run` is invoked afterwards with no intervening configuration command
- **THEN** the run uses the current security group identifier

#### Scenario: Config carries no derivable names
- **WHEN** `baas config show` reports the configuration after `baas config sync`
- **THEN** no stored field holds the bucket name, the results table name or the runner instance profile name

### Requirement: Read-only commands can address another installation's results table
`baas results` and `baas download` SHALL accept `--results-table <name>`, addressing that table for
the invocation only and persisting nothing. No command that writes measurements SHALL accept such an
override, so an operator cannot leave a configuration pointed at an archive and silently record new
runs into it.

#### Scenario: Reading a retired installation's history
- **WHEN** `baas results --results-table baas-3q7i7s65-results` runs on a machine configured for the current installation
- **THEN** the retired table's measurements are reported and `~/.baas/config.yaml` is unchanged

#### Scenario: The override is absent from the write path
- **WHEN** `baas run --results-table other-table` or `baas config set --results-table other-table` is invoked
- **THEN** picocli reports an unknown option error

## MODIFIED Requirements

### Requirement: Teardown reports what it retains
`baas admin teardown` SHALL state, before the confirmation prompt, that the results table and the working
bucket are retained by default, so an operator is not left believing that history was deleted. Its
messages SHALL describe the retained names as derived from the installation's AWS account, and SHALL
NOT attribute them to the caller's identity.

#### Scenario: Retention is stated before confirmation
- **WHEN** `baas admin teardown` prompts for confirmation
- **THEN** the prompt text names both the retained bucket and the retained results table

#### Scenario: Retention message explains the name a re-setup will ask for
- **WHEN** `baas admin teardown` completes having retained the bucket
- **THEN** the message states that a later `baas admin setup` in the same account will request that same bucket name, and says how to keep or remove it

## REMOVED Requirements

### Requirement: The results table is resolved from configuration, not a secret store
**Reason**: The table name is no longer carried as a stored configuration field. Under the unified composition rule it is `<prefix>-results`, so storing it would duplicate a value the CLI can determine, and the two could disagree after a `config sync` against a different installation. The requirement's load-bearing claim — that the name is not fetched from a parameter store and travels in user-data directly — is preserved by *Resource names the CLI needs are derived or resolved, not cached* together with the core stack's existing user-data requirements.

**Migration**: Nothing is fetched at boot that was not fetched before; the table name still reaches the runner in user-data. An existing `config.yaml` carrying `aws.resultsTable` is ignored rather than honoured — run `baas config sync --name <prefix>` to rewrite the file. To read a table belonging to a different or retired installation, use `baas results --results-table <name>`.
