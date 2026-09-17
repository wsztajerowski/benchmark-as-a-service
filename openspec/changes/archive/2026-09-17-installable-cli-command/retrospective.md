# Retrospective — installable-cli-command

> Written during finalize. Evidence first, opinion second.

## Wins

- The 30-case POSIX harness (`scripts/tests/install-test.sh`) runs green under both `sh` and `dash`,
  and both CI legs passed on their first-ever run (PR #57): `30 case(s), 0 failure(s)` on
  `ubuntu-latest` and `macos-latest`.
- Every manual §9 check has now executed against real AWS or a real release. 9.1/9.3/9.4/9.6/9.7 on
  2026-09-12; 9.5 on 2026-09-15 once a second installable release existed.
- The release-time `sed` bake — the one step no CI job exercises — is proven on a real artifact: the
  published v3.0.0 `install.sh` carries `BAAS_VERSION_DEFAULT=3.0.0` where the repo copy still holds
  the placeholder.
- The hand-off invariant is proven rather than argued: after `--update`, the stored installer is
  byte-identical to v3.0.0's own published `install.sh`, so the script installing a version really is
  that version's own.

## Misses

- The `macos-latest` CI leg was added for a reason that turned out not to hold. Both runners ship
  `sha256sum` (`/sbin/sha256sum` on macOS is Apple's SIP-restricted `com.apple.md5sum` multi-call
  binary), so both legs take `sha256_of`'s preferred branch and the `shasum -a 256` fallback executes
  nowhere. Confirming W3 is what exposed it; the stale rationale was corrected rather than papered over.
- Four spec scenarios reached finalize without automated coverage (W4 a, b, g, h). W4h is now closed
  by the v3.0.0 install; a, b and g remain, each for a structural reason — two sit in
  `RunCommand.call()`, which no test executes, and one needs a live JVM.
- `RunCommand.call()` having no test at all is a project-wide gap this change inherited and did not
  close.

## Plan deviations

- Iteration 2 of apply was driven by verification warnings, not by a task. It added four harness
  cases (W4 c/d/e/f) and touched no production code, so iteration 1's build evidence carried over.
- §9 ran across two dates instead of one sitting. 9.5's second half was genuinely impossible on
  2026-09-12: v2.2.0 was the first release carrying `baas-cli.jar.sha256`, so no older *installable*
  release existed to upgrade from. It waited for v3.0.0 rather than being marked done or skipped.

## Skill / workflow compliance

- The canonical git-side closeout did not apply and its own escape hatch was taken: the
  implementation was already merged (PR #57) and released, there is no
  `.worktrees/installable-cli-command/`, and the session sits on the integration branch — a case the
  instruction names explicitly.
- `superpowers:finishing-a-development-branch` was invoked as the escape hatch directs. Its three
  options all presume unintegrated work on a feature branch, so none applied; the outcome recorded is
  `merge-locally`, matching where the code actually is.
- `verification-before-completion` was applied throughout, and violated once outside this change —
  see Surprises.

## Surprises

- **The eight blocking manual tasks needed a release that only merging could produce.** 8.5 and
  9.2–9.7 all required a published installer, which did not exist until this change merged. The
  chicken-and-egg was real, not an oversight, and verify.md correctly classified them as blocking
  *archive* rather than finalize.
- **v2.2.0 shipped three `feat(cli)!:` breaking commits as a minor version.** Found while finishing
  this change: `release.yml` declared the angular preset, which honours only a `BREAKING CHANGE:`
  footer and never the `!` marker. Fixed in separate work (PRs #58, #59), and that fix is what
  produced v3.0.0 — which in turn unblocked 9.5. Two of this change's tasks were closed by a
  neighbouring bug fix.
- **A verification that covered half a pipeline passed while the pipeline was broken.** Exercising
  `@semantic-release/commit-analyzer` and not `release-notes-generator` let an incompatible preset
  reach `main` and fail a real release. The lesson generalises well beyond this change.
- **The e2e workflow's failure is not what the docs say.** Its `Build` job dies on `s3:PutObject`
  AccessDenied for `WorkflowRole` on the unified `runs/` prefix — an IAM gap opened by the
  `unified-run-prefix` re-key — before ever reaching the MongoDB break CLAUDE.md points debuggers at.

## Promote candidates

- **Prove "downloaded nothing" with inode and mtime, not just a checksum.** An identical re-download
  is checksum-indistinguishable from no download; the inode settles it. Used to close 9.5's first half.
- **Back up real user state before a destructive test against a live install.** A checksum detects
  damage but cannot undo it. The 2.2.0 artifacts were copied out before `--update` ran.
- **Put a separator before commit-message trailers when a footer precedes them.** A parsed note runs
  to end-of-message, so `Co-Authored-By` lines otherwise render inside published release notes.
