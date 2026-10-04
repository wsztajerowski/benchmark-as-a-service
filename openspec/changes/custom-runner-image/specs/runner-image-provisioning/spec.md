# Spec Delta

## ADDED Requirements

### Requirement: The image is a BaaS-owned base, an optional user extension, and a contract
Every runner image SHALL be built from three parts applied in this order: a base owned by BaaS and
rendered from the definition bundled with the CLI; an optional extension owned by the installation's
operators, which is an AWSTOE component document of any content; and a contract owned by BaaS, which
runs last. The extension SHALL NOT be constrained by any BaaS schema beyond being a valid AWSTOE
document within the size limit. An upgrade of the CLI SHALL change the base and the contract only, and
SHALL NOT change a deployed extension.

#### Scenario: An extension installs a tool BaaS does not know about
- **WHEN** an operator builds an image with an extension that installs an observability agent
- **THEN** the agent is present on instances launched from the resulting image

#### Scenario: An image without an extension
- **WHEN** an image is built and the installation holds no extension
- **THEN** the image contains the base and passes the contract, and no extension step runs

#### Scenario: A CLI upgrade keeps the extension
- **WHEN** an installation with an extension is rebuilt by a newer CLI whose base differs
- **THEN** the new image carries the newer base and the unchanged extension

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
- **THEN** the build succeeds, and runs on the image record `madvise` in `environment.json`

### Requirement: The parent image is resolved from a pinned release in the stack's region
The base SHALL name its parent as one exact Amazon Linux 2023 release, not as a region-bound AMI ID and
not as a selector that follows newer releases. `baas admin build-image` SHALL resolve that release to
exactly one Amazon-owned AMI in the region of the installation's stack, and SHALL fail before
submitting any stack change when the release resolves to no image or to more than one.

#### Scenario: Building outside eu-central-1
- **WHEN** `baas admin build-image` runs for an installation in `us-east-1`
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
be what runs record as `imageVersion`. The same extension content SHALL yield the same label in every
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

### Requirement: The extension is held by the installation and changed only by an explicit push
The deployed extension SHALL be stored verbatim in the installation's stack, so that it can be read
back exactly as it was written. It SHALL be at most 4096 bytes; a larger file SHALL be refused before
any stack change is submitted, naming its size and the limit. A file consisting only of comments and
blank lines SHALL mean that no extension is deployed. Only `baas admin build-image --extension <file>`
SHALL change the deployed extension; every other `build-image` and every `baas admin setup` on an
existing installation SHALL leave it unchanged.

#### Scenario: Pull returns what was pushed
- **WHEN** an extension with comments is pushed and then pulled
- **THEN** the pulled document matches the pushed one apart from the base marker line

#### Scenario: An oversized extension is refused up front
- **WHEN** `baas admin build-image --extension` is given a 5000-byte file
- **THEN** the command exits non-zero naming the size and the 4096-byte limit, and no stack change is
  submitted

#### Scenario: A plain rebuild keeps the extension
- **WHEN** `baas admin build-image` runs without `--extension` on an installation holding an extension
- **THEN** the new image carries the same extension

#### Scenario: Removing the extension
- **WHEN** a file of comments only is pushed with `--extension`
- **THEN** the installation holds no extension and the next image's label is the base version alone

### Requirement: A push based on a stale copy of the extension is refused
A pulled or starter extension file SHALL carry a marker line naming the extension it was based on —
its hash, or `none`. When the installation holds no extension, a push SHALL be accepted whatever the
marker says. When the installation holds an extension, a push SHALL be accepted only when the file's
marker names that extension's hash; otherwise it SHALL be refused before any stack change, naming the
deployed hash and the command that pulls it. The marker line SHALL NOT be part of the stored extension
or of its hash.

#### Scenario: A teammate pushed since the file was pulled
- **WHEN** the installation holds extension `9b1d04aa` and a file marked `3f9a1c2e` is pushed
- **THEN** the command exits non-zero naming `9b1d04aa` and `baas admin image --extension`, and no
  stack change is submitted

#### Scenario: A file kept across a teardown is accepted
- **WHEN** an installation was torn down and set up again, holds no extension, and a file marked
  `3f9a1c2e` is pushed
- **THEN** the push is accepted

#### Scenario: A hand-written file without a marker
- **WHEN** a file with no marker is pushed to an installation that holds an extension
- **THEN** the push is refused

#### Scenario: A deliberate replacement after a pull
- **WHEN** an operator pulls the deployed extension, replaces its whole content, and pushes it
- **THEN** the push is accepted

### Requirement: The base definition ships with the CLI and pins only what it must
`infra/runner-image.yaml` SHALL declare the base's version, its parent as one exact Amazon Linux 2023
release, and the pinned versions of the Amazon Corretto package and async-profiler. It SHALL NOT pin
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

#### Scenario: perf follows the parent's kernel
- **WHEN** an instance launched from an image is inspected
- **THEN** the installed `perf` package matches the running kernel release

## MODIFIED Requirements

### Requirement: The image declares the kernel tunables that affect measurement
`infra/runner-image.yaml` SHALL declare `perf_event_paranoid`, `kptr_restrict`, the transparent hugepage
mode, and the swap state, and the base SHALL apply them so they are in effect at boot. These values
SHALL NOT be left to the parent image's defaults. An extension MAY change the transparent hugepage mode
and the swap state; `perf_event_paranoid` and `kptr_restrict` are additionally bounded by the contract.

#### Scenario: Tunables are declared alongside tool versions
- **WHEN** `infra/runner-image.yaml` is read
- **THEN** it declares `perf_event_paranoid`, `kptr_restrict`, transparent hugepage mode, and swap state

#### Scenario: Tunables are in effect on a launched runner
- **WHEN** an instance launched from an image built without an extension is inspected
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
- **WHEN** an instance launched from an image built without an extension is inspected
- **THEN** `java -version` reports the Corretto version declared in `infra/runner-image.yaml`, and
  `/app/async-profiler/lib/libasyncProfiler.so` exists at the declared version

#### Scenario: Baked async-profiler is at the path the runner defaults to
- **WHEN** `jmh-with-async` runs without an explicit `--async-path`
- **THEN** the runner's default path resolves to the baked async-profiler

### Requirement: Exactly one runner image exists at a time
The system SHALL maintain exactly one runner AMI, published at `/<prefix>/runner/ami-id`. It SHALL NOT
maintain named slots, an AMI history, or any second pointer. Each AMI SHALL carry the tags
`baas-image-version`, holding the image's label, and `baas-parent-ami`.

#### Scenario: A build replaces the previous image
- **WHEN** `baas admin build-image` completes
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
- **THEN** the second run reuses the registered versions and completes

#### Scenario: An extension edit needs no version from the operator
- **WHEN** an operator pushes a changed extension without touching any version
- **THEN** the build proceeds

### Requirement: Every run records the environment it ran on
Before starting the benchmark process, user-data SHALL write `<result-path>/environment.json` recording at
least the image label and AMI ID, the instance type and region, the CPU model and topology, total
memory, the OS version and kernel release, the JVM version, the JVM vendor, the vendor's version string
and the VM name, the baked tool versions, and the kernel tunables in effect. It SHALL additionally record
the run's identity — its project, branch, run identifier and creation instant — so that a run which
stores no measurement remains identifiable from S3 alone. It SHALL also write
`<result-path>/packages.txt` containing `rpm -qa`. Both SHALL be uploaded before the benchmark process
starts. `environment.json` SHALL carry a `schemaVersion` field.

#### Scenario: Manifest accompanies a successful run
- **WHEN** a benchmark completes
- **THEN** `<result-path>/environment.json` and `<result-path>/packages.txt` exist alongside the run output

#### Scenario: Manifest survives a failed run
- **WHEN** the benchmark process exits non-zero
- **THEN** `<result-path>/environment.json` and `<result-path>/packages.txt` are still present

#### Scenario: Manifest identifies a run that stored nothing
- **WHEN** a run fails before writing any measurement
- **THEN** its `environment.json` still records the project, branch, run identifier and creation instant

#### Scenario: Manifest records what the image does not control
- **WHEN** `environment.json` is read
- **THEN** it records the instance type and CPU model, which are properties of the run rather than of the
  image

#### Scenario: Manifest is versioned
- **WHEN** `environment.json` is read
- **THEN** it carries a `schemaVersion` field identifying its structure

#### Scenario: Two vendors' builds of one Java version are told apart
- **WHEN** two runs used Corretto 25.0.4 and another vendor's 25.0.4 and `baas env diff` compares them
- **THEN** the vendor fields are reported as differing

## REMOVED Requirements

### Requirement: Image definition is a versioned repository artifact
**Reason**: Replaced by "The base definition ships with the CLI and pins only what it must". The parent
is no longer an AMI ID bound to one region, `perf` and the AWS CLI are no longer pinned, and the file is
the declaration of the base only, since an extension declares its own tools.
**Migration**: None for operators — only the copy bundled in the CLI is ever read. BaaS developers edit
`infra/runner-image.yaml` as before, under its new keys.
