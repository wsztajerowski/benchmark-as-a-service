## ADDED Requirements

### Requirement: `baas run` consumes a pre-built benchmark JAR
`baas run` SHALL NOT build the benchmark project. It SHALL require the benchmark JAR to be named
explicitly on the command line, SHALL NOT fall back to a configured or defaulted path, and SHALL
fail before resolving the runner image and before any upload when no JAR is named or the named JAR
does not exist. The failure message SHALL name the option that supplies it.

#### Scenario: A named JAR is used as-is
- **WHEN** `baas run --benchmark-jar target/benchmarks.jar jmh -- MyBenchmark` is invoked
- **THEN** that JAR is uploaded for the run, and no build is performed in the working directory

#### Scenario: An unnamed JAR fails before provisioning
- **WHEN** `baas run jmh -- MyBenchmark` is invoked with no benchmark JAR named
- **THEN** the command exits non-zero naming the required option, no EC2 instance is launched, and
  nothing is uploaded

#### Scenario: A named JAR that does not exist fails before provisioning
- **WHEN** the named benchmark JAR does not exist
- **THEN** the command exits non-zero naming the path it tried, and no EC2 instance is launched

#### Scenario: No build is attempted in any circumstance
- **WHEN** `baas run` is invoked from a directory containing a buildable project
- **THEN** no build tool is invoked, and the run uses only the named JAR

### Requirement: `commit` and `branch` are derived, with overrides, and omitted when unknown
`baas run` SHALL derive the `commit` and `branch` values from the current git repository and SHALL
accept `--commit <value>` and `--branch <value>` to override them. When a value is neither supplied
nor derivable, the corresponding tag SHALL be omitted from the run entirely; the command SHALL NOT
substitute a placeholder value, and SHALL NOT fail.

#### Scenario: Derived values are forwarded
- **WHEN** `baas run` is invoked inside a git repository with neither option given
- **THEN** the repository's current commit and branch are forwarded as the `commit` and `branch` tags

#### Scenario: Explicit values win over derivation
- **WHEN** `--commit` or `--branch` is given inside a git repository
- **THEN** the supplied value is forwarded and the derived one is not

#### Scenario: An unresolvable value is omitted, not invented
- **WHEN** `baas run` is invoked outside a git repository with neither option given
- **THEN** the run proceeds, and its stored measurement carries no `commit` and no `branch` tag

#### Scenario: No placeholder value reaches the stored result
- **WHEN** a run's commit or branch cannot be resolved
- **THEN** no tag is stored whose value stands in for the unknown value
