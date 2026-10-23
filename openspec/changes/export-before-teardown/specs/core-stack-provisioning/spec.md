# Spec Delta

## MODIFIED Requirements

### Requirement: Teardown safety gates
`baas admin deployment teardown` SHALL abort if any EC2 instance tagged `baas-role=benchmark-runner` and
`baas-deployment=<the deployment being torn down>` is in the `pending` or `running` state; runners of
another deployment SHALL NOT block it. It SHALL require explicit confirmation by retyping the
deployment's name on an interactive terminal; no option SHALL skip the confirmation. Without an
interactive terminal it SHALL refuse, delete nothing and exit 1. Both gates SHALL pass before anything is deleted, the runner image
included. When it aborts over in-flight jobs, it SHALL name each job by the identifier in its
instance's `baas-job-id` tag, alongside the instance identifier and state, and SHALL name
`baas jobs terminate <jobId>` as the way to stop one. It SHALL read those identifiers from the
instances, not from the results table. Before the prompt it SHALL state that the deployment's bucket
and results table, with every job and measurement in them, will be deleted and cannot be recovered,
and, when the deployment holds a runner-image extension, that the extension will be deleted with the
stack.

After the in-flight gate and the notice, and before the confirmation, teardown SHALL offer to export
the deployment, naming a default bundle directory the user may replace. An accepted offer SHALL run
`baas admin deployment export` with every part into that directory; an export that fails SHALL stop
the teardown with nothing deleted. A declined offer SHALL proceed to the confirmation. Teardown SHALL
write nothing to the local filesystem itself — not the extension, not any part of the deployment's
data — other than through an export the user accepted.

#### Scenario: Abort when a job is in flight
- **WHEN** `baas admin deployment teardown` runs while a runner instance of the same deployment is `running`
- **THEN** the command exits with an error listing each in-flight job's identifier, instance ID and
  state, names `baas jobs terminate <jobId>`, and performs no destructive action

#### Scenario: Abort when a job is still booting
- **WHEN** `baas admin deployment teardown` runs while a runner instance of the same deployment is `pending`
- **THEN** the command exits with an error listing that job's identifier and instance ID and performs
  no destructive action, so a job launched moments earlier does not lose its role, subnet or image
  mid-boot

#### Scenario: Another deployment's runner does not block
- **WHEN** `baas --deployment wiktor-dev admin deployment teardown` runs while a runner of deployment `baas-123456789012` in the same region is `running`
- **THEN** the in-flight gate passes for `wiktor-dev`

#### Scenario: The gate needs no table access
- **WHEN** `baas admin deployment teardown` runs under the deployer policy, which grants no read of the results table
- **THEN** the in-flight check and its message succeed

#### Scenario: The prompt says everything will be deleted
- **WHEN** `baas admin deployment teardown` prompts for confirmation
- **THEN** the prompt states that the bucket and the results table will be deleted, and `--delete-bucket`
  is not an option

#### Scenario: The notice names a deployed extension
- **WHEN** `baas admin deployment teardown` runs for a deployment holding a runner-image extension
- **THEN** the notice before the prompt says the extension is deleted with the stack

#### Scenario: Confirmation cannot be skipped
- **WHEN** `baas admin deployment teardown --yes` is invoked
- **THEN** picocli reports an unknown option error and nothing is deleted

#### Scenario: No terminal, no teardown
- **WHEN** `baas admin deployment teardown` runs with standard input not a terminal
- **THEN** it exits 1 saying a terminal is required, and nothing is deleted

#### Scenario: An accepted export runs before the confirmation
- **WHEN** the user accepts the export offer and then types the deployment's name
- **THEN** a complete bundle, manifest included, exists in the chosen directory before the bucket is emptied

#### Scenario: A failed export stops the teardown
- **WHEN** the user accepts the export offer and the export fails
- **THEN** teardown exits non-zero without asking for the confirmation, and nothing is deleted

#### Scenario: A declined export saves nothing
- **WHEN** the user declines the export offer and confirms the teardown of a deployment holding an extension
- **THEN** the deployment is torn down and no file is written, the extension included

### Requirement: Bucket emptying handles object versions
`S3UploadService.deleteAllObjects` SHALL delete every object version and delete marker, not only current versions. This SHALL remain true after versioning is suspended, because versions written before suspension persist until a lifecycle rule reaps them. A bucket that does not exist SHALL count as already empty, so that teardown proceeds to delete the stack; any other failure to empty the bucket SHALL stop the teardown before the stack is deleted.

#### Scenario: Versioned bucket is fully emptied
- **WHEN** `deleteAllObjects` runs against a bucket whose keys have multiple versions
- **THEN** a subsequent `listObjectVersions` returns no versions and no delete markers

#### Scenario: Suspension does not remove the need to walk versions
- **WHEN** `deleteAllObjects` runs against a bucket whose versioning is suspended but which still holds versions written earlier
- **THEN** those earlier versions and any delete markers are deleted

#### Scenario: A missing bucket does not block teardown
- **WHEN** `baas admin deployment teardown` runs for a deployment whose bucket no longer exists
- **THEN** the teardown deletes the stack, retires the image and removes the configuration file, and exits zero

#### Scenario: An undeletable object still stops teardown
- **WHEN** an object in an existing bucket cannot be deleted
- **THEN** teardown exits non-zero and the stack is not deleted

### Requirement: baas admin deployment setup is self-sufficient
`baas admin deployment setup` SHALL accept `--region` and `--deployer-aws-profile` directly as command-line options, resolve the deployment name as *A deployment is named by its prefix, derived from the account by default* states, apply defaults for any omitted option, render the deployer policy for that deployment and check the caller's rights against it, deploy or update the core stack, and write the deployment's configuration file as *Each deployment has one configuration file, named by the deployment* states. It SHALL NOT require any configuration file to pre-exist. It SHALL NOT expose a `--prefix` option: the name is given only by the global `--deployment`. It SHALL NOT accept `--aws-profile`. When the deployment's stack is in `ROLLBACK_COMPLETE` — a first create that failed and rolled back, which leaves no resources and cannot be updated — setup SHALL delete that stack, wait for the deletion to complete, and then create the stack as for a new deployment. It SHALL NOT direct the user to `teardown` for that state.

#### Scenario: First run with no prior config
- **WHEN** `baas admin deployment setup` runs and no deployment is configured
- **THEN** the core stack is deployed using the default region and the account-derived prefix, and `~/.baas/deployments/<prefix>.yaml` is created with the deployment's region and the credential settings it needs

#### Scenario: No --prefix option
- **WHEN** `baas admin deployment setup --prefix foo` is invoked
- **THEN** picocli reports an unknown option error

#### Scenario: The deployer profile option is renamed
- **WHEN** `baas admin deployment setup --aws-profile baas-admin` is invoked
- **THEN** picocli reports an unknown option error, and `--deployer-aws-profile baas-admin` is accepted and stored as `aws.deployerProfile` once there is evidence it can deploy

#### Scenario: A failed first create is recovered by re-running setup
- **WHEN** a first `baas admin deployment setup` failed, leaving the stack in `ROLLBACK_COMPLETE` and the deployment's configuration file, and setup is run again
- **THEN** setup deletes the rolled-back stack, creates the stack anew, and keeps the deployment's configuration file, without the user running any other command

#### Scenario: A failed first create can also be torn down
- **WHEN** a first create rolled back and the user decides not to retry
- **THEN** `baas --deployment <name> admin deployment teardown` deletes the rolled-back stack and the deployment's configuration file

### Requirement: Teardown retires the runner image
After the core stack is deleted, `baas admin deployment teardown` SHALL retire the deployment's runner image,
unconditionally and with no option to keep it:
- the AMI named by `/<prefix>/runner/ami-id` SHALL be deregistered and its snapshots deleted;
- the `/<prefix>/runner/ami-id` parameter SHALL be deleted;
- every Image Builder image record of the deployment's recipe SHALL be deleted.

`<prefix>` SHALL be the deployment being torn down, so `--deployment` retires that deployment's
image and not the one this machine is configured for. Retirement SHALL NOT fail the teardown: a
pointer that is absent, a pointer naming an AMI that no longer exists, or a deletion that fails
SHALL be reported as a warning that names what remains. The command SHALL still exit 0 once the
stack is deleted.

#### Scenario: A torn-down deployment leaves no image behind
- **WHEN** `baas admin deployment teardown` completes for a deployment with a published image
- **THEN** the pointer parameter no longer exists, the AMI it named is deregistered, that AMI's
  snapshots are deleted, and no Image Builder image record of the deployment's recipe remains

#### Scenario: A later setup cannot inherit the image
- **WHEN** the same deployment is set up again after a teardown
- **THEN** `baas run` refuses to launch for want of a runner image until `baas admin image build`
  has run

#### Scenario: Nothing to retire
- **WHEN** teardown runs for a deployment whose pointer does not exist
- **THEN** the stack is deleted, no image-related error is raised, and the command exits 0

#### Scenario: The pointer names an AMI that is already gone
- **WHEN** the pointer names an AMI that has already been deregistered
- **THEN** the pointer is still deleted and the command exits 0

#### Scenario: A failed retirement is reported, not fatal
- **WHEN** deregistering the AMI or deleting a snapshot fails after the stack is deleted
- **THEN** the command warns naming the AMI or snapshot left behind and exits 0

#### Scenario: Another deployment's image
- **WHEN** `baas admin deployment teardown --deployment baas-123456789012-dev` runs on a machine configured for
  `baas-123456789012`
- **THEN** it retires the image published at `/baas-123456789012-dev/runner/ami-id`, and the
  configured deployment's image is untouched

### Requirement: Teardown removes every deployment resource
The working bucket and the results table SHALL carry `DeletionPolicy: Delete` and `UpdateReplacePolicy:
Delete`. `baas admin deployment teardown` SHALL empty the bucket — every object version and delete
marker — then delete the core stack, so that the bucket, the results table and every other stack
resource are removed, and then retire the runner image. A completed teardown SHALL leave no BaaS resource
of the deployment in the account, and SHALL report no retained resource of the deployment. The
account's GitHub OIDC provider is not a resource of any deployment and SHALL be left in place.

#### Scenario: Nothing is left after teardown
- **WHEN** `baas admin deployment teardown` completes
- **THEN** the stack, the bucket, the results table, the runner image and its pointer no longer exist

#### Scenario: A later setup starts clean
- **WHEN** the same deployment is set up again after a teardown
- **THEN** setup creates the bucket and the table anew, and its check that their names are free passes

### Requirement: The CLI creates the GitHub OIDC provider and never modifies or deletes it
When a deployment is set up with GitHub federation, `baas admin deployment setup` SHALL ensure the
account's GitHub OIDC identity provider exists at
`arn:aws:iam::<account>:oidc-provider/token.actions.githubusercontent.com`, deriving that ARN from the
caller's account. When it is absent, setup SHALL create it through the IAM API — outside the core
stack — with the issuer URL and the client ID `sts.amazonaws.com` only, no thumbprint and no tags. When
it exists, setup SHALL reuse it. An existing provider that does not list `sts.amazonaws.com` as a
client ID SHALL NOT be modified: setup SHALL print the IAM administrator's
`aws iam add-client-id-to-open-id-connect-provider` command with the ARN filled in, and wait
interactively or exit 1 unattended. No `baas` command SHALL modify or delete the provider, since it is
account-global and may serve other projects. A teardown of a deployment whose operator role trusted
it SHALL say that the provider is left in place.

#### Scenario: A missing provider is created
- **WHEN** setup runs with `--github-org org --github-repo a` in an account with no GitHub OIDC provider
- **THEN** the provider exists afterwards with client ID `sts.amazonaws.com`, it is not a resource of the core stack, and the operator role trusts it

#### Scenario: An existing provider is reused
- **WHEN** the provider already exists in the account with client ID `sts.amazonaws.com`
- **THEN** setup creates no provider and the operator role trusts the existing one

#### Scenario: A provider lacking the audience is left to an administrator
- **WHEN** the existing provider lists only another client ID
- **THEN** setup prints `aws iam add-client-id-to-open-id-connect-provider` with the provider's ARN and `sts.amazonaws.com`, does not call any IAM write on the provider, and waits interactively or exits 1 unattended

#### Scenario: Teardown leaves the provider
- **WHEN** `baas admin deployment teardown` completes for a deployment with federation
- **THEN** the provider still exists, and the teardown's output says it was left in place

#### Scenario: The ARN option is gone
- **WHEN** `baas admin deployment setup --oidc-provider-arn arn:aws:iam::1:oidc-provider/x` is invoked
- **THEN** picocli reports an unknown option error

## ADDED Requirements

### Requirement: Deployer policy covers export and import
The rendered deployer policy SHALL grant `dynamodb:Scan` and `dynamodb:PutItem` on the deployment's
results table, and `s3:PutObject` on the deployment's bucket's objects, by name and without wildcard
verbs, so that `baas admin deployment export` and `baas admin deployment import` run under it. It
SHALL NOT grant `dynamodb:BatchWriteItem`, `dynamodb:DeleteItem` or `s3:Put*`. The operator policy
SHALL gain none of these. The rendered policy SHALL still fit the budget *The deployer policy is sized
as a customer-managed policy* states.

#### Scenario: Export and import run under the deployer policy alone
- **WHEN** an identity holding only the rendered deployer policy exports a deployment and imports the bundle into another set up under the same policy's name
- **THEN** both commands complete

#### Scenario: The grants are named, not wildcarded
- **WHEN** the rendered deployer policy is inspected
- **THEN** it names `dynamodb:Scan`, `dynamodb:PutItem` and `s3:PutObject`, and names neither `s3:Put*` nor `dynamodb:BatchWriteItem`

#### Scenario: The operator still cannot write measurements
- **WHEN** the operator policy is evaluated for `dynamodb:PutItem` on a `RESULT#` key
- **THEN** the request is not permitted

#### Scenario: The setup preflight probes the new actions
- **WHEN** setup's permission check runs for a caller whose attached deployer policy predates this change
- **THEN** it reports `dynamodb:Scan`, `dynamodb:PutItem` and `s3:PutObject` as missing
