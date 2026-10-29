# Spec Delta

## MODIFIED Requirements

### Requirement: Every job records its environment, grouped
Before starting the benchmark process, user-data SHALL write `<result-path>/environment.json` carrying
`schemaVersion` 7 and the measurement environment in seven groups: `machine` (`imageVersion`, `amiId`,
`instanceType`), `cpu` (`model`, `arch`, `vcpus`, `physicalCores`, `threadsPerCore`, `maxMhz`), `memory`
(`totalMib`, `swapTotalMib`), `os` (`version`, `kernelRelease`), `jvm` (`version`, `release`, `vendor`,
`vendorVersion`, `name`), `tools` (`perf`, `asyncProfiler`) and `tunables` (`perfEventParanoid`,
`kptrRestrict`, `transparentHugepages`). `cpu.vcpus` SHALL be the number of online logical CPUs, and
`cpu.physicalCores` the number of sockets multiplied by the cores per socket, or empty when either is not
reported. `memory.totalMib` and `memory.swapTotalMib` SHALL be the kernel's KiB totals divided by 1024 and
rounded down. `jvm.release` SHALL be the version number quoted in the JVM's version banner, and
`jvm.version` the banner itself. It SHALL NOT record the job's identity — project, branch, type, job
identifier, creation instant — nor the region or the AWS CLI version. It SHALL also write
`<result-path>/packages.txt` containing `rpm -qa`. Both SHALL be uploaded before the benchmark process
starts. Every value SHALL be captured into a shell variable before the file is written, and the
machine-observed result tags SHALL be read from the same variables.

#### Scenario: Manifest accompanies a successful job
- **WHEN** a benchmark completes
- **THEN** `<result-path>/environment.json` and `<result-path>/packages.txt` exist alongside the job output

#### Scenario: Manifest survives a failed job
- **WHEN** the benchmark process exits non-zero
- **THEN** `<result-path>/environment.json` and `<result-path>/packages.txt` are still present

#### Scenario: The manifest is the environment only
- **WHEN** `environment.json` is read
- **THEN** it has exactly the members `schemaVersion`, `machine`, `cpu`, `memory`, `os`, `jvm`, `tools`
  and `tunables`, and no job identity

#### Scenario: Manifest records what the image does not control
- **WHEN** `environment.json` is read
- **THEN** `machine.instanceType` and `cpu.model` are present, properties of the job rather than of the image

#### Scenario: Logical CPUs and physical cores are told apart
- **WHEN** a job runs on an instance with 8 logical CPUs, one socket and 4 cores per socket
- **THEN** its manifest records `cpu.vcpus` `8`, `cpu.physicalCores` `4` and `cpu.threadsPerCore` `2`, and
  has no `cpu.cores`

#### Scenario: Memory is recorded in whole MiB
- **WHEN** the kernel reports a `MemTotal` of 15882372 KiB and no swap
- **THEN** the manifest records `memory.totalMib` `15510` and `memory.swapTotalMib` `0`, and has no
  `memory.totalKb`

#### Scenario: Tags agree with the manifest
- **WHEN** a job's result carries the tags `jdk` and `cpuModel`
- **THEN** they equal `jvm.release` and `cpu.model` in that job's `environment.json`

### Requirement: Two jobs' environments are compared group by group
`baas jobs diff <jobA> <jobB>` SHALL resolve each job id through the job-ID index, fetch both jobs'
`environment.json`, and compare every field of the seven groups — and nothing else. It SHALL print
first `Differs in:` naming the differing groups, then each differing field with both values, grouped. When
`machine.amiId` differs it SHALL also read both jobs' `packages.txt` and report, as a `packages` group,
the packages whose version changed and those added or removed; when the AMIs are equal it SHALL read no
`packages.txt`. When nothing differs it SHALL say that both jobs were measured on the same environment and
exit 0. Every field SHALL be compared, and printed when it differs, exactly as recorded, without unit
conversion and without mapping a field of one schema version onto another's name. A field present in only
one manifest SHALL be reported as a difference. A `schemaVersion` mismatch SHALL be warned about on
standard error. A job id that resolves to no job SHALL fail naming that id before any S3 read, and a
missing manifest SHALL fail naming the job. It SHALL run under operator credentials and write its payload
to standard output.

#### Scenario: Jobs named by id
- **WHEN** `baas jobs diff <jobIdA> <jobIdB>` runs for two stored jobs
- **THEN** both resolve through the job-ID index and the comparison is printed

#### Scenario: Identical environments report no differences
- **WHEN** two jobs on different branches used the same AMI and instance type
- **THEN** `baas jobs diff` prints that both jobs were measured on the same environment, and exits 0

#### Scenario: A JVM change is named by group
- **WHEN** two jobs used JDK 25.0.3 and 25.0.4
- **THEN** the output starts `Differs in: jvm` and lists `version` and `release` with both values under `jvm`

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

#### Scenario: Memory a few KiB apart is not a difference
- **WHEN** two jobs on the same AMI and instance type ran where the kernel reported `MemTotal` 15882372 and
  15882380 KiB, so both recorded `memory.totalMib` 15510
- **THEN** `baas jobs diff` reports no difference in `memory`

#### Scenario: A real memory difference is printed in MiB
- **WHEN** two jobs recorded `memory.totalMib` 15510 and 15708
- **THEN** `Differs in:` includes `memory`, and `totalMib` is listed with the values `15510` and `15708`,
  as recorded

#### Scenario: Manifests of different schema versions are compared without translation
- **WHEN** a job whose manifest has `schemaVersion` 6 and `cpu.cores` 8 is compared with a schema-7 job
  recording `cpu.vcpus` 8
- **THEN** a schema-version mismatch is warned about on standard error, and `cores` and `vcpus` are each
  listed under `cpu` as present in one job only
