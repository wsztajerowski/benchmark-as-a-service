# Change queue

OpenSpec has no priority or park state; `openspec list` shows every active change, newest first.
This file is the order. Work the first unblocked item; never pick up a parked one unasked.
Set 2026-10-02. A row may be a review walkthrough rather than a change: it has no directory under
`openspec/changes/` and starts by reading the review, not with an `/opsx` command.

| # | Change | From | State | Starts with |
|---|---|---|---|---|
| 1 | [`export-before-teardown`](export-before-teardown/design.md) | U2 | Implemented on branch `export-before-teardown` (worktree `../benchmark-as-a-service-export`); live checks 9.4–9.6a remain (tasks section 9) | Tasks section 9, then `/opsx:verify export-before-teardown` |

## Deferred checks

Verifications that could not run when their change shipped. Pick one up when its precondition
exists. None is a change on its own.

| Check | From | Needs | Then |
|---|---|---|---|
| Two deployments in one region: the runner tag scopes the teardown gate | U36, `multiple-deployments` task 7.4, deferred 2026-10-07 by the user to its own branch | `multiple-deployments` merged into `next-release`. A deployer policy for a second name in `eu-central-1` (setup prints it; attach it customer-managed) | On a branch from `next-release`: `baas --deployment <name> admin deployment setup --region eu-central-1` (no image build needed). Start a `fake-jmh-benchmarks` job on `baas-381492019823`, and while it runs, confirm `baas --deployment <name> admin deployment teardown` does not list that runner and `--deployment <name> jobs list` does not show it. Tear down only `<name>`, confirm its file is gone, and record the result in `archive/2026-10-07-multiple-deployments/verify.md`, then delete this row |
| R16 another-account refusal, live | `pr83-review-fixes` task 12.2 (W4 step 4), deferred 2026-10-12. R2 ran and passed on 2026-10-13 under `narrow-bucket-grants` (`archive/2026-10-12-pr83-review-fixes/verify.md`) | A deployer policy for the name `test` (its bucket belongs to another account, confirmed in `pr83-review-fixes` task 1.1) attached to `baas-admin-wiktor` **customer-managed** by an IAM admin, since the refusal comes after the preflight. `baas-admin` cannot attach it itself | `baas --deployment test admin deployment setup --region us-east-1`: expect "taken by another AWS account", exit 1, no stack. Record it in `archive/2026-10-12-pr83-review-fixes/verify.md`, detach the policy, then delete this row |

## Parked

| Change | Why | Unpark by |
|---|---|---|
| [`private-runner-network`](private-runner-network/PARKED.md) | Not wanted now (2026-10-02). Fully specified but partly stale | Deleting its `PARKED.md` and adding it to the table above |
