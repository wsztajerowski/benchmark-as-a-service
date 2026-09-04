## ADDED Requirements

### Requirement: Runner egress reaches MongoDB Atlas
`RunnerSecurityGroup` SHALL permit outbound TCP on port 27017 in addition to 443 and 80, so the benchmark runner can reach a MongoDB Atlas cluster.

#### Scenario: Atlas port is open
- **WHEN** the core stack is deployed with `UseExistingVpc=false`
- **THEN** `RunnerSecurityGroup`'s egress rules include a TCP rule covering port 27017

### Requirement: Working bucket survives stack deletion by default
`S3MainBucket` SHALL declare `DeletionPolicy: Retain` and `UpdateReplacePolicy: Retain`, and SHALL declare lifecycle rules expiring noncurrent versions and aborting incomplete multipart uploads.

#### Scenario: Default teardown retains the bucket by design
- **WHEN** `baas admin teardown --yes` deletes the core stack without `--delete-bucket`
- **THEN** the stack reaches `DELETE_COMPLETE` and the bucket still exists

### Requirement: Bucket emptying handles object versions
`S3UploadService.deleteAllObjects` SHALL delete every object version and delete marker, not only current versions.

#### Scenario: Versioned bucket is fully emptied
- **WHEN** `deleteAllObjects` runs against a versioning-enabled bucket whose keys have multiple versions
- **THEN** a subsequent `listObjectVersions` returns no versions and no delete markers

### Requirement: Deployer policy covers the full setup path
`BaasCliDeployerPolicy` SHALL include `ssm:PutParameter` on the mongo connection-string path, the S3 actions needed to empty and delete the working bucket (`s3:ListBucket`, `s3:ListBucketVersions`, `s3:DeleteObject`, `s3:DeleteObjectVersion`, `s3:DeleteBucket`), and the IAM read-back actions CloudFormation invokes after role creation (`iam:GetRolePolicy`, `iam:ListRolePolicies`, `iam:ListAttachedRolePolicies`).

#### Scenario: Setup with a Mongo URI succeeds end to end
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin setup --mongo-uri "mongodb+srv://user:pass@host/db"`
- **THEN** the stack deploys, the SecureString parameter is written, and the command exits 0

### Requirement: Deployer policy holds no CI-stack permissions
`BaasCliDeployerPolicy` SHALL NOT grant any OIDC-provider action or `iam:UpdateAssumeRolePolicy`.

#### Scenario: No OIDC actions present
- **WHEN** `infra/deployer-policy.json` is inspected
- **THEN** it contains no action beginning `iam:` and ending `OpenIDConnectProvider`, and no `iam:UpdateAssumeRolePolicy`

### Requirement: Deployer policy is resource-scoped
`BaasCliDeployerPolicy`'s CloudFormation statement SHALL be scoped to `stack/baas-*/*` and its IAM statement to `role/*-runner-role`, `role/*-operator-role`, and the corresponding instance-profile ARNs, rather than `Resource: "*"`.

#### Scenario: Cannot create an arbitrarily-named role
- **WHEN** the deployer policy is evaluated for `iam:CreateRole` on `arn:aws:iam::<acct>:role/admin-backdoor`
- **THEN** the request is not permitted

### Requirement: Day-to-day commands resolve operator credentials
`~/.baas/config.yaml` SHALL carry `aws.operatorProfile`. `baas run`, `baas results`, and `baas config show` SHALL resolve AWS credentials from it; `baas admin setup` and `baas admin teardown` SHALL continue using `aws.profile`. When `aws.operatorProfile` is unset, day-to-day commands SHALL fall through to the default credential chain and SHALL NOT use `aws.profile`.

#### Scenario: Deployer profile is never silently reused
- **WHEN** `config.yaml` has `aws.profile: baas-deployer` and no `aws.operatorProfile`, and `baas run jmh` is invoked
- **THEN** the AWS client is built without an explicit profile, and a warning naming `baas config set --operator-profile` is printed

#### Scenario: Operator profile is honoured
- **WHEN** `config.yaml` has `aws.operatorProfile: baas-operator` and `baas run jmh` is invoked
- **THEN** the AWS client is built with the `baas-operator` profile

### Requirement: Operators can bootstrap config without the deployer's machine
`BaasCliOperatorRole` SHALL be granted `cloudformation:DescribeStacks` scoped to its own core stack, and `baas config sync --core-stack-name <name>` SHALL populate `bucket`, `subnetId`, `securityGroupId`, `vpcId`, `runnerInstanceProfileName`, `prefix`, and `coreStackName` in `~/.baas/config.yaml` from that stack's outputs.

#### Scenario: Operator on a fresh machine
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas config sync --core-stack-name baas-a1b2c3d4` with no prior `config.yaml`
- **THEN** `config.yaml` is written with the stack's outputs and `baas run` works without hand-copying any file

### Requirement: Failed runs leave diagnosable output
The user-data script SHALL upload `/var/log/cloud-init-output.log` to `s3://<bucket>/<resultPath>/cloud-init-output.log` before terminating the instance, on both the success and failure paths.

#### Scenario: Log survives self-termination
- **WHEN** a benchmark run exits non-zero and the instance self-terminates
- **THEN** the run's S3 prefix contains `cloud-init-output.log` alongside `run-status`

### Requirement: Poll loop detects a dead instance
`baas run` SHALL check the runner instance's state while polling and SHALL abort with a non-zero exit as soon as the instance reaches `terminated` or `shutting-down` without a `run-status` sentinel, rather than waiting for the wall-clock cap.

#### Scenario: Boot failure fails fast
- **WHEN** the runner instance terminates before writing `run-status`
- **THEN** `baas run` reports the instance state and exits non-zero well before `wallClockHardKillSeconds` elapses

### Requirement: Mongo URI is validated before provisioning
`baas admin setup` SHALL validate `--mongo-uri` before making any AWS API call.

#### Scenario: Bad URI costs no deploy
- **WHEN** `baas admin setup --mongo-uri "mongodb+srv://host"` (no database) is invoked
- **THEN** the command fails with a validation error and no CloudFormation stack operation is started

### Requirement: Operator policy reference copy stays in sync
`infra/operator-policy.json` SHALL grant exactly the same set of actions as the `OperatorRole` inline policies in `infra/cf-template-core.yaml`.

#### Scenario: Drift is caught
- **WHEN** an action is added to `OperatorRole` but not to `operator-policy.json`
- **THEN** the drift test fails
