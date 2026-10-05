# Fire-and-forget `baas run` — analysis

Stub change: this is a pre-proposal exploration, not an OpenSpec artifact. It was written as
`docs/analysis/fire-and-forget-run-analysis.md` and moved here on 2026-10-05.

Status: **exploration complete (2026-10-05)**; no OpenSpec change yet. Three changes follow from it,
in this order: `jcstress-e2e`, `runs-command` (already queued), then `detached-run`. The
failure-notification recipe (§8) is a suggestion, written only once `detached-run` works.

Baseline: `next-release` at `5d33b81` (2026-10-05). That is PR #77 rebase-merged (the CLI usage
fixes, ADRs 0002–0005, `docs/review/open-findings.md`) plus the LocalStack 4.14.0 pin (N1). The pin
touches tests, docs and the queue only. Line numbers are from `5d33b81` and are identical to the
pre-merge `7714681`.

## 1. The ask

Let `baas run` launch a benchmark and return without waiting for it to finish, so a CI job can fire
a run and finish without holding a GitHub Actions runner for the whole benchmark. Then provide a way
to check the run's status later.

## 2. How the discussion got here

1. **Pass 1 (`main`, S3 sentinel).** The proposal was a `launch.json` in the run prefix, a
   `baas status`/`wait`/`cancel` command set, and a stored deadline. **Superseded** by run items.
2. **Pass 2 (`main`, DynamoDB status decided but not built).** The proposals were: create the item
   before launching, the watchdog writes `timed-out`, tags on the item, `gsi1sk = RUN`. **All built**
   in `run-status-in-dynamodb`.
3. **Pass 3 (`next-release` `cb35cb2`, 2026-10-04).** Six gaps. Since then:
   - the summary gap is **closed** by U27;
   - the single-run lookup gap is **decided** under `runs-command`;
   - the unverified-watchdog gap is **closed** by W1;
   - and a dependency, U38, is **fixed**.
4. **Pass 4 (`cli-usage-reanalysis` `7714681`, 2026-10-05).**
5. **Pass 5 (this document, `next-release` `5d33b81`, 2026-10-05).** PR #77 merged, and the N1
   LocalStack pin was added. No change to the CLI, model, runner, infra or CI code since pass 4, so
   the gaps and findings carry over unchanged.
   - Side note: `QUEUE.md` row 1 still reads "In review: PR #77 … Merge PR #77 into
     `next-release`", which is now stale.
6. **Decisions on 2026-10-05:**
   - Q1 deferred, with (b) preferred (§8);
   - Q2: a passive waiter;
   - then **`runs wait` dropped from scope altogether** (§9), which makes Q2, Q2a, G2, G3, DR2 and
     DR3 moot;
   - Q3: `detached-run` becomes a separate change after `runs-command`;
   - **no special run-item status for a detached launch** (§10); a `detached` item field is
     deferred;
   - Q4: the summary prints `"status":"launched"` for a successful detach;
   - Q5: no superseding. Every CI-started benchmark runs to completion;
   - Q6: E2E runs both modes, the detached leg as `jmh-with-async` and the attached leg as
     `jcstress` in sanity mode. That surfaced Q7;
   - Q7: the runner's JCStress mode option and the attached JCStress leg go into a separate
     `jcstress-e2e` change, first;
   - Q1 resolved as a suggested option: a consumer recipe with pending-at-launch statuses (§8).
     It is not written until `detached-run` works.

## 3. What the baseline provides

- **Run item** (`RunItem`, `RunItemMapper`, keys in `ResultKeys`):
  - keys: `pk = RUN`, `sk = <createdAt>#<runId>`, `gsi1pk = <runId>`, `gsi1sk = RUN`;
  - fields: runId, project, createdAt, resultPath, instanceType, status, instanceId, errorCode,
    tags, updatedAt;
  - there is **no timeout or watchdog-bound field.**
- **Statuses (`RunStatus`, state diagram `docs/diagrams/baas-states-run.mmd`):**
  - in flight: `launching` → `launched` → `running`;
  - terminal: `completed`, `failed:<n>`, `timed-out`, `cancelled`, `launch-failed`;
  - computed when reading, never stored: `vanished` (non-terminal and no live tagged instance) and
    `status-lost` (the poll only: vanished, but measurements exist).

  Every write is a conditional `UpdateItem`, and the first terminal status wins.
- **ADR 0003:**
  - Only the instance writes `completed`/`failed:<n>`, and no CLI path overrides or terminates
    after them (`RunStatus.isRecordedByInstance`, used by `stop`, `confirmLaunched` and
    `RunTermination`).
  - A late `launched` refused over `completed` now counts as confirmed (U38 fixed).
- **`RunCommand.execute()`:**
  1. reserve (~l.419);
  2. register the shutdown hook **before** the launch (l.443). It calls
     `session.stop(CANCELLED)` whenever `!session.ended()`;
  3. `runInstances`;
  4. `confirmLaunched` (l.478), which exits 1 on `CANCELLED_WHILE_LAUNCHING`;
  5. `poll` → `RunSession.await`.
- **`RunSession.await()`:**
  - its cap clock starts at the call;
  - on cap overrun it *writes* `timed-out` and terminates the instance;
  - it reads state as `instanceId == null ? "unknown" : instances.state(instanceId)` (l.226).
- **`RunRecorder.find(runId)`**, plus **`Instances.findLive(runId)`**, a `baas-request-id` tag
  lookup used by `RunTermination` and `RunListing`.
- **`baas runs list`** takes `--limit`, `--in-flight`, `--project`, `--tag` and
  `--format table|json|csv`.
- **`baas runs terminate <runId> [--yes]`** never cuts off an instance that recorded its own
  outcome (U30).
- **The run summary** (`--format json`) carries `runId`, `project`, `resultPath`,
  `status` (`completed`/`failed`, the command's verdict), `runStatus` (the item's status, U27),
  `exitCode` and `instanceId`. CI reads `runStatus` from it and no longer lists 50 runs with `jq`.
- **W1 has been run live** (ADR 0003; `run-status-in-dynamodb/verify.md`):
  - with the CLI `kill -9`ed after launch, the watchdog recorded `timed-out`, uploaded the boot log
    and terminated the instance. That is exactly the detached-timeout path;
  - layer 2 (`timeout` → `failed:124`) was also confirmed with the CLI detached.
- **The termination layers** (CLAUDE.md "Three termination layers, all required"; ADR 0001) still
  name the CLI shutdown hook as layer 3.
- **The archived `run-status-in-dynamodb` design** lists "reattaching … (`--detach`/`attach`,
  rejected in the usage analysis §5)" as a non-goal. That analysis file is deleted now (see
  `git log -- docs/analysis`), and its reason ("it would need run discovery by tag") is gone.
- **The queued `runs-command` change** (`docs/review/open-findings.md`, *runs-command*, P11 + U28)
  already decides that `baas runs show <run>` prints the run item, including the resolved status,
  above the manifest. **That is the "status of one run" command this feature needs.**

## 4. What fire-and-forget still has to close

**G1. The shutdown hook would cancel a detached run.** A detached `run` returns after
`confirmLaunched`. The JVM exit then runs the hook (l.443), which calls `stop(CANCELLED)` and
terminates the instance.
- Fix: keep the hook armed through `RunInstances` and `confirmLaunched`, which preserves the
  Ctrl+C-during-launch coverage, then hand the session off with a `detached` flag the hook checks,
  or deregister the hook.
- `CANCELLED_WHILE_LAUNCHING` must still exit 1.
- The three-termination-layers rule (CLAUDE.md, ADR 0001) must state that a detached run's layer 3
  is `baas runs terminate`.
- Record that in a new ADR, which also supersedes the archived non-goal.
- W1 has already shown that the watchdog and `timeout` alone end a CLI-less run.

> **G2 and G3 are moot since 2026-10-05: `runs wait` is out of scope (§9).** They are kept below
> because they apply unchanged if a waiter is ever reintroduced.

**G2. A waiter started later has no cap.** `await()` caps from the moment it is called and writes
`timed-out` on overrun. A later process doesn't know the run's watchdog bound. Options:
- **Passive `runs wait` — decided 2026-10-05 (Q2).** It writes nothing and terminates
  nothing. Ctrl+C stops watching, not the run. Timeouts are left to the watchdog, which is now
  verified. Needs an `await` mode without the cap-and-stop path, or a small separate loop.
- **Active waiter.** Add `watchdogBoundSeconds` to the reservation, so the remaining time can be
  computed from `createdAt`. This restores layer 3 while someone waits, but makes a reader a writer.

**G3. `await` can poll forever when it has no instance id (l.226).** A session rebuilt from the item
can see `launching`/`launched` with no `instanceId`: `launched` is best effort, and the instance's
`running` write may never land if it dies at boot. With state `unknown`, `await` never decides
`vanished`.
- Fix: the waiter resolves the instance with `findLive(runId)`. That is the rule the state diagram
  already states for readers: non-terminal and no live tagged instance means vanished.
- **Caveat (U31, Info, still open):** right after launch, `DescribeInstances` may not see the
  tagged instance yet, so `runs list` briefly shows a launching run as `vanished`. A waiter started
  immediately after `run --detach` must not conclude `vanished` in that window.
- Simplest fail-safe: never conclude `vanished` while the status is still `launching` and the item
  is younger than a fixed grace period of a few minutes. While the grace period runs, keep waiting.

**G4. Where `run --detach` lives — decided (Q3): its own change, `detached-run`, after
`runs-command`.** `runs-command` already makes `runs show <runId>` the lookup
by id (U28), and with `runs wait` dropped, `--detach` is the only new surface. Where it goes:
- into `runs-command`, which is a breaking `feat(cli)!` for the next major anyway, so the batch
  carries one major;
- or a follow-up change after it.

**G5. What the summary reports for a detached run — decided (Q4): `status` = `launched`.** The run item needs no special case (§10): its
lifecycle is the same with or without a CLI. Only the summary printed on exit differs, because it is
a snapshot taken seconds after `RunInstances`, before the instance's `running` write.
- `runStatus` is set from `RunSession.endStatus()` (`RunCommand` l.265), which is assigned only on
  paths where the run ended. A confirmed launch with no poll leaves it `null`. **Fix:** set it to
  `launched` (or `launching` if that write was lost) when the launch is confirmed, so it reports
  what the item says.
- `status` is the command's verdict, documented as `completed`/`failed`, and existing consumers
  read it. A detach that succeeded would print `"status":"completed"` for a run that has not
  started. Options:
  - `"status":"launched"`, a new verdict value, seen only by callers who passed `--detach`
    (recommended);
  - keep `completed`, meaning the command did what it was asked, and rely on `runStatus`.
- `status` is derived as `exitCode == 0 ? "completed" : "failed"` (`RunCommand` l.858). Nothing in
  this repository reads it: E2E reads `runId` and `runStatus` (`e2e-cloud-test.yml:125`).

## 5. Proposed surface

```
baas run --detach … --format json      reserve → launch → confirm → disarm hook
                                       → {"status":"launched","runStatus":"launched",…}, exit 0
baas runs show <runId>                 (runs-command, decided) run item + resolved status, then the manifest
baas runs list --tag source=ci --in-flight            (exists)
baas runs terminate <runId> --yes                     (exists; layer 3 for a detached run)
```

- Attached `baas run` stays exactly as it is. `--detach` only stops after `confirmLaunched` and
  disarms the hook, so `RunSession.await` needs no refactor.
- **Not in scope:** `runs wait` (§9).
- **No IAM change.** Every write involved is already granted.

**CI ideas:**
- **E2E: two legs (Q6).**
  - **detached `jmh-with-async`:** `run --detach`, then a bounded shell loop polls
    `runs show <id> --format json` and asserts `completed`. A bad bake still turns the job red, and
    so does a hook that cancels the detached run (G1);
  - **attached `jcstress` in sanity mode:** the first JCStress end-to-end coverage.
- **Superseding stale runs — rejected (Q5, 2026-10-05): CI-started benchmarks run to completion.**
  Kept for reference: `cancel-in-progress: false` exists because
  cancelling mid-`baas run` killed layer 3 (CLAUDE.md, *e2e-cloud-test.yml* paragraph), and a
  detached job makes that moot. But superseded runs would keep running. A PR job could
  `--tag pr=<n>` and, before launching, run `runs list --tag pr=<n> --in-flight` followed by
  `runs terminate`.

## 6. Review findings

The IDs use the prefix `DR` (detached run). Plain `D` is taken by module-review findings in
`docs/review/open-findings.md`. The open ones (DR1, DR4) are indexed there under `detached-run`;
DR2 and DR3 are moot (§9) and kept here only.

| ID | Finding | Sev | Where | State |
|---|---|---|---|---|
| DR1 | Detaching with the current hook cancels and terminates the run just launched (G1) | High if detach ships naively | `RunCommand` l.443 | Open — design input |
| DR2 | `await` never decides `vanished` when `instanceId` is null (G3). Latent: attached mode always has the id locally | Med for any waiter | `RunSession.await` l.226 | Moot (no waiter, §9). Reopen with `runs wait` |
| DR3 | A waiter that uses the reader rule (`findLive`) would misreport a just-launched run as vanished in U31's window (G3) | Low | `RunListing` / U31 | Moot (no waiter, §9). `runs show` shares U31's accepted window |
| DR4 | The archived non-goal "no `--detach`/`attach`" cites a reason that run items removed. A detach change must supersede it in an ADR and amend the termination-layers rule | Info | archived `run-status-in-dynamodb/design.md` Non-Goals; CLAUDE.md; ADR 0001 | Open |

Closed since pass 3:
- the CI `--limit 50 | jq` lookup (U27; CI reads `runStatus`);
- the unverified watchdog path (W1, run live 2026-10-05);
- the late `launched` misreport (U38, ADR 0003).

## 7. Open questions (ask one at a time, in this order)

1. **Q1: who learns that a detached CI run failed?** **Resolved 2026-10-05 as a suggested option
   only (§8):** (b), delivered as a **documented recipe for consumer repositories** with
   pending-at-launch commit statuses. **Nothing is written into the README, a workflow or the CLI
   until `detached-run` works**, and the requirements may change before then. Option (b) is
   marked preferred, but it is neither designed in detail nor implemented until the detached-run
   design (Q2–Q5) is complete. It is not part of the detached-run change's scope. See §8.
   - (a) nobody automatically;
   - **(b) preferred:** a scheduled sweep that posts commit statuses (§8);
   - (c) a later job in the same PR runs `runs wait`, which gives back most of the saving.
2. **Q2:** a passive or active waiter (G2)? **Moot since `runs wait` was dropped (§9).** It had been
   decided as passive on 2026-10-05, which still stands if a waiter returns. It writes nothing,
   terminates nothing, and Ctrl+C stops watching, not the run. Timeouts belong to the watchdog
   (verified live, W1). The run item gains no time-limit field.
   - **Q2a, the command's name: moot (§9).** The notes are kept for a future waiter. The user suggested `watch`. In this CLI
     `--watch` already means something else: `baas results --watch` redraws a table every 30 s on
     the alternate screen, refuses `--format json` and a non-interactive terminal, and never ends by
     itself (`ResultsCommand` l.100, `watchRefusal()`). A CI-facing command that blocks and exits
     with the run's outcome is the opposite on each point. Candidates:
     - `runs wait` (recommended): AWS CLI waiters (`aws ec2 wait …`) and `kubectl wait` mean
       "block until a condition, then exit with the result";
     - `runs show <id> --wait`: no new subcommand, since `show` is already the by-id lookup
       (`runs-command`). But a flag would change `show`'s exit code;
     - rejected: `watch` (collides with `results --watch`), `follow` (suggests streaming logs,
       which this does not do), `attach` (suggests control; it is the archived design's name
       for an *active* reattach), `await` (only `wait` in Java dress).
3. **Q3:** fold `--detach` into `runs-command`, or a follow-up change (G4)? **Decided 2026-10-05: a
   separate change, `detached-run`, after `runs-command`.** Reasons:
   - detach needs `runs show`, so it cannot ship first;
   - `next-release` batching gives one release either way;
   - display (P11) and termination safety (hook, ADR) are separate reviews.

   Not decided: its `QUEUE.md` position relative to `export-before-teardown`,
   `narrow-bucket-grants` and `retire-mongodb`. Add the row when the change is proposed.
4. **Q4:** the summary's `status` for a detach: `launched` or `completed` (G5)? **Decided
   2026-10-05: `launched`.** A successful `run --detach` prints `"status":"launched"` and
   `"runStatus":"launched"` (or `launching`), with exit 0. A failed detach prints `failed`, as
   today.
5. **Q5:** a superseding policy for PR runs, or none? **Decided 2026-10-05: none (a).** A
   benchmark that CI started runs to completion. No CLI option, and no workflow recipe for
   terminating superseded runs. Accepted consequence: under `--detach` the per-ref concurrency group
   (`e2e-cloud-test.yml:31-34`, `cancel-in-progress: false`) no longer bounds instances. It used to
   allow at most one running and one pending job per ref, and middle pushes in a burst were
   skipped. A detached job frees its slot about a minute after launch, so every push runs its own
   instance, concurrently, each bounded only by its watchdog. Cost scales with push count
   (CLAUDE.md's accepted "cents per push"), and every commit gets measured. The `detached` item
   field (§10) no longer has Q5 as a reason; only the §8 sweep might need it.
6. **Q6:** which mode the paid E2E exercises? **Decided 2026-10-05: both, as two legs with
   different benchmark types:**
   - **detached leg: `jmh-with-async`**, today's fixture run (`fake-jmh-benchmarks`,
     `Incrementing_Synchronized`, `-f 1 -wi 1 -i 1`, flamegraph+jfr). It runs `run --detach`,
     asserts `"status":"launched"`, then polls `runs show <id> --format json` every ~15 s in a
     bounded loop until the status is terminal, and asserts `completed`. Today's measurement,
     artifact, flamegraph and jfr assertions then apply unchanged;
   - **attached leg: `jcstress` in sanity mode** against `fake-stress-tests`. This closes CLAUDE.md's
     "`jcstress` has no end-to-end coverage at all" (l.451) and keeps the attached poll, report
     and results-table path covered.

   Cost: one extra short fixture instance per triggering push. The legs run in parallel, so the
   job takes no longer. Consequences found while checking the code (2026-10-05):
   - **The runner cannot select a JCStress mode.** `ApiJCStressOptions` has no `-m`/`--mode`, and
     `JCStressSubcommandService` builds JCStress's argument list explicitly (`-r -c -f -fsm -hs
     -jvmArgs -jvmArgsPrepend -pth -sc -spinStyle -strideCount -strideSize -t`), with no
     passthrough. An unknown `-m sanity` is a picocli error. Sanity mode needs a `-m`/`--mode`
     option threaded through `ApiJCStressOptions` → `JCStressOptions` → the service. Where that goes
     is **Q7**.
   - **The fixture contains a FORBIDDEN outcome.** `IntegerIncrementing.TestWithForbiddenResults`
     fails whenever the non-atomic increment races, so whether it fails is nondeterministic. The
     runner treats a non-zero JCStress exit as non-fatal and stores the summary anyway
     (`JCStressSubcommandService` l.71–76), so the run still ends `completed`. **The leg must
     assert the item and summary shape** (one `JCSTRESS#` item, tags, `jcstress-output.txt`), not
     pass or fail counts. Or it selects only `TestWithInterestingResults` with `-t`.
   - **Per-leg assertions.** `jmh-result.json`, the flamegraph and the `.jfr` exist only for the
     `jmh-with-async` leg. The workflow becomes two jobs, or a matrix with type-specific steps.

7. **Q7:** where does the runner's JCStress `-m`/`--mode` option go? **Decided 2026-10-05: a
   separate small change, `jcstress-e2e`, that lands before `detached-run`.** Its scope:
   - the runner `-m`/`--mode` option (`ApiJCStressOptions` → `JCStressOptions` →
     `JCStressSubcommandService`);
   - the **attached** JCStress sanity-mode E2E leg against `fake-stress-tests`, asserting the
     item's shape rather than pass/fail counts.

   It needs neither detach nor `runs show`, so it can go ahead of `runs-command`. `detached-run`
   then only switches the existing `jmh-with-async` leg to detached and stays CLI- and
   workflow-only. Not decided: its `QUEUE.md` position. Add the row when it is proposed.

**Every question is settled. The exploration is complete.** Order of work:
`jcstress-e2e` → `runs-command` → `detached-run`, each `/opsx:propose`d from `next-release` with a
`QUEUE.md` row. `detached-run` is proposed with
`/opsx:propose detached-run`, branched from `next-release`, with a row in
`openspec/changes/QUEUE.md`. Q1/§8 is
revisited after that design exists.

## 8. Failure notification — suggested option, not to be implemented yet

Decided 2026-10-05:
- **(b) is the preferred direction.**
- **Q1a:** it ships as a **documented recipe for consumer repositories**. Not a `baas` command:
  that would give the CLI a GitHub dependency and token handling. Not a sweep in this repository:
  it could reach only this repository's PRs, and here nothing fires and forgets, because the E2E
  detached leg polls `runs show` itself (Q6).
- **The recipe stays a suggestion until `detached-run` has a working implementation.** Write it
  then, against the real JSON, because the requirements may change before that.

The facts and limits below are what the 2026-10-05 discussion established.

- **Who runs it:** GitHub's scheduler, through an `on: schedule` workflow (plus `workflow_dispatch`)
  on a hosted runner. AWS access works the way `e2e-cloud-test.yml` does: OIDC into `OperatorRole`,
  then `baas config sync`. No new IAM is needed: `SetupCommand` writes the trust as
  `repo:<org>/<repo>:*`, which matches a scheduled run's token.
- **Why a red sweep alone is not enough:** GitHub notifies a scheduled workflow's failure to the
  user who **last edited the cron line**, not to the author of the PR whose benchmark failed.
- **Getting the result back to the PR:** the sweep posts a commit status, for example
  `baas-benchmark: failed (timed-out)`, on the run's `commit` tag.
  - CI already tags `github.event.pull_request.head.sha` (`e2e-cloud-test.yml:58`), so the status
    shows in the PR's checks list.
  - It needs `permissions: statuses: write` and `GITHUB_TOKEN` only, with no PAT. That avoids the
    expired-`GHA_EC2_PAT` failure this repository has had before.
- **Limits to design around:**
  - `GITHUB_TOKEN` can post statuses only to its own repository. Runs launched from another
    (consumer) repository need a sweep in that repository, or a GitHub App token.
  - `schedule` triggers run only from the default branch, so the sweep does nothing while it sits
    on `next-release`.
  - Cron runs can be delayed, and GitHub disables schedules in public repositories after 60 days
    without activity. The cadence (nightly vs every ~30 min) sets how fast a failure is reported.
  - `baas runs list` has no time filter, so the sweep filters on `createdAt`.
  - It needs a record of what it has already reported, or it re-reports old failures. An existing
    status on the commit can serve as that record.

### Recipe sketch (non-binding, 2026-10-05)

The model is **pending at launch, resolved by the sweep**.
- The PR shows the benchmark as in progress, and successes are reported, not only failures.
- The sweep touches only commits that carry a `pending` `baas/…` status, so attached runs, which
  never post one, are never re-reported. That means the `detached` item field (§10) is not needed.
- A status that is already resolved is left alone, which handles deduplication.

Launch step, in the consumer's PR workflow (`permissions: statuses: write, id-token: write`):

```bash
sha="${{ github.event.pull_request.head.sha || github.sha }}"
baas run jmh --detach --format json --tag commit="$sha" … -- MyBenchmark … > summary.json
run_id=$(jq -r .runId summary.json)
gh api "repos/$GITHUB_REPOSITORY/statuses/$sha" -f state=pending \
  -f context="baas/<workflow>/<leg>" -f description="run $run_id launched"
```

Sweep, a scheduled workflow in the same consumer repository (cron plus `workflow_dispatch`, from the
default branch):

```bash
baas runs list --tag source=ci --limit 100 --format json > runs.json   # filter createdAt with jq
# for each run that is terminal or vanished and carries tags.commit:
#   read the commit's latest status for the context whose description names the run id;
#   if it is still pending: post success when status == completed, otherwise failure,
#   with the status (failed:<n> / timed-out / cancelled / vanished) in the description
```

Open in the sketch, to settle against the working implementation:
- the context naming;
- how a run maps back to its context (the run id in the description, or a caller tag);
- the cadence;
- how far back to look;
- whether `runs list`'s JSON fields (`status` resolved vs `storedStatus`) are what the sweep should
  read.

## 9. `runs wait` — dropped from scope (2026-10-05)

Decided 2026-10-05: **no `runs wait` in the first cut.** The scope is `run --detach` plus the
`runs show <runId>` already decided in `runs-command`. Reintroducing a waiter later is additive
and non-breaking, so do it when a second consumer of the E2E polling loop appears.

Why: fire-and-forget itself never waits. The only strong use was CI's own E2E check, and a shell loop
over `runs show` covers it.

| Use case | Needs `runs wait`? |
|---|---|
| CI fires a benchmark and moves on (the ask) | No. That is `run --detach`, and nothing waits afterwards |
| Failure notification (Q1 (b), deferred) | No. The sweep reads `runs list`/`runs show` on a schedule |
| A later job in the same PR waits (Q1 (c)) | Yes, but (c) was not chosen: it gives back the saving |
| An operator at a laptop checks on a run | No. `runs show <id>` or `runs list --in-flight`; to sit and watch, use attached `baas run` |
| A script launches several runs and waits for all | No. `baas run … &` per run plus the shell's `wait`, and each attached CLI keeps Ctrl+C protection |
| E2E self-test of the detached path | It would be convenient, but a bounded loop polling `runs show --format json` in the workflow is enough |

What dropping it removes:
- G2, and with it Q2 and Q2a;
- G3, DR2 and DR3;
- the `RunSession.await` refactor.

The passive decision and the naming notes (`runs wait` over `watch`, because `results --watch`
already means an interactive redraw) stay in §7 for when a waiter returns.

## 10. No special run-item status for a detached launch (2026-10-05)

Considered: the CLI writes `detached` instead of `launched` when `--detach` is passed. **Rejected.**
- **Status is lifecycle, "detached" is launch mode.** The instance's `running` write replaces it
  about a minute after launch, so for the rest of a long run the item no longer says it.
- **Every reader and writer would have to learn it:** `RunStatus.isInFlight`, the `launched`-only-
  while-`launching` condition, `runs list --in-flight`, the vanished rule, the state diagram and
  ADR 0003's predicates. Anything that missed it would treat a detached run as ended or vanished.
- **It records intent, not who is watching.** An attached CLI killed with `kill -9` is just as
  unwatched, and its item still says `launched`.

**Deferred alternative:** if the distinction is ever needed, the reservation, which already knows the
mode, writes a never-overwritten item field such as `detached: true`, shown by `runs show` and
`runs list`. Not a tag: the run item's tags also go to the runner and land on every measurement,
where launch mode means nothing.

Revisit when either of these needs it:
- ~~the failure-notification sweep (§8)~~: the pending-at-launch model already tells detached runs
  apart, because only they post a `pending` status;
- ~~the superseding policy (Q5)~~: dropped, since Q5 decided on no superseding.

Adding the field later is non-breaking.
