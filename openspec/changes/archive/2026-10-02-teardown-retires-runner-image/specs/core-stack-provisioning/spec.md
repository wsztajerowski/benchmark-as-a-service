## MODIFIED Requirements

### Requirement: Teardown safety gates
`baas admin teardown` SHALL abort if any EC2 instance tagged `baas-role=benchmark-runner` is in the
`pending` or `running` state, SHALL require explicit confirmation (retyping the stack name, or
`--yes`), and SHALL retain both the S3 bucket and the DynamoDB results table by default. The bucket
is deletable with `--delete-bucket`, the table by no flag at all. It SHALL name both retained
resources on exit. Both gates SHALL pass before anything is deleted, the runner image included.

#### Scenario: Abort when a run is in flight
- **WHEN** `baas admin teardown` runs while a `baas-role=benchmark-runner` instance is `running`
- **THEN** the command exits with an error listing the running instance ID(s) and performs no destructive action

#### Scenario: Abort when a run is still booting
- **WHEN** `baas admin teardown` runs while a `baas-role=benchmark-runner` instance is `pending`
- **THEN** the command exits with an error listing that instance ID and performs no destructive
  action, so a run launched moments earlier does not lose its role, subnet or image mid-boot

#### Scenario: Bucket retained unless explicitly deleted
- **WHEN** `baas admin teardown --yes` runs without `--delete-bucket`
- **THEN** the core stack is deleted but the S3 bucket persists

## ADDED Requirements

### Requirement: Teardown retires the runner image
After the core stack is deleted, `baas admin teardown` SHALL retire the installation's runner image,
unconditionally and with no option to keep it:
- the AMI named by `/<prefix>/runner/ami-id` SHALL be deregistered and its snapshots deleted;
- the `/<prefix>/runner/ami-id` parameter SHALL be deleted;
- every Image Builder image record of the installation's recipe SHALL be deleted.

`<prefix>` SHALL be the installation being torn down, so `--stack-name` retires that installation's
image and not the one this machine is configured for. Retirement SHALL NOT fail the teardown: a
pointer that is absent, a pointer naming an AMI that no longer exists, or a deletion that fails
SHALL be reported as a warning that names what remains. The command SHALL still exit 0 once the
stack is deleted.

#### Scenario: A torn-down installation leaves no image behind
- **WHEN** `baas admin teardown --yes` completes for an installation with a published image
- **THEN** the pointer parameter no longer exists, the AMI it named is deregistered, that AMI's
  snapshots are deleted, and no Image Builder image record of the installation's recipe remains

#### Scenario: A later setup cannot inherit the image
- **WHEN** the same installation is set up again after a teardown and the retained table is removed
- **THEN** `baas run` refuses to launch for want of a runner image until `baas admin build-image`
  has run

#### Scenario: Nothing to retire
- **WHEN** teardown runs for an installation whose pointer does not exist
- **THEN** the stack is deleted, no image-related error is raised, and the command exits 0

#### Scenario: The pointer names an AMI that is already gone
- **WHEN** the pointer names an AMI that has already been deregistered
- **THEN** the pointer is still deleted and the command exits 0

#### Scenario: A failed retirement is reported, not fatal
- **WHEN** deregistering the AMI or deleting a snapshot fails after the stack is deleted
- **THEN** the command warns naming the AMI or snapshot left behind and exits 0

#### Scenario: Another installation's image
- **WHEN** `baas admin teardown --stack-name baas-123456789012-dev` runs on a machine configured for
  `baas-123456789012`
- **THEN** it retires the image published at `/baas-123456789012-dev/runner/ami-id`, and the
  configured installation's image is untouched
