## ADDED Requirements

### Requirement: Image definition is a versioned repository artifact
`infra/runner-image.yaml` SHALL declare the image version, the pinned versions of every tool baked into
the image (Amazon Corretto, async-profiler, AWS CLI), and the exact parent AL2023 AMI ID. It SHALL ship
as a `baas-cli` classpath resource so the CLI can render it without repository access.

#### Scenario: Image definition ships in the JAR
- **WHEN** `baas-cli.jar` is inspected
- **THEN** it contains `/templates/runner-image.yaml`

#### Scenario: Parent image is pinned by ID, not by selector
- **WHEN** `infra/runner-image.yaml` is read
- **THEN** the parent image is an exact `ami-` identifier and not a `x.x.x` semantic-version selector

### Requirement: The runner installs no tooling at run time
The user-data script SHALL NOT invoke `yum update`, install a JDK, or download async-profiler. Every
tool the benchmark run requires SHALL already be present in the AMI.

#### Scenario: User-data performs no package installation
- **WHEN** `UserDataScriptBuilder.build(...)` output is decoded
- **THEN** it contains no `yum` invocation and no async-profiler download

#### Scenario: Baked tooling is present and pinned
- **WHEN** an instance launched from the current AMI is inspected
- **THEN** `java -version` reports the Corretto version declared in `infra/runner-image.yaml`, and
  `/app/async-profiler` exists at the declared version

### Requirement: Recipe version is bumped automatically when content changes
`baas admin build-image` SHALL render the Image Builder recipe and component from
`infra/runner-image.yaml` and SHALL bump the recipe's patch version whenever the rendered content differs
from the version currently registered. When the rendered content is unchanged, it SHALL NOT create a new
recipe version.

#### Scenario: Editing a tool version requires no manual version bump
- **WHEN** a user changes only the async-profiler version in `infra/runner-image.yaml` and runs
  `baas admin build-image`
- **THEN** the command registers a new recipe version and completes without a CloudFormation
  version-collision error

#### Scenario: Unchanged content does not create a version
- **WHEN** `baas admin build-image` runs twice with no edit between runs
- **THEN** the second run reuses the existing recipe version

### Requirement: Two AMI slots with independent pointers
The system SHALL maintain exactly two AMI slots. The `current` slot SHALL hold the newest image built
from `infra/runner-image.yaml`, and the `adhoc` slot SHALL hold at most one image rebuilt from an
archived template. Their AMI IDs SHALL be published at `/<prefix>/runner/ami-id` and
`/<prefix>/runner/adhoc-ami-id`. Every AMI SHALL carry the tags `baas-image-version`, `baas-slot`, and
`baas-parent-ami`.

#### Scenario: Current build replaces only the current slot
- **WHEN** `baas admin build-image` completes while the adhoc slot is occupied
- **THEN** `/<prefix>/runner/ami-id` points at the new AMI and `/<prefix>/runner/adhoc-ami-id` is
  unchanged

#### Scenario: Adhoc build replaces only the adhoc slot
- **WHEN** `baas admin build-image --from-version 1.2.0` completes
- **THEN** `/<prefix>/runner/adhoc-ami-id` points at the new AMI and `/<prefix>/runner/ami-id` is
  unchanged

#### Scenario: Slot is discoverable from the AMI itself
- **WHEN** an AMI produced by either build path is described
- **THEN** its tags include `baas-slot` naming its slot and `baas-image-version` naming its version

### Requirement: A slot's previous occupant is pruned at build start
A build SHALL deregister the AMI previously occupying its target slot and delete that AMI's snapshots
**before** starting the new build, so pruning cannot race a `baas run` that has already resolved an AMI
ID.

#### Scenario: Previous AMI and snapshot are removed
- **WHEN** `baas admin build-image` runs while the current slot holds AMI `ami-old`
- **THEN** `ami-old` is deregistered and its snapshots are deleted before the new build is triggered

#### Scenario: Pruning does not affect the other slot
- **WHEN** an adhoc build prunes the adhoc slot
- **THEN** the AMI in the current slot and its snapshots still exist

#### Scenario: `--clear-adhoc` empties the adhoc slot
- **WHEN** `baas admin build-image --clear-adhoc` runs with the adhoc slot occupied
- **THEN** that AMI is deregistered, its snapshots are deleted, and
  `/<prefix>/runner/adhoc-ami-id` no longer resolves to an existing image

### Requirement: Build provenance is archived to S3
Each successful build SHALL upload to the results bucket, under `images/by-version/<imageVersion>/`: the
rendered `runner-image.yaml`, the rendered Image Builder `component.yaml`, a `packages.txt` containing
`rpm -qa` captured from the built instance, and a `build.json` recording the resulting AMI ID, the exact
parent AMI ID, artifact checksums, the region, and the build timestamp. It SHALL also write a pointer
object at `images/by-ami/<amiId>` whose content identifies the image version.

#### Scenario: Archive is complete after a build
- **WHEN** `baas admin build-image` completes for version `1.3.0`
- **THEN** `images/by-version/1.3.0/` contains `runner-image.yaml`, `component.yaml`, `packages.txt`, and
  `build.json`

#### Scenario: AMI ID resolves to its version without listing
- **WHEN** the version for a known AMI ID is looked up
- **THEN** it is obtained with a single `GetObject` on `images/by-ami/<amiId>`

#### Scenario: No mutable shared index exists
- **WHEN** the `images/` prefix is listed
- **THEN** it contains no aggregate index object that concurrent builds would have to read and rewrite

### Requirement: A historical image can be rebuilt from its archive
`baas admin build-image --from-version <v>` SHALL fetch the archived template for `<v>`, register its
recipe if that version is not already registered in the account, build the image into the `adhoc` slot,
and SHALL NOT modify the CloudFormation stack.

#### Scenario: Rebuild from an archived version
- **WHEN** `baas admin build-image --from-version 1.2.0` runs and `images/by-version/1.2.0/` exists
- **THEN** an AMI is produced from that archived template and published to the adhoc slot

#### Scenario: Rebuild leaves infrastructure untouched
- **WHEN** an adhoc rebuild completes
- **THEN** the core stack's last-updated time is unchanged

#### Scenario: Unknown version fails clearly
- **WHEN** `baas admin build-image --from-version 9.9.9` runs and no such archive exists
- **THEN** the command exits non-zero naming the version and the `images/by-version/` prefix it searched

### Requirement: Rebuild drift is detected and reported
A rebuild SHALL capture `rpm -qa` from the newly built instance and compare it against the
`packages.txt` archived for that version. When the two differ, the resulting AMI SHALL be marked as
drifted and the difference SHALL be reported to the operator.

#### Scenario: Divergent rebuild is flagged
- **WHEN** a rebuild of version `1.2.0` produces a package set differing from the archived manifest
- **THEN** the command reports the differing packages and the resulting AMI is marked drifted

#### Scenario: Faithful rebuild is not flagged
- **WHEN** a rebuild produces a package set identical to the archived manifest
- **THEN** the resulting AMI is not marked drifted

### Requirement: Every result records the image that produced it
`baas run` SHALL resolve the selected AMI's image metadata and pass `amiId`, `imageVersion`, and
`imageSlot` into user-data, which SHALL forward them to the runner as result tags. When the selected AMI
is marked drifted, `imageDrifted=true` SHALL also be recorded. `baas results` SHALL surface drift and
SHALL NOT exclude drifted results automatically.

#### Scenario: Image tags appear on results
- **WHEN** a benchmark completes on the current AMI
- **THEN** its stored result carries `amiId`, `imageVersion`, and `imageSlot=current` tags

#### Scenario: Drifted adhoc run is distinguishable
- **WHEN** a benchmark completes on a drifted adhoc AMI
- **THEN** its stored result carries `imageSlot=adhoc` and `imageDrifted=true`

#### Scenario: Drift is reported, not filtered
- **WHEN** `baas results` returns rows that include a drifted run
- **THEN** the drift is visible in the output and the row is still present
