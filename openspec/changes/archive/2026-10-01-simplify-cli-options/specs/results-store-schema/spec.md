# Spec Delta

## ADDED Requirements

### Requirement: `project` is supplied explicitly or derived from the benchmark JAR's repository
`baas run` SHALL take `project` from `--project`, or — only when `git.resolveProject` is enabled — from
the name of the git repository containing the benchmark JAR, resolving the main repository rather than a
linked worktree directory. A measurement SHALL NOT be written when `project` cannot be resolved. The
runner SHALL reject an unresolved `project` outright rather than substituting a placeholder value.

#### Scenario: Explicit project is stored
- **WHEN** `--project lynx-journal` is given
- **THEN** the stored measurement has `pk = RESULT#lynx-journal`

#### Scenario: Derived from the JAR's repository when enabled
- **WHEN** `git.resolveProject` is `true`, no `--project` is given, and the JAR lies inside the
  `lynx-journal` repository or one of its linked worktrees
- **THEN** the stored measurement has `pk = RESULT#lynx-journal`

#### Scenario: The runner refuses an unresolved project
- **WHEN** `benchmark-runner` is invoked with no project value and no `project` tag
- **THEN** it exits non-zero rather than storing a measurement under a placeholder project

## MODIFIED Requirements

### Requirement: Tags are the queryable dimensions, with a shared known-key vocabulary
The runner SHALL record `project`, `type`, `jdk`, `cpuModel`, `cpuArch`, `instanceType` and
`imageVersion` as tags on every measurement. It SHALL record `commit`, `branch` and `source` on every
measurement for which they are supplied, and SHALL omit them otherwise rather than storing a
placeholder value standing in for an unknown one. These key names SHALL be defined once as constants
in the shared model module and used by both the runner and the CLI. `branch` and `source` SHALL be
caller-supplied, like `project` and `commit`, rather than machine-observed. `commit` and `branch` SHALL be
supplied only as caller tags; `baas run` SHALL NOT derive them. `source` SHALL identify
how the run was triggered; `baas run` SHALL derive it as `ci` when it detects a continuous-integration
environment and `local` otherwise, and an explicitly supplied value SHALL win over the derived one.
Tag keys outside the vocabulary SHALL be permitted, and a query naming an unknown key SHALL produce a
warning rather than silently returning nothing.

#### Scenario: Environment tags are observed on the instance
- **WHEN** a benchmark runs on an instance
- **THEN** its stored measurement carries `jdk`, `cpuModel`, `cpuArch` and `instanceType` values matching
  that run's `environment.json`

#### Scenario: Branch is recorded as a tag
- **WHEN** a run is launched with `--tag branch=main`
- **THEN** its stored measurement carries a `branch` tag, and that tag is usable as a filter

#### Scenario: An unsupplied commit or branch is absent, not a placeholder
- **WHEN** a run is launched with no `commit` and no `branch` tag
- **THEN** its stored measurement carries neither key, and no stored value stands in for them

#### Scenario: Unknown tag key warns
- **WHEN** `baas results --project p --tag jvm=21` is queried and no measurement uses the key `jvm`
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

## REMOVED Requirements

### Requirement: `project` is derived from the git repository name
**Reason**: The invocation directory no longer determines the project.
**Migration**: Pass `--project`, or enable `git.resolveProject`. See *`project` is supplied explicitly or
derived from the benchmark JAR's repository*.
