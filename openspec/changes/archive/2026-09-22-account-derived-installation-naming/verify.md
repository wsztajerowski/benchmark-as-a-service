# Verification — account-derived-installation-naming

Verified 2026-09-21 against account `381492019823`, region `eu-central-1`. Unit and integration
suites: **344 tests green** in `baas-cli` (350 before `--mode` was withdrawn, which removed its
tests), full reactor `mvn -o clean verify` **BUILD SUCCESS**
(6m54s) with `ASYNC_PATH` exported — `JmhWithAsyncProfilerSubcommandServiceIT` **ran** (37.76s,
`Skipped: 0`), so the async path was covered rather than silently skipped.

## Requirement → code → test → gap

### core-stack-provisioning

| Requirement | Code | Test | Gap |
|---|---|---|---|
| Resource names are derived from the caller's AWS account | `SetupCommand.computePrefix(accountId)` | `SetupCommandTest` ×5 | — Live: deployed as `baas-381492019823` |
| Resource names follow one composition rule | `cf-template-core.yaml` (29 targeted edits), `ResourceNamePrefix` as full stem | `CoreTemplateTest` (32), `OperatorPolicyDriftTest`, `DeployerPolicyTest` (42) | — Live: every resource confirmed on the rule |
| *(withdrawn)* An installation is shared per account, with an explicit dev mode | `InstallationMode`/`Installation` **deleted** | `SetupCommandTest.theInstallationCannotBeSelectedOnTheCommandLine` | — Replaced by a by-hand procedure; see design.md |
| VPC parameters are immutable once the installation exists | `SetupCommand.networkingParameters` / `requireNetworkingUnchanged`, `CloudFormationService.getStackParameters` | `SetupCommandTest` ×5 | — Live: refusal exit 1, plain re-setup exit 0 |
| baas admin setup is self-sufficient | `SetupCommand.call` | `SetupCommandTest` | — |
| Deployer policy is resource-scoped | `deployer-policy.json` | `DeployerPolicyTest`, `DeployerPolicyRendererTest` | — Live: proven by the refusal when addressing another prefix |
| Operators can bootstrap config without the deployer's machine | `ConfigSyncSubcommand` (`--name` required) | `ConfigSyncSubcommandTest` ×3 | **W3** — adopt-an-existing-installation path not run live |
| *(removed)* Resource prefix is derived from the caller's AWS identity | `computePrefix`/`base32Encode` deleted | old tests deleted | — |
| *(removed)* config.yaml core stack field is unambiguously scoped | `aws.coreStackName` deleted | `BaasConfigYamlTest` | — |

### cli-command-structure

| Requirement | Code | Test | Gap |
|---|---|---|---|
| Resource names the CLI needs are derived or resolved, not cached | `BaasConfig` derived accessors; `RunCommand.resolveNetworking` | `BaasConfigYamlTest` ×6 | **W4** — the stale-security-group scenario is asserted by design, not by a test that replaces a group |
| Read-only commands can address another installation's results table | `--results-table` on `ResultsCommand`/`DownloadCommand`, `--bucket` on download | `DownloadArgumentTest` ×4 | — Live: old table read with the old operator profile |
| Teardown reports what it retains | `TeardownCommand` messages | — | **W5** — message text has no test |
| *(removed)* The results table is resolved from configuration, not a secret store | derived from prefix | `BaasConfigYamlTest` | — |

## Live verification

| Step | Result |
|---|---|
| `baas admin setup` | `baas-381492019823` CREATE_COMPLETE in 3m35s |
| `baas admin build-image` | `ami-05bbf52f9c50475b1`, image 1.2.0, ~11min, pointer `/baas-381492019823/runner/ami-id`; tags `baas-image-version`, `baas-parent-ami`, `project` all present |
| CI end to end (PR #65) | all four checks pass; EC2 job 1m52s — OIDC federation, `config sync --name`, AMI resolution, run, terminate |
| `baas run jmh-with-async` | run `20260921T143032906Z-acbd0d0f`, `status: completed`, exit 0, 1 measurement, instance `i-05eef6d06aee56010` self-terminated |
| S3 layout | `runs/benchmark-as-a-service/<runId>/` with `run-status`, `cloud-init-output.log`, `environment.json`, `jmh-result.json`, `packages.txt`, `input/`, flamegraph + JFR |
| `environment.json` before the benchmark | Yes — written 16:31:16, benchmark output 16:31:17 |
| Stored tags vs `environment.json` | Agree: `imageVersion` 1.2.0, `instanceType` c5.2xlarge, `jdk` 25.0.4, `cpuModel`, `cpuArch`, `type`; plus `branch`/`commit`/`project`/`source=local` |
| Key encoding | `pk=RESULT#benchmark-as-a-service`, `sk=<class>#<method>#thrpt#<ts,3dp>#<runId>` |
| One clock read | `createdAt` = `2026-09-21T14:30:32.906Z` = run id's instant |
| Networking immutability | different values → exit 1 naming all four, nothing submitted; omitted → exit 0, carried forward |
| Score | **11,238,813** (local) and **11,505,081** (CI, PR #65) vs nearest pre-change **11,787,431** — three points spanning 11.24M–11.79M against a recent cluster of 8.6M–12.2M and a history of 6.74M–29.56M over 57 measurements. No regression indicated; the −4.7% of the first pair does not survive the second sample |
| Environment comparability | 2 of 21 fields differ (`cpuModel` 8275CL→8124M, `memoryTotalKb`) — the physical host EC2 assigned. jdk, kernel, OS, perf, awscli, async-profiler, imageVersion, both kernel tunables identical |

## Open warnings

- ~~**W1 — dev mode has no end-to-end coverage.**~~ **Closed by removal.** `--mode` is gone;
  a second installation is a by-hand procedure (`infra/README.md`, *A second installation*), so
  there is no shipped behaviour left uncovered. What the live attempt established is kept below.
- ~~**W2 — the two installations' deployer policies do not fit inline together.**~~ **Closed by
  documentation.** Still true — 4219 + 4271 = 8490 against IAM's 5120 inline cap, shared across
  every inline policy on the principal — but it is now a documented property of the by-hand
  procedure rather than an undiscovered trap. `infra/README.md` says to attach the second as
  customer-managed, and `baas admin deployer-policy --prefix` renders it.
- ~~**W3 — `baas config sync --name` was not run against a live installation.**~~ **Closed.**
  Exercised by CI on PR #65: a clean runner federated into the new operator role, ran
  `baas config sync --name baas-381492019823` against real stack outputs, and completed a run.
- **W4 — the stale-security-group failure is argued, not tested.** Resolving per run removes it by
  construction, but no test replaces a security group and asserts the run still works.
- **W5 — teardown's retention messages have no test**, and they were rewritten in this change.
- **W6 — `RunCommand.call()`'s success path still has no JVM test.** Pre-existing (CLAUDE.md
  records it); this change added `resolveNetworking` to that same uncovered path.
- **W7 — the concurrent `build-image` exposure is open by choice.** See design.md, Risks. A shared
  installation makes two simultaneous bakes reachable; the ordering invariant does not survive it.
- **W8 — the by-hand second-installation procedure is untested.** It is documented and each step
  uses a command that works, but the sequence has not been run end to end. Its sharpest edge is
  the template's placeholder `RunnerImageComponentData` at default version `1.0.0`: it is only
  harmless while `infra/runner-image.yaml` declares something else (today, `1.2.0`).

## Deviations from design and tasks

1. **Task ordering was wrong: 8.3 must precede 8.1.** The rendered deployer policy is prefix-exact,
   so the previously attached one granted nothing for the new names, and the deployer cannot attach
   its own policy (`iam:List*` is scoped to roles by design). Anyone following the task list in
   order would have stalled at 8.1. Corrected in practice; the list should be reordered.
2. **Attaching the new policy revoked access to the old installation.** Confirmed by `AccessDenied`
   on `DescribeStacks` for `baas-3q7i7s65`. Teardown (8.5) and any rollback need the old policy
   re-attached first. Not anticipated in the migration plan.
3. **Three bugs found only by going to AWS**, all fixed with regression tests:
   - `SetupCommand` discarded the federation options on the **create** path, submitting all three
     parameters empty. A fresh installation deployed an operator role with no federated principal,
     reported success, and CI could not assume it. Invisible until now because every federated
     installation so far was federated by an update. Fixed; the first fix recursed into a
     `StackOverflowError`, which the existing revocation test caught.
   - `SetupCommand`'s retained-resource pre-check still composed `"baas-" + prefix`, probing
     `baas-baas-381492019823-results`. Now uses the single `BaasConfig` derivation. New test
     `grantsExactlyTheNamesTheConfigDerives` pins the policy against the config's names.
   - `ImageBuilderService.preflightVersion` compared component documents exactly, but Image Builder
     strips the trailing newline it stores while `renderComponent()` ends with one (1766 vs 1765
     bytes). It reported "content differs" on identical content and **blocked every first build on
     a fresh installation**, because `setup` registers the component before `build-image` runs.
     Pre-existing; surfaced by this change. Now compares `stripTrailing()`.
4. **`.github/workflows/e2e-cloud-test.yml` called `config sync --core-stack-name`**, which this
   change renames. CI would have broken on the flag, not the value. Not in the task list; fixed.
5. **`SetupCommand` now catches `IllegalStateException` from `deploy`** so a refused precondition
   exits 1 with its own message instead of surfacing through the generic crash handler with a
   "set BAAS_DEBUG=1 for the full stack trace" hint. Not specified; the refusal read as a bug.
6. **Design predicted 4213/4265 rendered policy characters; the measured values are 4219/4271.**
   The estimate had two instance-profile lines unrewritten. Corrected in proposal.md and design.md.
7. **`--mode shared|dev` was built, verified against a live account, and then removed.** It shipped
   a BaaS-development convenience in the released command surface, and it could not be used without
   a second deployer policy that does not fit inline. Replaced by a documented by-hand procedure
   plus `baas admin deployer-policy --prefix`, which prints and grants nothing. `InstallationMode`,
   `Installation` and `InstallationTest` are deleted; four commands lost the option. Net effect on
   the change is a simplification.

## Not done

- **8.4** point CI at the new installation, **8.5** tear down `baas-3q7i7s65`, **8.6** deregister
  the retired AMI and snapshot — all awaiting explicit go-ahead; they go beyond deploying and
  baking, and 8.5 additionally needs the old deployer policy re-attached.
- **9.4** superseded — `--mode` removed, nothing left to verify. The by-hand procedure that
  replaces it is untested end to end (**W8**).

## Incidental observations

- The account also holds `baas-main` and `baas-parameters` CloudFormation stacks, and
  `baas-lambda` / `baas-lynx-lambda` / `baas-lynx-main` buckets. All predate the current design —
  CLAUDE.md states there is no `cf-template-main.yaml`, no bootstrap stack, and that
  `s3-hook-lambda` is gone. Unrelated to this change; noted because a reader sweeping `baas-*`
  will meet them.
