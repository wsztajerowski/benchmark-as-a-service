# Proposal

## Why

The GitHub Actions benchmark path is broken by design decisions taken elsewhere and has never
passed a run. Six independent blockers stand between it and green — a deleted SSM Mongo parameter,
a revoked IAM grant, no 27017 egress, an expired PAT, an unresolvable project, and an assertion
(`tags.source == gha-e2e-test`) that no job has ever been able to satisfy. Fixing them in place
would leave two orchestrators that must stay behaviourally identical, one of which re-implements in
bash twelve responsibilities `baas run` already owns.

Deleting the bash path and calling `baas run` instead dissolves all six rather than fixing them —
and it is forced, not merely preferred: `private-runner-network` moves runners onto a subnet with no
route to `github.com`, which a self-hosted Actions runner agent must reach. The two are structurally
incompatible, so this change is a prerequisite for that one.

## What Changes

- **CI calls the CLI.** `e2e-cloud-test.yml` becomes one `ubuntu-latest` job: checkout, `mvn
  package`, OIDC credentials, `baas config sync`, `baas run jmh-with-async`, assert. No
  provisioning, no id minting, no toolchain installation in bash.
- **BREAKING — four workflow files and the `act` harness are deleted:** `benchmark-runner.yml` (the
  only `workflow_dispatch` entry point), `exec-single-benchmark.yml`, `start-ec2-runner.yml`,
  `stop-ec2-runner.yml`, `.github/test/**`. The consumer contract changes from *call our reusable
  workflow* to *install the CLI*. `machulav/ec2-github-runner`, `GHA_EC2_PAT`, `RUNNER_ROLE_NAME`,
  `SUBNET_ID`, `SECURITY_GROUP_ID`, `RESOURCE_NAME_PREFIX`, `ASYNC_PROFILER_VERSION`,
  `MONGOSH_VERSION` and six shell scripts die with them.
- **BREAKING — `WorkflowRole` is deleted and GitHub OIDC federates directly into `OperatorRole`.**
  Role chaining caps a session at 60 minutes against a 7200 s default benchmark timeout, and the
  failure mode is a red job with a good measurement and a full EC2 bill. `cf-template-core.yaml`
  gains `GitHubOidcProviderArn`, `GitHubOrg` and `GitHubRepo` (a `CommaDelimitedList`), a conditional
  federated trust statement, and a raised `MaxSessionDuration`. `cf-template-ci.yaml` reduces to the
  OIDC provider alone and takes no parameters from core; the deploy order inverts.
- **`baas admin setup` gains `--github-org`, `--github-repo`, `--oidc-provider-arn` and an explicit
  `--revoke-github-oidc`, and switches to CloudFormation's `UsePreviousValue` for parameters the
  caller does not name** — the carry-forward path `baas admin build-image` already uses. Without it a
  setup run for an unrelated reason resubmits `Default: ""`, the condition goes false, the trust
  statement vanishes and CI silently loses access. With it, removing the trust becomes a deliberate
  gesture rather than a side effect, and the deployed stack stays the single source of truth for what
  the trust policy says.
- `deployer-policy.json` gains `iam:UpdateAssumeRolePolicy` (~35 bytes against ~300 of headroom
  under the 4608-character test cap).
- **`baas run` gains `--format json`** — one summary object on stdout carrying at least `runId`,
  `project`, `resultPath`, `status`, `exitCode` and `instanceId`, printed on the failure path too
  while the command still exits non-zero. Today the run id reaches only `logger.info` → stderr, so
  CI cannot correlate a run it launched.
- **`queryByRequestId` stops applying the exclusion filter.** New contract: exclusion applies to
  project sweeps; an explicit single-run lookup returns the run. Without it a self-test tagged
  `exclude_from_results=true` is invisible to every assertion surface but `baas download`.
- **`source` joins the known-tag vocabulary** with the fixed values `ci` and `local`, derived by
  `baas run` from the environment and caller-overridable like `project`, `commit` and `branch`.
  Today `source` is a free-form workflow string smearing trigger and benchmark variant together.
- `ci-pr-build.yml` exports `ASYNC_PATH`, so the only test exercising async-profiler end to end
  stops being silently skipped on every PR.
- `WorkflowRole` is removed from the legacy `baas-main` stack, which otherwise stays standing (it
  still trusts this repository and holds unscoped `ec2:RunInstances`). There is no separate
  `baas-lynx` CI stack to delete: `baas-main` also owns the account's OIDC identity provider and
  the `baas-lynx-main` bucket, neither with a `DeletionPolicy`, so a wholesale delete would destroy
  the provider this change federates through. See design.md.
- CLAUDE.md's MongoDB accepted-risk entry is corrected to record that no live standalone user is
  known and that retirement is an open decision.

## Capabilities

### New Capabilities
- `ci-benchmark-execution`: how continuous integration runs benchmarks — through the CLI rather than
  a re-implementation, under what identity, what the self-test covers and asserts, and the standing
  constraint that nothing here may narrow the mechanism to the one benchmark type the self-test
  happens to exercise.

### Modified Capabilities
- `core-stack-provisioning`: the core stack and `baas admin setup` take GitHub/OIDC parameters and
  the stack declares `OperatorRole`'s federated trust — reversing three requirements that currently
  forbid exactly this; `WorkflowRole` and its grants are removed; the deployer policy gains
  `iam:UpdateAssumeRolePolicy`, which one requirement currently forbids by name.
- `benchmark-results-query`: exclusion becomes a property of project sweeps rather than of every
  query, so an explicit request-ID lookup returns an excluded run.
- `results-store-schema`: `source` enters the shared known-key vocabulary as a derived,
  caller-overridable key.
- `cli-command-structure`: `baas run` gains a machine-readable summary on stdout, on both the success
  and failure paths.

## Impact

**Code.** `.github/workflows/` (four deletions, two rewrites, one two-line edit), `.github/test/**`
(deleted), `infra/cf-template-core.yaml`, `infra/cf-template-ci.yaml`, `infra/deployer-policy.json`,
`infra/operator-policy.json` (drift test), `baas-model`'s `TagKeys`, `baas-cli`'s `RunCommand`,
`SetupCommand`, `BaasConfig` and `ResultsQueryService`, plus `CoreTemplateTest` and an additive
`ResultsQueryServiceIT` case.

**Implementation landmine.** Deleting only the `filterExpression` line from `queryByRequestId`
orphans `#tags`, `#excluded` and `:excluded`; DynamoDB rejects a request carrying unused expression
names or values, so *every* `--request-id` query would fail at runtime. A builder-level unit test
would not catch it — the new case must round-trip against LocalStack.

**Cost.** This introduces the project's first recurring *per-event* AWS spend: roughly one
`c5.2xlarge` instance-lifetime (~10 minutes — cents, not dollars) per triggering push, bounded by a
path filter on `pull_request` plus `workflow_dispatch`. No new *standing* cost: D3 reuses the
existing `baas-3q7i7s65` installation, so there is no second AMI bake and no second retained
snapshot. Removing `WorkflowRole` from `baas-main` removes a role, not a charge.

**Deliberately not changed.**
- The runner AMI, the measurement environment and every termination layer. CI and laptop runs boot
  the same image by construction (D3), which is what makes their results comparable at all.
- `baas run`'s benchmark-type surface. The self-test covers `jmh-with-async` only (D9), but the type
  stays a positional parameter: consumer repositories will run `jmh`, `jmh-with-async` and
  `jcstress` through this same mechanism. Any task that "simplifies" by hard-coding a type violates
  this.
- `benchmark-runner`'s MongoDB adapter. Retiring it is a user-visible BREAKING removal deserving its
  own change; only the accepted-risks wording is corrected here.
- `OperatorRole`'s account-root trust principal. The federated statement is added alongside it, so
  finding **S9** stays open even though this change edits that exact trust policy.
- The `baas-lynx-main` bucket, and `run-artifact-layout`'s CI requirements, which `baas run`
  satisfies by construction rather than by amendment.

**Review findings closed.** `benchmark-runner-findings.md` **S1** (shell injection via
`${{ inputs.parameters }}` — the workflow is deleted), **S3** (`WorkflowRole`'s unscoped
`ec2:RunInstances`; `OperatorRole` is scoped by region and instance family), **A10** (CI's
hardcoded `baas-lynx-main`, which stops existing rather than being corrected), **D3** (`ASYNC_PATH`
unset in PR CI), **S12** (the classic `GHA_EC2_PAT`). **S2** is materially reduced but not closed —
the self-hosted runner carrying an instance profile disappears, while the `sub: repo:org/repo:*`
wildcard is deliberately left in place pending verification that fork PRs cannot obtain an
`id-token`. **S10** (third-party actions on mutable tags) shrinks in surface without being resolved.
