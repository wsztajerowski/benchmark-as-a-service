# Apply Receipt

> Generated at the end of the apply phase to mark code-implementation
> complete and provide verify with the state it needs.
> Overwritten on each apply iteration; iteration counter grows.

**Change**: `dynamodb-results-store`
**Iteration**: `2`
**Applied at**: `2026-08-18 23:58`
**Executor**: `subagent-driven-development`

---

## Workspace

- **Worktree**: `.claude/worktrees/ddb-phase2/`
- **Branch**: `impl/ddb-phase2` (branched from `feat/baas-cli-openspec-test` @ `c920bac`)

Not `.worktrees/<change-name>/` as the schema assumes: the native worktree tool defaults to
branching from `origin/main`, which contains no `openspec/` directory at all, so the worktree was
created from HEAD.

---

## Commits

- **Range**: `c920bac..d1e5953`
- **Count**: `14`

| SHA | What |
|---|---|
| `f629a8f` `2518981` `88c0bef` | `baas-model` module, Mongo banned mechanically (both groupIds) |
| `0914008` `0f5e938` `041fdbc` | Stored measurement shape; `JcstressSummary` null-guard fix |
| `49700f9` | Key encoding, fixed-width UTC sort timestamps |
| `69c541a` | Tag vocabulary defined once, adopted by `baas-cli` |
| `cccf7c5` `ecff28a` | Item mapper; non-finite normalisation + millisecond truncation fix |
| `2d14ebf` | Results table, `requestId` index, DynamoDB gateway endpoint, stack output |
| `21deb3d` `39509df` | Scoped IAM for runner / operator / deployer; policy budget 4096 → 4608 |
| `d1e5953` | Whole-branch review fix wave (F1–F7) |

---

## Tasks

- **Completed**: `29 of 108` checkboxes in tasks.md flipped to `- [x]`
- **Remaining**: `4.3, 4.8 (deliberately deferred), 12.x-adjacent deploy verification, and all of §5–§12`

**§3 is complete. §4 is complete except two items held back on purpose.**

`tasks.md` 4.3 (remove TCP 27017 egress) and 4.8 (remove Mongo SSM grants) are **not** oversights.
The runner still writes to MongoDB Atlas until §5 lands, and `CLAUDE.md` states that omitting 27017
makes every run fail at the database write. `design.md`'s migration plan step 2 governs — *"Ship
the table, gateway endpoint and IAM changes… **Atlas is untouched**."* `tasks.md` §4's ordering
contradicts `design.md`; `design.md` was ruled to govern. Both items are annotated in `tasks.md`
and move to the cutover phase.

Plan Task 8 (deploy and verify against the live stack) has **not** run — see *Not done* below.

---

## Verification performed

- `mvn -pl baas-model test` — **33/33**
- `mvn -pl baas-cli test` — **202/202**
- Full reactor `mvn clean verify` — **BUILD SUCCESS**, all 6 modules, with a real `ASYNC_PATH` so
  the async-profiler IT ran rather than silently skipping
- Every task individually reviewed; one whole-branch review; one fix wave; one scoped re-review

Nothing here is deployed, and nothing yet writes to the table. Every existing benchmark run
continues to work exactly as before — verified across the whole diff, not per-file.

## Findings fixed during apply

Six defects were found by review that the plan itself had authored. Two would have reached
production:

1. **`NaN` score would have lost entire runs** (Task 5). DynamoDB's `N` type rejects `NaN`, and JMH
   reports it for *any* single-iteration run — so `baas run jmh -- X -i 1` would have failed with an
   opaque `ValidationException`. Fixed by normalising non-finite to absent, matching
   `ResultsCommand`'s existing convention. A second instance in `secondaryMetrics` was caught by
   the whole-branch review and fixed in the same way.
2. **Multi-mode runs would have silently overwritten each other** (whole-branch review, F1). The
   sort key omitted `mode`, which the Mongo `_id` carried as `benchmarkType`, so
   `-bm thrpt,avgt` produced two rows differentiated only by a millisecond timestamp. Fixed before
   any migration writes history onto the key shape.

Also fixed: `Map.copyOf(null)` NPE in `JcstressSummary`; `createdAt` not round-tripping below
millisecond precision; the table's key names existing in three unlinked copies; and
`DeployerPreflight` simulating no DynamoDB action.

## Deviations from plan.md

| Plan text | What was done, and why |
|---|---|
| `tasks.md` §4 lists 4.3/4.8 alongside the additive items | Deferred to the cutover phase. Applying them now breaks every run. |
| Enforcer bans `dev.morphia:*` | Also bans `dev.morphia.morphia:*` — the groupId this repo actually uses. The original pattern matched nothing. |
| `JcstressSummary` copies its maps directly | Null-defaults them, matching `StoredMeasurement`. |
| Task 4 adds an independent `JDK_VERSION` capture | Superseded in phase 1; not revisited here. |
| Budget test holds under 4096 | Raised to 4608. Measured: the DynamoDB statement does not fit 4096 even fully wildcarded. Inline on an IAM group (5120 shared), nothing else attached. |

## Not done — the remaining gate

**Plan Task 8: deploy and verify.** It mutates the live CloudFormation stack and creates a
`DeletionPolicy: Retain` table that outlives a teardown, so it is a human gate.

Two things must happen first, in this order:

1. **Re-render and re-attach the deployer policy.** The attached policy has no DynamoDB actions.
   `d1e5953` makes `DeployerPreflight` *detect* that instead of failing partway through the stack
   update — but detection is not the fix. Note `infra/README.md` documents a customer-managed
   policy flow, which is **not** how this is attached; it is inline on an IAM group.
2. **Then `baas admin setup`**, and prove Atlas still works with a real benchmark run.

If a deploy fails partway *after* `CreateTable` succeeds, rollback removes the table while
honouring `Retain`, leaving an orphan that blocks the next setup with an error that never names
DynamoDB. Recovery is manual until the `export-before-teardown` change lands.

## Open decisions carried forward

None block verify.

1. **`BENCHMARK_PARAMETERS` carries the same `eval` injection weakness** fixed for tags in phase 1.
   Pre-existing, outside all 108 task items. Wants its own change.
2. **§1.6 migrated-row `project` default** (task 9.4) — recommended `unknown`; note it collides
   with `currentGitCommit()`'s existing `unknown` fallback.
3. **`source` tag mapping** at 9.4 — map or drop.
4. **Two uncovered Mongo references**: `DeployerPreflight.java` and `TeardownCommand.java`. Add as
   7.11/7.12 when §7 is planned.
5. **`infra/README.md` is wrong about how the deployer policy is attached** — both the mechanism
   and the size figures. Operationally relevant *right now*, because re-attaching that policy is
   the next action.

---

## Next step

`Run /opsx:verify`. Then either run plan Task 8 (live deploy, human-gated) or plan §5–§12, which
have no plan yet. Note the `export-before-teardown` change now exists and changes what §7's
teardown and setup behaviour should be.

---

## Deploy verification (plan.md Task 8) — executed 2026-08-19

Human-gated deploy, run after the deployer policy was re-rendered and re-attached.

| Check | Result |
|---|---|
| Stack | `UPDATE_COMPLETE` in ~90s, no rollback, so no orphaned table |
| Preflight | Passed — confirms the re-attached policy carries the DynamoDB actions |
| Table | `ACTIVE`, `PAY_PER_REQUEST`, **0 items**, `pk` HASH / `sk` RANGE |
| Index | `requestId-index` `ACTIVE`, `gsi1pk`/`gsi1sk`, projection `ALL` |
| TTL | `null` — none |
| Gateway endpoint | DynamoDB, type `Gateway` (not interface), `available`, on `rtb-021189b027d003d9a` — the same route table as the S3 endpoint |
| Stack output | `ResultsTableName` = `baas-3q7i7s65-results` |
| **27017 egress** | **Present** on the live security group (80, 443, 27017) — deliberately, since 4.3 is deferred |
| **Atlas end to end** | Live run `jmh-20260819_082707` on a real `c5.2xlarge` completed, self-terminated, and stored its measurement in Atlas with all nine tags including the custom `phase=2-postdeploy` |
| Table after the run | Still **0 items** — correct; nothing writes to DynamoDB until §5 |

`baas admin setup` warned "No MongoDB connection string provided" because it was invoked without
`--mongo-uri`. It did **not** overwrite the existing value — verified immediately via `config show`,
because an emptied connection string selects `NoOpDatabaseService` and would make runs report
success while discarding measurements.

### No §12 item is ticked by this, and 12.1 in particular cannot be

- **12.1** requires "*the absence of a 27017 egress rule*". This phase deliberately keeps that rule,
  so 12.1 describes the **post-cutover** state and is not satisfiable until 4.3 lands. It also
  specifies a *clean prefix*; this was an update to an existing stack.
- **12.2–12.7** all require measurements in the DynamoDB table. Nothing writes to it yet.
- **12.8** — not claimed. The post-deploy run scored **9,071,763 ops/s ±12,462,803**, against
  **14,075,511 ops/s ±10,632,927** pre-change. The task's own note gives a CI band of 10.0M–29.6M,
  and 9.07M falls just below it — but each run's error bar exceeds its own score, so at `-wi 1 -i 3`
  the configuration cannot support a comparability claim in either direction. Nothing in this phase
  touched the measurement environment: same AMI (`ami-0aa25ec7fbf1c80f5`), same `imageVersion`
  1.2.0, same instance type, JDK and CPU model, and the only changes were a table nobody writes to,
  a gateway endpoint and IAM grants. A real regression is implausible; a meaningful comparison needs
  more iterations.
