# Change queue

OpenSpec has no priority or park state; `openspec list` shows every active change, newest first.
This file is the order. Work the first unblocked item; never pick up a parked one unasked.
Set 2026-10-02.

| # | Change | From | State | Starts with |
|---|---|---|---|---|
| 1 | [`custom-runner-image`](custom-runner-image/assumptions.md) | U20 | Stub + `assumptions.md` | `/opsx:explore custom-runner-image` |
| 2 | `runs-command` | P11 | Decided, no change directory yet. Design in `docs/review/prebaked-runner-ami-review.md` §11 | `/opsx:propose runs-command` |
| 3 | [`export-before-teardown`](export-before-teardown/brainstorm.md) | U2, U12 | `brainstorm.md`; its dated 2026-10-02 block overrides the text above it | `/opsx:propose export-before-teardown` |

## Parked

| Change | Why | Unpark by |
|---|---|---|
| [`private-runner-network`](private-runner-network/PARKED.md) | Not wanted now (2026-10-02). Fully specified but partly stale | Deleting its `PARKED.md` and adding it to the table above |
