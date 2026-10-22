# Change queue

OpenSpec has no priority or park state; `openspec list` shows every active change, newest first.
This file is the order. Work the first unblocked item; never pick up a parked one unasked.
Set 2026-10-02. A row may be a review walkthrough rather than a change: it has no directory under
`openspec/changes/` and starts by reading the review, not with an `/opsx` command.

| # | Change | From | State | Starts with |
|---|---|---|---|---|
| 1 | Instance-lookup refactor *(review fix, not a change)* | B13, and the four "is it alive?" definitions found at `runner-shell-access` verify (2026-10-22) | Decided 2026-10-22 as a refactor PR, not an OpenSpec change: no requirement changes (S effort, no spec delta, no invariant, IAM, infra or runner touched). One rule for a job's instance — the pending or running instance carrying its `baas-job-id` tag within the deployment (`ofJob`), plus the bulk `ofDeployment` for `jobs list` and teardown — and one instance-state enum with `isLive()`, so no state string is compared outside the EC2 adapter. `jobs terminate` drops its item-first step; `AwsConnectSources.instanceLive` goes. Touches `baas run`'s poll and stop hook: keep `UNKNOWN` (a failed describe) meaning "alive" everywhere it does today, or it becomes a behaviour change that needs its own OpenSpec change | A branch from `next-release` (`runner-shell-access` merged 2026-10-22, PR #104, and archived). Delete B13 from `docs/review/open-findings.md` in the same PR |
| 2 | [`export-before-teardown`](export-before-teardown/brainstorm.md) | U2 | `brainstorm.md`; its dated 2026-10-02 block overrides the text above it | `/opsx:propose export-before-teardown` |
| 3 | `retire-mongodb` | C4, A13 | Decided 2026-10-04, no change directory yet. Design in `docs/review/open-findings.md` (*retire-mongodb*): service ITs onto LocalStack DynamoDB first, then delete the Mongo adapter, `-m`, Morphia, the driver, the Mongo testcontainer, the compose service and local storage mode; `--results-table` and `--s3-bucket` become required | `/opsx:propose retire-mongodb` |

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
