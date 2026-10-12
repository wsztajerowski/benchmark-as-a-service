## MODIFIED Requirements

### Requirement: baas admin deployment setup is self-sufficient
`baas admin deployment setup` SHALL accept `--region` and `--deployer-aws-profile` directly as command-line options, resolve the deployment name as *A deployment is named by its prefix, derived from the account by default* states, apply defaults for any omitted option, render the deployer policy for that deployment and check the caller's rights against it, deploy or update the core stack, and write the result to that deployment's configuration file. It SHALL NOT require any configuration file to pre-exist. It SHALL NOT expose a `--prefix` option: the name is given only by the global `--deployment`. It SHALL NOT accept `--aws-profile`. When the deployment's stack is in `ROLLBACK_COMPLETE` — a first create that failed and rolled back, which leaves no resources and cannot be updated — setup SHALL delete that stack, wait for the deletion to complete, and then create the stack as for a new deployment. It SHALL NOT direct the user to `teardown` for that state.

#### Scenario: First run with no prior config
- **WHEN** `baas admin deployment setup` runs and no deployment is configured
- **THEN** the core stack is deployed using the default region and the account-derived prefix, and `~/.baas/deployments/<prefix>.yaml` is created with the deployment's prefix and the credential settings it needs

#### Scenario: No --prefix option
- **WHEN** `baas admin deployment setup --prefix foo` is invoked
- **THEN** picocli reports an unknown option error

#### Scenario: The deployer profile option is renamed
- **WHEN** `baas admin deployment setup --aws-profile baas-admin` is invoked
- **THEN** picocli reports an unknown option error, and `--deployer-aws-profile baas-admin` is accepted and stored as `aws.deployerProfile`

#### Scenario: A failed first create is recovered by re-running setup
- **WHEN** a first `baas admin deployment setup` failed, leaving the stack in `ROLLBACK_COMPLETE` and no configuration file, and setup is run again
- **THEN** setup deletes the rolled-back stack, creates the stack anew, and writes the deployment's configuration file, without the user running any other command

### Requirement: Bucket emptying handles object versions
`S3UploadService.deleteAllObjects` SHALL delete every object version and delete marker, not only current versions. This SHALL remain true after versioning is suspended, because versions written before suspension persist until a lifecycle rule reaps them. A bucket that does not exist SHALL count as already empty, so that teardown proceeds to delete the stack; any other failure to empty the bucket SHALL stop the teardown before the stack is deleted.

#### Scenario: Versioned bucket is fully emptied
- **WHEN** `deleteAllObjects` runs against a bucket whose keys have multiple versions
- **THEN** a subsequent `listObjectVersions` returns no versions and no delete markers

#### Scenario: Suspension does not remove the need to walk versions
- **WHEN** `deleteAllObjects` runs against a bucket whose versioning is suspended but which still holds versions written earlier
- **THEN** those earlier versions and any delete markers are deleted

#### Scenario: A missing bucket does not block teardown
- **WHEN** `baas admin deployment teardown --yes` runs for a deployment whose bucket no longer exists
- **THEN** the teardown deletes the stack, retires the image and removes the configuration file, and exits zero

#### Scenario: An undeletable object still stops teardown
- **WHEN** an object in an existing bucket cannot be deleted
- **THEN** teardown exits non-zero and the stack is not deleted

### Requirement: Teardown removes every deployment resource
The working bucket and the results table SHALL carry `DeletionPolicy: Delete` and `UpdateReplacePolicy:
Delete`. `baas admin deployment teardown` SHALL empty the bucket — every object version and delete
marker — then delete the core stack, so that the bucket, the results table and every other stack
resource are removed, and then retire the runner image. A completed teardown SHALL leave no BaaS resource
of the deployment in the account, and SHALL report no retained resource.

#### Scenario: Nothing is left after teardown
- **WHEN** `baas admin deployment teardown --yes` completes
- **THEN** the stack, the bucket, the results table, the runner image and its pointer no longer exist

#### Scenario: A later setup starts clean
- **WHEN** the same deployment is set up again after a teardown
- **THEN** setup creates the bucket and the table anew, and its check that their names are free passes

## ADDED Requirements

### Requirement: Setup checks the stack's fixed names are free before creating it
The core stack creates a bucket named `<prefix>` and a table named `<prefix>-results`; if either name is
taken, the whole create rolls back. Before creating the stack — for a new deployment, and before
recovering a stack in `ROLLBACK_COMPLETE` — `baas admin deployment setup` SHALL check both names, and
SHALL refuse, creating and deleting nothing, exit non-zero, and name the cause and the remedy, when:
a bucket of that name exists in the deployment's region and is accessible to the caller (a leftover of an
earlier deployment: remove it with `aws s3 rb s3://<prefix> --force`, after saving anything it holds); a
bucket of that name is owned by another account (choose another name with `--deployment`); or a table of
that name exists in the region (a leftover: remove it with `aws dynamodb delete-table --table-name
<prefix>-results`). The early bucket-region lookup SHALL treat only a bucket the caller can reach as the
deployment's own, so that another account's bucket is never reported as this deployment living in
another region. An update of an existing stack SHALL NOT run these checks: its bucket and table are its
own.

#### Scenario: A leftover bucket is named before anything is created
- **WHEN** `baas admin deployment setup` creates a deployment whose bucket name already exists in its region with no stack of that name
- **THEN** setup exits non-zero naming the bucket and `aws s3 rb s3://<prefix> --force`, and no stack is created

#### Scenario: A leftover table is named before anything is created
- **WHEN** `baas admin deployment setup` creates a deployment whose table `<prefix>-results` already exists
- **THEN** setup exits non-zero naming the table and `aws dynamodb delete-table`, and no stack is created

#### Scenario: A name taken by another account
- **WHEN** `baas --deployment wiktor-dev admin deployment setup` runs and a bucket named `wiktor-dev` belongs to another account
- **THEN** setup exits non-zero saying the name is taken by another account and to choose another with `--deployment`, does not say the deployment lives in another region, and creates nothing

#### Scenario: A rolled-back stack with a leftover is not deleted
- **WHEN** the stack is in `ROLLBACK_COMPLETE` because its bucket name was taken, and setup is run again
- **THEN** setup refuses naming the bucket, and the rolled-back stack is left in place

#### Scenario: An update does not check its own names
- **WHEN** `baas admin deployment setup` runs against a deployment whose stack exists and is updatable
- **THEN** the existing bucket and table do not block it
