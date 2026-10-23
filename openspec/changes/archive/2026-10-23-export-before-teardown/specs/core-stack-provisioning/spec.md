# Spec Delta

## MODIFIED Requirements

### Requirement: Teardown safety gates
`baas admin deployment teardown` SHALL abort if any EC2 instance tagged `baas-role=benchmark-runner` and
`baas-deployment=<the deployment being torn down>` is in the `pending` or `running` state; runners of
another deployment SHALL NOT block it. It SHALL require explicit confirmation — retyping the
deployment's name on an interactive terminal, or `--yes`. Without an interactive terminal and without
`--yes` it SHALL refuse, delete nothing and exit 1, naming `--yes` and `baas admin deployment export`. Both gates SHALL pass before anything is deleted, the runner image
included. When it aborts over in-flight jobs, it SHALL name each job by the identifier in its
instance's `baas-job-id` tag, alongside the instance identifier and state, and SHALL name
`baas jobs terminate <jobId>` as the way to stop one. It SHALL read those identifiers from the
instances, not from the results table. Before the prompt it SHALL state that the deployment's bucket
and results table, with every job and measurement in them, will be deleted and cannot be recovered,
and, when the deployment holds a runner-image extension, that the extension will be deleted with the
stack.

Without `--yes`, after the in-flight gate and the notice, and before the confirmation, teardown SHALL offer to export
the deployment, naming a default bundle directory the user may replace. An accepted offer SHALL run
`baas admin deployment export` with every part into that directory; an export that fails SHALL stop
the teardown with nothing deleted. A declined offer SHALL proceed to the confirmation. Teardown SHALL
write nothing to the local filesystem itself — not the extension, not any part of the deployment's
data — other than through an export the user accepted. `--yes` SHALL skip both the offer and the
confirmation and export nothing, and its help SHALL say so and name `baas admin deployment export`.

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

#### Scenario: --yes asks nothing and exports nothing
- **WHEN** `baas admin deployment teardown --yes` runs without a terminal for a deployment holding an extension
- **THEN** no export is offered, no confirmation is asked, the deployment is torn down, and no file is written

#### Scenario: No terminal, no --yes, no teardown
- **WHEN** `baas admin deployment teardown` runs with standard input not a terminal and no `--yes`
- **THEN** it exits 1 naming `--yes` and `baas admin deployment export`, and nothing is deleted

#### Scenario: An accepted export runs before the confirmation
- **WHEN** the user accepts the export offer and then types the deployment's name
- **THEN** a complete bundle, manifest included, exists in the chosen directory before the bucket is emptied

#### Scenario: A failed export stops the teardown
- **WHEN** the user accepts the export offer and the export fails
- **THEN** teardown exits non-zero without asking for the confirmation, and nothing is deleted

#### Scenario: A declined export saves nothing
- **WHEN** the user declines the export offer and confirms the teardown of a deployment holding an extension
- **THEN** the deployment is torn down and no file is written, the extension included

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
