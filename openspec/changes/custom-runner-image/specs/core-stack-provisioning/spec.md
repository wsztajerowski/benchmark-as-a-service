# Spec Delta

## ADDED Requirements

### Requirement: Setup carries the image parameters forward on an existing installation
When `baas admin setup` updates an existing stack, it SHALL leave the runner image's parameters — the
base version, the parent AMI, the base component and the extension — at their deployed values. It SHALL
submit rendered image parameters only when it creates the stack, where the template's placeholder
defaults would otherwise be registered.

#### Scenario: A plain setup does not revert an extended image
- **WHEN** an installation holds an extension and a newer base, and `baas admin setup` runs again
  without options
- **THEN** the stack's image parameters are unchanged after the update

#### Scenario: Setup on a new installation registers the bundled base
- **WHEN** `baas admin setup` creates a stack
- **THEN** the registered base component is the rendering of the bundled `runner-image.yaml`, not the
  template's placeholder

### Requirement: Setup writes a starter extension file
`baas admin setup` SHALL write `~/.baas/runner-image-extension.yaml` containing the starter extension —
comments only, with a base marker naming `none` — when that file does not exist, and SHALL NOT modify it
when it does. The starter SHALL be the same document `baas admin image --extension` prints for an
installation holding no extension. Setup SHALL name the file and the command that pushes it.

#### Scenario: First setup writes the starter
- **WHEN** `baas admin setup` completes and `~/.baas/runner-image-extension.yaml` does not exist
- **THEN** the file exists, carries a marker naming `none`, and deploying it would install nothing

#### Scenario: An edited extension file survives setup
- **WHEN** `~/.baas/runner-image-extension.yaml` holds the operator's edits and `baas admin setup`
  runs again
- **THEN** the file is byte-identical afterwards

## MODIFIED Requirements

### Requirement: Core stack declares the image build pipeline
`cf-template-core.yaml` SHALL declare `AWS::ImageBuilder::ImageRecipe`,
`AWS::ImageBuilder::InfrastructureConfiguration`, `AWS::ImageBuilder::DistributionConfiguration`, and
`AWS::ImageBuilder::ImagePipeline`, and an `AWS::ImageBuilder::Component` for each of the base, the
extension and the contract. The extension component SHALL exist only while an extension is deployed,
and its document SHALL be carried by a stack parameter. The recipe SHALL list the base, then the
extension when present, then the contract. The recipe's root volume SHALL be 30 GB gp3, preserving the
existing volume requirement. The infrastructure configuration SHALL place build instances in the public
subnet.

#### Scenario: Pipeline resources are present
- **WHEN** the core stack is deployed
- **THEN** it contains an image pipeline, recipe, base and contract components, infrastructure
  configuration, and distribution configuration

#### Scenario: The extension component follows the parameter
- **WHEN** the core stack is deployed with an empty extension parameter
- **THEN** it contains no extension component, and the recipe lists the base and the contract only

#### Scenario: The contract runs last
- **WHEN** the recipe of a stack holding an extension is inspected
- **THEN** its components are the base, the extension and the contract, in that order

#### Scenario: Build volume matches the runner volume
- **WHEN** the image recipe is inspected
- **THEN** its root block device is 30 GB and of type gp3
