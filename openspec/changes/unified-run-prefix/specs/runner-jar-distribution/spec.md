## ADDED Requirements

### Requirement: The instance obtains its runner JAR from the working bucket only
User-data SHALL download the runner JAR from the working bucket and SHALL NOT contact any external
host to discover or fetch it. No release-discovery request, and no unpinned "latest" reference,
SHALL remain in the rendered user-data script.

#### Scenario: Rendered user-data reaches no external host for the runner
- **WHEN** the user-data script is rendered for a run
- **THEN** it contains no request to a source-forge or release API, and its only runner-JAR download
  is from the working bucket

#### Scenario: The instance needs no egress to fetch the runner
- **WHEN** a run boots
- **THEN** obtaining the runner JAR requires no connectivity beyond the bucket

### Requirement: The runner artifact is pinned to the launching CLI's version
A run SHALL execute the runner JAR built from the same released version as the CLI that launched it.
The CLI SHALL determine that version from its own packaged metadata, SHALL resolve the object at
`releases/<version>/benchmark-runner.jar`, and SHALL pass that key to the instance.

#### Scenario: Two runs from one CLI version execute one runner build
- **WHEN** two runs are launched a week apart by the same CLI version
- **THEN** both execute the same runner JAR object

#### Scenario: The CLI reports the version it pinned
- **WHEN** a run is launched
- **THEN** the CLI reports which runner version the run will execute

### Requirement: The runner artifact slot is seeded once per version, and never silently replaced
When `releases/<version>/benchmark-runner.jar` is absent, the CLI SHALL fetch that version's release
asset and upload it before launching. When the object is already present the CLI SHALL use it as-is
and SHALL NOT overwrite it.

#### Scenario: First run of a version seeds the slot
- **WHEN** a run is launched with a CLI version whose slot is empty
- **THEN** the asset is fetched, verified and uploaded, and the run proceeds

#### Scenario: Later runs reuse the slot
- **WHEN** a subsequent run is launched with the same CLI version
- **THEN** no fetch and no upload occur, and the existing object is used

#### Scenario: Seeding failure prevents the launch
- **WHEN** the release asset for the CLI's version cannot be retrieved
- **THEN** the command exits non-zero naming the version, and no EC2 instance is launched

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

### Requirement: The runner source repository is configuration, not a hardcoded literal
The repository the CLI fetches release assets from SHALL be a configuration value with a documented
default, so that a fork can point at its own releases without editing code.

#### Scenario: A fork can retarget the source
- **WHEN** the configured source repository is changed
- **THEN** the CLI fetches its release asset from that repository

#### Scenario: A retrieval failure names what it tried
- **WHEN** the release asset cannot be retrieved
- **THEN** the error names the repository and version it attempted, rather than failing downstream
  with an unrelated message

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
- **THEN** the run proceeds and the instance uses that JAR

#### Scenario: No unreleased artifact reaches the release prefix
- **WHEN** a development build launches a run with an explicit runner JAR
- **THEN** nothing is written under `releases/`

### Requirement: The release publishes correctly versioned artifacts and a checksum
The release process SHALL publish, as release assets, the CLI JAR, the runner JAR and a SHA-256
checksum for the runner JAR. Every published JAR SHALL be built after the release version has been
set, so that the version readable from a published artifact is the released version. The CLI JAR
SHALL carry its version in packaged metadata readable at run time.

#### Scenario: A published artifact reports its own release version
- **WHEN** the CLI JAR from a release is executed
- **THEN** the version it reports is that release's version, not the unreleased placeholder

#### Scenario: The checksum matches the published JAR
- **WHEN** the published checksum is compared against the published runner JAR
- **THEN** they match

#### Scenario: The CLI JAR is a release asset
- **WHEN** a release is published
- **THEN** its assets include the CLI JAR
