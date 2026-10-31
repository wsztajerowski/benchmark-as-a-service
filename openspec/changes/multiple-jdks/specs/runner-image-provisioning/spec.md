# Spec Delta

## MODIFIED Requirements

### Requirement: The runner installs no tooling at run time
The user-data script SHALL NOT invoke `yum update`, install a JDK, or download async-profiler. Every tool
the benchmark job requires — every JDK a job can select included — SHALL already be present in the AMI.

#### Scenario: User-data performs no package deployment
- **WHEN** `UserDataScriptBuilder.build(...)` output is decoded
- **THEN** it contains no `yum` invocation and no async-profiler download

#### Scenario: Baked tooling is present and pinned
- **WHEN** an instance launched from an image built without an extension is inspected
- **THEN** `java -version` reports the Corretto version declared in `infra/runner-image.yaml`, `jcmd` is
  present beside it, and `/app/async-profiler/lib/libasyncProfiler.so` exists at the declared version

#### Scenario: Baked async-profiler is at the path the runner defaults to
- **WHEN** a `jmh` job names `-prof async` without a library path
- **THEN** the library directory the runner puts on the benchmark process's library path holds the baked
  async-profiler

### Requirement: The contract fails a bake that breaks a BaaS workflow
The contract SHALL verify, on an instance booted from the newly built image, that: a `java` is on
`PATH` and its specification version is at least the Java version the runner is compiled for; `aws`
runs; async-profiler's library and `asprof` are present at the path the runner defaults to; `perf`
runs and matches the running kernel; `amazon-ssm-agent` is installed and enabled to start at boot;
`/etc/baas-image-version` exists; `kernel.perf_event_paranoid` is at most 1;
`kernel.kptr_restrict` is 0; and every entry of `/opt/jdks` is a JDK a job can select — its name matches
`[a-z0-9][a-z0-9.-]*` and is at most 32 characters, its `bin/java` runs and its `bin/jcmd` exists — with
the base's default among them and every id together within the length an AMI tag value can hold. The
contract SHALL then record each JDK's id, release, vendor and whether it is the default in the image's
JDK list (see `jdk-selection`). When any check fails, the build SHALL fail, the pointer SHALL remain
unchanged, and the failure SHALL name the check. The contract SHALL NOT constrain the JVM vendor, a
JVM version above the floor, the SSM agent's version, the transparent hugepage mode or the swap state.

#### Scenario: An extension that removes async-profiler fails the bake
- **WHEN** an extension deletes the async-profiler deployment and an image is built
- **THEN** the build fails naming the async-profiler check, and `/<prefix>/runner/ami-id` still names
  the previous image

#### Scenario: An extension that replaces the JVM with another of the same version
- **WHEN** an extension removes Corretto and installs another vendor's Java 25
- **THEN** the build succeeds

#### Scenario: A JVM below the floor fails the bake
- **WHEN** an extension leaves only a Java 17 on `PATH`
- **THEN** the build fails naming the Java check

#### Scenario: A JDK without jcmd fails the bake
- **WHEN** an extension unpacks a JRE, which has no `bin/jcmd`, into `/opt/jdks/some-jre`
- **THEN** the build fails naming the JDK check and `some-jre`

#### Scenario: A malformed JDK id fails the bake
- **WHEN** an extension creates `/opt/jdks/Temurin_26`
- **THEN** the build fails naming the JDK id check

#### Scenario: A kernel upgrade that strands perf fails the bake
- **WHEN** an extension upgrades the kernel after the base installed `perf`
- **THEN** the build fails naming the `perf` check

#### Scenario: Measurement choices are the extension's to change
- **WHEN** an extension sets transparent hugepages to `madvise`
- **THEN** the build succeeds, and jobs on the image record `madvise` in `environment.json`

#### Scenario: An extension that disables the SSM agent fails the bake
- **WHEN** an extension disables or removes `amazon-ssm-agent` and an image is built
- **THEN** the build fails naming the SSM agent check, and `/<prefix>/runner/ami-id` still names the
  previous image

### Requirement: The base definition ships with the CLI and pins only what it must
`infra/runner-image.yaml` SHALL declare the base's version, its parent as one exact Amazon Linux 2023
release, and the pinned versions of the Amazon Corretto package and async-profiler. The Corretto package
SHALL be the full JDK (`-devel`), so `jcmd` is present, and the base SHALL make it the default `java` and
link it as `/opt/jdks/corretto-25`, the image's default JDK. The base SHALL install
`baas-install-jdk <id> <url> <sha256>` on `PATH` for extensions: it SHALL download the archive, refuse it
when its SHA-256 differs, unpack it into `/opt/jdks/<id>`, and refuse an id already present or a JDK
without `bin/jcmd`, failing the bake in each case. It SHALL NOT pin
`perf`, which the base SHALL install for the running kernel, and SHALL NOT pin the AWS CLI, which the
base SHALL take from the parent image. It SHALL ship as a `baas-cli` classpath resource so the CLI can
render it without repository access. The file SHALL be the only place a base tool version is declared;
tools added by an extension are declared in that extension.

#### Scenario: Image definition ships in the JAR
- **WHEN** `baas-cli.jar` is inspected
- **THEN** it contains `/templates/runner-image.yaml`

#### Scenario: Parent image is pinned by release, not by selector or region-bound ID
- **WHEN** `infra/runner-image.yaml` is read
- **THEN** the parent is one exact Amazon Linux 2023 release name and not a selector that resolves to
  the newest release

#### Scenario: Changing a tool version is a one-line edit
- **WHEN** a BaaS developer changes the async-profiler version
- **THEN** the only file requiring an edit is `infra/runner-image.yaml`

#### Scenario: An extension adds a JDK in one line
- **WHEN** an extension runs `baas-install-jdk temurin-26 <url> <sha256>` with the archive's correct digest
- **THEN** `/opt/jdks/temurin-26/bin/java` and `bin/jcmd` exist on the image, and `java` on `PATH` is still
  Corretto

#### Scenario: A wrong digest fails the bake
- **WHEN** an extension runs `baas-install-jdk temurin-26 <url> <sha256>` with a digest the archive does not
  have
- **THEN** the build fails before anything is unpacked into `/opt/jdks`

#### Scenario: perf follows the parent's kernel
- **WHEN** an instance launched from an image is inspected
- **THEN** the installed `perf` package matches the running kernel release

### Requirement: Every job records its environment, grouped
Before starting the benchmark process, user-data SHALL write `<result-path>/environment.json` carrying
`schemaVersion` 8 and the measurement environment in eight groups: `machine` (`imageVersion`, `amiId`,
`instanceType`), `cpu` (`model`, `arch`, `vcpus`, `physicalCores`, `threadsPerCore`, `maxMhz`), `memory`
(`totalMib`, `swapTotalMib`), `os` (`version`, `kernelRelease`), `jdk` (`id`, `home`, `version`, `release`,
`vendor`, `vendorVersion`, `vmName`), `runnerJdk` (`release`, `vendorVersion`), `tools` (`perf`,
`asyncProfiler`) and `tunables` (`perfEventParanoid`, `kptrRestrict`, `transparentHugepages`). `jdk` SHALL
describe the JDK the benchmark runs on — the one the job selected, read from `/opt/jdks/<id>/bin/java` — and
`runnerJdk` the `java` on `PATH` the runner itself runs on. `cpu.vcpus` SHALL be the number of online logical CPUs, and
`cpu.physicalCores` the number of sockets multiplied by the cores per socket, or empty when either is not
reported. `memory.totalMib` and `memory.swapTotalMib` SHALL be the kernel's KiB totals divided by 1024 and
rounded down. `jdk.release` SHALL be the version number quoted in the selected JDK's version banner,
`jdk.version` the banner itself, and `jdk.vmName` its `java.vm.name`. It SHALL NOT list the image's other
JDKs. It SHALL NOT record the job's identity — project, branch, type, job
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
- **THEN** it has exactly the members `schemaVersion`, `machine`, `cpu`, `memory`, `os`, `jdk`,
  `runnerJdk`, `tools` and `tunables`, and no job identity

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
- **WHEN** a job's result carries the tags `jdkId`, `jdkVersion`, `jdkVendor` and `cpuModel`
- **THEN** they equal `jdk.id`, `jdk.release`, `jdk.vendor` and `cpu.model` in that job's `environment.json`

#### Scenario: The selected JDK and the runner's are recorded apart
- **WHEN** `baas run --jdk temurin-26 jmh -- MyBenchmark` runs on an image whose `java` on `PATH` is Corretto 25
- **THEN** `jdk` records `id` `temurin-26`, `home` `/opt/jdks/temurin-26` and Temurin's release and vendor,
  and `runnerJdk` records Corretto's release

### Requirement: Two jobs' environments are compared group by group
`baas jobs diff <jobA> <jobB>` SHALL resolve each job id through the job-ID index, fetch both jobs'
`environment.json`, and compare every field of the eight groups — and nothing else. It SHALL print
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
- **THEN** the output starts `Differs in: jdk` and lists `version` and `release` with both values under `jdk`

#### Scenario: Two vendors' builds of one Java version are told apart
- **WHEN** two jobs used Corretto 25.0.4 and another vendor's 25.0.4
- **THEN** the `jdk` vendor fields are reported as differing

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
