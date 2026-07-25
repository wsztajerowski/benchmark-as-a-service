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
- [ ] 4.1 User-data ships `/var/log/cloud-init-output.log` to S3 before terminating.
- [ ] 4.2 `RunCommand` poll loop checks instance state.
- [ ] 4.3 `S3MainBucket` lifecycle rules.

## 5. Phase D — hygiene
- [ ] 5.1 Tag convention (`baas-role` on the bucket, `baas-request-id` on instances).
- [ ] 5.2 `${AWS::Partition}` throughout both templates.
- [ ] 5.3 `ec2:RunInstances` instance-type and region conditions.
- [ ] 5.4 CI template `ssm:GetParameter` alignment + `s3:GetObject`.

## 6. Documentation
- [ ] 6.1 `infra/README.md`: operator-profile flow, Atlas allowlist reality, failure-log location.
- [ ] 6.2 Correct the "443 reaches Atlas" claim in `docs/redesign.md` and the "runner's egress IP" instruction in `docs/aws-migration-plan.md`.

## 7. Manual verification
- [ ] 7.1 `baas admin setup --mongo-uri "..."` against a scratch account under the revised deployer policy only.
- [ ] 7.2 `baas run jmh` under an assumed `BaasCliOperatorRole` — confirm the Mongo write succeeds and results appear.
- [ ] 7.3 `baas admin teardown --yes --delete-bucket` — confirm the bucket empties and the stack reaches `DELETE_COMPLETE`.
