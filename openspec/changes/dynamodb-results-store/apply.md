# Apply Receipt

> Generated at the end of the apply phase to mark code-implementation
> complete and provide verify with the state it needs.
> Overwritten on each apply iteration; iteration counter grows.

**Change**: `dynamodb-results-store`
**Iteration**: `1`
**Applied at**: `2026-08-16 23:33`
**Executor**: `subagent-driven-development`

---

## Workspace

- **Worktree**: `.claude/worktrees/dynamodb-results-store/`
- **Branch**: `impl/dynamodb-results-store` (branched from `feat/baas-cli-openspec-test`)

Not `.worktrees/` as the template suggests: the native worktree tool defaults to branching from
`origin/main`, which is at `8038f67` and contains no `openspec/` directory at all, so the worktree
was created from HEAD instead and `.claude/worktrees/` was added to `.gitignore`.

---

## Commits

- **Range**: `9529b17..1b55203`
- **Count**: `6`

| SHA | What |
|---|---|
| `9529b17` | chore: ignore `.claude/worktrees` |
| `c1f83b4` | §1 blocking-assumption findings |
| `63a2fce` | §2 forward caller-supplied tags to the runner |
| `1b25b54` | §2 stop caller tag values being re-parsed as shell in `eval` |
| `5b6bcd8` | §2 derive `project` and `commit`, forward as tags |
| `e743421` | §2 capture and forward `jdk`, `cpuModel`, `cpuArch` |
| `1b55203` | final-review fix wave (reserved tag keys, ordering, docs, tasks.md) |

---

## Tasks

- **Completed**: `11 of 108` checkboxes in tasks.md flipped to `- [x]`
- **Remaining**: `2.6, and all of §3-§12 (97 items)`

**This is deliberate, not an incomplete apply.** `plan.md` scopes phase 1 to §1-§2 only, because
task 1.2 could have changed the partition key that §3 and §6 encode. Task 1 resolved it: 121 JMH
documents and 0 JCStress documents, far below the 100k threshold, so `pk = RESULT#<project>`
stands and `design.md` and `specs/results-store-schema/spec.md` were correctly left untouched.
§3-§12 have no plan yet and must be planned before they can be applied.

Task 2.6 (verify a user tag reaches the stored result on a real run) is `plan.md` Task 5. It is
live AWS spend and has not been run.

---

## Verification performed

- `mvn -pl baas-cli test` — 187/187
- Full reactor `mvn clean verify` — BUILD SUCCESS across all 5 modules, with
  `JmhWithAsyncProfilerSubcommandServiceIT` confirmed **running, not skipped**. Note `ASYNC_PATH`
  must point at a library that exists on the build machine; `plan.md` Task 4 Step 8's literal
  value is the on-instance Linux path and silently skips that test locally.
- Every task individually reviewed; one whole-branch review; one fix wave; one scoped re-review.

## Findings fixed during apply

Two defects were found by review that the plan itself had authored:

1. **Command execution via tag value (Critical).** A tag containing `$(...)` or a backtick
   executed on the instance, because the rendered tags are re-split with `eval`. Reproduced
   empirically. It ran under `RunnerRole`, which holds an SSM read of the Mongo connection string
   that `operator-policy.json` deliberately withholds from operators — an escalation inside the
   project's own IAM split. Fixed in `1b25b54`; independently re-verified in real bash.
2. **A caller `--tag` silently overrode an instance-observed tag (Important).** `--tag jdk=8`
   stored `jdk=8` while the same run's `environment.json` reported the real JDK, breaking
   `specs/results-store-schema/spec.md:94-97`. Invisible to per-task review — the caller path,
   the observed path and picocli's last-wins parsing live in three separate places. Fixed in
   `1b55203` by reserving the machine-observed keys and reordering the array.

## Deviations from plan.md

| Plan text | What was done, and why |
|---|---|
| Task 3 Step 7's code block places `resolveProject()` late | Hoisted to the top of `call()`. The step's own prose says "before any AWS call", but its code block sat after image resolution, a full Maven build and the S3 JAR upload. Prose ruled binding. |
| Task 4 Step 4 adds an independent `JDK_VERSION=$(java -version …)` | Used a raw/escaped split of the existing capture instead. `JVM_VERSION` is json-escaped in place, so a second call is a second observation and parsing the escaped value is brittle. One observation, two projections. |
| Task 4 Step 6's tag ordering | Reversed: the caller array now expands before the observed tags. As written it let a caller tag win over an observed one (finding 2 above). |

## Parked findings

- **Reserved-key validation is not fail-fast.** `buildRunnerTags` throws after the Maven build,
  the S3 upload and the image-resolution API calls, rather than beside `resolveProject()` at the
  top of `call()`. Real, but not load-bearing: no EC2 instance is launched and no data is lost —
  it wastes build time on a user error. Parked rather than fixed because the process allows one
  fix wave, and a second would be unbounded. Worth a follow-up.

## Open decisions for the human

None block verify.

1. **`BENCHMARK_PARAMETERS` carries the same `eval` injection weakness** that finding 1 fixed for
   tags, so an operator can still reach `RunnerRole`'s SSM read via a crafted benchmark parameter.
   Pre-existing, outside all 108 task items. Wants its own change.
2. **§1.6 migrated-row `project` default** (task 9.4). Recommended: an `unknown` sentinel — 36 of
   the 41 untagged rows are `gha-e2e-test*` CI fixtures. Note `currentGitCommit()` already falls
   back to the literal `unknown`, so the two sentinels would collide.
3. **Two uncovered Mongo references** no task item mentions: `DeployerPreflight.java:68` and
   `TeardownCommand.java:99-104`. Add as 7.11/7.12, or leave to whoever plans §7.
4. **`source` tag** (36 rows, `gha-e2e-test*`) is outside the known-key vocabulary — map or drop
   at task 9.4.

---

## Next step

`Run /opsx:verify`. Then either run plan.md Task 5 (live AWS verification of §2.6) or plan §3-§12,
which have no plan yet.
