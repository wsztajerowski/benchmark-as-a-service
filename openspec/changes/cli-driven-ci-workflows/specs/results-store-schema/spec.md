# Spec Delta

## MODIFIED Requirements

### Requirement: Tags are the queryable dimensions, with a shared known-key vocabulary
The runner SHALL record `project`, `type`, `jdk`, `cpuModel`, `cpuArch`, `instanceType` and
`imageVersion` as tags on every measurement. It SHALL record `commit`, `branch` and `source` on every
measurement for which they are supplied, and SHALL omit them otherwise rather than storing a
placeholder value standing in for an unknown one. These key names SHALL be defined once as constants
in the shared model module and used by both the runner and the CLI. `branch` and `source` SHALL be
caller-supplied, like `project` and `commit`, rather than machine-observed. `source` SHALL identify
how the run was triggered; `baas run` SHALL derive it as `ci` when it detects a continuous-integration
environment and `local` otherwise, and an explicitly supplied value SHALL win over the derived one.
Tag keys outside the vocabulary SHALL be permitted, and a query naming an unknown key SHALL produce a
warning rather than silently returning nothing.

#### Scenario: Environment tags are observed on the instance
- **WHEN** a benchmark runs on an instance
- **THEN** its stored measurement carries `jdk`, `cpuModel`, `cpuArch` and `instanceType` values matching
  that run's `environment.json`

#### Scenario: Branch is recorded as a tag
- **WHEN** a run is launched from a git branch
- **THEN** its stored measurement carries a `branch` tag, and that tag is usable as a filter

#### Scenario: An unsupplied commit or branch is absent, not a placeholder
- **WHEN** a run is launched with no resolvable commit and no resolvable branch
- **THEN** its stored measurement carries neither key, and no stored value stands in for them

#### Scenario: Unknown tag key warns
- **WHEN** `baas results --tag jvm=21` is queried and no measurement uses the key `jvm`
- **THEN** the command reports that `jvm` is not a known tag key and lists the known keys

#### Scenario: Custom tags are stored and queryable
- **WHEN** a run is invoked with `--tag branch=main --tag experiment=gc-tuning`
- **THEN** both tags are present on the stored measurement and both are usable as filters

#### Scenario: A laptop run is tagged as local
- **WHEN** `baas run` is invoked outside a continuous-integration environment with no `source` tag
- **THEN** the stored measurement carries `source=local`

#### Scenario: A continuous-integration run is tagged as such
- **WHEN** `baas run` is invoked from a continuous-integration environment with no `source` tag
- **THEN** the stored measurement carries `source=ci`

#### Scenario: An explicit source wins over the derived one
- **WHEN** `baas run --tag source=nightly` is invoked
- **THEN** the stored measurement carries `source=nightly`, and the command does not reject the tag

#### Scenario: Source is usable as a grouping dimension
- **WHEN** results carrying `source=ci` and `source=local` exist for one benchmark and `source` is the
  grouping tag
- **THEN** they are reported as separate groups rather than merged
