# runner-image-provisioning Specification

## Purpose

The measurement environment itself: the pinned runner AMI that every benchmark boots from, how it is
declared (`infra/runner-image.yaml`), how it is built and replaced (`baas admin build-image`), and how
each run records the environment it actually measured on so two results can be shown comparable — or
shown not to be.

## Requirements

### Requirement: Image definition is a versioned repository artifact
`infra/runner-image.yaml` SHALL declare the image version, the pinned versions of every tool baked into
the image (Amazon Corretto, async-profiler, `perf`, AWS CLI), and the exact parent AL2023 AMI ID. It SHALL
ship as a `baas-cli` classpath resource so the CLI can render it without repository access. The file SHALL
be the only place a tool version is declared.

#### Scenario: Image definition ships in the JAR
- **WHEN** `baas-cli.jar` is inspected
- **THEN** it contains `/templates/runner-image.yaml`

#### Scenario: Parent image is pinned by ID, not by selector
- **WHEN** `infra/runner-image.yaml` is read
- **THEN** the parent image is an exact `ami-` identifier and not a `x.x.x` semantic-version selector

#### Scenario: Changing a tool version is a one-line edit
- **WHEN** a user changes the async-profiler version
- **THEN** the only file requiring an edit is `infra/runner-image.yaml`

### Requirement: The image declares the kernel tunables that affect measurement
`infra/runner-image.yaml` SHALL declare `perf_event_paranoid`, `kptr_restrict`, the transparent hugepage
mode, and the swap state, and the image build SHALL apply them so they are in effect at boot. These values
SHALL NOT be left to the base image's defaults.

#### Scenario: Tunables are declared alongside tool versions
- **WHEN** `infra/runner-image.yaml` is read
- **THEN** it declares `perf_event_paranoid`, `kptr_restrict`, transparent hugepage mode, and swap state

#### Scenario: Tunables are in effect on a launched runner
- **WHEN** an instance launched from the current AMI is inspected
- **THEN** the running kernel reports the values declared in `infra/runner-image.yaml`

#### Scenario: async-profiler can walk kernel stacks
- **WHEN** a `jmh-with-async` benchmark runs on the current AMI
- **THEN** profiling succeeds without a permissions error from `perf_event_open`

### Requirement: The runner installs no tooling at run time
The user-data script SHALL NOT invoke `yum update`, install a JDK, or download async-profiler. Every tool
the benchmark run requires SHALL already be present in the AMI.

#### Scenario: User-data performs no package installation
- **WHEN** `UserDataScriptBuilder.build(...)` output is decoded
- **THEN** it contains no `yum` invocation and no async-profiler download

#### Scenario: Baked tooling is present and pinned
- **WHEN** an instance launched from the current AMI is inspected
- **THEN** `java -version` reports the Corretto version declared in `infra/runner-image.yaml`, and
  `/app/async-profiler/lib/libasyncProfiler.so` exists at the declared version

#### Scenario: Baked async-profiler is at the path the runner defaults to
- **WHEN** `jmh-with-async` runs without an explicit `--async-path`
- **THEN** the runner's default path resolves to the baked async-profiler

### Requirement: Exactly one runner image exists at a time
The system SHALL maintain exactly one runner AMI, published at `/<prefix>/runner/ami-id`. It SHALL NOT
maintain named slots, an AMI history, or any second pointer. Each AMI SHALL carry the tags
`baas-image-version` and `baas-parent-ami`.

#### Scenario: A build replaces the previous image
- **WHEN** `baas admin build-image` completes
- **THEN** `/<prefix>/runner/ami-id` names the new AMI, and the AMI it replaced is deregistered and its
  snapshots deleted

#### Scenario: The pointer is repointed before the old image is removed
- **WHEN** a build completes
- **THEN** the new AMI ID is written to `/<prefix>/runner/ami-id` before the previous AMI is deregistered

#### Scenario: Image identity is discoverable from the AMI itself
- **WHEN** the current AMI is described
- **THEN** its tags include `baas-image-version` naming its version and `baas-parent-ami` naming the exact
  parent image it was built from

#### Scenario: A failed build leaves the previous image in place
- **WHEN** an image build fails
- **THEN** `/<prefix>/runner/ami-id` is unchanged and the previous AMI is still registered

### Requirement: The image version is bumped by hand and validated before building
`baas admin build-image` SHALL render the Image Builder recipe and component from
`infra/runner-image.yaml` using the `imageVersion` declared in that file. When that version is already
registered with different content, the command SHALL fail before starting a build, naming the field to
edit. It SHALL NOT derive or auto-increment a version.

#### Scenario: Stale version is rejected with an actionable message
- **WHEN** a user edits a tool version but not `imageVersion`, and runs `baas admin build-image`
- **THEN** the command exits non-zero, names `imageVersion` in `infra/runner-image.yaml`, and no build is
  started

#### Scenario: Unchanged content rebuilds without a version bump
- **WHEN** `baas admin build-image` runs twice with no edit between runs
- **THEN** the second run reuses the registered recipe version and completes

### Requirement: Every run records the environment it ran on
Before starting the benchmark process, user-data SHALL write `<result-path>/environment.json` recording at
least the image version and AMI ID, the instance type and region, the CPU model and topology, total
memory, the OS version and kernel release, the JVM version, the baked tool versions, and the kernel
tunables in effect. It SHALL also write `<result-path>/packages.txt` containing `rpm -qa`. Both SHALL be
uploaded before the benchmark process starts. `environment.json` SHALL carry a `schemaVersion` field.

#### Scenario: Manifest accompanies a successful run
- **WHEN** a benchmark completes
- **THEN** `<result-path>/environment.json` and `<result-path>/packages.txt` exist alongside the run output

#### Scenario: Manifest survives a failed run
- **WHEN** the benchmark process exits non-zero
- **THEN** `<result-path>/environment.json` and `<result-path>/packages.txt` are still present

#### Scenario: Manifest records what the image does not control
- **WHEN** `environment.json` is read
- **THEN** it records the instance type and CPU model, which are properties of the run rather than of the
  image

#### Scenario: Manifest is versioned
- **WHEN** `environment.json` is read
- **THEN** it carries a `schemaVersion` field identifying its structure

### Requirement: Results carry coarse environment tags
`baas run` SHALL record `imageVersion` and `instanceType` as result tags so that environment differences
are detectable from the results store alone, without fetching any S3 object. `BenchmarkMetadata.tags` is a
free-form `Map<String,String>`, so this SHALL require no schema change.

#### Scenario: Tags appear on stored results
- **WHEN** a benchmark completes on the current AMI
- **THEN** its stored result carries `imageVersion` and `instanceType` tags

#### Scenario: Mismatched environments are visible without S3 access
- **WHEN** `baas results` returns a comparison group whose rows carry differing `imageVersion` values
- **THEN** the difference is surfaced in the output

#### Scenario: Differing environments are reported, not filtered
- **WHEN** a comparison group contains rows from two different image versions
- **THEN** both rows are still present in the output

### Requirement: Environments can be compared field by field
`baas env diff <resultPathA> <resultPathB>` SHALL fetch both runs' `environment.json` from the results
bucket and report the fields that differ. It SHALL run under operator credentials, consistent with the
other read-only day-to-day commands. Command payload SHALL be written to `System.out` so it remains
pipeable.

#### Scenario: Differing fields are reported
- **WHEN** two runs used different JDK patch levels
- **THEN** `baas env diff` reports the JDK field with both values

#### Scenario: Identical environments report no differences
- **WHEN** two runs used the same image version on the same instance type
- **THEN** `baas env diff` reports no differing fields and exits 0

#### Scenario: Missing manifest fails clearly
- **WHEN** one of the given result paths has no `environment.json`
- **THEN** the command exits non-zero naming the path it could not read

#### Scenario: Diff uses operator credentials
- **WHEN** `config.yaml` sets both `aws.profile` and `aws.operatorProfile` and `baas env diff` runs
- **THEN** AWS clients are built from `aws.operatorProfile`
