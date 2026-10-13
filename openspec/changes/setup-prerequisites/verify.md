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
