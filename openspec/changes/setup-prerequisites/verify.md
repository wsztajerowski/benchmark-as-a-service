# Verify — setup-prerequisites

## 1. Blocking assumptions

### 1.1 Simulator spike

Run with `baas-admin` (`user/baas-admin-wiktor`) in us-east-1, against the policy rendered for
`baas-development`.

2026-10-13, single action and single resource:

| Action | Resource | Decision |
|---|---|---|
| `ec2:Describe*` | `*` | `allowed` |
| `imagebuilder:CreateComponent` | `…:component/baas-development-*` | `allowed` |
| `imagebuilder:CreateComponent` | `…:component/baas-development-component-runner-base/1.3.0` | `allowed` |
| `sts:AssumeRole` | `…:role/baas-development-role-operator` | `allowed` |
| `cloudformation:CreateStack` | `…:stack/nobody-here/*` | `implicitDeny` |
| `s3:Get*` | `arn:aws:s3:::baas-development` | `allowed` |
| `s3:Get*` | `arn:aws:s3:::nobody-here` | `implicitDeny` |

So wildcard action names and pattern resources are accepted, and matched as IAM matches them.

2026-10-13, multiple actions and resources (the `IAM` statement: 16 actions × 5 resources):

- **All 16 actions in one call** → `InvalidInput: Actions [iam:AddRoleToInstanceProfile, …,
  iam:UntagRole] and [iam:Get*, iam:List*] require different authorization information.` One call
  per *statement* is therefore impossible in general. **Deviation from the design:** one call per
  *action*, carrying all of its statement's resources.
- **One action, five resources** returns one evaluation result whose `ResourceSpecificResults`
  holds one entry per resource. A resource whose type the action does not apply to is reported
  `implicitDeny` there, while the action's own decision stays `allowed`:
  - `iam:CreateRole`: 3 role ARNs `allowed`, 2 instance-profile ARNs `implicitDeny`, overall `allowed`;
  - `iam:AddRoleToInstanceProfile`: the reverse, overall `allowed`;
  - `iam:Get*`: all 5 `allowed`.
- **A real gap of the right type** turns the overall decision into a denial: `iam:CreateRole` on
  `role/baas-development-role-runner` (`allowed`) and `role/nobody-role-runner` (`implicitDeny`) →
  overall `implicitDeny`.
- **Deviation from the design:** a pair is judged by the **action's overall decision**, not by its
  resource-specific results, which would raise false alarms on every type mismatch inside a
  statement. This is the rule `simulateCriticalActions` already applies.

### 1.2 Permission-failure reasons from CloudFormation

Neither live stack holds a `*_FAILED` event: both were recreated on 2026-10-12. The reasons recorded
in the repository from earlier failures:

- `OperatorRole: User … is not authorized to perform: iam:UpdateRole on resource: …` (the
  `baas-3q7i7s65` update, `cli-driven-ci-workflows`);
- `AccessDenied` on `RunnerImagePipeline` (the first pipeline in an account without
  `iam:CreateServiceLinkedRole`, CLAUDE.md);
- `is not authorized to perform: s3:PutObject on resource: …` (an S3 grant gap).

Shapes AWS also uses for the resources this stack creates:

| Service | Shape | Example |
|---|---|---|
| EC2 | message | `You are not authorized to perform this operation.` |
| EC2 | code | `UnauthorizedOperation` |
| S3 | message | `Access Denied (Service: S3, Status Code: 403 …)`, with a space |

The first is covered by `not authorized to perform`; the S3 one by neither design string.
**Deviation from the design:** a reason is a permission failure when, lower-cased, it contains
`not authorized to perform`, `accessdenied`, `access denied` or `unauthorizedoperation`.

### 1.3 Removed parameters on update

CloudFormation drops a parameter the new template no longer declares, but it **refuses** an update
that names one: passing a key absent from the template fails with `Parameters: [<key>] do not exist
in the template` (`UpdateStack`, parameter validation). The assumption as written holds only if
setup stops sending the key. It does not: `CloudFormationService.updateStackParameters` builds its
key set from `stack.parameters()`, the *deployed* stack's, and sends each one absent from `changed`
as `UsePreviousValue`. After this change the first update of either live stack would therefore be
refused for `UseExistingVpc` and the three `Existing*` keys, for `admin image build` as well as for
setup.

**Deviation from the design (added decision):** the carried-forward set becomes the deployed keys
**that the new template declares**, plus `changed`. The declared keys are read from the bundled
template's top-level `Parameters:` block, and a test pins that read against the full YAML parse the
template tests use. Alternative rejected: `cloudformation:GetTemplateSummary`, which would add an
action to the deployer policy, and the design keeps the policy's content unchanged.

## 2–7. Implementation

| Task | Code | Tests (all green) |
|---|---|---|
| 2.1–2.2 | `DeployerPreflight.simulate`, `probes`; `criticalActionsToResources` deleted | `DeployerPreflightTest` (7): the nine unconditioned statements and none of the four conditioned ones; one action per request; a denied action reported once; a caller that may not simulate gets no check |
| 2.3 | `SetupCommand` uses `simulate(callerArn, policy)` | `SetupCommandTest.aStatementNewerThanTheAttachedPolicyIsCaught` |
| 3.1–3.3 | `--policy-file`, `writePolicyFile`, `handOverPolicy`, `administratorCommands` | `SetupCommandTest`: file written, unwritable path refused, commands for an IAM user / an assumed role / an Identity Center role, stdout still only the policy with and without the file |
| 4.1 | `CloudFormationService.StackOperationException`, `isPermissionFailure` | `CloudFormationServiceTest`: five permission shapes recognised, three other failures not |
| 4.2 | `SetupCommand.call()` hands over the policy on a permission `StackOperationException` | **no unit test (W1)** |
| 5.1 | `OperatorSession.stalePolicyHint` | `OperatorSessionTest` (15): the deployer-source denial carries the hint; an operator-profile denial does not |
| 6.1 | template: four parameters, `CreateNetworking`, 11 conditions and 5 `!If`s gone | `CoreTemplateTest` (44), incl. `noOperatorSuppliedNetworkingIsAccepted`; `theRunnerSecurityGroupDescriptionIsNeverEdited` unchanged |
| 6.2 | the four options, `validateNetworkingOptions`, `networkingParameters`, `requireNetworkingUnchanged` gone | `SetupCommandTest.theExistingVpcOptionsAreGone`; the built JAR's `setup --help` lists none of them and `--use-existing-vpc` is `Unknown option` |
| 6.4 | `updateStackParameters` carries forward only declared keys; `declaredParameters` | `CloudFormationServiceTest`: an undeclared deployed key is not sent, a declared one is sent as `UsePreviousValue`; the read matches `InfraFixtures.coreTemplate()` |
| 7.1–7.2 | `EnvironmentManifest.diff` compares memory in MiB | `EnvironmentManifestTest` (13): 8 KiB apart → no difference; 15510 vs 15708 MiB shown; non-numeric compared as is; the manifest keeps KiB |

Whole `baas-cli` unit suite: 832 tests, 0 failures.

**Deviation:** the design's "small failure value" returned by `CloudFormationService` is an
`IllegalStateException` subtype, `StackOperationException`, carrying the flag. Every existing
`catch (IllegalStateException)` keeps working unchanged.

- **W1:** the branch in `SetupCommand.call()` that hands over the policy on a permission stack failure
  has no unit test, because `call()` builds its AWS clients itself (the same seam gap as
  `pr83-review-fixes` W1). Its parts are tested: `isPermissionFailure`, `StackOperationException` and
  `handOverPolicy` (via `refusalForMissingRights`).

## 9. Live verification (manual)

CLI and runner from this branch's build: `1bd76f6` for 9.1–9.2b, `b28ed20` from 9.4 on, each copied
aside before use.
`baas-development` (us-east-1) unless stated.

| # | Check | Result |
|---|---|---|
| 9.1 | `admin deployment setup -v` on the existing stack, which still carries `UseExistingVpc`, `ExistingVpcId`, `ExistingSubnetId`, `ExistingSecurityGroupId` | Exit 0. `Simulated 56 action(s) …; denied: []`. The update sent only the declared parameters and CloudFormation answered "No updates are to be performed": removing parameters and conditions without touching a resource is no update to it, so nothing was replaced. Subnet `subnet-0b0af0585e518511a`, runner group `sg-091964ac0cf2601d0` and VPC `vpc-0b45f6c3f0c7b3f9e` unchanged. **The preflight took ~26 s** (56 sequential calls): see the parallel fix below |
| 9.2 | setup with `--deployer-aws-profile lynx --policy-file p.json` | **Not the intended path.** `lynx` (`user/wsztajerowski_baas`) may not simulate (`Cannot simulate policies (AccessDenied)`, confirmed with `-v` in a scratch `user.home`). The update had nothing to change, so CloudFormation made no resource call that could be denied, and setup exited 0. It then saved `deployerProfile: lynx` in `~/.baas/deployments/baas-development.yaml`; the file was restored from the copy taken before the run and checked byte-identical. Recorded as W2 |
| 9.2b | the shortfall path, live: `--deployment prtest-shortfall setup --region us-east-1 --deployer-aws-profile baas-admin --policy-file p3.json`, in a scratch `user.home` (baas-admin's policy covers its own deployments only) | Exit 1. stderr names the denied actions, then `Attach the deployer policy … written to …p3.json` and the filled-in `create-policy --policy-name BaasCliDeployerPolicy-prtest-shortfall`, `attach-user-policy --user-name baas-admin-wiktor` and `create-policy-version`. stdout is the 13-statement policy, byte-identical to `p3.json`. No deployment file written, nothing created (the preflight comes before any CloudFormation call) |

| 9.4 | with the build at `b28ed20`: `admin deployment teardown --yes`, then `setup --region us-east-1 --deployer-aws-profile baas-admin` (the deployment held no jobs; its results, table and `~/.baas` backed up first) | Teardown exit 0, 70 s: bucket emptied, stack deleted, image `ami-0d7ec69cf6ed538c9` and snapshot retired, file removed. Setup exit 0, 3 min 44 s: `Simulated 56 action(s) in 13 call(s) …; denied: []`, `CREATE_COMPLETE` on the new template, which carries none of the four networking parameters; the file was written back with the same content as before |
| 9.3 | `admin image build` (paid 1), then the same `jmh Incrementing_Synchronized -f 1 -wi 1 -i 2` twice in parallel on `c5.2xlarge` with this build's runner (paid 2 and 3) | Image 1.3.0 `ami-05f4851d3c30e3cb2` in 13 min 48 s; its own stack update was accepted ("already up to date") on the new template. Jobs `20261013T183232073Z-21ea4e88` and `20261013T183232065Z-2aac7065` `completed`, exit 0, instances terminated by themselves. `jobs diff`: "No differences. Both jobs measured on the same environment.", exit 0. Cross-checked against the downloaded `environment.json`s: all 22 fields identical, `memory.totalKb` included, so this pair did **not** exercise the MiB rounding, which rests on `EnvironmentManifestTest` (W3) |
| 9.5 | `baas-381492019823` (CI's): `admin deployment setup -v` after 9.1–9.4 passed | Exit 0. `Simulated 56 action(s) in 13 call(s) …; denied: []`; the update was accepted ("already up to date"). Outputs (subnet, security group, VPC, operator role) and the three federation parameters are unchanged, and so is its deployment file. CI's e2e on the PR is the remaining check |

Paid tests: 3 of the 5 allowed (one image build, two runs).

- **W3:** the live re-run pair recorded identical memory, so the MiB normalisation is proven by unit
  tests only (`memoryAFewKibApartIsNoDifference`, `aRealMemoryDifferenceIsShownInMib`). The 8 KiB case
  it fixes was seen live on 2026-10-07 and 2026-10-10.
- **W4:** a stack created before this change keeps its old template, `UseExistingVpc` included,
  until an update that touches a resource: CloudFormation treats dropping parameters and conditions
  alone as no change (9.1, 9.5). Harmless, since every update now sends only the declared
  parameters, but `describe-stacks` still lists the four parameters on both live stacks for now.

- **W2** (predates this change): with an identity that may not simulate, a re-setup whose update
  has nothing to change succeeds, because no resource call is made that could be denied, and it
  stores that identity as `aws.deployerProfile`. A later setup that does change something then fails
  on the stack's first permission check. That failure is now handed over with the policy (task 4),
  but the stored profile is still the wrong one. Candidate: store `--deployer-aws-profile` only after
  a create or an applied update.

**Deviation: fewer simulator calls, not parallel ones.** One call per action cost ~26 s per setup
(9.1, 9.2b), and the bound is IAM's throttling of `SimulatePrincipalPolicy`, not latency: eight
parallel callers exhausted the SDK's retries (`Cannot simulate policies (Throttling)`), which silently
turned the check off, and four with per-call backoff were no faster. What helps is fewer calls. Each
statement's actions now go in one call, its wildcard actions in a second, because IAM refused every
mixed statement of this policy along exactly that line (spiked: ImageBuilder 8 + 2, IAM 14 + 2,
ResultsTableLifecycle 7 + 1, S3WorkingBucketLifecycle 9 + 2, all accepted). A call refused all the
same is split in half, and a throttled call backs off and retries. Live, 56 actions in **13 calls,
~4–6 s**, with a denied set and a stdout identical to the one-call-per-action run (shortfall name),
and nothing denied on `baas-development`.

## 10. Verify

- 10.1: full reactor `mvn clean verify`, `ASYNC_PATH` exported: BUILD SUCCESS (model 73, fixtures
  44 + 18, runner 42 including `JmhWithAsyncProfilerSubcommandServiceIT`, not skipped; CLI 816).
  After the call-count change (`b28ed20`), the `baas-cli` unit suite again: 819, 0 failures.
- 10.2: `/opsx:verify`, below.

## Verification report

| Dimension | Status |
|---|---|
| Completeness | 29/29 tasks |
| Correctness | 6/6 delta requirements implemented; 26/27 scenarios have a test or a live check (W1) |
| Coherence | Design followed after four recorded deviations (below); `design.md` updated to match |

### Requirement → code → test

| Requirement (delta) | Code | Test / live | Gap |
|---|---|---|---|
| MODIFIED *Setup renders the deployer policy and stops when the caller lacks it* | `DeployerPreflight.simulate`/`probes`, `SetupCommand` (`--policy-file`, `writePolicyFile`, `handOverPolicy`, `administratorCommands`), `CloudFormationService.StackOperationException`/`isPermissionFailure` | `DeployerPreflightTest` (10), `SetupCommandTest` (41), `CloudFormationServiceTest` (6), `HelpAndHintsTest` (command gone); live 9.1, 9.2b, 9.4, 9.5 | W1 (stack-event hand-over through `call()`) |
| MODIFIED *Day-to-day commands resolve operator credentials* (stale-policy hint) | `OperatorSession.stalePolicyHint` | `OperatorSessionTest` (15) | — |
| ADDED *The image build and the runner use separate groups in the stack's own VPC* | `infra/cf-template-core.yaml` | `CoreTemplateTest.theImageBuildHasItsOwnSecurityGroupAndOnlyItKeepsPort80`, `noOperatorSuppliedNetworkingIsAccepted`, `theRunnerSecurityGroupDescriptionIsNeverEdited` | — |
| REMOVED *The image build and the runner do not share a security group* | (replaced by the ADDED one) | as above | — |
| REMOVED *VPC parameters are immutable once the deployment exists* | options, checks and template parameters deleted; `updateStackParameters` filters to declared keys | `SetupCommandTest.theExistingVpcOptionsAreGone`, `CloudFormationServiceTest.aDeployedParameterTheTemplateNoLongerDeclaresIsNotSent`, `theDeclaredParametersAreTheBundledTemplatesParameters`; live 9.1, 9.4, 9.5 | W4 |
| MODIFIED *Two jobs' environments are compared group by group* (MiB) | `EnvironmentManifest.compared` | `EnvironmentManifestTest` (13); live 9.3 | W3 |

### Deviations from design and tasks

1. Simulation is per statement, plain actions and wildcard actions apart, with a halving fallback,
   not per statement as designed nor per action as first corrected (tasks 1.1, 9; `design.md`
   rewritten).
2. Permission failures are matched in four shapes, not two (task 1.2).
3. `updateStackParameters` carries forward only declared parameters, a decision added after task 1.3
   showed the removal would otherwise break every existing stack's next update (task 6.4 added).
4. The stack failure is an `IllegalStateException` subtype rather than a separate value.

### Warnings

W1–W4 above. None is critical. W2 (a no-op setup stores an identity that cannot deploy) and W4
(old templates linger until a resource change) predate or follow from CloudFormation's behaviour,
not from defects in this change.

### Assessment

No critical issues; four warnings recorded. Ready for review, and for archive once merged.
