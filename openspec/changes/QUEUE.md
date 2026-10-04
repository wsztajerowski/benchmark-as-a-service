# Change queue

OpenSpec has no priority or park state; `openspec list` shows every active change, newest first.
This file is the order. Work the first unblocked item; never pick up a parked one unasked.
Set 2026-10-02.

| # | Change | From | State | Starts with |
|---|---|---|---|---|
| 1 | [`custom-runner-image`](custom-runner-image/assumptions.md) | U20 | Stub + `assumptions.md` | `/opsx:explore custom-runner-image` |
| 2 | CLI usage fixes — one batch, one commit per fix, no OpenSpec change | U22, U23, U24, U25, U26, U27, U29, U30, U32, U34, U35 (`docs/analysis/cli-usage-analysis.md` §3, §7) | Decided 2026-10-04. **Blocked until `custom-runner-image` is committed**: it edits `RunCommand` and `SetupCommand` | Each row's fix in that file, a test with each, one commit per ID (`fix(cli)`; U27 is `feat(cli)`; U22 removes an option — release type settled then) |
| 3 | `runs-command` | P11 | Decided, no change directory yet. Design in `docs/review/prebaked-runner-ami-review.md` §11 | `/opsx:propose runs-command` |
| 4 | [`export-before-teardown`](export-before-teardown/brainstorm.md) | U2, U12 | `brainstorm.md`; its dated 2026-10-02 block overrides the text above it | `/opsx:propose export-before-teardown` |

## Deferred checks

Verifications that could not run when their change shipped. Pick one up when its precondition
exists. None is a change on its own.

| Check | From | Needs | Then |
|---|---|---|---|
| The watchdog and the poll cap, fired live | `run-status-in-dynamodb` task 13.4 / W1 (archived) | A fixture benchmark that runs past `--timeout` **and survives SIGTERM**, for example a JVM whose shutdown hook blocks. Length alone is not enough: `timeout` sends SIGTERM at `--timeout`, `margin` (≥ 60 s) before the watchdog, so any benchmark that exits on SIGTERM records `failed:124` instead | (1) Detach the CLI with `kill -9` after launch, using the minimum `--watchdog-margin 60`. The item should read `timed-out`, and `cloud-init-output.log` should be in the prefix. (2) Separately, let an attached CLI reach its poll cap. It should record `timed-out`, not `cancelled`. Record both in the archived change's `verify.md` |
| An installation outside `eu-central-1`, built and run live | U21, decided 2026-10-04 (`docs/analysis/cli-usage-analysis.md` §7) | `custom-runner-image` shipped, and an installation in another region: a second account, or the by-hand `-dev` installation (`infra/README.md`, *A second installation*) deployed with `--region us-east-1`. Not a second region of the account's own installation: the bucket name is global, so setup refuses it (U23) | `baas admin setup --region us-east-1`, then `baas admin build-image`. The resolved parent should be `ami-07a5b367e8dc8bd92` (the `baas-parent-ami` tag and `RunnerParentAmiId`), the bake should pass its checks, and a `fake-jmh-benchmarks` `baas run` should complete with an `environment.json`. Tear it down afterwards and record the result in the change's `verify.md`, archived or not; then mark U21 Fixed in `docs/review/baas-cli-findings.md` |

## Parked

| Change | Why | Unpark by |
|---|---|---|
| [`private-runner-network`](private-runner-network/PARKED.md) | Not wanted now (2026-10-02). Fully specified but partly stale | Deleting its `PARKED.md` and adding it to the table above |
