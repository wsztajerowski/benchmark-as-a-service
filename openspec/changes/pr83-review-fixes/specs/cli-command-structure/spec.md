## MODIFIED Requirements

### Requirement: Listings share one ordering and paging pipeline
`baas jobs list` and `baas results query` SHALL apply, in order: their filters, then `--best-per` where
offered, then sorting, then `--offset <m>`, then `--limit <n>`. The default sort SHALL be newest
first; `--sort-by <field>` SHALL choose another field and `--asc` SHALL reverse the direction.
`--limit` SHALL default to 20, except for `baas results query --job-id`, which SHALL default to no limit;
`--limit 0` SHALL mean no limit, and a cut SHALL be announced on standard error as how many of how many
rows were reported. Sorting by score SHALL place rows whose score is unknown last, in either direction.

#### Scenario: The default is the twenty newest
- **WHEN** 30 rows match and no paging option is given
- **THEN** the 20 newest are reported, newest first, and standard error reports 20 of 30

#### Scenario: An offset pages backwards in time
- **WHEN** `--offset 20 --limit 20` is given over 30 matching rows
- **THEN** the 10 oldest rows are reported

#### Scenario: Another order is chosen explicitly
- **WHEN** `baas results query --project p --sort-by benchmark --asc` is run
- **THEN** rows are ordered by benchmark name, ascending

#### Scenario: A job lookup is not cut by default
- **WHEN** `baas results query --job-id <id>` matches 30 rows and no paging option is given
- **THEN** all 30 rows are reported, and nothing is announced on standard error

#### Scenario: Unknown scores sort last
- **WHEN** `baas results query --project p --sort-by score` is run, with and without `--asc`, over rows of which one has no stored score
- **THEN** that row is reported last both times

### Requirement: Each deployment has its own configuration file
The CLI SHALL keep one configuration file per deployment, `~/.baas/deployments/<name>.yaml`, where
`<name>` is the deployment's prefix. No file SHALL record a default deployment, and no command SHALL
switch one. `baas admin deployment setup` and `baas config sync` SHALL write the file of the
deployment they act on, and `baas admin deployment teardown` SHALL delete the file of the deployment
it tears down once the teardown has completed, whether or not other deployments remain. A file whose
recorded prefix is not its own `<name>` SHALL be refused by every command that reads it, before any AWS
call, with a message naming the file, both values, and the correction: rename the file or correct the
prefix.

#### Scenario: Setup writes the deployment's own file
- **WHEN** `baas --deployment wiktor-dev admin deployment setup` completes
- **THEN** `~/.baas/deployments/wiktor-dev.yaml` holds that deployment's prefix and region, and no other file changes

#### Scenario: Teardown removes the file
- **WHEN** `baas --deployment wiktor-dev admin deployment teardown --yes` completes
- **THEN** `~/.baas/deployments/wiktor-dev.yaml` no longer exists

#### Scenario: Tearing down the last deployment
- **WHEN** the only configured deployment is torn down
- **THEN** no deployment is configured, and the next command other than setup reports "No deployment is configured"

#### Scenario: A copied file is refused
- **WHEN** `~/.baas/deployments/wiktor-dev.yaml` records `prefix: baas-123456789012` and `baas --deployment wiktor-dev admin deployment teardown --yes` is run
- **THEN** the command exits non-zero naming the file, `wiktor-dev` and `baas-123456789012`, and deletes nothing in AWS or on disk
