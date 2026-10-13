# Spec Delta

## MODIFIED Requirements

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
