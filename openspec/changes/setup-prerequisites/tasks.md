# Tasks

## 1. Verify blocking assumptions

- [ ] 1.1 Record the 2026-10-13 simulator spike in `verify.md` (wildcard actions and pattern resources
  accepted and matched; an uncovered deployment's stack and bucket `implicitDeny`), then repeat one
  multi-resource call (the `IAM` statement's 16 actions × 5 resources) and confirm that
  resource-specific results come back per pair. Done when `verify.md` holds both outputs.
- [ ] 1.2 Collect the permission-failure reasons CloudFormation reports from past failed operations
  (`aws cloudformation describe-stack-events` on both live stacks, filtered to `*_FAILED`) and confirm
  that the two matched shapes (`not authorized to perform`, `AccessDenied`) cover every
  permission-caused one. Done when the reasons are listed in `verify.md` and any third shape is added
  to the design.
- [ ] 1.3 Confirm from the CloudFormation documentation that an update omitting a template parameter
  the stack has drops it without `UsePreviousValue`, and record the citation in `verify.md`.

## 2. Derived preflight

- [ ] 2.1 `DeployerPreflight`: build the probe set from the rendered document (`Allow` statements
  without `Condition`; actions and resources verbatim; one `SimulatePrincipalPolicy` per statement;
  denied when the resource-specific decision is not `allowed`), and delete
  `criticalActionsToResources`. Verify with a unit test over the rendered fixture: exactly the nine
  unconditioned statement IDs are probed, none of the four conditioned ones, and each request
  carries the statement's own actions and resources.
- [ ] 2.2 Unit-test the decision handling with a fake `IamClient`: a denied pair is reported by
  action name (de-duplicated across resources), a statement missing entirely is reported, and an
  `AwsServiceException` still yields "cannot check". Verify `mvn -pl baas-cli test
  -Dtest=DeployerPreflight*` passes.
- [ ] 2.3 Add the scenario *A statement newer than the attached policy is caught*: a fake simulator
  denying only `sts:AssumeRole` makes setup exit non-zero naming it, with nothing created. Verify in
  `SetupCommandTest`.

## 3. `--policy-file` and the administrator's commands

- [ ] 3.1 `SetupCommand`: `--policy-file <path>`, written right after rendering (overwriting), with a
  write failure stopping setup at exit 1 before any create. Verify with tests: the file holds the
  rendered policy when rights are present (and setup proceeds), and when they are missing (exit 1,
  the file named on stderr); an unwritable path creates nothing.
- [ ] 3.2 Print the IAM administrator's commands whenever missing rights are reported:
  `create-policy`, then `attach-user-policy` / `attach-role-policy`, or the IAM Identity Center note
  for an `AWSReservedSSO_` role; plus `create-policy-version --set-as-default`, all named
  `BaasCliDeployerPolicy-<deployment>`. Verify with a test per principal type, asserting the filled-in
  account, deployment, user or role name, and file.
- [ ] 3.3 Confirm `setup > policy.json` is unchanged: on a shortfall, stdout holds only the policy JSON,
  with and without `--policy-file`. Verify with the existing *The policy can be handed over* test plus
  a `--policy-file` variant.

## 4. Permission failures reported in stack events

- [ ] 4.1 `CloudFormationService.failureReport`: return the message plus whether a root-cause reason
  is a permission failure (the shapes from 1.2). Verify with unit tests over recorded event reasons,
  both shapes and a non-permission failure.
- [ ] 4.2 `SetupCommand`: on a permission stack failure, append the rendered policy on stdout, or name
  the `--policy-file` path; on any other stack failure, print no policy. Verify with tests for both.

## 5. Stale-policy hint for day-to-day commands

- [ ] 5.1 `OperatorSession`: when the source is `aws.deployerProfile` and `AssumeRole` is denied, add
  the paragraph naming the possibly stale deployer policy and `baas --deployment <name> admin
  deployment setup --policy-file <name>-deployer-policy.json`. Verify in `OperatorSessionTest`: the
  deployer-source denial carries it, and an `aws.operatorProfile` or default-chain denial does not.

## 6. Remove existing-VPC support

- [ ] 6.1 Template: delete `UseExistingVpc` and the three `Existing*` parameters, the `CreateNetworking`
  condition and every `Condition: CreateNetworking` line, and reduce each `!If [CreateNetworking, …]`
  to its `!Ref`. Leave logical IDs, output names and `RunnerSecurityGroup`'s `GroupDescription`
  unchanged. Verify `CoreTemplateTest`, with an added assertion that no parameter names an existing
  VPC, subnet or security group and no condition chooses between created and supplied networking;
  `theRunnerSecurityGroupDescriptionIsNeverEdited` still passes.
- [ ] 6.2 `SetupCommand` / `CloudFormationService`: remove `--use-existing-vpc`, `--vpc-id`,
  `--subnet-id`, `--sg-id`, `validateNetworkingOptions` and the update-time networking comparison,
  with their tests. Verify that `baas admin deployment setup --help` lists none of them, and that
  `--use-existing-vpc` is an unknown option.
- [ ] 6.3 Diagrams `baas-setup.mmd`, `baas-lifecycle.mmd`, `baas-build-image.mmd`: drop the
  existing-VPC branch. Render each with `mmdc` into the scratchpad and look at the PNG.

## 7. `jobs diff` memory in MiB

- [ ] 7.1 `EnvironmentManifest.diff`: normalise `memory.totalKb` and `memory.swapTotalKb` on both sides
  to `<KiB / 1024, rounded down> MiB` before comparing, and print the normalised values; a
  non-numeric value is compared as is. Verify in `EnvironmentManifestTest`: 15882372 vs 15882380 is
  no difference; 15882372 vs 16085124 prints `15510 MiB` and `15708 MiB`; a non-numeric value still
  diffs.
- [ ] 7.2 Confirm that `jobs show` and the downloaded `environment.json` still show KiB. Verify with
  the existing `jobs show` test unchanged.

## 8. Documentation

- [ ] 8.1 CLAUDE.md: delete *Networking is immutable once a deployment exists*, the `--use-existing-vpc`
  sentence under the `RunnerSecurityGroup` rule, and `UseExistingVpc` from the core stack's
  description. Record that the preflight probes the rendered policy's unconditioned statements, and
  that the deployer policy is now attached as a customer-managed `BaasCliDeployerPolicy-<deployment>`.
- [ ] 8.2 `infra/README.md`:
  - `--stack-name` for `--deployment` in the core stack's `aws cloudformation` commands (the CI
    stack's section is left to `interactive-setup`);
  - the current prefix rule;
  - the policy size and the test's ceiling;
  - per-deployment policy names and `--policy-file` in the attach and upgrade steps;
  - remove the existing-VPC text.
- [ ] 8.3 `docs/review/open-findings.md`: delete jobs-command/W1's entry and its index row.
- [ ] 8.4 Release note: carry the four removed flags in the docs commit's body as a feature release,
  not a `!` and not a `BREAKING` footer (the semantic-release footer landmine). Verify the commit
  message parses as `feat`/`fix` only.

## 9. Live verification (manual)

- [ ] 9.1 Manual, on `baas-development` (the second deployment): run `admin deployment setup` with this
  build. Confirm in the stack events that the update removed the networking parameters and conditions
  and replaced no resource, and that the runner subnet and security-group IDs are unchanged.
- [ ] 9.2 Manual: run setup with `--deployer-aws-profile lynx` (an identity without the deployer
  policy) and `--policy-file p.json`. Expect exit 1, nothing created, `p.json` the rendered policy, and
  the administrator's commands naming `BaasCliDeployerPolicy-baas-development` and lynx's IAM user.
  If `lynx` may not simulate, the preflight is skipped and the first denied CloudFormation call
  reports instead, which lynx cannot get past either. Record which path ran.
- [ ] 9.3 Manual: `admin image build`, one `baas run` (jmh, `fake-jmh-benchmarks`), and a re-run on
  the same instance type. Confirm `jobs diff` reports no `memory` difference (and only a real one
  if the CPU model moved, finding E6).
- [ ] 9.4 Manual: `admin deployment teardown`, then setup again from scratch on `baas-development`, to
  confirm that a create on the new template succeeds. Record 9.1–9.4 in `verify.md`.
- [ ] 9.5 Manual: re-run setup on `baas-381492019823` (CI's deployment), checking as in 9.1, and
  confirm CI's e2e is green on the PR afterwards.

## 10. Verify

- [ ] 10.1 Full reactor `mvn verify` with `ASYNC_PATH` exported, green.
- [ ] 10.2 Run `/opsx:verify` and record the result in `verify.md`: the requirement → code → test →
  gap table, open warnings as W1, W2…, and any deviation from design or tasks.
