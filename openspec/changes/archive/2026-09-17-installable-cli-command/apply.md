# Apply Receipt

> Generated at the end of the apply phase to mark code-implementation
> complete and provide verify with the state it needs.
> Overwritten on each apply iteration; iteration counter grows.

**Change**: `installable-cli-command`
**Iteration**: `2`
**Applied at**: `2026-09-12 10:40`
**Executor**: `subagent-driven-development`

---

## Workspace

- **Worktree**: `.claude/worktrees/installable-cli-command/`
- **Branch**: `feat/installable-cli-command` (branched from `feat/baas-user-artifact` at `e4ce6bb`)

---

## Commits

- **Range**: `e4ce6bb..994efbf`
- **Count**: `23` — iteration 1 delivered 21 (twelve plan tasks, each reviewed individually, then a
  final fix wave after a whole-branch review, then the bookkeeping commit). Iteration 2 added 2.

---

## What iteration 2 changed

The user asked, after reading iteration 1's verification report, for the cheap coverage gaps to be
closed before hand-off. **Test code only** — `scripts/tests/install-test.sh` alone. No production
file changed, so iteration 1's build evidence still stands.

The installer harness went from **23 to 30 cases**. Seven were added, closing four of the eight
scenario gaps that iteration 1 recorded as warning W4:

| Was W4 | Scenario now covered | Cases |
|---|---|---|
| c | *An absent or too-old Java runtime blocks installation* — at install time, in `check_prerequisites` | 3: Java 17 refused; `java_major` parsing `1.8.0_402` as 8; Java absent entirely |
| d | *An optional tool is reported, not enforced* — `git` missing warns, exit stays 0 | 1 |
| e | *A newer release is installed by its own installer* — `--update` hands off | 1 |
| f | *An unreadable installed version stops the update* | 2: no shim at all; shim present but its version unreadable |

The absent half of **c** was missed on the first pass of this iteration, because the recommendation
written in iteration 1's `verify.md` described only the too-old case. A review caught it and it was
closed in a follow-up commit.

Still open from W4, and unchanged: **a** (a named-but-missing JAR, inside `call()`, which no test
executes), **b** (positive `commit`/`branch` derivation), **g** (an in-flight command during a
concurrent install — needs a live JVM), **h** (the published installer and the release-time version
bake — only exist on a real release). **g** and **h** are covered by the manual items in §9.

---

## Tasks

- **Completed**: `57 of 65` checkboxes in tasks.md flipped to `- [x]` — unchanged by iteration 2,
  whose work came from verification warnings rather than from a task.
- **Remaining**: `8.5, 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7` — every one manual and post-release. 8.5
  is the clean-machine README walk; §9 is end-to-end verification against real AWS. Both need a
  published release carrying the installer asset, which will not exist until this change is merged
  and released. They block **archive**, not finalize.

The total is 65, not the plan's 64, because task 7.10 was added to record the
`docs/diagrams/baas-run.mmd` update that the plan omitted.

---

## Notes for verify

- **Build.** Unchanged since iteration 1 and still valid, because iteration 2 touched no production
  code. Full `mvn -B clean verify` with Docker up and every integration test running was green at
  `d7f4b05`; `mvn -B -DskipITs clean install` was green at `14138b9` (51 / 36 / 283 tests, zero
  failures).
- **Installer harness.** 30 cases, green under both `sh` and `dash`. Every case added in either
  iteration was mutation-tested: the behaviour was broken in a scratch copy and the case confirmed
  to fail. That discipline exists because five assertions in this change passed no matter what
  until it was applied.
- **The version chain.** Proven locally: a build at `9.9.9-ci` made `baas --version` print exactly
  `baas 9.9.9-ci`, both directly and through the installed shim. That is the failure class this
  change exists to prevent.
- **CI has still never run.** `.github/workflows/install-test.yml` is committed but not pushed, and
  it triggers only on `pull_request` or a push to `main`. `feat/baas-user-artifact` has no upstream
  and no PR, so pushing the branch alone would not start it.
- **The project's `verify` rule does not apply.** Comparing a benchmark score against a pre-change
  run is for measurement-affecting changes; this one touches no runner, image or user-data code.
  Verify should say so rather than skip it silently.
- **Departures from the plan text** are all recorded as rulings in the execution ledger, with what
  each costs if wrong. The spec was treated as the binding authority throughout.

---

## Next step

Re-run `/opsx:verify` (openspec-verify-change) for iteration 2. Then finalize, which merges this
worktree into `feat/baas-user-artifact` and pushes — that needs the user's go-ahead, and must run
from the main checkout, since the target branch is checked out there rather than in this worktree.
