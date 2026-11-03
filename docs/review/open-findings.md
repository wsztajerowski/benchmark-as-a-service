# Open findings

Every review finding that is **neither fixed nor accepted**, in one file, merged on 2026-10-05 from
the per-module review files (`baas-cli-findings.md`, `benchmark-runner-findings.md`,
`prebaked-runner-ami-review.md`) and the CLI usage analysis (`docs/analysis/cli-usage-analysis.md`).
Those files are gone; `git log -- docs/review docs/analysis` holds them.

- **IDs are kept from where each finding was filed** — `S`/`A`/`D`/`C` (module reviews), `P`
  (prebaked-image review), `U` (CLI usage analysis), `N` (new on 2026-10-05), `E` (live end-to-end
  test, 2026-10-10), `X`/`G`/`K`/`T`/`B` (the 2026-10-13 codebase review: security, API gaps, code
  and test complexity, architecture), and `<change>/W<n>` (a change's verify warning) — so commits, change artifacts
  and `openspec/config.yaml` rules that cite them still resolve.
- **Closing a finding deletes its entry here.** If the decision is worth keeping, it becomes an ADR
  in [`docs/adr/`](../adr/). ADRs 0002–0005 hold what was closed or declined up to 2026-10-05.
- **Excluded on purpose:** everything in CLAUDE.md's *Accepted risks*, and the hardenings and
  intended behaviour recorded in [ADR 0005](../adr/0005-declined-hardenings-and-intended-behaviour.md).
- State machines for the deployment, an operator machine and a job, which locate most `U`
  findings: `docs/diagrams/baas-states-*.mmd`.

## How findings are categorised

Each finding carries two grades, so the index reads as a work list.

- **Necessity.** *Must*: wrong output, lost data or a false green that someone will act on.
  *Should*: a security narrowing, a misleading step or a dead end a user meets in a normal workflow.
  *Could*: polish or tidying, safe to leave.
- **Effort.** *XS*: a message, a flag or a single line, plus its test, under an hour. *S*: one module
  and its tests, a few hours. *M*: an OpenSpec change of its own, about a day. *L*: a multi-day
  change touching several modules.

`Sev` is kept from the review that filed each finding. Where the two disagree, the necessity grade
decides what to pick up next.

## Index

### Must

None open.

### Should

| ID | Finding | Effort | Sev | Where it goes |
|---|---|---|---|---|
| N2 | `3q7i7s65-operator-role` from the August deployment still exists | XS | Low | open, by hand |
| G3 | No way to exclude, re-tag or delete a measurement after the run | M | — | users first |
| G8 | The job record does not say what ran: runner version or override, timeout, JCStress mode, JAR digest (arguments, profilers and JDK are recorded as tags since `results-diff`, `benchmark-args-pass-through` and `multiple-jdks`) | S | — | users first |

### Could

| ID | Finding | Effort | Sev | Where it goes |
|---|---|---|---|---|
| G15 | No prune or retention: `jobs/` (every `input/benchmark.jar` included) and items grow until teardown ([api-gaps](2026-10-13-codebase-review/api-gaps.md)) | M | — | open, when nearby |
| T11 | Each LocalStack IT class starts its own container (the pin is shared since `testsupport.LocalStack`) ([test-complexity](2026-10-13-codebase-review/test-complexity.md)) | XS | — | open, when nearby |

### Informational

| ID | Finding | Where |
|---|---|---|
| U14 | After a teardown and setup, the AWS CLI fails on the operator profile until its cached session is removed (recurred 2026-10-10) | last section |

IDs `E<n>` were filed by the live end-to-end test of v7.0.0 on 2026-10-10; its results are in
[`openspec/changes/pr83-review-fixes/verify.md`](../../openspec/changes/pr83-review-fixes/verify.md),
*Full end-to-end test*. `<change>/W<n>` are verify warnings from that change's `verify.md` that
describe product behaviour rather than a test gap, moved here so that this file stays the one list.

## Queued as OpenSpec changes

Order and state are in [`openspec/changes/QUEUE.md`](../../openspec/changes/QUEUE.md). Each design
below is what the proposal starts from.

## Open, not scheduled

### N2 — the August deployment's operator role is still in the account · Should · XS

`3q7i7s65-operator-role`, from the caller-ARN naming that `account-derived-deployment-naming`
replaced, still exists: the `baas-operator` profile in `~/.aws/config` assumes it successfully
(2026-10-05). The deployer cannot remove it (outside its prefix). Delete it with an identity above
the deployer. The profile is already gone from `~/.aws/config` (2026-11-03).

### Codebase review 2026-10-13 — X, G, K, T, B

Filed by the review in [`2026-10-13-codebase-review/`](2026-10-13-codebase-review/README.md), triaged 2026-10-20
([`alive-findings.md`](2026-10-13-codebase-review/alive-findings.md) holds the decisions), and moved here on 2026-10-22 after
each was re-checked against `next-release`: all still open. Each ID's evidence, failure scenario and
recommendation stay in its report, linked from the index. *When nearby* means fixed when work touches
that code, not scheduled. B6's remainder (thin read commands building clients inline) is not listed:
ADR 0009 adds a port only when a sequence test needs one.

- **G3, G8 — users first.** Each needs a decision about what BaaS records or offers before it is
  built; the proposals are in [`user-discussion.md`](2026-10-13-codebase-review/user-discussion.md).

## Informational

Kept for whoever next works in the area; none is a defect worth a change on its own.

- **U14.** After a teardown and setup, the AWS CLI (not `baas`) fails with `InvalidClientTokenId` on
  the operator profile until its cached session in `~/.aws/cli/cache` expires: the role was
  recreated with a new id. The SDK does not read that cache. **Recurred 2026-10-10** after the
  end-to-end test's teardown and setup: `aws --profile baas-operator-baas-dev sts get-caller-identity`
  failed while `baas` on the same profile worked; deleting the one cache file naming
  `baas-development-role-operator` fixed it. It matters only to someone using the AWS CLI beside
  `baas`, such as when removing a leftover (backing up data no longer needs it: `baas admin deployment
  export`). A one-line note in teardown's or
  setup's output would save the search.

