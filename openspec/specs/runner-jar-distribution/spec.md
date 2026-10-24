# runner-jar-distribution Specification

## Purpose
Makes every job run the runner JAR of the CLI version that launched it, fetched from the working
bucket only and seeded once per version from a checksum-verified release asset. A build with no
release version refuses to launch without an explicit override.

## Requirements

### Requirement: The instance obtains its runner JAR from the working bucket only
User-data SHALL download the runner JAR from the working bucket and SHALL NOT contact any external
host to discover or fetch it. No release-discovery request, and no unpinned "latest" reference,
SHALL remain in the rendered user-data script.

#### Scenario: Rendered user-data reaches no external host for the runner
- **WHEN** the user-data script is rendered for a job
- **THEN** it contains no request to a source-forge or release API, and its only runner-JAR download
  is from the working bucket

#### Scenario: The instance needs no egress to fetch the runner
- **WHEN** a job boots
- **THEN** obtaining the runner JAR requires no connectivity beyond the bucket

### Requirement: The runner artifact is pinned to the launching CLI's version
A job SHALL execute the runner JAR built from the same released version as the CLI that launched it.
The CLI SHALL determine that version from its own packaged metadata, SHALL resolve the object at
`releases/<version>/benchmark-runner.jar`, and SHALL pass that key to the instance.

#### Scenario: Two jobs from one CLI version execute one runner build
- **WHEN** two jobs are launched a week apart by the same CLI version
- **THEN** both execute the same runner JAR object

#### Scenario: The CLI reports the version it pinned
- **WHEN** a job is launched
- **THEN** the CLI reports which runner version the job will execute

### Requirement: The runner artifact slot is seeded once per version, and never silently replaced
When `releases/<version>/benchmark-runner.jar` is absent, the CLI SHALL fetch that version's release
asset and upload it before launching. When the object is already present the CLI SHALL use it as-is
and SHALL NOT overwrite it. The upload SHALL be a write that the bucket refuses when the object
already exists, so that the slot is written at most once even when two launches find it absent at
the same time; a refused upload SHALL be treated as the slot having been seeded, and the launch SHALL
proceed with the existing object.

#### Scenario: First job of a version seeds the slot
- **WHEN** a job is launched with a CLI version whose slot is empty
- **THEN** the asset is fetched, verified and uploaded, and the job proceeds

#### Scenario: Later jobs reuse the slot
- **WHEN** a subsequent job is launched with the same CLI version
- **THEN** no fetch and no upload occur, and the existing object is used

#### Scenario: Seeding failure prevents the launch
- **WHEN** the release asset for the CLI's version cannot be retrieved
- **THEN** the command exits non-zero naming the version, and no EC2 instance is launched

#### Scenario: Two first jobs of a version write the slot once
- **WHEN** two jobs of the same CLI version both find the slot empty, and the first upload completes
  before the second
- **THEN** the second upload is refused by the bucket, the object written by the first is left
  unchanged, and both jobs proceed

### Requirement: The downloaded release asset is verified against a published checksum
The CLI SHALL retrieve a published SHA-256 checksum for the runner release asset and SHALL verify
the downloaded bytes against it before uploading. A mismatch SHALL be a hard failure, and SHALL
leave nothing uploaded.

#### Scenario: A tampered download is rejected
- **WHEN** the downloaded asset does not match its published checksum
- **THEN** the command exits non-zero, uploads nothing, and launches no instance

#### Scenario: Verification precedes upload
- **WHEN** an asset is fetched
- **THEN** it is verified before any object is written to the bucket

#### Scenario: A missing checksum is a failure, not a skip
- **WHEN** the published checksum for the version cannot be retrieved
- **THEN** the command exits non-zero rather than proceeding unverified

### Requirement: A CLI without a released version refuses to run without an explicit override
When the CLI's own version is the unreleased placeholder, `baas run` SHALL fail unless an explicit
runner JAR is supplied. The failure SHALL occur before the benchmark project is built and before any
upload, and SHALL name the option that resolves it. The CLI SHALL NOT fall back to any other runner
artifact.

#### Scenario: A development build fails fast
- **WHEN** `baas run` is invoked from a build whose version is the unreleased placeholder, with no
  explicit runner JAR
- **THEN** the command exits non-zero naming the option to pass, before building the project and
  before uploading anything

#### Scenario: The explicit override is accepted
- **WHEN** the same build is invoked with an explicit runner JAR
- **THEN** the job proceeds and the instance uses that JAR

#### Scenario: No unreleased artifact reaches the release prefix
- **WHEN** a development build launches a job with an explicit runner JAR
- **THEN** nothing is written under `releases/`

### Requirement: The release publishes correctly versioned artifacts and a checksum
The release process SHALL publish, as release assets, the CLI JAR, the runner JAR, a SHA-256 checksum
for each of those JARs, and the installer script. Every published JAR SHALL be built after the
release version has been set, and every published checksum SHALL be computed from the artifact built
after that point, so that the version readable from a published artifact is the released version and
its checksum matches it. The CLI JAR SHALL carry its version in packaged metadata readable at run
time. The published installer SHALL carry the version of the release that published it, and that
version SHALL NOT be committed back to the repository, which SHALL retain the unreleased placeholder.

#### Scenario: A published artifact reports its own release version
- **WHEN** the CLI JAR from a release is executed
- **THEN** the version it reports is that release's version, not the unreleased placeholder

#### Scenario: The checksum matches the published JAR
- **WHEN** a published checksum is compared against the published JAR it names
- **THEN** they match

#### Scenario: The CLI JAR is a release asset
- **WHEN** a release is published
- **THEN** its assets include the CLI JAR

#### Scenario: The CLI JAR's checksum is a release asset
- **WHEN** a release is published
- **THEN** its assets include a SHA-256 checksum for the CLI JAR

#### Scenario: The installer is a release asset carrying that release's version
- **WHEN** a release is published
- **THEN** its assets include the installer, and running it with no version requested installs that
  release

#### Scenario: The repository keeps the unreleased placeholder
- **WHEN** a release completes
- **THEN** the installer in the repository still carries the unreleased placeholder version

### Requirement: The runner source repository is a property of the release build
The repository the CLI fetches release assets from SHALL be fixed when the CLI is built, defaulting to
the upstream repository and set by the release to the repository that published it, so that a fork's
release fetches its own runner without editing code. It SHALL NOT be read from any configuration file
or option. A CLI without a released version never fetches a release asset, and so never uses it.

#### Scenario: A fork's release fetches its own runner
- **WHEN** a CLI released from repository `someone/baas-fork` seeds a missing runner slot
- **THEN** it fetches the release asset from `someone/baas-fork`

#### Scenario: A retrieval failure names what it tried
- **WHEN** the release asset cannot be retrieved
- **THEN** the error names the repository and version it attempted, and names no configuration
  setting to change
