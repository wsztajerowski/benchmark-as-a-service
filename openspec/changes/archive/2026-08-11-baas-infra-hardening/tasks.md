## 1. Test harness for infra data files
- [x] 1.1 Add `<testResources>` to `baas-cli/pom.xml` copying `cf-template-ci.yaml`, `deployer-policy.json`, `operator-policy.json` into `target/test-classes/infra/`.
- [x] 1.2 Add `InfraFixtures` (SnakeYAML with an undefined-tag constructor + Jackson JSON loader + policy-action flattener).

## 2. Phase A — blocking fixes
- [x] 2.1 `RunnerSecurityGroup`: add TCP 27017 egress.
- [x] 2.2 `deployer-policy.json`: add `ssm:PutParameter`, S3 delete/list/version actions, IAM read-back actions.
- [x] 2.3 `S3MainBucket`: `DeletionPolicy: Retain` + `UpdateReplacePolicy: Retain`.
- [x] 2.4 `S3UploadService.deleteAllObjects`: version-aware.
- [x] 2.5 `SetupCommand`: validate `--mongo-uri` before any AWS call.

## 3. Phase B — security boundary
- [x] 3.1 `BaasConfig`: `aws.operatorProfile` + `resolveOperatorProfile()`.
- [x] 3.2 `run`/`results`/`config show` use operator credentials; `admin` commands keep `aws.profile`.
- [x] 3.3 `deployer-policy.json`: drop OIDC actions and `iam:UpdateAssumeRolePolicy`.
- [x] 3.4 `deployer-policy.json`: scope CloudFormation and IAM resources.
- [x] 3.5 `operator-policy.json`: pin account wildcards; add the drift test.
- [x] 3.6 `baas config sync --core-stack-name <name>` + scoped `cloudformation:DescribeStacks` on `OperatorRole`, so an operator can populate `config.yaml` without hand-copying it.

## 4. Phase C — operability
- [x] 4.1 User-data ships `/var/log/cloud-init-output.log` to S3 before terminating.
- [x] 4.2 `RunCommand` poll loop checks instance state.
- [x] 4.3 `S3MainBucket` lifecycle rules.

## 5. Phase D — hygiene
- [x] 5.1 Tag convention (`baas-role` on the bucket, `baas-request-id` on instances).
- [x] 5.2 `${AWS::Partition}` throughout both templates.
- [x] 5.3 `ec2:RunInstances` instance-type and region conditions.
- [x] 5.4 CI template `ssm:GetParameter` alignment + `s3:GetObject`.

## 6. Documentation
- [x] 6.1 `infra/README.md`: operator-profile flow, Atlas allowlist reality, failure-log location.
- [x] 6.2 Correct the "443 reaches Atlas" claim in `docs/redesign.md` and the "runner's egress IP" instruction in `docs/aws-migration-plan.md`.

## 7. Manual verification
- [x] 7.1 `baas admin setup --mongo-uri "..."` against a scratch account under the revised deployer policy only.
  - Run against account 381492019823, eu-central-1, as `baas-admin-wiktor` holding only the deployer policy. Stack `CREATE_COMPLETE`, SSM SecureString written, config populated from outputs.
  - Found a real gap: the policy had no `iam:PassRole`, so `RunnerInstanceProfile` could not be created. Fixed and re-verified.
  - Confirmed deployed: security-group egress 80/443/**27017**; all four bucket lifecycle rules; the two-statement `ec2:RunInstances` split with the instance-type constraint only on the instance leg.
  - `ssm:GetParameter` is denied to the deployer by design — writing is the deployer's job, reading the operator's.
- [x] 7.2 `baas run jmh` under an assumed `BaasCliOperatorRole` — confirm the Mongo write succeeds and results appear.
  - Ran `baas run jmh` with `fake-jmh-benchmarks.jar` under an operator profile assuming `3q7i7s65-operator-role`. `Run status: completed`; `jmh-output.txt`, `run-status` and `cloud-init-output.log` all in S3; instance self-terminated.
  - **The split `ec2:RunInstances` grant works** — `Instance launched: i-0c87b8f59461b2f19`. This was the one thing no test could prove, since the bug it fixes is an IAM evaluation behaviour.
  - Also exercised: S3 upload under operator credentials, instance-state polling, the `failed:` path and its log pointer (on the first attempt), and the cloud-init log upload.
  - Found and fixed a regression: the runner scans below its cwd for `.log` files, and cloud-init starts user-data in `/`, so it walked the whole root filesystem and died on `/proc`. Now runs from `/app`.
  - The Mongo write itself was deliberately not exercised — the SSM parameter was removed so `DatabaseServiceBuilder` took its `NoOpDatabaseService` path, which the log confirms. Re-run against a reachable Atlas URI to close that last gap.
- [x] 7.3 `baas admin teardown --yes --delete-bucket` — confirm the bucket empties and the stack reaches `DELETE_COMPLETE`.
  - Emptied the bucket, deleted it explicitly, deleted the stack and the SSM parameter. The explicit `DeleteBucket` is the fix for `DeletionPolicy: Retain` leaving the bucket behind; without it the deterministic prefix makes the next `setup` unrecoverable.
