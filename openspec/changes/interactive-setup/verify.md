# Verify — interactive-setup

Paid tests used: **11 / 20** (see the running count under section 13) (image builds and `baas run` jobs; user's budget, 2026-10-14).

## 1. Blocking assumptions

### 1.1 Provider absent, policy published

2026-10-14, first attempt (before the user had published the new policy version):

```
$ aws --profile baas-admin iam get-open-id-connect-provider --open-id-connect-provider-arn \
    arn:aws:iam::381492019823:oidc-provider/token.actions.githubusercontent.com
An error occurred (AccessDenied) when calling the GetOpenIDConnectProvider operation: User:
arn:aws:iam::381492019823:user/baas-admin-wiktor is not authorized to perform:
iam:GetOpenIDConnectProvider ... because no identity-based policy allows ...
```

Not published yet. Code work continues; re-checked before live work (section 13).

2026-10-14, after the user published both policy versions:

```
$ aws --profile baas-admin iam get-open-id-connect-provider --open-id-connect-provider-arn …
ClientIDList:
- sts.amazonaws.com
CreateDate: '2024-12-15T18:53:42.260000+00:00'
```

The new `GitHubOidcProvider` statement is in effect. The old provider still exists. It is to be
deleted by the user before 13.3, which tests setup creating it, and is re-checked there.

### 1.2 `ListImagePipelineImages`

Read-only against `baas-development`'s pipeline:

```
$ aws imagebuilder list-image-pipeline-images --image-pipeline-arn \
    arn:aws:imagebuilder:us-east-1:381492019823:image-pipeline/baas-development-pipeline-runner
[ { "arn": "arn:aws:imagebuilder:us-east-1:381492019823:image/baas-development-recipe-runner/1.0.0/1",
    "status": "AVAILABLE", "created": "2026-10-13T18:18:29.772Z",
    "ami": "ami-05f4851d3c30e3cb2", "ver": "1.0.0/1" } ]
```

A finished build carries its output AMI and its recipe version in the ARN
(`image/<recipe>/<recipe version>/<build>`), which is what adoption compares. The API reference
describes the call as listing the images a pipeline created, with no state filter. Whether an
in-progress build appears is confirmed live in 13.2, the first paid build. If it does not, the lookup
falls back to `ListImageBuildVersions` on the recipe's image version, which lists every build state.

**Confirmed live in 13.2 (2026-10-14 17:29:50):** `ListImagePipelineImages` lists the killed CLI's
build as `BUILDING` (`…/baas-development-recipe-runner/1.0.0/1`).

### 1.3 OIDC provider calls

From the IAM API reference:
- `GetOpenIDConnectProvider`'s documented errors are `NoSuchEntity`, `InvalidInput` and
  `ServiceFailure`. Authorisation is evaluated against the named ARN first, so a caller allowed on
  that ARN gets `NoSuchEntity` for an absent provider.
- `CreateOpenIDConnectProvider`: `Url` is required. `ClientIDList`, `ThumbprintList` and `Tags` are
  optional. Without a thumbprint, IAM verifies the IdP through its trusted root CAs (exploration D4
  spike).

### 1.4 CloudFormation waiters

SDK 2.54.12, `CloudFormationWaiter`: `waitUntilStackCreateComplete`, `…UpdateComplete`,
`…DeleteComplete`, `…RollbackComplete` (the `RollbackStack` API's `UPDATE_ROLLBACK_COMPLETE`),
`…ImportComplete`, `…Exists`, and change-set, refactor and type-registration waiters.

There is no waiter for `ROLLBACK_IN_PROGRESS` (create rollback) or for
`UPDATE_COMPLETE_CLEANUP_IN_PROGRESS`, and the create and update waiters *fail* on rollback states
rather than returning them. **Deviation from the design:** step 8 waits on any `*_IN_PROGRESS` status
with one poll loop over `DescribeStacks` (every 10 s, the waiters' own cadence) until the status no
longer ends in `_IN_PROGRESS`, then decides from the settled status. This is the fallback the design
names for a status with no waiter, applied to all of them so there is one path.

## 11.1 Commands still printed without `--deployment`, and why

`git grep '"baas \|  baas \|`baas '` over `baas-cli/src/main`, after the change. Every remaining
bare `baas` is one of the following:

- **Help text and usage examples:** `BaasApp`'s quick start, `RunCommand`'s footer and its
  `--detach` description, `ImageBuildSubcommand`'s footer, and option descriptions that name
  another command (`JobsShow`/`JobsDownload`/`JobsDiff`, `ConfigSet`, `AdminCommand`). They are
  generic by nature, and D12 puts the rule in `--deployment`'s own help instead.
- **Printed before any deployment is resolved:** `RunCommand`'s unreleased-build refusal
  (`baas run --runner-jar`), checked before the config is read. Also `ConfigService`'s
  `noneConfigured`/`ambiguous` texts, where `--deployment <name>` is a placeholder or there is no
  deployment to name.
- **Descriptions, not instructions:** "`baas run` fails until…", "`baas jobs list` shows…",
  StackStep's "a permission `baas admin deployment setup` needs", `OperatorSession`'s "the name
  `baas admin deployment setup` printed".
- **Portable file content:** `RunnerImageExtension`'s marker comments (`# Push: baas admin image
  build --extension <this file>`), since one file can be pushed to any deployment, and its
  stale-push refusal, a static check that is given no deployment.
- **Comments** in `UserDataScriptBuilder` and `simplelogger.properties`.
- **`JobSession`'s two stop-failure messages** (`or run: baas jobs terminate <id>`): `JobSession`
  is built from a job item and recorders, and never learns the deployment's name. Threading it
  through for two failure-path messages was not worth widening that class. Recorded as **W1**.

The first search (`"baas \|  baas \|`baas `) missed commands written after "— " or ": ". The
13.1 live run showed one: `Job … stored 1 measurement(s) — baas results query --job-id …`.
A wider search found these, which now go through `Hints.command` too:
- `RunCommand`'s detach, launch-error, status-lost and measurement-count lines;
- `results query`'s "To see everything";
- `config sync`'s "Create the deployment again".

Every instruction printed after a deployment is resolved goes through `Hints.command`:
- run hints and the no-image refusal;
- the `jobs list` and `jobs show` hints;
- the `results query` hint;
- `admin image show`'s "no image" message and drift warning;
- teardown's in-flight refusal, saved-extension notice, image notice and extension-rescue failure;
- `RunCommand`'s missing-outputs and reservation messages;
- `CloudFormationService`'s missing stack;
- setup's closing text.


## 13. Live verification

Run with the working-tree CLI (`baas-cli/target/baas-cli.jar`, built from each commit under test).
`-Duser.home` points at a scratch home holding copies of the two deployment files, and
`AWS_CONFIG_FILE`/`AWS_SHARED_CREDENTIALS_FILE` point at the real `~/.aws`. The real `~/.baas` was
backed up first (byte-identical, `diff -r`) and is never written by these checks.

"Interrupted" means `kill -9` of the CLI's JVM. That is the closed-lid case. It also avoids the
inherited-SIGINT trap in background shells, which would make the check say nothing.

### 13.1 baas-development from scratch, interrupted create

1. `admin deployment teardown --yes` (16:55–16:57):
   - the stack is deleted;
   - the image is retired (`ami-05f4851d3c30e3cb2` and its snapshot);
   - the file is removed;
   - no provider notice, correctly, since dev had no federation.
2. `setup --non-interactive --region us-east-1 --deployer-aws-profile baas-admin`, killed with
   `-9` right after `Creating stack baas-development...` (16:57:27):
   - `DoneStep - Configuration written to …/baas-development.yaml` was logged 33 ms after the
     create was accepted;
   - the file holds `deployerProfile: baas-admin` (the preflight was Met) and `region: us-east-1`;
   - the stack was `CREATE_IN_PROGRESS`.
   **D20 holds.**
3. Interactive re-run under a pseudo-terminal (`script`), with answers piped:
   - Step 1 found the deployment in its file (`Deployment: baas-development (configured on this
     machine)`), and the profile prompt defaulted to `[baas-admin]`. Nothing was retyped.
   - The GitHub question was asked and answered `n`.
   - Step 8: `Stack baas-development is CREATE_IN_PROGRESS: waiting for that to finish first...`
     at 16:57:59, then `already up to date` at 17:01:58. No refused `UpdateStack`. **D19
     (stack) holds.**
   - Step 9: operator role assumable.
   - Step 10: no image, so the build was offered. The piped input had reached EOF by then; a closed
     input answers a confirmation *no* by design, so it did not build. The next steps named
     `baas --deployment baas-development admin image build`. Later interactive checks use `expect`
     instead of a pipe.

### 13.2 Interrupted image builds (paid: 2)

- **Paid 1, still running.** `admin image build`, killed with `-9` at 17:29:50 after
  `Image build started: …/1.0.0/1`. The pipeline showed `/1` as `BUILDING`. The re-run at 17:30:17
  logged `A build of this image started at 2026-10-14T15:29:39.623Z is still running; waiting for
  it instead of starting another.`, then at 17:48:39 `Published ami-07219be12b971faf7 to
  /baas-development/runner/ami-id`. The pipeline then held exactly one image, `/1`. **No second
  build.**
- **Paid 2, finished unseen.** `admin image build` started `…/1.0.0/2` at 17:49:21 and was killed
  with `-9`. The build finished with no CLI attached (`AVAILABLE` at 18:00:54,
  `ami-09ec488e03afa7719`), while the pointer still named `/1`'s `ami-07219be12b971faf7`. The re-run
  at 18:01:29 logged `Adopting ami-09ec488e03afa7719, built at 2026-10-14T15:49:21.094Z by a run
  that did not publish it. No new build is needed.`, then published it and retired
  `ami-07219be12b971faf7` with its snapshot. It finished in 4 s.
  `aws ec2 describe-images --owners self` (us-east-1) lists exactly one AMI, the published one.
  **No orphaned AMI, and no second build.**

### 13.1 continued

- An interactive re-run (driven by `expect`, Enter at every prompt) at 18:01–18:02 asked exactly
  two questions: the deployer profile `[baas-admin]` and the GitHub question. There was no plan
  confirmation, no image offer (`Runner image: 1.3.0 (current)`) and no repeat command, and the
  stack was `already up to date`. The deployment file is byte-identical before and after (`diff`).
  **A healthy re-run changes nothing.**
- **Paid 3:** `baas run jmh` with a benchmark class I made up (`pl.wsztajerowski.HelloWorld`)
  failed as it should: `No matching benchmarks`, status `failed:1`, artifacts downloadable. That
  was my mistake, not a defect.
- **Paid 4:** `baas run jmh … -- Incrementing_Synchronized -f 1 -wi 1 -i 1` on the recreated dev
  deployment: `completed`, 1 measurement stored (`20261014T160517359Z-dbba0693`). Its closing line
  showed a bare hint, fixed above (11.1).

### 13.4 Provider reuse, and an identity that may not simulate (no paid tests)

- `setup --non-interactive --github-org wsztajerowski --github-repo benchmark-as-a-service` on dev:
  - logged `GitHub OIDC provider: present, reused`, with no IAM write, and the stack updated;
  - the operator role's trust named `arn:aws:iam::381492019823:oidc-provider/token.actions.githubusercontent.com`,
    the ARN the template composes, beside the account root.
  - Then `--revoke-github-oidc` left only the account-root principal. Dev is back to no federation.
- `setup --non-interactive --deployer-aws-profile lynx > policy.json`:
  - The permission check reported *not checked*. `lynx` turned out to be allowed the (no-op) stack
    update and the operator-role assumption, so it got as far as step 10.
  - It was then denied `imagebuilder:ListImagePipelineImages`. **That denial escaped as a generic
    error, exit 1, with nothing on standard output**, contradicting *Setup renders the deployer
    policy…*, which reports a denial from any setup call with the policy.
  - **Fixed** in `SetupWalkthrough`: any access denial a step raises after the policy is rendered
    becomes the same hand-over as a stack permission failure (resume at step 4), unit-tested by
    `aDenialAtALaterStepHandsThePolicyOver`.
  - Re-run on the rebuilt JAR: exit 1, standard output is exactly one policy document (14
    statements), and the deployment file is byte-identical (`lynx` not stored over `baas-admin`).

### 13.5 Teardown leaves the provider; dev recreated interactively

- Dev was federated again (`present, reused`), then torn down with `--yes`. The output included
  `The account's GitHub OIDC provider (token.actions.githubusercontent.com) is left in place: it is
  account-wide and may serve other projects. baas-development no longer trusts it, since its operator
  role is gone.` The provider still exists afterwards (`CreateDate 2024-12-15T18:53:42Z`). The
  image was retired, and only dev's file was removed.
- *Environment note, not a defect:* that federation update's waiter took 16.5 min for an update
  CloudFormation finished in 30 s (the two earlier updates took 30 s each). `pmset -g log` shows the
  Mac in Normal Sleep with only maintenance dark-wakes from 18:36:55, when a `caffeinate` assertion
  ended. Every later check runs under `caffeinate -dimsu`.
- **Paid 5:** dev recreated by a full interactive create (`expect`, 18:56–19:12). It asked for:
  - the profile (no stored one, so no default; answered `baas-admin`);
  - the region (`[eu-central-1]`, answered `us-east-1`);
  - GitHub (`n`);
  - the plan, which listed account and ARN, region, deployment, the create with bucket and
    table, `GitHub: none` and the ~$0.20/month cost (confirmed);
  - the image build offer (`y`).

  It did **not** ask for the name, since `--deployment` named it. The file was written 36 ms after
  `Creating stack`, the stack completed, the operator role was assumable, and build `/1` was
  published as `ami-0ac8369b05bf4024b`. The closing repeat command was
  `baas --deployment baas-development admin deployment setup --non-interactive --region us-east-1
  --deployer-aws-profile baas-admin --build-image`, exactly the answers given.

### 13.3 CI's deployment recreated from scratch (paid: 1 so far)

- **The provider was not deleted before this check** (it still exists, `CreateDate
  2024-12-15T18:53:42Z`), so the live run covers **reuse**, not creation.
  `CreateOpenIDConnectProvider` is covered by `anAbsentProviderIsCreatedBeforeTheStack` against
  the fake, by the policy test pinning the `Create` grant on the exact ARN, and by the permission
  check, which simulated that grant as Met in every run here. A live create waits for the provider
  to be deleted — **W2**.
- `teardown --yes` (19:13–19:14): the stack was deleted, image `ami-0528c58373032fb85` retired,
  and the output said `The account's GitHub OIDC provider … is left in place`.
- `setup --non-interactive --region eu-central-1 --deployer-aws-profile baas-admin --github-org
  wsztajerowski --github-repo benchmark-as-a-service --build-image` (19:14–19:30):
  - `GitHub OIDC provider: present, reused`;
  - the file was written 35 ms after `Creating stack`;
  - the stack was created and the operator role assumable;
  - **paid 6:** build `/1` published as `ami-006bf9067587a5c80`;
  - the closing text printed both `gh variable set` lines, filled in.
- The operator role ARN is unchanged (`…:role/baas-381492019823-role-operator`), and its trust holds
  the account root plus `Federated: …:oidc-provider/token.actions.githubusercontent.com`. The
  repository variables `OPERATOR_ROLE_ARN` and `AWS_REGION` already match, so nothing changes on
  GitHub.
- **CI half (paid 7–8):** PR #95's `e2e-cloud-test.yml` (run `38607340125`, success) federated from
  GitHub Actions into the recreated `baas-381492019823-role-operator` through the reused provider.
  The JMH job ran `jmh-with-async` on the freshly built AMI and the JCStress job ran in sanity mode;
  both passed. Together with `build` and both `install.sh` checks, every check on the PR is green.

## 14. `/opsx:verify` (2026-10-14)

| Dimension | Status |
|---|---|
| Completeness | 45/45 tasks. 37 requirements over 4 specs, all with an implementation |
| Correctness | 37/37 requirements mapped to code. Every scenario has a unit test or a live check (W3 closed) |
| Coherence | Design followed, with 4 recorded deviations (below) |

Unit suite: 880 tests, 0 failures. CI on PR #95 green, both paid E2E jobs included.

### Requirement → code → test

`setup/` is `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/admin/setup/`. Tests are in
`SetupStepsTest` unless named otherwise.

**guided-setup (new)**

| Requirement | Code | Test (live) |
|---|---|---|
| Ordered series of probed steps | `SetupSteps.all`, `SetupWalkthrough` | `SetupWalkthroughTest.stepsRunInOrder…`, `aHealthyReRunChangesNothing` (13.1 re-run) |
| Interactive on a terminal, unattended otherwise | `SetupWalkthrough.run` (Wait), `SetupCommand.call` | `aWaitingStepRunsAgainOnEachEnter`, `unattendedAWaitingStepStopsWithExitOne`, `aClosedInputStops…`, `SetupCommandTest.…ThereIsNoYes` (13.1, 13.4) |
| Every prompt has an option | `SetupOptions`, each step asks only when its option is absent | `RepeatCommandTest` round trip through picocli |
| Deployment chosen from files before AWS | `DeploymentStep` | `aNewDeploymentAsksAwsNothing…`, `severalDeployments…` |
| Credentials shown before use | `CredentialsStep` | `anExpiredSession…`, `theStoredProfileIsTheDefault` |
| A new name is suggested, never required | `NameAndRegionStep` | `theSuggestedNameIsTheDefault`, `anyValidNameIsAccepted…`, `anExistingDeploymentsNameAndRegionAreNotAsked` (13.5) |
| Missing rights handed over, then waited for | `PermissionsStep`, `PolicyHandOver` | `missingRightsAreSavedAndWaitedFor…`, `…CanBePrintedInstead`, `thePolicyFileOptionAnswers…`, `aCheckThatCannotRun…` (13.4) |
| A name another account holds is asked again | `NamesStep` | `aNameAnotherAccountHoldsIsAskedAgainAndCheckedAgain`, `…UnattendedExitsOne`, `aLeftoverIsNamed…` |
| GitHub Actions step, never touching GitHub | `GitHubStep`, `DoneStep.nextSteps` | `federationSkipped…`, `theNextStepsAlwaysNameTheDeployment` (13.3) |
| An update asks what can change | `GitHubStep.decide`, `CredentialsStep` | `aRepositoryIsAddedOnUpdate`, `unchangedFederationIsCarriedForward` |
| The plan is confirmed on create or change | `PlanStep` | `decliningThePlanChangesNothing`, `aTemplateOnlyUpdateIsNotConfirmed` (13.5) |
| A stack permission failure returns to step 4 | `StackStep.permissionFailure`, `SetupWalkthrough.outcome` | `aPermissionFailureInTheStack…` (two), `aDenialAtALaterStepHandsThePolicyOver` (13.4) |
| The operator role is proven, with propagation | `OperatorRoleStep` | `aNewRoleIsGivenTimeToPropagate`, `aRoleThatStaysRefused…` |
| The image is offered, never built by surprise | `ImageStep` | `aCurrentImageIsNotOffered`, `unattendedSetupNeverBuilds…`, `theOfferAcceptedBuilds…`, `theBuildImageOption…` (13.5) |
| An interrupted run resumes | `CloudFormationService.awaitSettled`, `StackStep` | `aCreateStillInProgressIsWaitedFor…` (13.1) |
| Ends with next steps and the repeat command | `DoneStep`, `RepeatCommand` | `RepeatCommandTest` (four cases) (13.5) |

**core-stack-provisioning**

| Requirement | Code | Test (live) |
|---|---|---|
| CLI creates the provider, never modifies or deletes it | `GitHubStep`, `StackStep`, `AwsSetupAws.createOidcProvider`, `TeardownCommand.providerLeftNotice` | `anAbsentProviderIsCreatedBeforeTheStack`, `anExistingProviderIsReused`, `aProviderWithoutTheAudience…`, `TeardownNoticeTest.theProviderIsSaidToStay`, `SetupCommandTest.rejectsRemoved…` (reuse 13.3/13.4, teardown note 13.5/13.3; create W2) |
| Deployer policy may create the provider, nothing more | `deployer-policy.json` `GitHubOidcProvider` | `DeployerPolicyTest.mayCreateTheGitHubProviderButNeverChangeIt`, `DeployerPreflightTest` |
| Sized as customer-managed | `DeployerPolicyTest` budget | `renderedPolicyLeavesRoomInACustomerManagedPolicyBudget` |
| Core stack contains only CLI-owned infrastructure (MOD) | `cf-template-core.yaml` composed `Federated` | `CoreTemplateTest.theFederatedPrincipal…`, `noParameterCarriesTheProviderArn` (13.4 trust policy) |
| Setup is self-sufficient (MOD) | `StackStep` rollback recovery, `DoneStep.save` | `aRolledBackFirstCreateIsDeletedAndCreatedAgain` (teardown of a rolled-back create: W3) |
| Policy covers the table lifecycle (MOD) | policy | `DeployerPolicyTest` |
| Federation carried forward, revoked on request (MOD) | `GitHubStep.forCreate/forUpdate`, `CloudFormationService` filter | `unchangedFederationIsCarriedForward`, `revocationSubmitsBothEmpty`, `CloudFormationServiceTest.theRemovedProviderArnParameterIsDropped…` (13.4) |
| Setup renders the policy and stops (MOD) | `PermissionsStep`, `PolicyHandOver` | `missingRightsUnattendedPrintOnlyThePolicy…`, administrator-command tests (13.4) |
| Named by its prefix (MOD) | `NameAndRegionStep` | `theDerivedNameIsTheAccountAlone`, `theSuggestedNameIsTheDefault` |
| Fixed names checked before create (MOD) | `NamesStep` | `nameConflictsAreNamedWithTheirRemedy`, `aRolledBackStackWithALeftover…` |
| Deployer may assume its operator role (MOD) | policy | `DeployerPolicyTest` |
| Teardown removes every deployment resource (MOD) | `TeardownCommand` | existing tests, plus notice (13.1/13.3/13.5) |
| 3 REMOVED (CI stack, no-OIDC policy, CI template) | file and statement deleted | `CiTemplateTest` deleted, `DeployerPolicyTest` |

**cli-command-structure**

| Requirement | Code | Test (live) |
|---|---|---|
| Profile stored only on evidence | `DoneStep.storeProfile`, `DoneStep.save` | `anUnverifiableIdentityDoesNotReplace…`, `aVerifiedIdentityIsStored…`, `aCreateIsItsOwnEvidence` (13.4 lynx) |
| `baas run` requires a built image (MOD) | `RunCommand` no-image message via `Hints.command` | none (W3) |
| Hints shown to people, with `--deployment` (MOD) | `Hints.command` at every site | `HelpAndHintsTest.aHintedCommand…`, `RunCommandDetachTest`, `TeardownNoticeTest`, `ImageCommandsTest` |
| `--deployment` names a deployment (MOD) | `BaasApp` description, `DeploymentStep` choice | `HelpAndHintsTest.theDeploymentOptionStatesWhenItIsNeeded`, `severalDeploymentsAreOfferedAsAChoice` |
| One file per deployment, written on accepted create (MOD) | `StackStep` → `DoneStep.save` | `theFileIsWrittenOnceTheCreateIsAccepted`, `aRefusedCreateLeavesNoFile`, `anUnprovenProfileIsNotInTheEarlyFile` (13.1 kill -9) |

**runner-image-provisioning**

| Requirement | Code | Test (live) |
|---|---|---|
| Exactly one image, also across an interrupted build (MOD) | `ImageBuilderService.adoptOrBuild`, `runningBuild` | six `ImageBuilderServiceTest` adoption cases (13.2: waited, adopted, one AMI) |

### Deviations from the design

1. **Step 8 waits with one poll loop** (`CloudFormationService.awaitSettled`), not the SDK
   waiters. No waiter exists for `ROLLBACK_IN_PROGRESS` or `UPDATE_COMPLETE_CLEANUP_IN_PROGRESS`,
   and the create and update waiters fail on rollback states (task 1.4).
2. **The engine converts a later access denial into the policy hand-over**
   (`SetupWalkthrough.outcome`). The design kept AWS knowledge out of the engine. This uses only
   `DeployerPreflight.isAccessDenied`, a pure function, and was added after 13.4 found a denial
   escaping bare.
3. **`RunnerImageBuild` was extracted** from `ImageBuildSubcommand`, so setup's image step and
   `admin image build` share one build path. The design named the port method `buildOrAdoptImage`;
   it is `buildImage`, which adopts through `ImageBuilderService.publish`.
4. Naming: `PreflightResult` is `SetupAws.Preflight`; `imageState` is `image(deployment)` returning
   `SetupAws.Image`.

### Warnings

- **W1** — `JobSession`'s two stop-failure messages still print `baas jobs terminate <id>`
  without `--deployment`. `JobSession` never learns the deployment's name (see 11.1).
  *Recommendation:* pass the name in when `JobSession` is next touched.
- **W2** — Setup's provider *creation* was not exercised live: the account's provider was not
  deleted before 13.3, so the live runs cover reuse only. Creation is unit-tested
  (`anAbsentProviderIsCreatedBeforeTheStack`), the grant is simulated as Met in every live run, and
  the request shape follows the API reference (task 1.3).
  *Recommendation:* the next time the provider is absent (a new account, or after an IAM admin
  deletes it), run `setup --github-org … --github-repo …` and check `aws iam
  get-open-id-connect-provider` lists `sts.amazonaws.com`.
- **W3** — *closed 2026-10-14.* Four scenarios had no test of their own. Each now has a test
  and a live run; see *W3 follow-up* below.

### Suggestions

- The closing text prints both `gh variable set` lines once per repository, so with several
  repositories `AWS_REGION` repeats; a per-repo loop is still correct.
- `PolicyHandOver.attachThen` says "Nothing was created." unattended even after an update earlier
  in the same run. True for this run's resources, but it could say "Setup stopped here." instead.

**Assessment:** no critical issues, 2 warnings open (W1, W2; W3 closed). Ready for archive once the PR is merged,
with the warnings noted.


## W3 follow-up (2026-10-14, on request)

Unit suite: 884 tests, 0 failures.

| Scenario | Unit test | Live check |
|---|---|---|
| The option forces unattended on a terminal | `SetupCommandTest.theOptionForcesUnattendedOnATerminal`, through `BaasApp`'s real command tree with a terminal console, a denying `SetupAws` and a line reader that counts reads: 0 reads, exit 1, the policy as the only payload. The control `withoutTheOptionATerminalIsAsked` reads lines | `expect` under a pseudo-terminal, failing on any prompt: `setup --non-interactive --deployer-aws-profile lynx` showed none and exited 1, the policy printed |
| No image built yet (`baas run`) | `RunCommandTest.noImageNamesTheDeploymentsBuildCommand`, after extracting `RunCommand.noImageMessage` | Dev's pointer saved (`ami-0ac8369b05bf4024b`), deleted, then `baas run jmh`: exit 1, `Deployment baas-development has no runner image (us-east-1). Build one: baas --deployment baas-development admin image build`. No instance was launched. The pointer was restored to the same AMI |
| A failed first create can also be torn down | none: teardown has no AWS seam | See below |
| A re-run continues after an administrator acted (two processes) | `SetupStepsTest.aSecondRunContinuesOnceTheAdministratorActed`: two runs on one root. The first is denied (exit 1, no file); the second is met, creates, and writes the file | Not repeated live: it needs an IAM administrator between the runs. 13.1 covered the cross-process resume of an interrupted create |

**The rolled-back create, live:**

1. Dev was torn down.
2. An interactive setup was driven by `expect`. At `Go ahead?` — after setup's names check — the
   table `baas-development-results` was created by hand, then the plan confirmed.
3. `Creating stack` was followed by the file written 30 ms later. The create failed on the taken
   table name, and the stack ended `ROLLBACK_COMPLETE`, with the file holding the region and
   `baas-admin`.
4. `teardown --yes` exited 0:
   - `Bucket baas-development does not exist; nothing to empty.`
   - stack deleted (`DescribeStacks`: does not exist);
   - the file removed, leaving only `baas-381492019823.yaml`.
5. The hand-made table was deleted, and **paid 11** rebuilt dev with
   `setup --non-interactive --build-image`.
