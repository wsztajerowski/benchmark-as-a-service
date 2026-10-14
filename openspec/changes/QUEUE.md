# Change queue

OpenSpec has no priority or park state; `openspec list` shows every active change, newest first.
This file is the order. Work the first unblocked item; never pick up a parked one unasked.
Set 2026-10-02. A row may be a review walkthrough rather than a change: it has no directory under
`openspec/changes/` and starts by reading the review, not with an `/opsx` command.

| # | Change | From | State | Starts with |
|---|---|---|---|---|
| 1 | [`interactive-setup`](interactive-setup/exploration.md) | new-user setup analysis, E2 | Explored 2026-10-13. Unblocked: `setup-prerequisites` (the whole-policy preflight and `--policy-file` it builds on) merged in PR #94 and archived 2026-10-13 | `/opsx:propose interactive-setup` |
| 2 | Codebase review triage *(review, not a change)* | Security, API-gap, code- and test-complexity and architecture reviews, plus a survey of CLI architecture styles, 2026-10-13 | Written 2026-10-13, untriaged. It lives only on the separate branch `codebase-review` (commit `5e3a9e5`), not on `next-release`. It waits for `interactive-setup` by the user's choice (2026-10-14), not because of a dependency | `git switch codebase-review` and read `docs/review/2026-10-13-codebase-review/README.md`. Put its §4 decisions one at a time. Rebase the branch onto `next-release` before filing anything into `docs/review/open-findings.md` |
| 3 | [`export-before-teardown`](export-before-teardown/brainstorm.md) | U2 | `brainstorm.md`; its dated 2026-10-02 block overrides the text above it | `/opsx:propose export-before-teardown` |
| 4 | `retire-mongodb` | C4, A13 | Decided 2026-10-04, no change directory yet. Design in `docs/review/open-findings.md` (*retire-mongodb*): service ITs onto LocalStack DynamoDB first, then delete the Mongo adapter, `-m`, Morphia, the driver, the Mongo testcontainer, the compose service and local storage mode; `--results-table` and `--s3-bucket` become required | `/opsx:propose retire-mongodb` |

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
