# Spec Delta

## MODIFIED Requirements

### Requirement: `baas admin build-image` builds the runner image
`baas admin build-image` SHALL render the base from the `infra/runner-image.yaml` bundled with the CLI,
resolve the base's parent release in the stack's region, update the stack when the base or the
extension changed, trigger the image build, poll to completion, write the resulting AMI ID to
`/<prefix>/runner/ami-id`, retire the AMI it replaced, and report the new AMI ID and label. It SHALL
accept `--extension <file>` to replace the installation's extension, subject to the size limit and the
stale-push guard; without it, the deployed extension SHALL be carried forward unchanged. It SHALL run
under deployer credentials (`aws.profile`), consistent with every other `baas admin` subcommand.

#### Scenario: Successful build reports the AMI
- **WHEN** `baas admin build-image` completes
- **THEN** it prints the new AMI ID and the image label, and exits 0

#### Scenario: Build failure is surfaced
- **WHEN** the image build fails
- **THEN** the command exits non-zero, reports the Image Builder failure reason, and leaves the pointer
  and the previous AMI untouched

#### Scenario: Build uses deployer credentials
- **WHEN** `config.yaml` sets both `aws.profile` and `aws.operatorProfile` and `baas admin build-image`
  runs
- **THEN** AWS clients are built from `aws.profile`

#### Scenario: Pushing an extension
- **WHEN** `baas admin build-image --extension ext.yaml` runs with a file whose marker matches the
  deployed extension
- **THEN** the stack holds the file's content as the extension and the new image's label carries its
  hash

### Requirement: `baas admin image` reports the current image
`baas admin image` SHALL report the current runner image's label, AMI ID, build timestamp, and parent
AMI ID, and SHALL report clearly when no image has been built. When an extension is deployed, it SHALL
also report the extension's hash, its size against the 4096-byte limit, and the names of its steps
grouped by phase. When the image's base version differs from the base bundled with the running CLI, it
SHALL warn naming the bundled base version and `baas admin build-image`. With `--extension`, it SHALL
instead print the deployed extension preceded by its base marker line, or, when none is deployed, the
starter document marked `none`. Command payload SHALL be written to `System.out` rather than the
logger, so it remains pipeable.

#### Scenario: Current image is reported
- **WHEN** an image has been built and `baas admin image` runs
- **THEN** the output names the image label, AMI ID, build time, and parent AMI

#### Scenario: No image built yet
- **WHEN** no image has been built and `baas admin image` runs
- **THEN** the output states that no image exists and names `baas admin build-image`

#### Scenario: The extension is summarised
- **WHEN** the deployed extension has build steps `InstallOtelCollector` and `InstallBpftrace`
- **THEN** the output names its hash, its size against 4096 bytes, and both step names under the build
  phase

#### Scenario: Pulling the extension
- **WHEN** `baas admin image --extension > ext.yaml` runs on an installation holding extension
  `3f9a1c2e`
- **THEN** `ext.yaml` starts with a marker naming `3f9a1c2e`, followed by the deployed extension

#### Scenario: Pulling when nothing is deployed
- **WHEN** `baas admin image --extension` runs on an installation holding no extension
- **THEN** it prints the starter document with a marker naming `none`

#### Scenario: The drift warning names the bundled base
- **WHEN** the deployed image's base is `1.2.0` and the CLI bundles base `1.3.0`
- **THEN** a warning names `1.3.0` as the bundled base and `baas admin build-image`
