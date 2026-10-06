# Spec Delta

## MODIFIED Requirements

### Requirement: Exactly one runner image exists at a time
The system SHALL maintain exactly one runner AMI, published at `/<prefix>/runner/ami-id`. It SHALL NOT
maintain named slots, an AMI history, or any second pointer. Each AMI SHALL carry the tags
`baas-image-version`, holding the image's label, and `baas-parent-ami`.

#### Scenario: A build replaces the previous image
- **WHEN** `baas admin image build` completes
- **THEN** `/<prefix>/runner/ami-id` names the new AMI, and the AMI it replaced is deregistered and its
  snapshots deleted

#### Scenario: The pointer is repointed before the old image is removed
- **WHEN** a build completes
- **THEN** the new AMI ID is written to `/<prefix>/runner/ami-id` before the previous AMI is deregistered

#### Scenario: Image identity is discoverable from the AMI itself
- **WHEN** the current AMI is described
- **THEN** its tags include `baas-image-version` naming its label and `baas-parent-ami` naming the exact
  parent image it was built from

#### Scenario: A failed build leaves the previous image in place
- **WHEN** an image build fails, including a failure of the contract
- **THEN** `/<prefix>/runner/ami-id` is unchanged and the previous AMI is still registered

#### Scenario: A build that fails its contract leaves no image of its own
- **WHEN** an image build fails in its test stage, after its AMI was registered
- **THEN** that AMI is deregistered and its snapshots deleted, so only the published image remains

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
- **WHEN** `baas admin image build` runs twice with no edit between runs
- **THEN** the second job reuses the registered versions and completes

#### Scenario: An extension edit needs no version from the operator
- **WHEN** an operator pushes a changed extension without touching any version
- **THEN** the build proceeds

### Requirement: Results carry coarse environment tags
`baas run` SHALL record `imageVersion` and `instanceType` as result tags so that results can be shown,
filtered and grouped by environment from the results store alone, without fetching any S3 object. The
tags map is free-form, so this SHALL require no schema change. `baas results query` SHALL NOT warn about rows
whose environments differ: comparing two jobs' environments is what `baas jobs diff` is for.

#### Scenario: Tags appear on stored results
- **WHEN** a benchmark completes on the current AMI
- **THEN** its stored result carries `imageVersion` and `instanceType` tags

#### Scenario: Results can be sliced by environment without S3 access
- **WHEN** `baas results query --tag imageVersion=1.2.0` is invoked
- **THEN** only results measured on image 1.2.0 are returned, and no S3 object is read

#### Scenario: Differing environments raise no warning
- **WHEN** `baas results query` returns rows carrying differing `imageVersion` values
- **THEN** every row is printed and no environment warning is emitted

### Requirement: The parent image is resolved from a pinned release in the stack's region
The base SHALL name its parent as one exact Amazon Linux 2023 release, not as a region-bound AMI ID and
not as a selector that follows newer releases. `baas admin image build` SHALL resolve that release to
exactly one Amazon-owned AMI in the region of the deployment's stack, and SHALL fail before
submitting any stack change when the release resolves to no image or to more than one.

#### Scenario: Building outside eu-central-1
- **WHEN** `baas admin image build` runs for a deployment in `us-east-1`
- **THEN** the parent is the pinned release's AMI in `us-east-1` and the build proceeds

#### Scenario: A release not published in the region
- **WHEN** the pinned release resolves to no image in the stack's region
- **THEN** the command exits non-zero naming the release and the region, and no stack change is
  submitted

#### Scenario: The resolved parent is recorded
- **WHEN** an image is built
- **THEN** the AMI's `baas-parent-ami` tag names the exact AMI ID the release resolved to

### Requirement: The image label identifies the base and the extension
Each image SHALL carry a label equal to the base's version when no extension is deployed, and to
`<base version>+ext.<first 8 hex characters of the SHA-256 of the extension>` otherwise. The label SHALL
be written to `/etc/baas-image-version` on the image and to the AMI's `baas-image-version` tag, and SHALL
be what jobs record as `imageVersion`. The same extension content SHALL yield the same label in every
deployment.

#### Scenario: A stock image is labelled by its base version
- **WHEN** an image is built with base version `1.3.0` and no extension
- **THEN** its label is `1.3.0`

#### Scenario: An extended image never shares a stock image's label
- **WHEN** an image is built with base version `1.3.0` and an extension
- **THEN** its label is `1.3.0+ext.` followed by eight hex characters, and results from it are not
  returned by `baas results query --tag imageVersion=1.3.0`

#### Scenario: Editing the extension changes the label
- **WHEN** the extension's content changes and the image is rebuilt
- **THEN** the new image's label differs from the previous one

### Requirement: The extension is held by the deployment and changed only by an explicit push
The deployed extension SHALL be stored in the deployment's stack as written, apart from trailing
whitespace, so that it can be read back exactly as stored. It SHALL be at most 4096 bytes and SHALL
contain only printable ASCII, tabs and line breaks, because the stack does not read other characters
back as written; a file that breaks either rule SHALL be refused before any stack change is
submitted, naming its size and the limit, or the offending character's line and column. A file consisting only of comments and
blank lines SHALL mean that no extension is deployed. Only `baas admin image build --extension <file>`
SHALL change the deployed extension; every other `build-image` and every `baas admin deployment setup` on an
existing deployment SHALL leave it unchanged.

#### Scenario: Pull returns what was pushed
- **WHEN** an extension with comments is pushed and then pulled
- **THEN** the pulled document matches the pushed one apart from the base marker line and trailing
  whitespace

#### Scenario: A non-ASCII character is refused up front
- **WHEN** `baas admin image build --extension` is given a file containing an em dash
- **THEN** the command exits non-zero naming the character's line and column, and no stack change is
  submitted

#### Scenario: An oversized extension is refused up front
- **WHEN** `baas admin image build --extension` is given a 5000-byte file
- **THEN** the command exits non-zero naming the size and the 4096-byte limit, and no stack change is
  submitted

#### Scenario: A plain rebuild keeps the extension
- **WHEN** `baas admin image build` runs without `--extension` on a deployment holding an extension
- **THEN** the new image carries the same extension

#### Scenario: Removing the extension
- **WHEN** a file of comments only is pushed with `--extension`
- **THEN** the deployment holds no extension and the next image's label is the base version alone

### Requirement: A push based on a stale copy of the extension is refused
A pulled or starter extension file SHALL carry a marker line naming the extension it was based on —
its hash, or `none`. When the deployment holds no extension, a push SHALL be accepted whatever the
marker says. When the deployment holds an extension, a push SHALL be accepted only when the file's
marker names that extension's hash; otherwise it SHALL be refused before any stack change, naming the
deployed hash and the command that pulls it. The marker line SHALL NOT be part of the stored extension
or of its hash.

#### Scenario: A teammate pushed since the file was pulled
- **WHEN** the deployment holds extension `9b1d04aa` and a file marked `3f9a1c2e` is pushed
- **THEN** the command exits non-zero naming `9b1d04aa` and `baas admin image show --extension`, and no
  stack change is submitted

#### Scenario: A file kept across a teardown is accepted
- **WHEN** a deployment was torn down and set up again, holds no extension, and a file marked
  `3f9a1c2e` is pushed
- **THEN** the push is accepted

#### Scenario: A hand-written file without a marker
- **WHEN** a file with no marker is pushed to a deployment that holds an extension
- **THEN** the push is refused

#### Scenario: A deliberate replacement after a pull
- **WHEN** an operator pulls the deployed extension, replaces its whole content, and pushes it
- **THEN** the push is accepted

## REMOVED Requirements

### Requirement: Every job records the environment it ran on
**Reason**: The manifest becomes the environment only, grouped, and stops carrying the job's identity, which the job item and tags already hold.
**Migration**: See *Every job records its environment, grouped*; identity is read from the job item (`baas jobs show`).

### Requirement: Environments can be compared field by field
**Reason**: The comparison moves to `baas jobs diff`, compares only the environment, by group, adds packages when the AMIs differ, and no longer accepts a literal result path.
**Migration**: See *Two jobs' environments are compared group by group*.

## ADDED Requirements

### Requirement: Every job records its environment, grouped
Before starting the benchmark process, user-data SHALL write `<result-path>/environment.json` carrying
`schemaVersion` 6 and the measurement environment in seven groups: `machine` (`imageVersion`, `amiId`,
`instanceType`), `cpu` (`model`, `arch`, `cores`, `threadsPerCore`, `maxMhz`), `memory` (`totalKb`,
`swapTotalKb`), `os` (`version`, `kernelRelease`), `jvm` (`version`, `vendor`, `vendorVersion`, `name`),
`tools` (`perf`, `asyncProfiler`) and `tunables` (`perfEventParanoid`, `kptrRestrict`,
`transparentHugepages`). It SHALL NOT record the job's identity — project, branch, type, job identifier,
creation instant — nor the region or the AWS CLI version. It SHALL also write `<result-path>/packages.txt`
containing `rpm -qa`. Both SHALL be uploaded before the benchmark process starts. Every value SHALL be
captured into a shell variable before the file is written, and the machine-observed result tags SHALL be
read from the same variables.

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

#### Scenario: Tags agree with the manifest
- **WHEN** a job's result carries the tags `jdk` and `cpuModel`
- **THEN** they equal `jvm.version` and `cpu.model` in that job's `environment.json`

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
- **WHEN** `config.yaml` sets both `aws.profile` and `aws.operatorProfile` and `baas jobs diff` runs
- **THEN** AWS clients are built from `aws.operatorProfile`
