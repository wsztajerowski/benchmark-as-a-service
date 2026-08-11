## Why

Reviewing `baas-cli-core-ci-split`'s delivered infrastructure against the two command workflows it defines (`baas admin setup` under `BaasCliDeployerPolicy`, `baas run` under `BaasCliOperatorRole`) surfaced three hard failures and a security-boundary leak that unit tests could not have caught, because they live in CloudFormation YAML and IAM JSON rather than in Java.

The three hard failures: `RunnerSecurityGroup` allows egress on ports 443/80 only, but MongoDB Atlas listens on 27017, so every run fails at its database write; `deployer-policy.json` lacks `ssm:PutParameter`, so the documented first-run command `baas admin setup --mongo-uri "..."` deploys the stack and then throws `AccessDenied`; and `deployer-policy.json` lacks every S3 delete action while `S3MainBucket` carries no `DeletionPolicy`, so `baas admin teardown` cannot delete the stack in either its default or its `--delete-bucket` mode.

The boundary leak: `RunCommand` reads `aws.profile` from `config.yaml`, which `baas admin setup` populates with the *deployer's* profile. Nothing in the code or docs redirects day-to-day commands at `BaasCliOperatorRole`, so the operator role — the whole point of the previous change's Decision 4 — is never actually assumed on the happy path.

## What Changes

- `RunnerSecurityGroup` gains TCP 27017 egress. The `docs/redesign.md` claim that Atlas is reachable over 443 is corrected.
- `S3MainBucket` gains `DeletionPolicy: Retain` + `UpdateReplacePolicy: Retain`, making the spec's "bucket retained by default" real rather than an accident of a failing delete, plus lifecycle rules bounding noncurrent-version and incomplete-multipart storage growth.
- `S3UploadService.deleteAllObjects` becomes version-aware (`listObjectVersions` + delete markers); the current `listObjectsV2` implementation can never empty a versioned bucket.
- `deployer-policy.json` gains `ssm:PutParameter`, the S3 delete/list/version action set, and the IAM read-back actions CloudFormation's `AWS::IAM::Role` handler calls after create.
- **BREAKING**: `config.yaml` gains `aws.operatorProfile`. `run`/`results`/`config show` resolve credentials from it; `admin setup`/`admin teardown` keep using `aws.profile`. When `operatorProfile` is unset these commands fall through to the default credential chain (honouring `AWS_PROFILE`) and print a one-line warning — they no longer silently inherit the deployer profile.
- **Security**: `deployer-policy.json` drops `iam:CreateOpenIDConnectProvider`, `iam:GetOpenIDConnectProvider`, `iam:TagOpenIDConnectProvider`, and `iam:UpdateAssumeRolePolicy`. These are CI-stack actions that `design.md:25` of the previous change explicitly rejected putting on the local identity. Its `cloudformation:*` and `iam:*` blocks move off `Resource: "*"` onto `stack/baas-*/*` and `role/*-runner-role`/`*-operator-role` patterns, closing a create-role-then-attach-admin escalation path.
- **Observability**: user-data uploads `/var/log/cloud-init-output.log` to the run's S3 prefix before terminating, so a `failed:N` run is diagnosable; `RunCommand`'s poll loop checks instance state, so a boot failure fails in seconds instead of after the full 7500s wall-clock cap.
- `SetupCommand` validates `--mongo-uri` before any AWS call instead of after the stack deploy.
- Hygiene: dash-form tags throughout, `${AWS::Partition}` instead of a literal `arn:aws:`, `ec2:RunInstances` constrained by instance type and region, `operator-policy.json` wildcards pinned to the account, CI template `ssm:GetParameter`/`s3:GetObject` alignment.
- Infra data files (`cf-template-*.yaml`, `*-policy.json`) reach the `baas-cli` **test** classpath via `<testResources>`, enabling structural assertions on them. The shipped JAR is unchanged.

## Capabilities

### Modified Capabilities
- `core-stack-provisioning`: adds requirements for runner egress connectivity, bucket retention and lifecycle, deployer-policy completeness and scoping, operator credential resolution, and run observability.

## Impact

- **Infra**: `infra/cf-template-core.yaml`, `infra/cf-template-ci.yaml`, `infra/deployer-policy.json`, `infra/operator-policy.json`, `infra/README.md`.
- **Code**: `config/BaasConfig.java`, `commands/RunCommand.java`, `commands/ResultsCommand.java`, `commands/ConfigSetSubcommand.java`, `commands/ConfigShowSubcommand.java`, `commands/admin/SetupCommand.java`, `infra/S3UploadService.java`, `infra/Ec2ProvisioningService.java`, `infra/UserDataScriptBuilder.java`, `baas-cli/pom.xml`.
- **Docs**: `docs/redesign.md` (the "outbound 443 reaches Atlas" claim), `docs/aws-migration-plan.md` (the "add the runner's egress IP" instruction, which assumes a stable IP that does not exist).
- **No changes** to `benchmark-runner`, GHA workflow YAML, the ARN-derived prefix scheme, or the public-subnet networking model.
