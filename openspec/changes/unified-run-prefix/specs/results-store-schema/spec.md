## MODIFIED Requirements

### Requirement: Timestamps sort chronologically as strings
`createdAt` SHALL be stored as a fixed-width UTC ISO-8601 instant, so that lexicographic ordering of sort
keys equals chronological ordering. When the run was launched by `baas run`, `createdAt` SHALL be the
instant the CLI minted for the run rather than an instant read on the benchmark instance, so that a run's
identifier and its measurements' timestamps cannot disagree.

#### Scenario: Lexicographic order matches chronological order
- **WHEN** timestamps spanning a month boundary and a year boundary are formatted and sorted as strings
- **THEN** the resulting order is identical to their chronological order

#### Scenario: The stored timestamp is the launching CLI's instant
- **WHEN** `baas run` launches a run and the instance's clock differs from the launching machine's
- **THEN** every stored measurement's `createdAt` is the launching machine's instant

### Requirement: `project` is derived from the git repository name
`baas run` SHALL derive `project` from the name of the git repository it is invoked in, resolving the
main repository rather than a linked worktree directory, and SHALL accept a `--project` option
overriding it. A measurement SHALL NOT be written when `project` cannot be resolved. The runner SHALL
reject an unresolved `project` outright rather than substituting a placeholder value.

#### Scenario: Project defaults to the repository name
- **WHEN** `baas run` is invoked inside a repository named `lynx-journal` with no `--project`
- **THEN** the stored measurement has `pk = RESULT#lynx-journal`

#### Scenario: Explicit override wins
- **WHEN** `--project other-name` is given
- **THEN** the stored measurement has `pk = RESULT#other-name`

#### Scenario: A linked worktree is attributed to its repository
- **WHEN** `baas run` is invoked from a linked worktree of the `lynx-journal` repository with no
  `--project`
- **THEN** the stored measurement has `pk = RESULT#lynx-journal`, not the worktree directory's name

#### Scenario: The runner refuses an unresolved project
- **WHEN** `benchmark-runner` is invoked with no project value and no `project` tag
- **THEN** it exits non-zero rather than storing a measurement under a placeholder project

### Requirement: Tags are the queryable dimensions, with a shared known-key vocabulary
The runner SHALL record `project`, `type`, `commit`, `branch`, `jdk`, `cpuModel`, `cpuArch`,
`instanceType` and `imageVersion` as tags on every measurement. These key names SHALL be defined once as
constants in the shared model module and used by both the runner and the CLI. `branch` SHALL be
caller-supplied, like `project` and `commit`, rather than machine-observed. Tag keys outside the
vocabulary SHALL be permitted, and a query naming an unknown key SHALL produce a warning rather than
silently returning nothing.

#### Scenario: Environment tags are observed on the instance
- **WHEN** a benchmark runs on an instance
- **THEN** its stored measurement carries `jdk`, `cpuModel`, `cpuArch` and `instanceType` values matching
  that run's `environment.json`

#### Scenario: Branch is recorded as a tag
- **WHEN** a run is launched from a git branch
- **THEN** its stored measurement carries a `branch` tag, and that tag is usable as a filter

#### Scenario: Unknown tag key warns
- **WHEN** `baas results --tag jvm=21` is queried and no measurement uses the key `jvm`
- **THEN** the command reports that `jvm` is not a known tag key and lists the known keys

#### Scenario: Custom tags are stored and queryable
- **WHEN** a run is invoked with `--tag branch=main --tag experiment=gc-tuning`
- **THEN** both tags are present on the stored measurement and both are usable as filters
