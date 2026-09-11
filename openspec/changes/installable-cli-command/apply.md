# Apply Receipt

> Generated at the end of the apply phase to mark code-implementation
> complete and provide verify with the state it needs.
> Overwritten on each apply iteration; iteration counter grows.

**Change**: `installable-cli-command`
**Iteration**: `1`
**Applied at**: `2026-09-11 16:57`
**Executor**: `subagent-driven-development`

---

## Workspace

- **Worktree**: `.claude/worktrees/installable-cli-command/`
- **Branch**: `feat/installable-cli-command` (branched from `feat/baas-user-artifact` at `e4ce6bb`)

---

## Commits

- **Range**: `e4ce6bb..14138b9`
- **Count**: `20` — twelve plan tasks (each reviewed individually, several with one fix round), then
  one final fix wave of three commits after a whole-branch review

---

## Tasks

- **Completed**: `57 of 65` checkboxes in tasks.md flipped to `- [x]`
- **Remaining**: `8.5, 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7` — every one is manual and post-release.
  8.5 is the clean-machine README walk; §9 is end-to-end verification against real AWS. Both need
  a published release carrying the installer asset, which does not exist until the next release
  publishes one. They were left open deliberately, not skipped.

The total is 65, not the plan's 64: task 7.10 was added to record the `docs/diagrams/baas-run.mmd`
update, which the plan omitted and CLAUDE.md requires whenever a command changes.

---

## Notes for verify

- **Build.** Full `mvn -B clean verify`, Docker up and every integration test running, was green at
  `d7f4b05` (all six modules; baas-cli 283 unit + 16 IT, zero failures). The final fix wave after
  that changed only Java *comments*, shell scripts and docs, and was verified with
  `mvn -B clean install -DskipITs` (baas-cli 283/0) plus the installer harness.
- **Installer harness.** `scripts/tests/install-test.sh` has 23 cases, green under both `sh` and
  `dash`. Every case added or changed during this run was mutation-tested: the behaviour was broken
  in a scratch copy and the case was confirmed to fail. That requirement was added mid-run, after
  five assertions were found to pass no matter what.
- **The version chain.** Proven locally: a build at `9.9.9-ci` made `baas --version` print exactly
  `baas 9.9.9-ci`, both directly and through the installed shim. This is the failure class the
  change exists to prevent.
- **CI has not run.** `.github/workflows/install-test.yml` was committed but not pushed, so its
  macOS/Linux matrix has never executed. A push is the user's call.
- **The project's `verify` rule does not apply here.** That rule, comparing a benchmark score against
  a pre-change run, is for measurement-affecting changes. This change touches no runner, image or
  user-data code, so comparability with existing results is unaffected. Verify should say this
  explicitly rather than skip the rule.
- **Where the implementation departs from the plan text.** Every departure is recorded as a ruling
  in the execution ledger: four test cases corrected before execution (A-C1..A-C4), one test seam
  added (`BAAS_INSTALLED_VERSION`), version resolution scoped to install mode, and tasks 1.4-1.6
  verified by the controller because the plan never turned them into tasks. The spec was treated as
  the binding authority throughout.

---

## Next step

Run `/opsx:verify` (openspec-verify-change) to check completeness, correctness and coherence against
the specs, design and tasks. Then, on PASS or PASS_WITH_WARNINGS, run finalize. It merges this
worktree into `feat/baas-user-artifact` and pushes, and both need the user's go-ahead.
