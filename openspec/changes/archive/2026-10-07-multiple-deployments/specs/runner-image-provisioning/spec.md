# Spec Delta

## MODIFIED Requirements

### Requirement: Two jobs' environments are compared group by group
`baas jobs diff <jobA> <jobB>` SHALL resolve each job id through the job-ID index, fetch both jobs'
`environment.json`, and compare every field of the seven groups — and nothing else. It SHALL print
first `Differs in:` naming the differing groups, then each differing field with both values, grouped. When
`machine.amiId` differs it SHALL also read both jobs' `packages.txt` and report, as a `packages` group,
the packages whose version changed and those added or removed; when the AMIs are equal it SHALL read no
`packages.txt`. When nothing differs it SHALL say that both jobs were measured on the same environment and
exit 0. A `schemaVersion` mismatch SHALL be warned about on standard error. A job id that resolves to no
job SHALL fail naming that id before any S3 read, and a missing manifest SHALL fail naming the job. It
SHALL run under operator credentials and write its payload to standard output.

#### Scenario: Jobs named by id
- **WHEN** `baas jobs diff <jobIdA> <jobIdB>` runs for two stored jobs
- **THEN** both resolve through the job-ID index and the comparison is printed

#### Scenario: Identical environments report no differences
- **WHEN** two jobs on different branches used the same AMI and instance type
- **THEN** `baas jobs diff` prints that both jobs were measured on the same environment, and exits 0

#### Scenario: A JVM change is named by group
- **WHEN** two jobs used JDK 25.0.3 and 25.0.4
- **THEN** the output starts `Differs in: jvm` and lists `version` with both values under `jvm`

#### Scenario: Two vendors' builds of one Java version are told apart
- **WHEN** two jobs used Corretto 25.0.4 and another vendor's 25.0.4
- **THEN** the `jvm` vendor fields are reported as differing

#### Scenario: A rebuilt image reports its package changes
- **WHEN** two jobs ran on different AMIs whose `packages.txt` differ in one package's version
- **THEN** `Differs in:` includes `machine` and `packages`, and the package is listed with both versions

#### Scenario: Equal AMIs read no package lists
- **WHEN** two jobs ran on the same AMI
- **THEN** neither `packages.txt` is read

#### Scenario: An unknown job id
- **WHEN** one argument names no stored job
- **THEN** the command exits non-zero naming that id, and reads nothing from S3

#### Scenario: Missing manifest fails clearly
- **WHEN** one of the jobs has no `environment.json`
- **THEN** the command exits non-zero naming that job

#### Scenario: Diff uses operator credentials
- **WHEN** the configuration sets both `aws.deployerProfile` and `aws.operatorProfile` and `baas jobs diff` runs
- **THEN** AWS clients are built from `aws.operatorProfile`
