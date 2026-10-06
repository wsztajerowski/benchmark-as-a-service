# Spec Delta

## MODIFIED Requirements

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

#### Scenario: Two runs from one CLI version execute one runner build
- **WHEN** two jobs are launched a week apart by the same CLI version
- **THEN** both execute the same runner JAR object

#### Scenario: The CLI reports the version it pinned
- **WHEN** a job is launched
- **THEN** the CLI reports which runner version the job will execute

### Requirement: The runner artifact slot is seeded once per version, and never silently replaced
When `releases/<version>/benchmark-runner.jar` is absent, the CLI SHALL fetch that version's release
asset and upload it before launching. When the object is already present the CLI SHALL use it as-is
and SHALL NOT overwrite it.

#### Scenario: First run of a version seeds the slot
- **WHEN** a job is launched with a CLI version whose slot is empty
- **THEN** the asset is fetched, verified and uploaded, and the job proceeds

#### Scenario: Later runs reuse the slot
- **WHEN** a subsequent job is launched with the same CLI version
- **THEN** no fetch and no upload occur, and the existing object is used

#### Scenario: Seeding failure prevents the launch
- **WHEN** the release asset for the CLI's version cannot be retrieved
- **THEN** the command exits non-zero naming the version, and no EC2 instance is launched

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
