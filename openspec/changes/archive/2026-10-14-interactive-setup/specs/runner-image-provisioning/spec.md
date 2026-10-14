# Spec Delta

## MODIFIED Requirements

### Requirement: Exactly one runner image exists at a time
The system SHALL maintain exactly one runner AMI, published at `/<prefix>/runner/ami-id`. It SHALL NOT
maintain named slots, an AMI history, or any second pointer. Each AMI SHALL carry the tags
`baas-image-version`, holding the image's label, and `baas-parent-ami`.

A build interrupted after it started — the CLI stopped, the laptop slept — SHALL NOT leave an image
that nothing publishes or retires. Before starting a build, `baas admin image build` and
`baas admin deployment setup` SHALL look at the deployment's pipeline: a build still in progress SHALL
be waited for and then published, instead of starting another; a build that finished successfully but
was never published — the pipeline's newest available image, newer than the AMI the pointer names —
SHALL be published without building again, with the same tags, pointer write and retirement of the
replaced AMI as a build the command ran itself. A found build is published only when it was built
from the recipe version the command would build; one from another version — an extension pushed
since — SHALL be retired instead, its AMI deregistered and its snapshots deleted, and the command
SHALL then build as usual. Interactively, setup SHALL say when the running build started and ask
before waiting for it; unattended, it SHALL wait.

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

#### Scenario: A build still running is waited for, not repeated
- **WHEN** `baas admin image build` was interrupted mid-build and is run again while that build is still in progress
- **THEN** no second pipeline execution is started, and once the running build completes its AMI is published and the replaced AMI retired

#### Scenario: A build that finished unseen is adopted
- **WHEN** the CLI was interrupted mid-build, the build then completed, and `baas admin image build` or `baas admin deployment setup` runs again
- **THEN** that build's AMI is tagged and published at `/<prefix>/runner/ami-id`, the AMI it replaced is retired, and no build is started

#### Scenario: A build of a superseded recipe is retired, not adopted
- **WHEN** an interrupted build of one recipe version finished unpublished, and `baas admin image build --extension ext.yaml` then changes the recipe version
- **THEN** the unpublished build's AMI is deregistered and its snapshots deleted, a build of the new version is started, and only that one is published

#### Scenario: An already published image is not adopted again
- **WHEN** the pipeline's newest available image is the AMI the pointer already names
- **THEN** nothing is adopted, and the command behaves as before this requirement
