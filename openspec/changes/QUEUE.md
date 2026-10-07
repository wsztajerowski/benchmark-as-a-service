# Change queue

OpenSpec has no priority or park state; `openspec list` shows every active change, newest first.
This file is the order. Work the first unblocked item; never pick up a parked one unasked.
Set 2026-10-02.

| # | Change | From | State | Starts with |
|---|---|---|---|---|
| 1 | [`multiple-deployments`](multiple-deployments/proposal.md) | U36, C5 | Proposed 2026-10-07 on branch `multiple-deployments`, rebased onto `next-release` after `jobs-command` archived: a second deployment by free-form name, one config file per deployment, the selection rule, `config list`, `aws.deployerProfile`, removing `--config-path`, runners tagged `baas-deployment`. Implemented and verified live 2026-10-07 (`verify.md`), U21/U40 closed by the same run; 7.4 deferred below | `/opsx:archive multiple-deployments` after merge |
| 2 | [`export-before-teardown`](export-before-teardown/brainstorm.md) | U2 | `brainstorm.md`; its dated 2026-10-02 block overrides the text above it | `/opsx:propose export-before-teardown` |
| 3 | `narrow-bucket-grants` | S14, S7, S15 | Decided 2026-10-04, no change directory yet. Design in `docs/review/open-findings.md` (*narrow-bucket-grants*): runner S3 grant scoped to `PutObject`/`GetObject` on `jobs/*` + `GetObject` on `releases/*`, no `DeleteObject`; the operator's unused `DeleteObject` removed; the runner-JAR seed made an `If-None-Match: *` put. No bucket policy. Deployments pick it up on their next `baas admin deployment setup` | `/opsx:propose narrow-bucket-grants` |
| 4 | `retire-mongodb` | C4, A13 | Decided 2026-10-04, no change directory yet. Design in `docs/review/open-findings.md` (*retire-mongodb*): service ITs onto LocalStack DynamoDB first, then delete the Mongo adapter, `-m`, Morphia, the driver, the Mongo testcontainer, the compose service and local storage mode; `--results-table` and `--s3-bucket` become required | `/opsx:propose retire-mongodb` |

## Deferred checks

Verifications that could not run when their change shipped. Pick one up when its precondition
exists. None is a change on its own.

| Check | From | Needs | Then |
|---|---|---|---|
| Two deployments in one region: the runner tag scopes the teardown gate | U36, `multiple-deployments` task 7.4, deferred 2026-10-07 by the user to its own branch | `multiple-deployments` merged into `next-release`. A deployer policy for a second name in `eu-central-1` (setup prints it; attach it customer-managed) | On a branch from `next-release`: `baas --deployment <name> admin deployment setup --region eu-central-1` (no image build needed). Start a `fake-jmh-benchmarks` job on `baas-381492019823`, and while it runs, confirm `baas --deployment <name> admin deployment teardown` does not list that runner and `--deployment <name> jobs list` does not show it. Tear down only `<name>`, confirm its file is gone, and record the result in `multiple-deployments`' `verify.md` (archived or not) |

## Parked

| Change | Why | Unpark by |
|---|---|---|
| [`private-runner-network`](private-runner-network/PARKED.md) | Not wanted now (2026-10-02). Fully specified but partly stale | Deleting its `PARKED.md` and adding it to the table above |
