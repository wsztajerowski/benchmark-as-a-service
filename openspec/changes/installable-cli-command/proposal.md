# installable-cli-command — proposal

## Why

`baas` is not installable. The README tells you to alias a shaded JAR out of a reactor build, and a
reactor build carries no `Implementation-Version` — so `BaasVersion.current()` returns the
placeholder and `baas run` refuses to launch without `--runner-jar`. Every `baas` in existence is in
that state, which means the version-pinned runner path shipped by `unified-run-prefix` has never
been reachable in practice. Until an installed CLI exists, nobody — the author included — can
launch a run the way the tool is designed to be used, and it cannot be handed to anyone else at all.

The same change removes `baas run`'s Maven build. Once `baas` is a command invoked from anywhere
rather than an alias into a checkout, "build whatever is in the working directory" stops being a
coherent default — the installer is exactly what makes that behaviour wrong.

## What Changes

**Installation**
- From: `alias baas='java -jar '"$PWD"'/baas-cli/target/baas-cli.jar'` — a reactor build, placeholder
  version, `baas run` blocked without `--runner-jar`.
- To: `curl -fsSL https://github.com/<repo>/releases/latest/download/install.sh | sh` — a released
  JAR at `~/.local/share/baas/`, a shim at `~/.local/bin/baas`, a real version, `baas run` working.
- Reason: the pinning design requires a CLI that knows its own released version.
- Impact: non-breaking. The alias keeps working and becomes the documented *developer* path.

**`baas run` no longer builds the benchmark project — BREAKING**
- From: `baas run` shells out to `mvn clean package -q -DskipTests` in the working directory, unless
  `--skip-build` is passed. The JAR is `--benchmark-jar`, else the `benchmark.jarPath` config value,
  which defaults to `jmh-benchmarks/target/jmh-benchmarks.jar`.
- To: `baas run` builds nothing. `--benchmark-jar` is **required** on every invocation. `--skip-build`
  and the `benchmark.jarPath` config key are both deleted.
- Reason: an installed CLI is invoked from anywhere, so building the current working directory is no
  longer meaningful. Removing it also deletes Maven as a prerequisite of the tool.
- Impact: **BREAKING** for any invocation relying on the build or on the config default. The
  replacement is one `mvn package` before `baas run`, which `--skip-build` users already do.

**`commit` and `branch` tags become optional — BREAKING**
- From: both are always present. `currentGitCommit()` and `currentGitBranch()` fall back to the
  literal string `"unknown"` when git cannot answer.
- To: still derived from the working directory's git repository and still overridable by `--commit`
  and `--branch`, but **absent** when git cannot answer, rather than recorded as `"unknown"`.
- Reason: `"unknown"` is the same junk the project already refused for `project`, where
  `RESULT#unknown` accumulated as a partition nobody queries. With the JAR now named explicitly, a
  derived value can also describe a repository unrelated to the JAR, so a fake one is worse than none.
- Impact: **BREAKING** at the spec level — `results-store-schema` currently requires both on every
  measurement. Query behaviour degrades as already specified: rows lacking the grouping tag form one
  untagged group, and `--living-branches` is a no-op.

**Release assets**
- From: `benchmark-runner.jar`, `benchmark-runner.jar.sha256`, `baas-cli.jar`.
- To: the same plus `baas-cli.jar.sha256` and `install.sh`, the latter carrying the release version
  baked in by `prepareCmd` at publish time.
- Reason: the installer verifies before it writes anything, and must fetch an explicit version.
- Impact: non-breaking; additive to the existing `@semantic-release/github` assets list.

**CI coverage of `scripts/`**
- From: nothing in CI invokes `scripts/`; those utilities are unprotected.
- To: the release workflow rewrites `scripts/install.sh` at publish time, and a new workflow executes
  it on `ubuntu-latest` and `macos-latest` against an already-published release.
- Reason: an installer that breaks does so on someone else's machine, where the author cannot see it.
- Impact: non-breaking. CLAUDE.md's "Nothing in CI invokes `scripts/`" becomes false and is corrected
  as part of this change.

**Added**
- `install.sh` with `--version <v>` / `$BAAS_VERSION`, `--update`, `--uninstall`.
- A launcher shim resolving `java` through `PATH`, with a `BAAS_JAVA` override.
- Prerequisite checks at install time: Java 25 hard, `git` warn-only, an AWS-credentials note
  covering the SSO case. No Maven check — the build removal retires that prerequisite.
- A pinned copy of the installer at `~/.local/share/baas/install.sh`, so `--update` is reachable
  after a `curl … | sh` install.
- Documentation (scope B), written last so it describes what was actually built.

## Capabilities

### New Capabilities

- `cli-distribution`: how a released `baas` reaches a user's machine and stays current — version
  resolution and pinning, checksum verification, the on-disk layout, the launcher shim, prerequisite
  reporting, update and uninstall.

### Modified Capabilities

- `runner-jar-distribution`: the requirement named verbatim **"The release publishes correctly
  versioned artifacts and a checksum"** currently mandates a SHA-256 for the runner JAR only, while
  requiring the CLI JAR to be an asset. It extends to also mandate a published checksum for the CLI
  JAR and the installer as an asset carrying its release's version.
- `cli-command-structure`: `baas run` gains a requirement that it consumes a pre-built benchmark JAR
  named explicitly, and builds nothing. No existing requirement in this capability covers the Maven
  build, so this is an addition rather than a rewrite.
- `results-store-schema`: the requirement named verbatim **"Tags are the queryable dimensions, with a
  shared known-key vocabulary"** requires the runner to record `commit` and `branch` on *every*
  measurement. It changes to require them when known, and to forbid a placeholder value when not.

## Impact

**Code.** `RunCommand` loses `runMavenBuild()`, the `--skip-build` option, the `benchmark.jarPath`
fallback and the `"unknown"` tag fallbacks; `--benchmark-jar` becomes required. `BaasConfig` loses
`BenchmarkConfig.jarPath`. Everything else is a shell script, three additions to `release.yml`, one
new workflow, and documentation.

**A new contract on existing output.** `install.sh --update` reads the installed version by parsing
`baas --version`. That output — `baas <version>`, from `BaasApp.VersionProvider` — stops being
free-form text and becomes a parsed interface. The CI job asserts the format it depends on.

**Files.** `scripts/install.sh` (new); `.github/workflows/release.yml`; a new installer-test
workflow; `RunCommand.java`; `BaasConfig.java`; `README.md` (the Build/alias section and
Requirements); `CLAUDE.md` (the "Nothing in CI invokes `scripts/`" line, the gotcha "`baas run`
builds in the current working directory", and two *Accepted risks* rows — *Distribution* and
*`baas run` project layout*); `docs/adr/0001` (its amendment stating distribution "remains
backlog"); `docs/review/baas-cli-findings.md`.

**Cost.** None. No AWS resource is created, modified or deleted; no standing cost changes; a run
costs exactly what it costs today.

**Deliberately not changed.**
- No runner, image or user-data code is touched, so comparability with existing results is
  unaffected. What changes is reachability: runs can pin `releases/<version>/benchmark-runner.jar`
  for the first time.
- The no-fallback stance on an unreleased version stays. The installer installs *released* artifacts
  only and gets no `--from-source` mode.
- `releases/<version>/benchmark-runner.jar` stays seeded-once and never overwritten.
- Artifacts are still fetched by explicit version, never `latest`. The single `latest` lookup is the
  installer entry point, resolved by GitHub's redirect, and everything downstream of it is explicit.
- `~/.baas/config.yaml` is written by `baas admin setup` and touched by neither install nor
  uninstall.
- `pom.xml` stays `0.0.0-semantically-released`. The version bake is a build-time mutation of the
  working tree only — `@semantic-release/git` is not in the plugin list, so nothing is committed
  back, exactly as `versions:set` already behaves.
- Homebrew, jpackage, native image and Docker remain non-goals; the *Distribution* accepted risk is
  narrowed, not retired.
- `--project` keeps its existing behaviour, and the runner still refuses an unresolved project. Only
  `commit` and `branch` become optional.

**Review findings.**
- Files **A10**: `SetupCommand.computePrefix` hashes the raw `sts:GetCallerIdentity` ARN, which is
  stable for an IAM user but carries a per-session name for an SSO or assumed-role identity, moving
  the prefix — and so the stack, bucket, table and AMI — on every login. Its fix is a separate,
  deferred change; the scope-B documentation warns about it in the meantime.
- **Partially closes A6** (*"Config: silent unknown keys, no schema version, stale default"*).
  Deleting `benchmark.jarPath` removes the stale default this change touches; the silent-unknown-keys
  and schema-version components stay open, so the entry is narrowed rather than marked Fixed.
- **S11** (`~/.baas` default permissions) is adjacent and stays open; the installer writes outside
  `~/.baas` and neither fixes nor worsens it.
