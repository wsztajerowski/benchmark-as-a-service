## MODIFIED Requirements

### Requirement: Teardown safety gates
`baas admin teardown` SHALL abort if any EC2 instance tagged `baas-role=benchmark-runner` is in the
`pending` or `running` state, SHALL require explicit confirmation (retyping the stack name, or
`--yes`), and SHALL retain both the S3 bucket and the DynamoDB results table by default. The bucket
is deletable with `--delete-bucket`, the table by no flag at all. It SHALL name both retained
resources on exit. Both gates SHALL pass before anything is deleted, the runner image included.
When it aborts over in-flight runs, it SHALL name each run by the identifier in its instance's
`baas-request-id` tag, alongside the instance identifier and state, and SHALL name
`baas runs terminate <runId>` as the way to stop one. It SHALL read those identifiers from the
instances, not from the results table.

#### Scenario: Abort when a run is in flight
- **WHEN** `baas admin teardown` runs while a `baas-role=benchmark-runner` instance is `running`
- **THEN** the command exits with an error listing each in-flight run's identifier, instance ID and
  state, names `baas runs terminate <runId>`, and performs no destructive action

#### Scenario: Abort when a run is still booting
- **WHEN** `baas admin teardown` runs while a `baas-role=benchmark-runner` instance is `pending`
- **THEN** the command exits with an error listing that run's identifier and instance ID and performs
  no destructive action, so a run launched moments earlier does not lose its role, subnet or image
  mid-boot

#### Scenario: The gate needs no table access
- **WHEN** `baas admin teardown` runs under the deployer policy, which grants no read of the results table
- **THEN** the in-flight check and its message succeed

#### Scenario: Bucket retained unless explicitly deleted
- **WHEN** `baas admin teardown --yes` runs without `--delete-bucket`
- **THEN** the core stack is deleted but the S3 bucket persists

### Requirement: Operator role permissions
`BaasCliOperatorRole` SHALL cover: `ec2:RunInstances`/`Describe*` to launch and observe benchmark runner instances, tag-scoped `ec2:TerminateInstances` (condition `aws:ResourceTag/baas-role=benchmark-runner`), `ec2:CreateTags` scoped to the `RunInstances` create action, `ssm:GetParameter` on the runner AMI pointer path (`/<prefix>/runner/ami-id`) and no SSM write of any kind, `dynamodb:Query`/`Scan`/`GetItem` on the results table and its index, `dynamodb:UpdateItem` on the results table restricted to the `RUN` partition and no other write action, `ec2:DescribeImages` to validate the resolved AMI, S3 object access scoped to the core stack's bucket, and `iam:PassRole` scoped to `RunnerRole` only. It SHALL NOT cover the public AL2023 AMI lookup path (`/aws/service/ami-amazon-linux-latest/*`), which is no longer used now that the runner boots from a purpose-built image. `baas run`/`baas runs`/`baas results`/`baas config`/`baas env` SHALL succeed when invoked by an identity that has assumed `BaasCliOperatorRole`.

#### Scenario: Operator role suffices for daily use
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas run jmh -- ...`, `baas runs list`, `baas runs terminate`, `baas results`, or `baas env diff`
- **THEN** every AWS API call made succeeds under that role's permissions, including the SSM read of `/<prefix>/runner/ami-id` needed to resolve the runner's AMI ID and the run-item writes

#### Scenario: Public AMI lookup path is no longer granted
- **WHEN** `infra/operator-policy.json` is inspected
- **THEN** it contains no statement granting `ssm:GetParameter` on `/aws/service/ami-amazon-linux-latest/*`

### Requirement: Failed runs leave diagnosable output
The user-data script SHALL upload `/var/log/cloud-init-output.log` into the run's S3 prefix, alongside the run's other artifacts, before terminating the instance, on the success path, the failure path and the watchdog path.

#### Scenario: Log survives self-termination
- **WHEN** a benchmark run exits non-zero and the instance self-terminates
- **THEN** the run's S3 prefix contains `cloud-init-output.log`, and the run item reads `failed:<exitCode>`

#### Scenario: Log survives the watchdog
- **WHEN** the watchdog terminates the instance
- **THEN** the run's S3 prefix contains `cloud-init-output.log`, and the run item reads `timed-out`

### Requirement: Poll loop detects a dead instance
`baas run` SHALL check the runner instance's state while polling and SHALL stop polling with a non-zero exit as soon as the instance reaches `terminated` or `shutting-down` while the run item holds no terminal status, rather than waiting for the poll cap of `timeout + watchdog margin`. Before reporting, it SHALL re-read the run item once, so a final status written moments before termination is not reported as a failure.

#### Scenario: Boot failure fails fast
- **WHEN** the runner instance terminates before recording a terminal status
- **THEN** `baas run` reports the instance state and exits non-zero well before `timeout + watchdog margin` elapses

#### Scenario: A status written just before termination is honoured
- **WHEN** the instance records `completed` and terminates between two polls
- **THEN** `baas run` reports the run as completed

### Requirement: The runner can write results but not read them
`RunnerRole` SHALL be granted `dynamodb:PutItem` and `dynamodb:BatchWriteItem` on the results table ARN
restricted to `RESULT#` partitions, and `dynamodb:UpdateItem` restricted to the `RUN` partition, and
nothing else on that table. It SHALL NOT be granted `Query`, `Scan`, `GetItem`, or any delete action.

#### Scenario: Runner can store a result
- **WHEN** an instance using the runner instance profile writes a measurement
- **THEN** the write succeeds

#### Scenario: Runner can record its run's status
- **WHEN** that instance updates its run item in the `RUN` partition
- **THEN** the write succeeds

#### Scenario: Runner cannot put items outside the measurement partitions
- **WHEN** that instance attempts `dynamodb:PutItem` on a key in the `RUN` partition
- **THEN** the request is denied

#### Scenario: Runner cannot read the results history
- **WHEN** that instance attempts `dynamodb:Scan` on the results table
- **THEN** the request is denied

#### Scenario: Runner cannot delete results
- **WHEN** that instance attempts `dynamodb:DeleteItem` on the results table
- **THEN** the request is denied

### Requirement: The operator can read results but not write them
`BaasCliOperatorRole` SHALL be granted `dynamodb:Query`, `dynamodb:Scan` and `dynamodb:GetItem` on the
results table ARN and its index ARN, and `dynamodb:UpdateItem` on the table restricted to the `RUN`
partition. It SHALL NOT be granted any other write action, any write in a `RESULT#` partition, or any
delete action.

#### Scenario: Operator can query results
- **WHEN** an identity that has assumed the operator role runs `baas results`
- **THEN** the query succeeds

#### Scenario: Operator can query the request-ID index
- **WHEN** that identity runs `baas results --request-id <id>`
- **THEN** the index query succeeds

#### Scenario: Operator can record a run's status
- **WHEN** that identity runs `baas run`, which reserves and updates the run item
- **THEN** the writes succeed

#### Scenario: Operator cannot mutate results
- **WHEN** that identity attempts `dynamodb:PutItem` on the results table, or `dynamodb:UpdateItem` on a key in a `RESULT#` partition
- **THEN** the request is denied
