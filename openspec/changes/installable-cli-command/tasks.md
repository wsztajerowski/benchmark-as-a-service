# installable-cli-command — tasks

## 1. Verify blocking assumptions

- [x] 1.1 Repository is public, so release assets are fetchable anonymously — `gh repo view` reports
      `"visibility":"PUBLIC"`. The installer needs no token.
- [x] 1.2 Published releases exist to test against — `gh release list` shows `v2.1.0` (latest),
      `v2.0.0`, `v1.6.x`.
- [x] 1.3 **No published release carries `baas-cli.jar.sha256`.** `gh release view v2.1.0` lists
      exactly `baas-cli.jar`, `benchmark-runner.jar`, `benchmark-runner.jar.sha256`. The CI guard
      therefore **cannot** pin to an already-published release: the installer would correctly demand a
      checksum asset that does not exist and fail. Task 6 uses a locally-served fixture release
      instead. `design.md`'s CI section still describes the pin-to-a-published-release approach and
      must be corrected.
- [ ] 1.4 Confirm `https://github.com/<repo>/releases/latest/download/<asset>` redirects to the
      newest release's asset for this repository, and that the redirect chain is followable by `curl -L`.
- [ ] 1.5 Confirm `@semantic-release/github` uploads assets from the working tree **after**
      `@semantic-release/exec`'s `prepareCmd` has run, so a `sed`-rewritten `scripts/install.sh` is the
      file that gets published.
- [ ] 1.6 Confirm no CI workflow or script invokes `baas run` in a way that relies on the Maven build.
      `grep -rl "baas run"` currently hits `infra/cf-template-core.yaml`, `infra/runner-image.yaml`,
      `jmh-with-async.sh`, `.github/workflows/e2e-cloud-test.yml` and `openspec/config.yaml` — confirm
      each is a comment or documentation mention rather than a live invocation.

## 2. Remove the Maven build from `baas run`

- [ ] 2.1 Delete `RunCommand.runMavenBuild()` and its call site (`RunCommand:213-216`).
- [ ] 2.2 Delete the `--skip-build` option (`RunCommand:82-83`).
- [ ] 2.3 Make `--benchmark-jar` a required option, and delete the `config.getBenchmark().getJarPath()`
      fallback (`RunCommand:219`).
- [ ] 2.4 Rewrite the JAR-not-found message (`RunCommand:221`), which currently names `--skip-build`,
      so it names the path it tried and the required option.
- [ ] 2.5 Move the JAR existence check so it runs before the runner-image lookup and before any upload,
      as the spec requires.
- [ ] 2.6 Delete `BenchmarkConfig.jarPath` with its getter and setter (`BaasConfig:117,122-123`) and the
      `jarPath` line from `ConfigShowSubcommand:60`.
- [ ] 2.7 Update `RunCommandTest` for the removed build, the required option, and the new failure paths.
- [ ] 2.8 Confirm a full reactor `mvn clean verify` is green before moving on.

## 3. Tags carry values or nothing

- [ ] 3.1 Remove the `"unknown"` fallback from `RunCommand.currentGitBranch()` (`RunCommand:424`) and
      `currentGitCommit()` (`RunCommand:466-467`), returning absence instead.
- [ ] 3.2 Add a `--commit <value>` option. It does **not** exist today — `--branch` and `--project` have
      dedicated options but `commit` is overridable only through `--tag commit=…`, so this is new work,
      not a modification.
- [ ] 3.3 Update `buildRunnerTags` (`RunCommand:515`) to omit `commit` and `branch` entirely when
      unresolved, rather than putting a value.
- [ ] 3.4 Correct the comment at `RunCommand:477` and `RunCommand:524`, which claims `--commit` is an
      existing override.
- [ ] 3.5 Add tests: derived values forwarded; explicit values win; unresolved values omitted with the
      run still proceeding; no placeholder value reaches the tag map.

## 4. The installer

- [ ] 4.1 Create `scripts/install.sh` with `BAAS_VERSION_DEFAULT=0.0.0-semantically-released` and a
      configurable source repository, defaulting to this one.
- [ ] 4.2 Version resolution: `--version <v>`, else `$BAAS_VERSION`, else the baked default; refuse the
      placeholder with a message naming the option; reject `--version` with no value in a message that
      also reports the baked default.
- [ ] 4.3 Fetch `baas-cli.jar` and `baas-cli.jar.sha256` at the explicit version. Never use a floating
      `latest` reference for an artifact.
- [ ] 4.4 Verify: detect `sha256sum` or `shasum -a 256`, fail when neither exists, compare against the
      bare published hash with trailing whitespace stripped, and fail on mismatch having written nothing.
- [ ] 4.5 Install atomically — temp file on the **same filesystem** as the destination, then `mv`, so a
      `baas` process already running keeps its own inode.
- [ ] 4.6 Write the launcher to `~/.local/bin/baas`: `command -v` guard on `${BAAS_JAVA:-java}`, then
      `exec` with the absolute JAR path.
- [ ] 4.7 Download the pinned installer to `~/.local/share/baas/install.sh`. Do **not** `cp "$0"` — in
      the `curl … | sh` path `$0` is `sh` and no file exists.
- [ ] 4.8 Prerequisite checks: Java present and major ≥ 25 is a hard failure; `git` is a warning naming
      what it affects; report `~/.local/bin` missing from `PATH` with the `export` line, and edit no
      shell configuration file. Parse `java -version` from **stderr**, handling both `1.8.0_x` and
      `25.0.x` shapes. No Maven check — task 2 retires that prerequisite.
- [ ] 4.9 `--update`: resolve the newest release to a concrete tag, read the installed version by
      parsing `baas --version`, exit reporting "current" when equal, hand off to the newer release's own
      installer when older, report and change nothing when newer, and stop when the installed version
      cannot be read.
- [ ] 4.10 Version comparison field-wise and numeric, so `3.10.0` outranks `3.9.0`; bail with a message
      naming `--version` when a field is non-numeric. No `sort -V`.
- [ ] 4.11 `--uninstall`: remove the launcher, the JAR and the stored installer, and nothing else.
      Never touch `~/.baas/`.
- [ ] 4.12 Print the installed version on completion, stating that this installer is pinned to it and
      that the installer must be re-fetched for a newer release.

## 5. Release workflow

- [ ] 5.1 In `release.yml`'s `prepareCmd`, after `versions:set` and the repackage, emit
      `baas-cli.jar.sha256` mirroring the runner's line.
- [ ] 5.2 In the same `prepareCmd`, bake the version:
      `sed -i "s/^BAAS_VERSION_DEFAULT=.*/BAAS_VERSION_DEFAULT=${nextRelease.version}/" scripts/install.sh`.
- [ ] 5.3 Add `baas-cli.jar.sha256` and `scripts/install.sh` to the `@semantic-release/github` assets.
- [ ] 5.4 Confirm neither mutation is committed back — `@semantic-release/git` must stay absent from the
      plugin list, so the repository keeps the placeholder.

## 6. CI guard for the installer

- [ ] 6.1 New workflow, matrix `ubuntu-latest` × `macos-latest`.
- [ ] 6.2 Build a **fixture release** in the job: `mvn versions:set` to a test version, package, compute
      `baas-cli.jar.sha256`, and serve both from a local HTTP server. This is what replaces pinning to a
      published release (task 1.3) and additionally proves the manifest stamping.
- [ ] 6.3 Run this PR's `scripts/install.sh` against the fixture, pointed at the local server through
      the configurable source repository.
- [ ] 6.4 Assert `baas --version` prints exactly the fixture version and not the placeholder. This is
      also what pins the output format `--update` parses.
- [ ] 6.5 Assert a corrupted checksum fails the install and writes nothing.
- [ ] 6.6 Seed a sentinel `~/.baas/config.yaml`, run `--uninstall`, assert the launcher and share
      directory are gone and the sentinel survives byte-for-byte.
- [ ] 6.7 Assert the macOS leg exercises `shasum -a 256` rather than `sha256sum`.

## 7. Invariants and review findings

- [ ] 7.1 CLAUDE.md: correct "Nothing in CI invokes `scripts/`" — the release workflow now rewrites
      `scripts/install.sh` and the new workflow executes it.
- [ ] 7.2 CLAUDE.md: remove the gotcha "`baas run` builds in the current working directory".
- [ ] 7.3 CLAUDE.md: rewrite the *Accepted risks* row "`baas run` project layout", which describes the
      build and `--skip-build`.
- [ ] 7.4 CLAUDE.md: narrow the *Accepted risks* row "Distribution" — the shaded JAR is now installable;
      Homebrew, jpackage, native image and Docker remain unbuilt.
- [ ] 7.5 CLAUDE.md: record that `--benchmark-jar` is required and that `commit`/`branch` are omitted
      rather than defaulted, since both are silent-failure surfaces for anyone restoring a fallback.
- [ ] 7.6 `docs/review/baas-cli-findings.md`: file **A10** — `SetupCommand.computePrefix` hashes the raw
      `sts:GetCallerIdentity` ARN, stable for an IAM user but per-session for SSO or assumed-role
      identities, so a second `baas admin setup` creates a duplicate retained stack, bucket and table and
      repoints config at the empty one. Severity Med. Note that the fix is non-migrating — normalising
      only `assumed-role` ARNs leaves IAM-user hashes untouched — and that it is a separate deferred change.
- [ ] 7.7 `docs/review/baas-cli-findings.md`: narrow **A6**, whose stale-default component is closed by
      deleting `benchmark.jarPath`. The silent-unknown-keys and schema-version components stay Open.
- [ ] 7.8 `docs/review/baas-cli-findings.md`: update the *Deliberately excluded* list, which names
      "distribution mechanism" as an accepted risk not to be re-raised.
- [ ] 7.9 `docs/adr/0001-self-contained-baas-cli.md`: its amendment states distribution "remains backlog,
      not a decision this ADR records as made". Add an amendment rather than editing the original.

## 8. Documentation (scope B), written last

- [ ] 8.1 README: replace the alias instructions with the install one-liner; demote the alias to the
      documented developer path.
- [ ] 8.2 README Requirements: drop Maven; state that the AWS CLI is needed only for `aws sso login`.
- [ ] 8.3 README: document `--version`, `--update` and `--uninstall`, and that a saved installer
      reinstalls its own version rather than upgrading.
- [ ] 8.4 README: document that `baas run` requires `--benchmark-jar` and builds nothing, with the
      `mvn package && baas run …` shape.
- [ ] 8.5 Walk the zero-to-first-result path on a clean machine and fix what is stale — install, AWS
      credentials, `admin deployer-policy`, `admin setup`, `admin build-image`, first run.
- [ ] 8.6 Add the one-line SSO warning from A10: a second `admin setup` under a new session identity
      creates separate infrastructure and repoints config at it.

## 9. End-to-end verification

- [ ] 9.1 **Manual.** Nothing automated covers `baas run`; `RunCommand.call()` is executed by no test and
      `e2e-cloud-test.yml` drives the GitHub Actions path instead. Every check below is by hand.
- [ ] 9.2 **Manual.** After the first release publishing the installer: install on macOS and on Linux
      from the documented one-liner, and confirm `baas --version` reports that release.
- [ ] 9.3 **Manual.** `baas run --benchmark-jar <jar> jmh -- <benchmark>` completes against real AWS,
      pinning `releases/<version>/benchmark-runner.jar` — the path this change exists to make reachable,
      and which has never executed.
- [ ] 9.4 **Manual.** Confirm the stored measurement carries `commit` and `branch` when run from a
      checkout, and that a run from outside a repository stores neither and still succeeds.
- [ ] 9.5 **Manual.** `--update` on a current installation reports current and downloads nothing; on an
      older one it installs the newer release.
- [ ] 9.6 **Manual.** Upgrade while a `baas run` is polling, and confirm the in-flight run completes.
- [ ] 9.7 **Manual.** `--uninstall`, then confirm `~/.baas/config.yaml` is untouched and a reinstall
      restores a working `baas` without reconfiguration.
