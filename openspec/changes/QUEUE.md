# Change queue

OpenSpec has no priority or park state; `openspec list` shows every active change, newest first.
This file is the order. Work the first unblocked item; never pick up a parked one unasked.
Set 2026-10-02.

| # | Change | From | State | Starts with |
|---|---|---|---|---|
| 1 | [`custom-runner-image`](custom-runner-image/assumptions.md) | U20 | Stub + `assumptions.md` | `/opsx:explore custom-runner-image` |
| 2 | `runs-command` | P11 | Decided, no change directory yet. Design in `docs/review/prebaked-runner-ami-review.md` §11 | `/opsx:propose runs-command` |
| 3 | [`export-before-teardown`](export-before-teardown/brainstorm.md) | U2, U12 | `brainstorm.md`; its dated 2026-10-02 block overrides the text above it | `/opsx:propose export-before-teardown` |

## Deferred checks

Verifications that could not run when their change shipped. Pick one up when its precondition
exists. None is a change on its own.

| Check | From | Needs | Then |
|---|---|---|---|
| The watchdog and the poll cap, fired live | `run-status-in-dynamodb` task 13.4 / W1 (archived) | A fixture benchmark that runs past `--timeout` **and survives SIGTERM**, for example a JVM whose shutdown hook blocks. Length alone is not enough: `timeout` sends SIGTERM at `--timeout`, `margin` (≥ 60 s) before the watchdog, so any benchmark that exits on SIGTERM records `failed:124` instead | (1) Detach the CLI with `kill -9` after launch, using the minimum `--watchdog-margin 60`. The item should read `timed-out`, and `cloud-init-output.log` should be in the prefix. (2) Separately, let an attached CLI reach its poll cap. It should record `timed-out`, not `cancelled`. Record both in the archived change's `verify.md` |

## Parked

| Change | Why | Unpark by |
|---|---|---|
| [`private-runner-network`](private-runner-network/PARKED.md) | Not wanted now (2026-10-02). Fully specified but partly stale | Deleting its `PARKED.md` and adding it to the table above |
