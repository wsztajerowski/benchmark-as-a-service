# Change queue

OpenSpec has no priority or park state; `openspec list` shows every active change, newest first.
This file is the order. Work the first unblocked item; never pick up a parked one unasked.
Set 2026-10-02.

| # | Change | From | State | Starts with |
|---|---|---|---|---|
| 1 | CLI usage fixes — one batch, one commit per fix, no OpenSpec change | U22, U23, U24, U25, U26, U27, U29, U30, U32, U34, U35, U38, U39, U40 (`docs/analysis/cli-usage-analysis.md` §3, §7) | Decided 2026-10-04. Unblocked: `custom-runner-image` is on `next-release` (`b6193ec`) | Each row's fix in that file, a test with each, one commit per ID (`fix(cli)`; U27 is `feat(cli)`; U22 removes an option — release type settled then) |
| 2 | `runs-command` | P11 | Decided, no change directory yet. Design in `docs/review/prebaked-runner-ami-review.md` §11 | `/opsx:propose runs-command` |
| 3 | [`export-before-teardown`](export-before-teardown/brainstorm.md) | U2, U12 | `brainstorm.md`; its dated 2026-10-02 block overrides the text above it | `/opsx:propose export-before-teardown` |
| 4 | `narrow-bucket-grants` | baas-cli S14, S15 | Decided 2026-10-04, no change directory yet. Design in `docs/review/baas-cli-findings.md` §47–54: runner S3 grant scoped to `PutObject`/`GetObject` on `runs/*` + `GetObject` on `releases/*`, no `DeleteObject`; the operator's unused `DeleteObject` removed; the runner-JAR seed made an `If-None-Match: *` put. No bucket policy. Installations pick it up on their next `baas admin setup` | `/opsx:propose narrow-bucket-grants` |
| 5 | `retire-mongodb` | benchmark-runner C4, A13 | Decided 2026-10-04, no change directory yet. Design in `docs/review/benchmark-runner-findings.md` §12–20: service ITs onto LocalStack DynamoDB first, then delete the Mongo adapter, `-m`, Morphia, the driver, the Mongo testcontainer, the compose service and local storage mode; `--results-table` and `--s3-bucket` become required | `/opsx:propose retire-mongodb` |

## Deferred checks

Verifications that could not run when their change shipped. Pick one up when its precondition
exists. None is a change on its own.

| Check | From | Needs | Then |
|---|---|---|---|
| An installation outside `eu-central-1`, built and run live | U21, decided 2026-10-04 (`docs/analysis/cli-usage-analysis.md` §7) | **Blocked on an IAM grant (2026-10-05):** no identity in this account can deploy outside the account's own prefix and region — `baas-admin` is the prefix- and region-exact deployer and `lynx` holds no IAM. Attach `baas admin deployer-policy --prefix baas-381492019823-dev --region us-east-1` as a customer-managed policy, or use a second account. Then an installation in another region: a second account, or the by-hand `-dev` installation (`infra/README.md`, *A second installation*) deployed with `--region us-east-1` — by hand, override `RunnerParentAmiId=ami-07a5b367e8dc8bd92`, since the template's default is the eu-central-1 AMI. Not a second region of the account's own installation: the bucket name is global, so setup refuses it (U23) | `baas admin setup --region us-east-1`, then `baas admin build-image`. The resolved parent should be `ami-07a5b367e8dc8bd92` (the `baas-parent-ami` tag and `RunnerParentAmiId`), the bake should pass its checks, and a `fake-jmh-benchmarks` `baas run` should complete with an `environment.json`. Tear it down afterwards and record the result in the change's `verify.md`, archived or not; then mark U21 Fixed in `docs/review/baas-cli-findings.md` |

## Parked

| Change | Why | Unpark by |
|---|---|---|
| [`private-runner-network`](private-runner-network/PARKED.md) | Not wanted now (2026-10-02). Fully specified but partly stale | Deleting its `PARKED.md` and adding it to the table above |
