# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 Resolve the account's existing OIDC provider for `token.actions.githubusercontent.com` and
      record its ARN — verify by listing providers with an identity above the deployer (the deployer
      itself gets `AccessDenied` by design) and confirming exactly one exists, so the reduced CI
      template reuses it rather than failing with `EntityAlreadyExists`
- [x] 1.2 Confirm `configure-aws-credentials` can request a session longer than 3600 s against a role
      whose `MaxSessionDuration` is raised — verify by checking the action's `role-duration-seconds`
      input is honoured for a directly-federated (non-chained) assume, since the whole federation
      shape rests on it
- [x] 1.3 Confirm the rendered deployer policy still fits with `iam:UpdateAssumeRolePolicy` added —
      verify by running `renderedPolicyLeavesRoomInAnInlinePolicyBudget` and recording the resulting
      character count against the 4608 cap
- [x] 1.4 Confirm a reactor-built CLI refuses to resolve a pinned runner JAR — verify by running
      `baas run` from a reactor build without `--runner-jar` and observing it fail naming the
      unreleased version, which is what makes `--runner-jar` mandatory in the workflow
- [x] 1.5 Record `baas-lynx`'s `WorkflowRole` trust policy and attached grants before anything is
      deleted — verify the captured copy shows the OIDC trust on this repository, so the deletion in
      §6 is evidenced rather than assumed
- [x] 1.6 Determine whether a fork pull request can obtain an `id-token` (design's third open
      question) — verify by opening a fork PR against a branch carrying the workflow, or by citing
      GitHub's documented behaviour, and record the answer in `verify.md`; tighten the `sub`
      condition in §4.1 if the answer is yes

## 2. Shared tag vocabulary and query behaviour

- [x] 2.1 Add `SOURCE` to `TagKeys` and to `KNOWN`, deliberately not to `MACHINE_OBSERVED` — verify
      by a unit test asserting `source` is a known key and that `--tag source=…` is accepted rather
      than rejected the way `instanceType` is
- [x] 2.2 Derive `source` in `baas run` as `ci` in a continuous-integration environment and `local`
      otherwise, with an explicit `--tag source=…` winning — verify by unit tests covering all three
      paths, including that the derived tag reaches the rendered user-data as a runner `--tag`
      argument
- [x] 2.3 Remove the exclusion filter from `queryByRequestId`, deleting the now-orphaned `#tags`,
      `#excluded` and `:excluded` entries with it — verify by an additive LocalStack integration test
      that a `--request-id` lookup returns a row tagged `exclude_from_results=true`, and that an
      ordinary `--request-id` lookup still succeeds rather than failing with `ValidationException`
- [x] 2.4 Confirm the project sweep is unaffected — verify by running the existing
      `excludedRowsAreDroppedServerSide` and `aRowCarryingNoTagsAtAllSurvivesTheExcludeFilter`
      unchanged, and by adding a case that a `--tag` filter still omits an excluded run
- [x] 2.5 Confirm `RunCommand.showResults` reports a successful excluded run's own measurements —
      verify by a test that the post-run summary is non-empty for a run tagged
      `exclude_from_results=true`

## 3. Machine-readable run summary

- [x] 3.1 Add `--format json` to `baas run`, emitting one object carrying `runId`, `project`,
      `resultPath`, `status`, `exitCode` and `instanceId` — verify by a test that parses the captured
      standard output as JSON and asserts every field is present
- [x] 3.2 Emit the summary on the failure path with `status: "failed"` and the run's exit code while
      still exiting non-zero — verify by a test driving a failed run and asserting both the object's
      contents and the process exit code
- [x] 3.3 Keep diagnostics off standard output — verify by a test running with `-v` that the captured
      standard output parses as exactly one JSON object with no timestamp or log-level prefix, and
      that verbose output appears on standard error
- [x] 3.4 Leave default output unchanged — verify by a test that `baas run` without `--format` writes
      no JSON object and reports progress as before

## 4. Core stack federation

- [x] 4.1 Add `GitHubOidcProviderArn`, `GitHubOrg` and `GitHubRepo` parameters, a condition, a
      conditional federated statement on `OperatorRole`'s trust policy, and a raised
      `MaxSessionDuration` to `cf-template-core.yaml` — verify by `CoreTemplateTest` cases asserting
      the statement is present when the parameters are supplied, absent when they are not, and that
      the account-root principal survives in both cases
- [x] 4.2 Make `GitHubRepo` a `CommaDelimitedList` rendering one `StringLike` value per repository —
      verify by a template test supplying two repositories and asserting both appear in the
      condition
- [x] 4.3 Delete `WorkflowRole` from `cf-template-ci.yaml`, reducing it to the OIDC provider with no
      parameters from core — verify by a test asserting the template declares no IAM role, and that
      the existing "CI template is not bundled in the CLI" assertion still passes
- [x] 4.4 Add `--github-org`, `--github-repo` and `--oidc-provider-arn` to `SetupCommand`, and move
      its update path onto `CloudFormationService.updateStackParameters` so unnamed parameters carry
      forward with `UsePreviousValue` — verify by a test that a second setup naming no federation
      options still leaves the deployed federation parameters at their previous values rather than
      resubmitting `Default: ""`
- [x] 4.5 Add `--revoke-github-oidc`, submitting the three federation parameters explicitly empty,
      and reject it when combined with any of the setting options — verify by tests that revocation
      removes the trust statement while the account-root principal survives, and that naming both
      exits with a usage error having deployed nothing
- [x] 4.6 Keep the create path working, since `UsePreviousValue` is rejected on stack creation and on
      any parameter with no previous value — verify by a create-then-update test, not an update-only
      one, asserting the first deploy sends explicit empty values and the second carries them forward
- [x] 4.7 Confirm `BaasConfig` gains no federation fields — verify by asserting `baas config show`
      reports none and that the deployed stack's parameters are the only record of them
- [x] 4.8 Add `iam:UpdateAssumeRolePolicy` to `deployer-policy.json`, scoped to the same role
      resources as the existing IAM statement — verify by the rendering test and by re-running the
      policy-size test recorded in 1.3
- [x] 4.9 Keep `operator-policy.json` in sync — verify by running the operator-policy drift test
      against the edited core template

## 5. Workflows

- [x] 5.1 Delete `benchmark-runner.yml`, `exec-single-benchmark.yml`, `start-ec2-runner.yml`,
      `stop-ec2-runner.yml` and `.github/test/**` — verify by grepping the repository for
      `machulav`, `GHA_EC2_PAT`, `RUNNER_ROLE_NAME`, `RESOURCE_NAME_PREFIX`, `MONGOSH_VERSION` and
      `ASYNC_PROFILER_VERSION` and finding no live reference outside `docs/` and `openspec/`
- [x] 5.2 Rewrite `e2e-cloud-test.yml` as one `ubuntu-latest` job — checkout, `setup-java`,
      `mvn package`, `configure-aws-credentials` into the operator role, `baas config sync`,
      `baas run jmh-with-async --format json --runner-jar … --tag exclude_from_results=true`, then
      assertions driven by the run id from the summary — verify by `actionlint` (or equivalent) and
      by reading the file against `install-test.yml` for shape
- [x] 5.3 Express every self-test assertion against a value the job itself supplied or received —
      verify by confirming no assertion compares against a literal maintained separately from the
      run invocation, the defect that made `tags.source == gha-e2e-test` unpassable
- [x] 5.4 Trigger the workflow on a path-filtered `pull_request` plus `workflow_dispatch`, with no
      schedule — verify by confirming a documentation-only path is excluded by the filter
- [x] 5.5 Export `ASYNC_PATH` in `ci-pr-build.yml` — verify by confirming
      `JmhWithAsyncProfilerSubcommandServiceIT` reports as executed rather than skipped in the job's
      test summary
- [x] 5.6 Stop `operatorCredentialsWarning` firing with wrong advice under ambient credentials —
      verify by a test that no operator-profile warning is emitted when credentials come from the
      environment and no profile is configured

## 6. Deploy and prove — MANUAL, no automated test covers the `baas run` path

- [x] 6.1 Deploy the reduced CI template (or confirm the existing provider) with an identity above
      the deployer — verify the provider ARN matches the one recorded in 1.1. **Confirmed the
      existing provider; the reduced template is deliberately NOT deployed in this account.** The
      account already has `arn:aws:iam::381492019823:oidc-provider/token.actions.githubusercontent.com`
      (owned by the legacy `baas-main` stack), and deploying a second for the same issuer fails with
      `EntityAlreadyExists`
- [x] 6.2 Run `baas admin setup` against `baas-3q7i7s65` with the federation options — verify the
      stack reaches `UPDATE_COMPLETE` and `OperatorRole`'s trust policy carries both principals; then
      run `baas admin setup` again naming no federation options and verify the trust statement is
      still there, which is the carry-forward behaviour in the environment that matters
- [x] 6.3 Swap the repository secrets and variables: operator role ARN as a plain `vars.` entry,
      delete `WORKFLOW_ROLE_ARN`, `GHA_EC2_PAT`, `RUNNER_ROLE_NAME`, `SUBNET_ID`,
      `SECURITY_GROUP_ID`, `RESOURCE_NAME_PREFIX` — verify by listing the repository's secrets and
      variables afterwards
- [x] 6.4 Dispatch the rewritten workflow manually — verify the job is green, the instance is
      terminated by the CLI rather than by the shell watchdog, and the elapsed time is consistent
      with a normal run
- [x] 6.5 Inspect the run's stored measurements and S3 prefix — verify the item carries `source=ci`,
      `exclude_from_results=true`, `imageVersion` and `instanceType`, that `environment.json`,
      `jmh-result.json`, `run-status` and the profiling artifact directory are present under
      `runs/<project>/<runId>/`, and that `baas results` for the project omits the run while
      `baas results --request-id <runId>` returns it
- [x] 6.6 Compare the self-test's score against a pre-change run of the same fake benchmark and
      record the spread — verify by investigating any difference rather than accepting it, noting
      that run-to-run variance on this benchmark is large (CI history spans 10.0M–29.6M ops/s), so
      the finding is whether the new score sits inside that band and on the same `imageVersion`,
      `instanceType` and `jdk`
- [x] 6.7 Exercise the failure path — verify by forcing a failing run that the JSON summary is still
      printed with `status: "failed"`, the job exits non-zero, and `baas download <runId>` retrieves
      `cloud-init-output.log`
- [ ] 6.8 Execute the staged `remove-workflowrole` change set on `baas-main` (the legacy stack;
      there is no separate `baas-lynx` CI stack — see design.md and `verify.md` W1):
      `aws cloudformation execute-change-set --stack-name baas-main
      --change-set-name remove-workflowrole --profile lynx`, then wait for `UPDATE_COMPLETE`.
      Verify afterwards that no role other than `BaasCliOperatorRole` trusts this repository's
      workload identity, that the `baas-lynx-main` bucket still exists, and that the
      `token.actions.githubusercontent.com` provider still exists — the change set was confirmed to
      carry exactly one action, `Remove WorkflowRole`, but the provider is the thing a mistake here
      would cost most
- [x] 6.9 Prove revocation against the live installation — verify that
      `baas admin setup --revoke-github-oidc` removes the federated statement and that a dispatched
      workflow then fails to assume the role, then restore federation and confirm the workflow is
      green again

## 7. Records

- [x] 7.1 Update CLAUDE.md's *What isn't there* section — the GitHub Actions benchmark path is no
      longer broken-and-unclaimed but deleted; `baas run` is now driven end to end by CI, which
      reverses "No automated test drives `baas run` end to end"; the `.github/test/testing-scripts/
      logger.sh` "separate, still-live copy" note goes; verify by reading each edited claim against
      the tree
- [x] 7.2 Update CLAUDE.md's *What this is* and *Infrastructure* sections — there is one trigger path
      now, not two; `cf-template-ci.yaml` holds the provider alone; `baas admin setup` takes GitHub
      parameters, reversing the previous "accepts no GitHub/OIDC options" stance; verify by
      cross-reading against `infra/README.md`, updating it too
- [x] 7.3 Add `source` to CLAUDE.md's *Result tagging* table as a derived, caller-overridable key,
      and note that `--tag` for it is accepted — verify the table matches `TagKeys` field by field
- [x] 7.4 Rewrite the MongoDB row in *Accepted risks*: retained in `benchmark-runner`, **no known
      live user**, retirement an open decision — verify the row no longer claims a standalone
      justification the frozen java-wonderland branch cannot exercise
- [x] 7.5 Mark findings S1, S3, A10 (runner file), D3 and S12 **Fixed** in
      `docs/review/benchmark-runner-findings.md`, and record S2 as reduced-not-closed with the
      reason, S10 as reduced in surface — verify each status-table row matches the detail section
      below it
- [x] 7.6 Update the `baas run` sequence diagram under `docs/diagrams/` for `--format json` — verify
      the `.mmd` source renders and reflects the summary's position on the failure path too
- [x] 7.7 Before archiving, grep each main spec for the exact `### Requirement:` heading this
      change's `## REMOVED Requirements` block names — verify the heading matches verbatim, since
      `openspec archive` warns and archives anyway on a paraphrase, leaving the real requirement in
      place

## 8. Verification

- [x] 8.1 Run the full reactor build with `ASYNC_PATH` exported — verify `mvn verify` is green and
      that the async-profiler integration test executed rather than being skipped
- [x] 8.2 Run `/opsx:verify` and record the result in `verify.md` in the change directory — a
      requirement → code → test → gap table, open warnings under stable IDs (`W1`, `W2`…) that later
      notes can cite, and any deviation from this design or task list
