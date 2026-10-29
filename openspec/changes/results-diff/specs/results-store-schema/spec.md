## MODIFIED Requirements

### Requirement: Tags are the queryable dimensions, with a shared known-key vocabulary
The runner SHALL record `project`, `type`, `jdk`, `jvmVendor`, `cpuModel`, `cpuArch`, `instanceType` and
`imageVersion` as tags on every measurement. It SHALL record `commit`, `branch` and `source` on every
measurement for which they are supplied, and SHALL omit them otherwise rather than storing a
placeholder value standing in for an unknown one. These key names SHALL be defined once as constants
in the shared model module and used by both the runner and the CLI. `branch` and `source` SHALL be
caller-supplied, like `project` and `commit`, rather than machine-observed. `commit` and `branch` SHALL be
supplied only as caller tags; `baas run` SHALL NOT derive them. `source` SHALL identify
how the job was triggered; `baas run` SHALL derive it as `ci` when it detects a continuous-integration
environment and `local` otherwise, and an explicitly supplied value SHALL win over the derived one.
`baas run` SHALL record everything following its `--` separator as the tag `cliParameters`, the
arguments joined by a single space with any argument holding whitespace or a quote single-quoted, and
SHALL omit the tag when nothing follows `--`.
`imageVersion`, `instanceType`, `jdk`, `jvmVendor`, `cpuModel`, `cpuArch`, `type` and `cliParameters`
SHALL NOT be settable by the caller: `baas run` SHALL reject a `--tag` naming any of them before
launching anything.
Tag keys outside the vocabulary SHALL be permitted, and a query naming an unknown key SHALL produce a
warning rather than silently returning nothing.

#### Scenario: Environment tags are observed on the instance
- **WHEN** a benchmark runs on an instance
- **THEN** its stored measurement carries `jdk`, `jvmVendor`, `cpuModel`, `cpuArch` and `instanceType`
  values matching that job's `environment.json`

#### Scenario: Results can be grouped by JVM vendor
- **WHEN** results measured on two vendors' builds of the same Java version exist for one benchmark and
  `jvmVendor` is the grouping tag
- **THEN** they are reported as separate groups rather than merged

#### Scenario: A caller cannot set the JVM vendor
- **WHEN** `baas run --tag jvmVendor=Acme jmh -- MyBenchmark` is invoked
- **THEN** the command exits non-zero naming `jvmVendor` as reserved, and no instance is launched

#### Scenario: The run's parameters are recorded as a tag
- **WHEN** `baas run jmh-with-async -- --async-event wall MyBenchmark -f 1` completes
- **THEN** its stored measurement carries `cliParameters=--async-event wall MyBenchmark -f 1`

#### Scenario: A caller cannot set the recorded parameters
- **WHEN** `baas run --tag cliParameters=-f5 jmh -- MyBenchmark` is invoked
- **THEN** the command exits non-zero naming `cliParameters` as reserved, and no instance is launched

#### Scenario: Branch is recorded as a tag
- **WHEN** a job is launched with `--tag branch=main`
- **THEN** its stored measurement carries a `branch` tag, and that tag is usable as a filter

#### Scenario: An unsupplied commit or branch is absent, not a placeholder
- **WHEN** a job is launched with no `commit` and no `branch` tag
- **THEN** its stored measurement carries neither key, and no stored value stands in for them

#### Scenario: Unknown tag key warns
- **WHEN** `baas results query --project p --tag jvm=21` is queried and no measurement uses the key `jvm`
- **THEN** the command reports that `jvm` is not a known tag key and lists the known keys

#### Scenario: Custom tags are stored and queryable
- **WHEN** a job is invoked with `--tag branch=main --tag experiment=gc-tuning`
- **THEN** both tags are present on the stored measurement and both are usable as filters

#### Scenario: A job launched from a laptop is tagged as local
- **WHEN** `baas run` is invoked outside a continuous-integration environment with no `source` tag
- **THEN** the stored measurement carries `source=local`

#### Scenario: A CI-launched job is tagged as such
- **WHEN** `baas run` is invoked from a continuous-integration environment with no `source` tag
- **THEN** the stored measurement carries `source=ci`

#### Scenario: An explicit source wins over the derived one
- **WHEN** `baas run --tag source=nightly` is invoked
- **THEN** the stored measurement carries `source=nightly`, and the command does not reject the tag

#### Scenario: Source is usable as a grouping dimension
- **WHEN** results carrying `source=ci` and `source=local` exist for one benchmark and `source` is the
  grouping tag
- **THEN** they are reported as separate groups rather than merged
