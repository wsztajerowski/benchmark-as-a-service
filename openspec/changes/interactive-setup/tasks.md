# Tasks

No task compares benchmark scores: nothing here changes what a runner measures on. An adopted image
is the recipe version the command would have built (design, *adopted by recipe version*).

## 1. Verify blocking assumptions

- [ ] 1.1 Once the user has published the new deployer policy versions and deleted the old
  provider (user, 2026-10-14), confirm under `baas-admin` that `aws iam get-open-id-connect-provider`
  on `arn:aws:iam::381492019823:oidc-provider/token.actions.githubusercontent.com` answers
  `NoSuchEntity`. Done when `verify.md` records the answer; an `AccessDenied` means the policy is not
  published yet, and live work waits while code work continues.
- [x] 1.2 Confirm `ListImagePipelineImages` reports an in-progress build (status and version ARN)
  and a finished one with its output AMI: run it read-only against `baas-development`'s pipeline,
  and check the API reference for in-progress entries. Done when `verify.md` holds the output and the
  citation. If in-progress builds are not listed, adoption needs another lookup, and the design is
  updated first.
- [x] 1.3 Confirm from the API reference that `GetOpenIDConnectProvider` on an absent provider answers
  `NoSuchEntity` to a caller allowed on that ARN, not `AccessDenied`, and that
  `CreateOpenIDConnectProvider` accepts `Url` plus `ClientIDList` with no `ThumbprintList`. Done when
  `verify.md` cites both.
- [x] 1.4 List the CloudFormation waiters the SDK in the build offers
  (`CloudFormationWaiter` methods in the resolved `cloudformation` JAR) and map each `*_IN_PROGRESS`
  status to one. Done when `verify.md` holds the mapping; a status with no waiter gets a poll loop in
  the design.

## 2. Deployer policy and template (D4, D13)

- [x] 2.1 `infra/deployer-policy.json`: add the `GitHubOidcProvider` statement
  (`iam:GetOpenIDConnectProvider`, `iam:CreateOpenIDConnectProvider` on
  `arn:aws:iam::${ACCOUNT_ID}:oidc-provider/token.actions.githubusercontent.com`). Verify with a
  `DeployerPolicyTest` case asserting those are the only `*OpenIDConnectProvider` actions and that
  the one resource is that ARN, and delete the *No OIDC actions present* assertion.
- [x] 2.2 Rename `renderedPolicyLeavesRoomInAnInlinePolicyBudget` to a customer-managed name, move the
  budget to 5632 (6144 − 512), and rewrite its comment. Verify the test passes and prints the
  rendered length (~4581).
- [x] 2.3 `cf-template-core.yaml`: remove `GitHubOidcProviderArn` and compose the federated principal
  with `!Sub` from `AWS::Partition` and `AWS::AccountId`. Verify with `CoreTemplateTest`: no parameter
  carries a provider ARN, and the trust statement names the composed ARN under the federation
  condition.
- [ ] 2.4 Delete `infra/cf-template-ci.yaml` and every test reading it from the test classpath. Verify
  `mvn -pl baas-cli test` passes and `git grep cf-template-ci` finds only history docs (archived
  changes, ADRs).
- [x] 2.5 Add a test that an update of a stack still listing `GitHubOidcProviderArn` sends no value
  for it (the declared-parameter filter). Verify in `CloudFormationServiceTest`.

## 3. The `SetupAws` port (D9)

- [x] 3.1 Define `SetupAws` and its result types (`PreflightResult` = `Met | Denied(actions) |
  NotChecked`, `OidcProvider` = `Absent | Present(clientIds)`, image state), and `AwsSetupAws` over
  `DeployerPreflight`, `BucketProbe`, `CloudFormationService`, `ImageBuilderService`,
  `OperatorSession` and an `IamClient`. Verify it compiles, and that the adapter adds no logic beyond
  mapping, by review.
- [x] 3.2 Split `CloudFormationService.createStack` into `submitCreate` and `awaitCreate`, keeping
  `createStack` as their composition. Verify the existing service tests pass, plus one asserting
  `submitCreate` returns before any waiter call.
- [x] 3.3 Write `FakeSetupAws` by hand (no Mockito), recording calls in order. Verify it is used by the
  first step test in section 5.

## 4. Engine and prompts

- [x] 4.1 `SetupStep`, its outcomes (`Met`, `Done`, `WaitForUser`, `Stop`, `GoTo`) and
  `SetupWalkthrough`. Verify with stub-step tests: steps run in order; `WaitForUser` re-runs the step
  once per line read and exits 1 when not interactive; `GoTo` resumes from the named step; nothing
  re-probes without a line read.
- [x] 4.2 `Prompter` (`ask`, `confirm`, `choose`) over `Console` and an injectable reader. A `null`
  read takes the default for questions and *no* for confirmations. Verify with unit tests, including
  a closed stdin that never confirms.
- [x] 4.3 `SetupCommand`: add `--non-interactive` and `--build-image`; remove `--oidc-provider-arn`;
  reduce `validateFederationOptions` to org and repos together or neither; build the context and the
  steps and return the engine's exit code. Verify that picocli rejects `--oidc-provider-arn` and
  `--yes`, and that the federation-option tests are updated.

## 5. Steps 1–3: deployment, credentials, name and region (D14, D17)

- [x] 5.1 Step 1 from local files only: `--deployment`, the only one, a choice among several plus
  "new" (interactive), or today's refusal (non-interactive). Verify with tests for each, asserting no
  `FakeSetupAws` call happens before step 2.
- [x] 5.2 Step 2: `callerIdentity`, showing the account and ARN, with the stored deployer profile as
  the default; unresolvable credentials or an expired session explained with the fixing command.
  Verify with tests for the shown identity and for an expired session (wait, or exit 1).
- [x] 5.3 Step 3: for a new deployment, the name prompt suggesting `baas-<account>` and accepting any
  `DeploymentNameRule`-valid name, re-asking with the broken rule; the region prompt with
  "cannot be changed later"; for an existing one, shown only. Keep the bucket-in-another-region
  check here. Verify with tests: suggestion accepted, a custom name, an invalid name re-asked, an
  existing deployment not asked, non-interactive derivation unchanged.

## 6. Steps 4–5: permissions and names (D15, D17, D14)

- [x] 6.1 Step 4 on `Denied`: the print-or-save question (default `policy.json`, skipped by
  `--policy-file`), the administrator's commands, wait, then one preflight per Enter. `NotChecked` is
  shown as not checked and continues. Non-interactive keeps stdout and exit 1. Verify with tests: saved,
  printed, attached-while-waiting, not checked, and the existing `setup > policy.json` test unchanged.
- [x] 6.2 Step 5 after step 4: a leftover bucket or table named with its command (wait, or exit 1);
  `Forbidden` after `Met` returns `GoTo(step 3)` interactively, or exits 1 naming `--deployment`;
  after `NotChecked`, today's caveat. Verify with tests, including that a re-asked name runs step 4
  again before step 5.

## 7. Step 6: GitHub Actions (D4, D10)

- [x] 7.1 Ask for federation on create; on update, show the current org and repositories (read from
  the stack) with "Change? [y/N]"; flags answer in advance; `--revoke-github-oidc` as today. Verify with
  tests: skipped, set on create, a repository added on update, defaults keep the stack's values.
- [x] 7.2 Provider probe: absent → create with `Url` and `ClientIDList` only; present with the audience
  → reuse; present without it → print `add-client-id-to-open-id-connect-provider`, then wait or exit 1,
  with no IAM write. Verify with `FakeSetupAws` tests asserting the exact calls in each case.

## 8. Steps 7–9: plan, stack, operator role (D10, D18, D19, D20)

- [x] 8.1 Step 7: show the plan (account, region, name, create or update, federation, about
  $0.20/month) and confirm only on create or a changed answer; declining exits 1 with nothing changed;
  a template-only update proceeds unconfirmed. Verify with tests for all three.
- [x] 8.2 Step 8 probe: wait on any `*_IN_PROGRESS` status with the waiter from 1.4, then re-read; then
  create (absent or `ROLLBACK_COMPLETE`, recovery unchanged) or update. Verify with tests: an
  in-progress create is waited for, not updated; rollback recovery still deletes and recreates.
- [x] 8.3 On create: `submitCreate`, then save the file (region; the profile only after a `Met`
  preflight), then `awaitCreate`. A refused create writes no file. Verify with tests: the file exists
  when the fake's await throws; no file when the submit throws; the profile is absent after a
  `NotChecked` preflight until the create completes.
- [x] 8.4 A `permissionFailure()` stack failure: hand the policy over (as 6.1), wait, then `GoTo(step 4)`
  without re-confirming the plan; non-interactive exit 1 as today; other failures exit 1 with the
  events. Verify with tests for each.
- [x] 8.5 Step 9: `assumeOperatorRole` with about 30 s of backoff retries, then handled as 8.4. Verify with
  tests: success on the third attempt reports nothing; a lasting failure hands the policy over.
  Use an injectable sleeper so the test takes no real time.

## 9. Step 10: the runner image (D5, D19)

- [x] 9.1 `ImageBuilderService.publish`: list the pipeline's images first. Wait for an in-progress
  build of the current recipe version and then publish it. Adopt an available, unpublished one of
  that version, after checking its AMI exists. Retire a found build of another version and build.
  Otherwise build as today. Verify with unit tests over a fake `ImagebuilderClient` for each case,
  asserting `startImagePipelineExecution` is never called when adopting or waiting.
- [x] 9.2 Step 10: offer the build (default yes, about 15 min) only when the image is absent or older
  than the bundled base; non-interactive only with `--build-image`; a running build asks "started at
  HH:MM, wait?" interactively; "no" ends at step 11 naming `admin image build`. Verify with tests:
  current image not offered, scripted run not building, the offer accepted.

## 10. Step 11, the stored profile and the repeat command (D8, D11)

- [x] 10.1 Store the deployer profile only on evidence (a `Met` preflight, or a stack operation that
  changed something), otherwise keep the old value and say so. Verify with tests mirroring the spec:
  `lynx` on a no-op update leaves `baas-admin`; a `Met` preflight stores; a create stores.
- [x] 10.2 Print the next steps (first run; the teammate's grant and `config sync`; the GitHub
  variables with federation; `admin image build` when no image was built), each with `--deployment`.
  Verify with a test on the closing text.
- [x] 10.3 `RepeatCommand.render` and its round-trip test through picocli: printed only on an
  interactive run that created or changed something; `--region` only on create. Verify with tests on
  the create, update and no-change cases.

## 11. Hints carry `--deployment` (D7, D12)

- [x] 11.1 A `Hints.command(deployment, …)` helper. Use it in `RunCommand`'s no-image refusal (saying
  "deployment", not "account") and results hints, `ConfigShowSubcommand`, setup's closing text and
  every `→` hint that names a deployment-scoped command. Verify with updated tests, and with a
  `git grep '"baas '` review listing each remaining bare hint and why it stays bare.
- [x] 11.2 `BaasApp`'s `--deployment` description gets D12's text. Verify with a help-output test.

## 12. Documentation

- [x] 12.1 CLAUDE.md:
  - the deployer-policy size paragraph becomes customer-managed (6144, budget 5632);
  - the `cf-template-ci.yaml` bullet and its "deploy order inverts" text are deleted, along with the
    reference under *IAM is split deliberately*;
  - the *fixed names* bullet's "no configuration file was ever written for it" is rewritten;
  - the concurrent `admin image build` entry is rewritten (D19);
  - the *A deployer policy is prefix-exact* bullet's inline wording is fixed.

  Verify by re-reading each edited paragraph against the code.
- [x] 12.2 `infra/README.md` and the top-level README: the walkthrough as the setup path, no CI-stack
  section, customer-managed attachment. Verify that each documented command and option exists in
  `--help`.
- [x] 12.3 A new ADR, `docs/adr/0008`, superseding `baas-cli-core-ci-split` D1/D2 with *create, never
  modify or delete* the OIDC provider. Verify that it links the archived change and D4.
- [x] 12.4 Update `docs/diagrams/baas-setup.mmd` to the eleven steps, render it with `mmdc` to a
  scratch PNG and look at it, and keep the render out of the repository. Verify by inspecting the
  PNG.
- [x] 12.5 Delete E2's entry and index row from `docs/review/open-findings.md`. Verify with
  `git grep -n 'E2 ' docs/review/open-findings.md` finding nothing.

## 13. Live verification (manual)

Budget: up to 20 paid tests (image builds and `baas run` jobs) across development and verification
(user, 2026-10-14). Both local deployments are recreated from scratch by the new setup, so the OIDC
provider and federation are tested as one flow through a single stack. The old provider is deleted
by the user beforehand. Run under a scratch `-Duser.home` with the real `~/.aws`; interactive runs
under a pseudo-terminal (`script`), and Ctrl+C checks in the foreground. Record every paid test in
`verify.md` with a running count.

- [ ] 13.1 Manual: tear down `baas-development` with the current CLI, then set it up fresh and
  interactively with the new one. Interrupt the create with Ctrl+C after `CreateStack` is accepted,
  and confirm the file exists and the re-run's step 1 offers it and waits for the create. Accept the
  image offer, run one `baas run` job, then do an interactive re-run that accepts every default and
  changes nothing. Record each step in `verify.md`.
- [ ] 13.2 Manual: interrupt an `admin image build` on `baas-development` mid-build (paid). Re-run it
  while the build is in progress, and confirm it waits. Interrupt again, and once the build has
  finished unseen, re-run and confirm it adopts with no second build. Confirm no orphaned AMI remains
  (`aws ec2 describe-images --owners self`). Record in `verify.md`.
- [ ] 13.3 Manual: tear down `baas-381492019823` and recreate it with `--non-interactive
  --build-image --github-org <org> --github-repo <repo>` (values read from its stack before the
  teardown), so setup creates the GitHub OIDC provider. Confirm that the provider exists with
  `sts.amazonaws.com`, that `OPERATOR_ROLE_ARN` is unchanged, and that one `workflow_dispatch` of the
  e2e CI workflow on the PR branch federates and passes. Record in `verify.md`.
- [ ] 13.4 Manual: a second setup with federation on `baas-development` reuses the provider created in
  13.3 with no IAM write. Then `baas admin deployment setup --non-interactive > policy.json` under
  `lynx` leaves only the policy on stdout and exits 1. Record in `verify.md`.
- [ ] 13.5 Manual: tear down `baas-development` and confirm the teardown says the provider is left in
  place, then recreate it for later work. Record in `verify.md`.

## 14. Verify

- [ ] 14.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory: a
  requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation
  from the design or these tasks.
