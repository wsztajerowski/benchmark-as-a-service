# Tasks

## 1. Verify blocking assumptions

- [ ] 1.1 Confirm `jobs-command` accepts the scope split in design.md *Context*: it owns the `--deployment` grammar, and this change owns multiplicity, including the `--config-path` removal. Verified by its exploration or design recording the split.
- [ ] 1.2 Rebase onto `next-release` after `jobs-command` is archived. Re-check every MODIFIED and REMOVED header in `specs/` verbatim against the then-current main specs (`grep -n '^### Requirement' openspec/specs/<cap>/spec.md`), merge `jobs-command`'s teardown rewrite ("nothing survives") into *Teardown safety gates*, and verify with `openspec validate multiple-deployments` and no archive-time "not in the current spec" warning on a dry read.
- [ ] 1.3 Confirm by a live probe that `/aws-dev/runner/ami-id` and `/ssm-dev/…` are rejected by `ssm:PutParameter`, and record the error text in design.md *Resolved Questions*.
- [ ] 1.4 Confirm `-role-image-build` is still the longest role-name suffix in `cf-template-core.yaml` (`grep -o '\${ResourceNamePrefix}-role-[a-z-]*'`), and that no other composed name type has a tighter limit than IAM roles' 64.

## 2. Composed names in one place (C5)

- [ ] 2.1 Add `DeploymentNames.of(prefix)` covering the stack, bucket, table, AMI pointer, runner/operator/image-build roles, profiles, recipe and components. Make `BaasConfig`'s derivations delegate to it. Verify with a unit test asserting each name for `baas-123456789012` and `wiktor-dev`.
- [ ] 2.2 Move the hand-built names in `RunCommand`, `BuildImageCommand`, `TeardownCommand` and `DeployerPreflight` onto it. Verify with `grep -rn '"-role-\|"-results"\|"-recipe-\|"-component-' baas-cli/src/main` returning only `DeploymentNames`, and the full test suite green.
- [ ] 2.3 Add a test that renders `cf-template-core.yaml`'s composed role names and asserts `DeploymentNames`' longest role suffix matches the template's, so the two cannot drift.

## 3. Name validation

- [ ] 3.1 Add the deployment-name rule (length 3..N with N from `DeploymentNames`, pattern, no `--`, reserved prefixes and suffix), each failure naming its rule. Verify with a parameterised unit test covering every rule, the boundary lengths, and `baas-999999999999` accepted.
- [ ] 3.2 Call it from `admin deployment setup` before the preflight and any AWS call. Verify with a test that an invalid name exits non-zero with no SDK client invoked.

## 4. Per-deployment configuration

- [ ] 4.1 Make `ConfigService` root-based (`<root>/deployments/<name>.yaml`) with the root injected through the constructor, and give `BaasApp` a package-private seam to inject it. Verify with a unit test reading and writing under a temp root.
- [ ] 4.2 Implement the selection rule: `--deployment` names the file, otherwise exactly one is implied, two or more is an error listing the names, and none is an error except for setup. Verify with unit tests for each case, including that `BAAS_DEPLOYMENT` is ignored.
- [ ] 4.3 Implement the flat-file migration (move and overwrite, then delete `config.yaml`; a file without `prefix` is left alone). Verify with unit tests for first-run migration, overwriting an existing target, and the no-prefix file.
- [ ] 4.4 Remove `--config-path` and move the nine test classes that use it onto the injected root. Verify `grep -rn -- '--config-path' baas-cli/src` is empty and the suite is green.
- [ ] 4.5 `admin deployment setup`: use `--deployment` verbatim, the only file when one exists, or `baas-<accountId>` when none; write that deployment's file. Verify with tests for all three paths.
- [ ] 4.6 `config sync`: require `--deployment` when no deployment is configured, re-sync the only one, and refuse an ambiguous call. Verify with tests for each case.
- [ ] 4.7 Teardown: delete the deployment's file after a completed teardown. Verify with a test that it is gone, and that the other deployment's file is untouched.
- [ ] 4.8 Update `install-test.yml`'s sentinel check to the `deployments/` layout. Verify by running `scripts/tests/install-test.sh` locally.

## 5. Runner tag and scoped lookups (U36)

- [ ] 5.1 Add `baas-deployment=<prefix>` to `Ec2ProvisioningService.instanceTags`. Verify with the instance-tags unit test asserting exactly four fixed tags.
- [ ] 5.2 Filter `listRunningBenchmarkInstances` on `tag:baas-deployment`. Verify with a unit test on the request's filters, and that `findLive(jobId)` is unchanged.
- [ ] 5.3 Confirm `RunnerRole`'s `ec2:TerminateInstances` condition and `operator-policy.json` need no change for the new tag (`ec2:CreateTags` on `RunInstances` covers it). Verify with `CoreTemplateTest` green and a live launch in 7.2.

## 6. Documentation and project rules

- [ ] 6.1 Write ADR 0006 reversing *exactly one deployment per account*: what A10 protected (silent forking) and why an explicit name does not reintroduce it. Verify the ADR is linked from `CLAUDE.md`.
- [ ] 6.2 Update `CLAUDE.md`. Replace "There is no option to name a different one" with the default-plus-named rule and the validation summary. Add `baas-deployment` to the fixed instance tags. Replace the `--config-path` / `config sync --name` paragraph with per-deployment files and the selection rule. Note the no-env-var decision. Verify with `grep -n 'config-path\|no option to name' CLAUDE.md` returning nothing stale.
- [ ] 6.3 Replace `infra/README.md` *A second deployment* with the CLI procedure (deployer policy attached as customer-managed, `baas --deployment <name> admin deployment setup --region …`, `admin image build`, the `baas-dev` alias, teardown). Verify by following it in 7.1.
- [ ] 6.4 Add the project rule to `openspec/config.yaml` under `tasks`: a change touching `infra/`, IAM or the runner image includes a manual task to verify it on a second deployment and record the result in `verify.md`. Verify `openspec instructions tasks --change multiple-deployments --json` shows the rule.
- [ ] 6.5 Delete U36 and C5 from `docs/review/open-findings.md` (index rows and entries). Verify by grep.
- [ ] 6.6 Update `docs/diagrams/` where setup, teardown or config selection appear, rendering each edited `.mmd` with `mmdc` to a scratch PNG and inspecting it before committing.

## 7. End-to-end verification (manual — no automated test covers the `baas run` path)

- [ ] 7.1 With the dev deployer policy attached (customer-managed, by an identity above the deployer), run `baas --deployment <dev-name> admin deployment setup --region us-east-1`, then `admin image build`. Record that the parent AMI resolved for us-east-1 and that the bake's contract passed.
- [ ] 7.2 Run a `fake-jmh-benchmarks` job on the dev deployment through the `baas-dev` alias. Record that the instance carries `baas-deployment=<dev-name>`, that `environment.json` exists, and that the result is in `<dev-name>-results` only.
- [ ] 7.3 With both deployments configured, confirm a bare `baas results query` fails listing both names, and that `baas --deployment baas-<acct> jobs list` does not show the dev runner.
- [ ] 7.4 Same-region check: create a second named deployment in `eu-central-1`, start a job on it, and confirm the default deployment's teardown gate does not list it. Tear down only the second deployment. Record the result as U36's live closure.
- [ ] 7.5 Tear down the dev deployment. Confirm `~/.baas/deployments/<dev-name>.yaml` is gone, and that bare commands address the default deployment again. Use the same run to perform the deferred U21/U40 checks, and record them per `openspec/changes/QUEUE.md`.

## 8. Verify

- [ ] 8.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory: a requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation from design or tasks.
