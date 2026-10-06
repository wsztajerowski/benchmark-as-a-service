# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: Every run records the environment it ran on`
- TO: `### Requirement: Every job records the environment it ran on`

## MODIFIED Requirements

### Requirement: The runner installs no tooling at run time
The user-data script SHALL NOT invoke `yum update`, install a JDK, or download async-profiler. Every tool
the benchmark job requires SHALL already be present in the AMI.

#### Scenario: User-data performs no package installation
- **WHEN** `UserDataScriptBuilder.build(...)` output is decoded
- **THEN** it contains no `yum` invocation and no async-profiler download

#### Scenario: Baked tooling is present and pinned
- **WHEN** an instance launched from an image built without an extension is inspected
- **THEN** `java -version` reports the Corretto version declared in `infra/runner-image.yaml`, and
  `/app/async-profiler/lib/libasyncProfiler.so` exists at the declared version

#### Scenario: Baked async-profiler is at the path the runner defaults to
- **WHEN** `jmh-with-async` runs without an explicit `--async-path`
- **THEN** the runner's default path resolves to the baked async-profiler

### Requirement: The image version is bumped by hand and validated before building
The base's version SHALL be the `imageVersion` declared in `infra/runner-image.yaml`, bumped by hand
whenever the base changes. When that version is already registered with a different base, `baas admin
build-image` SHALL fail before starting a build, naming the field to edit. The versions Image Builder
requires for the extension and for the recipe SHALL be derived by the CLI whenever their content
changes, and SHALL NOT require any edit by the operator.

#### Scenario: Stale version is rejected with an actionable message
- **WHEN** a BaaS developer edits a base tool version but not `imageVersion`, and runs `baas admin
  build-image`
- **THEN** the command exits non-zero, names `imageVersion` in `infra/runner-image.yaml`, and no build is
  started

#### Scenario: Unchanged content rebuilds without a version bump
- **WHEN** `baas admin build-image` runs twice with no edit between runs
- **THEN** the second job reuses the registered versions and completes

#### Scenario: An extension edit needs no version from the operator
- **WHEN** an operator pushes a changed extension without touching any version
- **THEN** the build proceeds

### Requirement: Every job records the environment it ran on
Before starting the benchmark process, user-data SHALL write `<result-path>/environment.json` recording at
least the image label and AMI ID, the instance type and region, the CPU model and topology, total
memory, the OS version and kernel release, the JVM version, the JVM vendor, the vendor's version string
and the VM name, the baked tool versions, and the kernel tunables in effect. It SHALL additionally record
the job's identity — its project, branch, job identifier and creation instant — so that a job which
stores no measurement remains identifiable from S3 alone. It SHALL also write
`<result-path>/packages.txt` containing `rpm -qa`. Both SHALL be uploaded before the benchmark process
starts. `environment.json` SHALL carry a `schemaVersion` field.

#### Scenario: Manifest accompanies a successful run
- **WHEN** a benchmark completes
- **THEN** `<result-path>/environment.json` and `<result-path>/packages.txt` exist alongside the job output

#### Scenario: Manifest survives a failed run
- **WHEN** the benchmark process exits non-zero
- **THEN** `<result-path>/environment.json` and `<result-path>/packages.txt` are still present

#### Scenario: Manifest identifies a run that stored nothing
- **WHEN** a job fails before writing any measurement
- **THEN** its `environment.json` still records the project, branch, job identifier and creation instant

#### Scenario: Manifest records what the image does not control
- **WHEN** `environment.json` is read
- **THEN** it records the instance type and CPU model, which are properties of the job rather than of the
  image

#### Scenario: Manifest is versioned
- **WHEN** `environment.json` is read
- **THEN** it carries a `schemaVersion` field identifying its structure

#### Scenario: Two vendors' builds of one Java version are told apart
- **WHEN** two jobs used Corretto 25.0.4 and another vendor's 25.0.4 and `baas env diff` compares them
- **THEN** the vendor fields are reported as differing

### Requirement: Results carry coarse environment tags
`baas run` SHALL record `imageVersion` and `instanceType` as result tags so that results can be shown,
filtered and grouped by environment from the results store alone, without fetching any S3 object. The
tags map is free-form, so this SHALL require no schema change. `baas results` SHALL NOT warn about rows
whose environments differ: comparing two jobs' environments is what `baas env diff` is for.

#### Scenario: Tags appear on stored results
- **WHEN** a benchmark completes on the current AMI
- **THEN** its stored result carries `imageVersion` and `instanceType` tags

#### Scenario: Results can be sliced by environment without S3 access
- **WHEN** `baas results --tag imageVersion=1.2.0` is invoked
- **THEN** only results measured on image 1.2.0 are returned, and no S3 object is read

#### Scenario: Differing environments raise no warning
- **WHEN** `baas results` returns rows carrying differing `imageVersion` values
- **THEN** every row is printed and no environment warning is emitted

### Requirement: Environments can be compared field by field
`baas env diff <runA> <runB>` SHALL fetch both jobs' `environment.json` from the results bucket and
report the fields that differ. Each job SHALL be accepted either as its job id, resolved to the stored
result path through `jobId-index` exactly as `baas download` resolves one, or as a literal result
path. A job id that resolves to no stored job SHALL fail naming that id, before any S3 read. It SHALL run under operator credentials, consistent with the
other read-only day-to-day commands. Command payload SHALL be written to `System.out` so it remains
pipeable.


#### Scenario: Runs named by id
- **WHEN** `baas env diff <jobIdA> <jobIdB>` runs for two stored jobs
- **THEN** both resolve to their stored result paths and the differing fields are reported

#### Scenario: An unknown run id
- **WHEN** one argument has the job-id shape but names no stored job
- **THEN** the command exits non-zero naming that id, and reads nothing from S3

#### Scenario: Differing fields are reported
- **WHEN** two jobs used different JDK patch levels
- **THEN** `baas env diff` reports the JDK field with both values

#### Scenario: Identical environments report no differences
- **WHEN** two jobs used the same image version on the same instance type
- **THEN** `baas env diff` reports no differing fields and exits 0

#### Scenario: Missing manifest fails clearly
- **WHEN** one of the given result paths has no `environment.json`
- **THEN** the command exits non-zero naming the path it could not read

#### Scenario: Diff uses operator credentials
- **WHEN** `config.yaml` sets both `aws.profile` and `aws.operatorProfile` and `baas env diff` runs
- **THEN** AWS clients are built from `aws.operatorProfile`

### Requirement: The contract fails a bake that breaks a BaaS workflow
The contract SHALL verify, on an instance booted from the newly built image, that: a `java` is on
`PATH` and its specification version is at least the Java version the runner is compiled for; `aws`
runs; async-profiler's library and `asprof` are present at the path the runner defaults to; `perf`
runs and matches the running kernel; `/etc/baas-image-version` exists; `kernel.perf_event_paranoid` is
at most 1; and `kernel.kptr_restrict` is 0. When any check fails, the build SHALL fail, the pointer
SHALL remain unchanged, and the failure SHALL name the check. The contract SHALL NOT constrain the JVM
vendor, a JVM version above the floor, the transparent hugepage mode or the swap state.

#### Scenario: An extension that removes async-profiler fails the bake
- **WHEN** an extension deletes the async-profiler installation and an image is built
- **THEN** the build fails naming the async-profiler check, and `/<prefix>/runner/ami-id` still names
  the previous image

#### Scenario: An extension that replaces the JVM with another of the same version
- **WHEN** an extension removes Corretto and installs another vendor's Java 25
- **THEN** the build succeeds

#### Scenario: A JVM below the floor fails the bake
- **WHEN** an extension leaves only a Java 17 on `PATH`
- **THEN** the build fails naming the Java check

#### Scenario: A kernel upgrade that strands perf fails the bake
- **WHEN** an extension upgrades the kernel after the base installed `perf`
- **THEN** the build fails naming the `perf` check

#### Scenario: Measurement choices are the extension's to change
- **WHEN** an extension sets transparent hugepages to `madvise`
- **THEN** the build succeeds, and jobs on the image record `madvise` in `environment.json`

### Requirement: The image label identifies the base and the extension
Each image SHALL carry a label equal to the base's version when no extension is deployed, and to
`<base version>+ext.<first 8 hex characters of the SHA-256 of the extension>` otherwise. The label SHALL
be written to `/etc/baas-image-version` on the image and to the AMI's `baas-image-version` tag, and SHALL
be what jobs record as `imageVersion`. The same extension content SHALL yield the same label in every
installation.

#### Scenario: A stock image is labelled by its base version
- **WHEN** an image is built with base version `1.3.0` and no extension
- **THEN** its label is `1.3.0`

#### Scenario: An extended image never shares a stock image's label
- **WHEN** an image is built with base version `1.3.0` and an extension
- **THEN** its label is `1.3.0+ext.` followed by eight hex characters, and results from it are not
  returned by `baas results --tag imageVersion=1.3.0`

#### Scenario: Editing the extension changes the label
- **WHEN** the extension's content changes and the image is rebuilt
- **THEN** the new image's label differs from the previous one
