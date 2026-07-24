## 1. Commit the WIP infra templates

- [x] 1.1 Fix `infra/cf-template-core.yaml`: change `RunnerRole`'s S3 policy resource ARNs from `${ResourceNamePrefix}-main` to `baas-${ResourceNamePrefix}` to match the actual `S3MainBucket` name.
- [x] 1.2 Review `infra/cf-template-ci.yaml` and `infra/deployer-policy.json` as-is; confirm no other cross-references need fixing.
- [x] 1.3 Commit `infra/cf-template-core.yaml`, `infra/cf-template-ci.yaml`, `infra/deployer-policy.json` (currently untracked).

## 2. Source the core CloudFormation template from `infra/` (single source of truth)

- [x] 2.1 Configure `baas-cli/pom.xml` to copy `infra/cf-template-core.yaml` into the JAR at `templates/` — no duplicated copy under `src/main/resources/`.
- [x] 2.2 Delete `baas-cli/src/main/resources/templates/cf-template-main.yaml`.
- [x] 2.3 Update `SetupCommand.loadTemplate()` to read `/templates/cf-template-core.yaml`.

## 3. Strip GitHub/OIDC concerns from SetupCommand

- [x] 3.1 Remove `--github-org`, `--github-repo`, `--workflow-id`, `--workflow-branch`, `--oidc-provider-arn` options and their fields from `SetupCommand.java`.
- [x] 3.2 Remove the corresponding `params.put(...)` entries for the GitHub/OIDC parameters in `SetupCommand.call()`.
- [x] 3.3 Confirm the remaining `SetupCommand` parameter map contains only `ResourceNamePrefix`, `UseExistingVpc`, `ExistingVpcId`, `ExistingSubnetId`, `ExistingSecurityGroupId` (matches `cf-template-core.yaml`'s `Parameters:` block).

## 4. Update TeardownCommand

- [x] 4.1 Remove the `"GitHub OIDC provider retained..."` message from the end of `TeardownCommand.call()` — no OIDC concern is in scope for this stack anymore.

## 5. Rename config field: stackName → coreStackName

- [x] 5.1 In `BaasConfig.AwsConfig`, rename the `stackName` field (and getter/setter) to `coreStackName`. The default literal is only a placeholder — `baas admin setup` always overwrites it with `baas-<prefix>`.
- [x] 5.2 Update every `stackName` call site to `getCoreStackName()`/`setCoreStackName(...)`: `SetupCommand` (the `setStackName("baas-" + prefix)` call), `TeardownCommand`, `ConfigSetSubcommand` (its `setStackName(...)` call), and `ConfigShowSubcommand` (its `getStackName()` print — also relabel the printed `stackName:` line to `coreStackName:`).
- [x] 5.3 Update the `--stack-name` option handling in `ConfigSetSubcommand` and `TeardownCommand` to read/write `coreStackName`. *(`SetupCommand` has no `--stack-name` — it derives `baas-<prefix>` from the caller ARN.)*

## 6. Introduce the `admin` subcommand group

- [x] 6.1 Move `SetupCommand.java` and `TeardownCommand.java` into a new package `pl.wsztajerowski.baas.commands.admin`.
- [x] 6.2 Create `AdminCommand.java` (picocli `@Command(name = "admin", subcommands = {SetupCommand.class, TeardownCommand.class})`), mirroring `BaasApp`'s existing root-command style.
- [x] 6.3 Update `BaasApp.java`: replace `SetupCommand.class, TeardownCommand.class` in the root `subcommands` array with `AdminCommand.class`; update imports.
- [x] 6.4 Verify `baas setup`/`baas teardown` (without the `admin` prefix) are no longer resolvable, and `baas admin setup --help` / `baas admin teardown --help` work.
- [x] 6.5 Update the stale `baas teardown --stack-name ... --yes` hint in `CloudFormationService`'s ROLLBACK_COMPLETE error message to `baas admin teardown`.

## 7. Update documentation

- [x] 7.1 Document the two-stack deploy procedure in `infra/README.md`: `baas-core`, then a manual `aws cloudformation deploy --template-file cf-template-ci.yaml ...` (with `RunnerRoleArn`/`BucketName` parameter overrides taken from the core stack's outputs) for the CI stack.
- [x] 7.2 Document `BaasCliOperatorRole` and `BaasCliDeployerPolicy` in `infra/README.md`: the operator role's assume-role setup flow (referencing `infra/operator-policy.json` for its permission shape), and the deployer policy's out-of-band creation (referencing `infra/deployer-policy.json`). *(Revised: operator identity turned out to be an auto-created Role, not a policy meant to be manually attached — see section 10 below.)*
- [x] 7.3 Add a note to `docs/redesign.md` and `docs/aws-migration-plan.md` marking the setup/teardown/command-structure sections as superseded by this OpenSpec change (`openspec/changes/baas-cli-core-ci-split/`).

## 8. Tests

- [x] 8.1 Add a unit test verifying `SetupCommand` rejects removed options (`--github-org`, `--prefix`, or any removed option) — picocli should report an unknown-option error.
- [x] 8.2 Add a unit test verifying `BaasConfig` round-trips `aws.coreStackName` correctly via Jackson YAML (read/write `~/.baas/config.yaml`-shaped content).
- [x] 8.3 Add a unit test verifying `baas admin setup --help` and `baas admin teardown --help` resolve correctly through `BaasApp`'s picocli command tree, and that top-level `baas setup`/`baas teardown` do not.
- [x] 8.4 Add a unit test for `SetupCommand.computePrefix(...)`: deterministic, 8-character lowercase base32 output for a given ARN.

## 9. Manual verification

- [x] 9.1 Run `mvn -pl baas-cli clean package` and confirm the shaded JAR builds with only `cf-template-core.yaml` under `templates/`.
- [ ] 9.2 Manually run `baas admin setup --mongo-uri "..."` against a scratch AWS account; confirm the deployed stack (`baas-<prefix>`) has no OIDC/WorkflowRole resources and that the EC2 runner (via `baas run`) can write to `S3MainBucket` (validates the bucket-policy fix from task 1.1). *(Running `baas run` for this now requires assuming `BaasCliOperatorRole` first — see section 10.)*
- [ ] 9.3 Manually run `baas admin teardown` and confirm all four safety gates still behave as before (abort on active run, confirmation prompt, bucket retained by default, Mongo untouched). *(First attempt against a real scratch account crashed at the active-run gate due to a missing `ec2:DescribeInstances` permission — see section 10 for the fix; retry once the updated `deployer-policy.json` is applied to the deployer identity.)*

## 10. Operator role refinement (post-implementation)

- [x] 10.1 Replace the originally-implemented `OperatorPolicy` (`AWS::IAM::ManagedPolicy`, meant to be attached directly to a human IAM user) with `OperatorRole` (`AWS::IAM::Role`) in `cf-template-core.yaml`, trust-scoped to the account root, mirroring `RunnerRole`'s structure. Rationale: consistency with the existing assumed-role pattern (`RunnerRole`, `WorkflowRole`) and time-boxed sessions instead of standing access on long-lived user credentials.
- [x] 10.2 Add the AMI-lookup SSM permission (`ssm:GetParameter` on `/aws/service/ami-amazon-linux-latest/*`) to the operator role's policy — missing from the original design/spec text entirely; without it `baas run` cannot resolve the runner AMI ID.
- [x] 10.3 Revert the `iam:CreatePolicy`/`CreatePolicyVersion`/`DeletePolicy`/etc. additions to `deployer-policy.json` added for the ManagedPolicy approach — a Role reuses the `iam:CreateRole`/`PutRolePolicy` actions already granted for `RunnerRole`, so these are unnecessary.
- [x] 10.4 Fix `deployer-policy.json` gaps found via manual verification against a real scratch AWS account: add `cloudformation:DeleteStack` (teardown couldn't delete the stack at all), `ec2:DescribeInstances` (teardown's active-run safety gate crashed without it), and `ssm:DeleteParameter` scoped to the mongo path (teardown's SSM cleanup step would have failed).
- [x] 10.5 Add `infra/operator-policy.json` as a static reference copy of the operator role's permission statements (wildcard ARNs), for review or manual role creation before a core stack exists.
- [x] 10.6 Update `infra/README.md`'s IAM section and `SetupCommand`'s post-deploy output to describe the assume-role flow (`sts:AssumeRole` grant + `role_arn`/`source_profile` profile) instead of `iam:attach-user-policy`.
- [x] 10.7 Update `design.md`, `proposal.md`, and `specs/core-stack-provisioning/spec.md` to reflect the operator-role model (this revision).
