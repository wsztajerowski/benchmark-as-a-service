# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 Confirm that narrowing the Image Builder write statement from `*/${PREFIX}-runner*` to `*/${PREFIX}-*` does not disturb the `ImageBuilderRead` / `ImageBuilder` split — re-read the CLAUDE.md invariant that `GetComponent`/`List*` evaluate against `component/*` and must stay `Resource: "*"`, and verify only the write statement changes.
- [x] 1.2 Render `deployer-policy.json` with the edited resource lines for `baas-<12-digit account>` and `baas-<12-digit account>-dev`, and verify both land under the 4608 test cap. Design predicts 4219 and 4271; a materially larger number means the naming needs shortening before the template is touched.
- [x] 1.3 Confirm the core stack already outputs everything the run path must resolve per invocation — `SubnetId`, `SecurityGroupId`, `RunnerInstanceProfileName` — by reading the template's Outputs block, so no new output is needed for the un-cached config design.
- [x] 1.4 Verify the longest generated name fits every service limit: `baas-<12 digits>-dev-profile-image-build` against IAM's 64-character role and instance-profile cap, Image Builder's name pattern, and S3's 63-character bucket cap.

## 2. Prefix derivation and modes

- [x] 2.1 Replace `SetupCommand.computePrefix`/`base32Encode` with account-derived naming (`baas-<accountId>[-<mode>]`) and verify the existing `SetupCommandTest` prefix cases are deleted and replaced by ones asserting the account form.
- [x] 2.2 Keep the installation unnameable from the command line, and verify a parameterised test rejects `--mode`, `--prefix`, `--name` and `--installation` on `baas admin setup`. (Revised: an earlier `--mode shared|dev` was implemented and then removed — dev mode is a developer's workflow, not a released capability. See design.md.)
- [x] 2.3 Resolve the installation in `baas admin build-image`, `baas admin image` and `baas admin teardown` from the configured prefix, so a by-hand installation adopted with `config sync --name` is addressable, and verify `--stack-name` still overrides on teardown.
- [x] 2.4 Update `DeployerPolicyCommand` to render from account and region, replace `--for-arn` with `--for-account`, add `--prefix` for a by-hand installation, and verify the rendered output matches what `SetupCommand` targets for the same account.

## 3. Template and policy renaming

- [x] 3.1 Re-compose every `${ResourceNamePrefix}` site in `cf-template-core.yaml` onto `<prefix>` / `<prefix>-<type>-<name>`, stripping the four hardcoded `baas-` literals and re-rooting the SSM path, and verify `CoreTemplateTest` still passes including the pinned-absence assertions (`runnerHasNoEgressToMongoAtlasAnyMore`, the missing `expire-uploaded-benchmark-jars` lifecycle rule).
- [x] 3.2 Verify `RunnerSecurityGroup`'s `GroupDescription` is byte-identical after the rename — changing it replaces the security group and moves its id, which strands anything holding the old one.
- [x] 3.3 Update `deployer-policy.json` and `operator-policy.json` to the new name shapes and verify `OperatorPolicyDriftTest` passes (it resolves the template's intrinsics to placeholder tokens before comparing, so both sides must move together).
- [x] 3.4 Update the deployer-policy size test to render with a realistic 12-digit-account prefix instead of an 8-character one, and verify it reports a number consistent with task 1.2 rather than under-measuring.
- [x] 3.5 Update `DeployerPreflight.criticalActionsToResources` to the new names and verify `DeployerPreflightTest` still covers all seven probed actions, including the `iam:UpdateRole` case that cost a stuck stack.

## 4. VPC parameter immutability

- [x] 4.1 Stop sending `UseExistingVpc`/`ExistingVpcId`/`ExistingSubnetId`/`ExistingSecurityGroupId` unconditionally in `SetupCommand.deploy`, and verify that an update naming no networking options carries the deployed values forward via `updateStackParameters`.
- [x] 4.2 Add the compare-and-refuse check for an update whose networking parameters differ from the deployed ones, and verify a unit test asserts both values appear in the error and that no `updateStack` call is issued.
- [x] 4.3 Verify the create path is unaffected — `--use-existing-vpc` with all three ids still deploys against existing networking, and the existing partial-options validation still rejects an incomplete set.

## 5. Configuration surface

- [x] 5.1 Delete `benchmark.asyncProfilerVersion` and `aws.vpcId` from `BaasConfig`, `ConfigShowSubcommand` and `ConfigSyncSubcommand`, and verify no reference remains outside test fixtures.
- [x] 5.2 Merge `aws.coreStackName` into `prefix` (they are now the same string), remove the stale `prefix = "baas"` and `coreStackName = "baas-main"` defaults, and verify every former `getCoreStackName()` caller resolves the stack from the prefix.
- [x] 5.3 Derive `bucket`, `resultsTable` and `runnerInstanceProfileName` from the prefix instead of storing them, and verify `config show` no longer reports them as stored fields.
- [x] 5.4 Resolve `subnetId` and `securityGroupId` from stack outputs per `baas run` invocation instead of from config, and verify a test covers the case where the configured value would have been stale.
- [x] 5.5 Change `baas config sync --core-stack-name` to a **required** `--name`, narrow it to verifying the stack exists and recording the prefix, and verify a missing `--name` exits with a required-option error having written nothing.

## 6. Read-only results table override

- [x] 6.1 Add `--results-table <name>` to `baas results` and `baas download` as a per-invocation override that persists nothing, and verify a test asserts `~/.baas/config.yaml` is unchanged after use.
- [x] 6.2 Verify the override is absent from every write path — `baas run` and `baas config set` must both report an unknown option.

## 7. Documentation

- [x] 7.1 Rewrite CLAUDE.md's "Stack and bucket names are derived from caller identity" invariant to the account-derived rule, document the single composition rule and the `shared`/`dev` vocabulary, and verify no stale reference to the ARN hash remains anywhere in the file.
- [x] 7.2 Update CLAUDE.md's config and teardown sections for the removed keys and the derived names. Record that a shared installation makes two concurrent `baas admin build-image` runs reachable, which the one-image ordering invariant does not survive, and that guarding it is deliberately left to its own change.
- [x] 7.3 Rewrite `infra/README.md`, deleting the "the operator cannot derive it, because the prefix is a hash of the deployer's ARN" passage and the ARN-hash description of per-identity policy rendering.
- [x] 7.4 Rewrite `TeardownCommand`'s retained-bucket message, which currently states the name comes from a hash of the caller ARN, and verify the assertion covering that text moves with it.
- [x] 7.5 Mark finding **A10** Fixed in `docs/review/baas-cli-findings.md` — both the status table row and the section body — noting that the fix taken differs from the one the finding proposed and why.
- [x] 7.6 Update finding **A6**'s body to record that the stale `coreStackName`/`prefix` defaults and two dead keys are gone, leaving silent unknown keys and the absent schema version open; verify its status stays Open.
- [x] 7.7 Update `openspec/changes/export-before-teardown/brainstorm.md`'s import rationale, which asserts that resource names derive from a caller-ARN hash so a different identity always means different names — under this change the same account means the same names, though its conclusion that the current config is authoritative still holds.
- [x] 7.9 Document the by-hand second installation in `infra/README.md` (deploy the core template with another `ResourceNamePrefix`, adopt with `config sync --name`, tear down), including the placeholder-component version trap, the retained bucket/table, the extra AMI snapshot cost, and the separate-account alternative.
- [x] 7.8 Update the sequence diagrams under `docs/diagrams/` whose flows show the ARN-derived prefix or `config sync --core-stack-name`.

## 8. Migration against live AWS — manual

- [x] 8.1 Deploy the new installation with `baas admin setup`, supplying the same GitHub federation options the current stack carries (they are not carried forward onto a create), and verify the printed prefix is `baas-<accountId>`.
- [x] 8.2 Run `baas admin build-image` and verify `/baas-<accountId>/runner/ami-id` names the new AMI and the AMI carries `baas-image-version` and `baas-parent-ami` tags.
- [x] 8.3 Re-render and re-attach the deployer policy by hand before any live verification — it is an inline policy on an IAM group, not the customer-managed flow `infra/README.md` describes, and `dynamodb-results-store` was bitten by exactly this omission.
- [ ] 8.4 Point CI at the new installation by updating the `CORE_STACK_NAME` variable, and verify `e2e-cloud-test.yml` passes on a `workflow_dispatch` run.
- [ ] 8.5 Tear down `baas-3q7i7s65` and verify its bucket and results table survive as the archive, reachable via `baas results --results-table baas-3q7i7s65-results`.
- [ ] 8.6 Deregister the retired AMI and delete its snapshot by hand, and verify exactly one BaaS-owned snapshot remains — the one-image rule only retires images the same installation replaced, so this is not reclaimed automatically.

## 9. End-to-end verification — manual, no automated test covers the `baas run` path

- [x] 9.1 Run `baas run jmh-with-async` against `fake-jmh-benchmarks` on the new installation and verify `run-status` reads `completed`, `environment.json` and `cloud-init-output.log` are present, and the instance self-terminated.
- [x] 9.2 Verify the stored measurement's `tags` carry `imageVersion`, `instanceType`, `jdk`, `cpuModel`, `cpuArch` and `type`, and that they agree with the same run's `environment.json`.
- [x] 9.3 Compare the benchmark score against a pre-change run of the same benchmark on the retired installation, recording both values and the spread. Run-to-run variance here is large — CI history spans 10.0M–29.6M ops/s on one benchmark — so record the range rather than treating a single pair as signal, and investigate any difference that sits outside it rather than accepting it. Use `baas env diff` between the two runs' `environment.json` files to attribute any gap to a changed JDK, kernel or instance type.
      - Measured: new `20260921T143032906Z-acbd0d0f` = **11,238,813 ops/s**; nearest pre-change run `20260920T182614267Z-2c61b55d` = **11,787,431 ops/s** (-4.7%). Recent same-config cluster 8.6M-12.2M; full history of this benchmark 6.74M-29.56M over 57 measurements. The delta sits deep inside the spread. `environment.json` differs in 2 of 21 fields (cpuModel 8275CL->8124M, memoryTotalKb), both the physical c5.2xlarge host EC2 assigned rather than anything the image controls; jdk, kernel, OS, perf, awscli, async-profiler, imageVersion and both kernel tunables are identical. No regression indicated.
- [x] 9.4 **Superseded.** Originally: verify `--mode dev` switching. `--mode` was removed — dev installations are a by-hand procedure now (`infra/README.md`, *A second installation*), so there is no CLI behaviour left to verify here. What the attempt established is kept: with a prefix-exact policy attached, anything addressing another prefix is refused at `cloudformation:DescribeStacks` and creates nothing (verified: no dev stack, no dev bucket), and two rendered policies do not fit inline (4219 + 4271 > 5120). Both are now documented rather than open.
- [x] 9.5 Verify a second `baas admin setup` naming different networking against the existing installation is refused with both values named, and that no stack update was submitted.

## 10. Close out

- [x] 10.1 Run the full reactor with `ASYNC_PATH` exported and verify `JmhWithAsyncProfilerSubcommandServiceIT` *ran* rather than silently skipping — a plain `mvn verify` skips the only end-to-end async coverage.
- [x] 10.2 Run `/opsx:verify` and record the result in `verify.md` in this change directory — the requirement → code → test → gap table, any open warnings under stable IDs (`W1`, `W2`, …) that later notes can cite, and any deviation from this design or task list.
