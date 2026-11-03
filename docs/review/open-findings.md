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
| S2 | CI's OIDC trust is repo-wide and `pull_request` triggers it | M | High → reduced | open |
| N2 | `3q7i7s65-operator-role` from the August deployment still exists | XS | Low | open, by hand |
| G3 | No way to exclude, re-tag or delete a measurement after the run | M | — | users first |
| G4 | No comparison against a baseline, and no regression gate: `results diff` compares two jobs but exits 0 whatever it finds, so CI still hand-rolls `jq` over its JSON | M | — | users first |
| G8 | The job record does not say what ran: runner version or override, timeout, JCStress mode, JAR digest (arguments, profilers and JDK are recorded as tags since `results-diff`, `benchmark-args-pass-through` and `multiple-jdks`) | S | — | users first |
| G5 | No `jobs wait` (or `terminal` field); CI copies the terminal-status set into bash | S | — | deferred |
| G13 | No way to forget a deployment locally, or reset a preference to its default | XS | — | deferred |

### Could

| ID | Finding | Effort | Sev | Where it goes |
|---|---|---|---|---|
| PT3 | perfasm's `savePerfTo`, `savePerfBinTo`, `saveLogTo` (and `…ToFile`) write outside the uploaded tree | S | Low | open, untestable today |
| A4 | A store failure after the upload loses the measurement row | M | Med → reduced | open |
| S7 | A runner can overwrite another job's objects under `jobs/*` | M | Med → reduced | open |
| N3 | A failed run-item reservation strands the uploaded `input/` | S | Low | open |
| X8 | The CLI and runner-JAR checksums come from the same release as the assets: integrity, not authenticity (CLAUDE.md says so since `open-findings-pass`); immutable releases and build-provenance attestation remain ([security](2026-10-13-codebase-review/security.md)) | S | — | open, when nearby |
| G7 | Exit codes do not separate failure kinds; the runner's exit code is not propagated; unattended setup exits 1 for a failure and for a step awaiting an IAM admin alike ([api-gaps](2026-10-13-codebase-review/api-gaps.md)) | S | — | open, when nearby |
| G9 | Runner PARAMS are first validated on the paid instance (`failed:2` after boot) ([api-gaps](2026-10-13-codebase-review/api-gaps.md)) | M | — | open, when nearby |
| G11 | `jobs diff` exits 0 whether or not the environments differ, so it cannot gate CI; `--format json` exists since `open-findings-pass`, an `--exit-code` gate was left out ([api-gaps](2026-10-13-codebase-review/api-gaps.md)) | XS | — | open, when nearby |
| G12 | `config show`, `admin image show`/`build`, unattended setup, `jobs terminate` and `jobs download` have no machine-readable output ([api-gaps](2026-10-13-codebase-review/api-gaps.md)) | XS each | — | open, when nearby |
| G14 | No read-only, machine-readable view of the deployment's server-side state; unattended `--github-repo` replaces the list ([api-gaps](2026-10-13-codebase-review/api-gaps.md)) | S | — | open, when nearby |
| G15 | No prune or retention: `jobs/` (every `input/benchmark.jar` included) and items grow until teardown ([api-gaps](2026-10-13-codebase-review/api-gaps.md)) | M | — | open, when nearby |
| G21 | `baas run` has no caller idempotency key; a retried CI step launches a second paid instance ([api-gaps](2026-10-13-codebase-review/api-gaps.md)) | S | — | open, when nearby |
| T10 | `~/.baas` isolation is surefire's `user.home` only, so a unit test run from an IDE that builds a command outside `BaasApp` reads the real `~/.baas`. `~/.aws` is covered for every run by `testsupport.NoRealAwsProfile`; ITs keep the real `user.home` for `~/.testcontainers.properties` ([test-complexity](2026-10-13-codebase-review/test-complexity.md)) | XS | — | open, when nearby |
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

### S2 — CI's OIDC trust is repo-wide and `pull_request` triggers it · Should · M · reduced

`OperatorRole`'s federated statement matches `repo:<org>/<repo>:*` — every ref, PR branch and tag —
and `e2e-cloud-test.yml` runs on `pull_request`, so "can push a branch to the repository" means
"holds operator credentials". Reduced since filing: the job runs on `ubuntu-latest`, not a
self-hosted runner, fork PRs cannot obtain an `id-token`, and the operator role is the narrow one.
**Proposed:** pin `sub` to `repo:<org>/<repo>:ref:refs/heads/main` plus an
`environment:<name>` entry, put the E2E job behind an environment with required reviewers, and
replace the bare `pull_request` trigger with a label gate. S16 showed how this compounds with any
compromised step in a federated job.

### S7 — a runner can overwrite another job's objects under `jobs/*` · Could · M · reduced

Reduced by `narrow-bucket-grants` (2026-10-13): `RunnerRole` holds `PutObject`/`GetObject` on
`jobs/*` and `GetObject` on `releases/*` only — no delete, no listing, no write to the pinned runner.
What remains is that benchmark code can overwrite any job's objects under `jobs/*`, including its
`jmh-result.json`, since IAM has no per-job scope for an instance profile. Versioning is `Suspended`,
so an overwrite is unrecoverable. The same trust position as *Runners can terminate each other*
(CLAUDE.md, Accepted risks): only an operator supplies a benchmark JAR. **Would need:** per-job
credentials — e.g. the CLI vending a session-tagged role session to the instance — which is its own
change.

### A4 — a store failure after the upload loses the measurement row · Could · M · reduced

All four services order S3 first, then the store, on purpose: the item points at `resultJsonKey`, so
writing it first could publish a row referencing an object never uploaded. Writes are idempotent and
batched, and a failed write exits non-zero with the S3 artifacts intact (asserted by an IT). What
remains: the row itself is lost and nothing re-derives it from `jmh-result.json`.

### N3 — a failed reservation strands the uploaded `input/` · Could · S

`baas run` uploads `benchmark.jar` (and a `--runner-jar`) before it reserves the job item, so a
reservation that cannot be written leaves `jobs/<project>/<jobId>/input/` with no job item: invisible
to `jobs list` and never expired (no lifecycle rule under `jobs/`, by design). U25 removed the other
way into this state. Reserving first would instead leave a `launching` item for a failed upload,
which lists as `vanished` — arguably the better failure.

### N2 — the August deployment's operator role is still in the account · Should · XS

`3q7i7s65-operator-role`, from the caller-ARN naming that `account-derived-deployment-naming`
replaced, still exists: the `baas-operator` profile in `~/.aws/config` assumes it successfully
(2026-10-05). The deployer cannot remove it (outside its prefix). Delete it, and the profile, with an
identity above the deployer.

### PT3 — perfasm's save locations write outside the uploaded tree · Could · S

Found 2026-10-31 by `multiple-jdks`' review of every JMH option (would a user value break BaaS?). `-prof
perfasm:savePerfTo=…`, `savePerfBinTo`, `saveLogTo` and their `…ToFile` forms name where perfasm writes its
files; the runner neither points them at the job's `profiler-output` directory, as it does `dir` for async
and jfr, nor uploads anything outside it, so those files are lost with the instance. Not managed, because
perfasm cannot run on the image today: no instance type the operator policy allows (c5, c6i, c7i, m5,
m6i, m7i, within the 33-vCPU quota) exposes the hardware counters it needs (c5.2xlarge and c5.4xlarge
checked), so an injection could not be tested. **Fix**, once a counter-exposing type is allowed: inject
`savePerfTo`/`savePerfBinTo`/`saveLogTo` under `profiler-output` like `dir`, and manage the keys.

### Codebase review 2026-10-13 — X, G, K, T, B

Filed by the review in [`2026-10-13-codebase-review/`](2026-10-13-codebase-review/README.md), triaged 2026-10-20
([`alive-findings.md`](2026-10-13-codebase-review/alive-findings.md) holds the decisions), and moved here on 2026-10-22 after
each was re-checked against `next-release`: all still open. Each ID's evidence, failure scenario and
recommendation stay in its report, linked from the index. *When nearby* means fixed when work touches
that code, not scheduled. B6's remainder (thin read commands building clients inline) is not listed:
ADR 0009 adds a port only when a sequence test needs one.

- **G3, G4, G8 — users first.** Each needs a decision about what BaaS records or offers before it is
  built; the proposals are in [`user-discussion.md`](2026-10-13-codebase-review/user-discussion.md).
- **G5, G13 — deferred in triage.** Valid and small; not scheduled.

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

