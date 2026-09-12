# installable-cli-command — brainstorm

## Design Summary

`baas` becomes a command you install, rather than an alias onto a reactor build.

An `install.sh`, published as a release asset alongside the JARs, downloads the released
`baas-cli.jar`, verifies it against a published SHA-256, and writes a launcher shim onto `PATH`.
The CLI version it installs is **baked into the script at release time**, so the installer and the
artifact it installs always come from the same release.

**This is not packaging polish — it is the missing half of a shipped design.** `BaasVersion.current()`
reads `Implementation-Version` from the shaded manifest; a reactor build has none, and there is
deliberately no fallback. So today `baas run` refuses to launch for every `baas` in existence unless
`--runner-jar` is passed by hand. The `unified-run-prefix` brainstorm named an installed, released
CLI as "the premise the design rests on" and explicitly deferred building it. This change is where
that comes due.

**Audience.** Primarily the author (>90% of usage). Occasionally a friend asked to verify something —
a developer with a working knowledge of computers, following a README. Not a public distribution
channel, and the maintenance budget is sized accordingly.

**Scope order.** The mechanism (A) first; the documentation (B) last, immediately before the change
closes. B is mostly reading and fixing existing docs, not building.

## Superseded by the second pass

This file is the record of the first brainstorming session and is kept as written. A second pass,
after the Open Questions below were worked through, changed three of its assertions. Where this file
and [`design.md`](design.md) disagree, **design.md is authoritative.**

| Asserted here | Now |
|---|---|
| Removing `baas run`'s Maven build "is its own change" (Non-Goals) | Folded into this change, as its first task. An installed `baas` is invoked from anywhere, so building the working directory stops being coherent — the installer is what makes the build wrong, and the two belong together. |
| `mvn` is a warn-only installer prerequisite (Prerequisites table) | No Maven check is written at all. The build removal retires the prerequisite before the check would have existed. |
| The caller-ARN prefix instability "belongs in `docs/review/`" (Non-Goals, Open Question 3) | It gets its own OpenSpec change, deferred. `A10` is still filed as the record, and the scope-B documentation warns while the hazard is live. |

Two further decisions were added rather than changed: `--benchmark-jar` becomes required with
`benchmark.jarPath` deleted, and `commit`/`branch` become absent rather than `"unknown"`. Both follow
from removing the build, and both are argued in `design.md`.

## Alternatives Considered

### Distribution channel

#### Alternative A: Homebrew tap
- **Approach**: A `homebrew-baas` repository carrying a formula that downloads the release JAR and
  writes a jar script; `brew install wsztajerowski/baas/baas`.
- **Pros**: Familiar to macOS users; upgrade and uninstall come free; PATH handled by Homebrew.
- **Cons**: A second repository to own. The formula's `url` and `sha256` must be bumped on every
  release, which means release-workflow automation to do the bumping — a moving part that breaks
  silently and is only noticed at install time. Linux friends need Homebrew installed first.
- **Why not chosen**: All of that cost buys a technically-competent friend an escape from running
  one `curl` line. For an audience of one-plus-occasional-guest it is pure maintenance surface.

#### Alternative B: Bundled-runtime distribution (jpackage, GraalVM native-image, Docker)
- **Approach**: Ship a self-contained binary or image with a JRE inside, removing the Java
  prerequisite.
- **Pros**: No Java prerequisite; a single artifact per platform.
- **Cons**: Per-OS builds, macOS signing and notarisation for jpackage, AWS SDK v2 reflection
  configuration for native-image, and `~/.aws` plus working-directory mounting awkwardness for
  Docker. All of it spends its entire budget removing a dependency the user already has.
- **Why not chosen**: The user of this tool is, by definition, a Java developer benchmarking a Java
  project — the benchmark JAR they hand to `baas run` was built by their own JDK. Removing the Java
  prerequisite removes nothing real. (Noted honestly: this argument previously also rested on Maven
  being required, which the planned removal of `baas run`'s Maven build will retire. The conclusion
  is unchanged; one of its two legs is not.)

#### Alternative C: `install.sh` + launcher shim — **chosen**
- **Approach**: A POSIX shell installer, published as a release asset, that fetches and verifies the
  released JAR and writes a shim onto `PATH`.
- **Pros**: One implementation, no second repository, no per-OS build, no release-time formula
  bumping. Install and reinstall are the same idempotent operation. Works identically on macOS and
  Linux.
- **Cons**: `curl … | sh` asks the user to trust a script; discoverability of upgrades is weaker
  than a package manager's.
- **Why chosen**: It is the smallest thing that makes the released CLI reachable, and the trust
  concern is answered by the same discipline the runner JAR already lives under — pinned version,
  published checksum, verification before anything is written.

### Upgrade mechanism

#### Alternative A: Re-run the installer
- **Approach**: Upgrading is re-running the same `curl … | sh` one-liner.
- **Pros**: Zero new code — install *is* upgrade.
- **Cons**: Once the version is baked into the script (see Key Decisions), a *saved* copy of the
  installer reinstalls its own version forever instead of upgrading.
- **Why not chosen alone**: Correct for the common case, but leaves the saved-copy path silently
  doing nothing. Retained as the documented upgrade command, with Alternative C closing the gap.

#### Alternative B: `baas self-update` in Java
- **Approach**: A CLI subcommand that resolves the latest release, downloads, verifies and replaces
  the running JAR.
- **Pros**: Discoverable from `--help`; can report "you are on X, latest is Y"; native pinning flag.
- **Cons**: Overwrites the JAR the running JVM has open — survivable only via download-to-temp plus
  `ATOMIC_MOVE`, a correctness detail whose failure mode is a later, unrelated-looking crash. Forces
  the CLI to learn its own installation layout, so the shim and lib paths live in two places that
  must agree. Introduces the CLI's first `latest` lookup, in a codebase that deliberately removed
  release discovery as a drift axis. Testing it mutates the developer's own installation.
- **Why not chosen**: Its real content is duplicating the installer inside the artifact the
  installer replaces.

#### Alternative C: `install.sh --update` that hands off — **chosen**
- **Approach**: One request resolves the latest tag; the installed version is read from
  `baas --version`; if newer, the script *re-fetches that version's installer and execs it* rather
  than installing anything itself.
- **Pros**: Preserves the script/artifact self-consistency absolutely. Skips a tens-of-megabytes
  download when already current. Roughly fifteen lines.
- **Cons**: Two hops on a real upgrade; parses the CLI's own `--version` output format.
- **Why chosen**: It is the only shape where the script that installs version X is always version
  X's own script.

## Agreed Approach

Alternative C in both groups: a released `install.sh` with a baked version, and an `--update` flag
that resolves the latest release and hands off to that release's installer.

### Layout

```
~/.local/bin/baas                    # shim, generated with an absolute JAR path baked in
~/.local/share/baas/baas-cli.jar     # the artifact
~/.local/share/baas/install.sh       # verbatim copy of the installing script, so --update exists
~/.baas/config.yaml                  # written by `baas admin setup` — untouched by install AND uninstall
```

Separate roles: the installer owns the binary, `baas admin setup` owns the configuration, and
neither reaches into the other's directory.

### The installer

Responsibilities, in order: resolve the version (`--version` / `$BAAS_VERSION`, else the baked
default); fetch `baas-cli.jar` and `baas-cli.jar.sha256` at that **explicit** version; verify;
install via temp file plus `mv`; write the shim; copy itself alongside the JAR; run prerequisite
checks; print the installed version.

Flags: `--version <v>`, `--update`, `--uninstall`.

The source repository is a script variable with a documented default, not a hardcoded literal —
mirroring `runner.sourceRepo`, so a fork can point the installer at its own releases.

### The shim

```sh
#!/bin/sh
command -v "${BAAS_JAVA:-java}" >/dev/null 2>&1 || {
  echo "baas needs Java 25 on PATH (or set BAAS_JAVA). See <link>." >&2; exit 1; }
exec "${BAAS_JAVA:-java}" -jar "$HOME/.local/share/baas/baas-cli.jar" "$@"
```

`command -v` is a shell builtin, so the guard costs no fork on any invocation.

### Prerequisites, split by cost

| Check | Where | Severity |
|---|---|---|
| `java` present and ≥ 25 | installer, once | hard fail |
| `~/.local/bin` on `PATH` | installer, once | warn, printing the `export` line |
| `mvn` | installer, once | warn — only `baas run` needs it |
| `git` | installer, once | warn — project and branch derivation |
| AWS credentials; the AWS CLI **only** if authenticating via SSO (`aws sso login` writes the token cache the SDK reads) | installer, once | informational |
| `java` present | shim, every invocation | hard fail, zero cost |

`baas-cli` shells out to `mvn` (`RunCommand:410`) and `git` (`GitProject:65`, `RunCommand:419`,
`ResultsCommand:146`) only. It never invokes `aws` — the AWS CLI in `infra/runner-image.yaml` is
baked into the *runner image* for user-data's `aws s3 cp`, on the instance, not on the laptop.

### `--update`

```
install.sh (v3.2.0) --update
  ├─ 1 request: HEAD /releases/latest → tag v3.9.0
  ├─ installed version: `baas --version` → 3.2.0
  ├─ equal?    → "already on 3.9.0, nothing to do", exit 0    (no JAR download)
  ├─ older?    → exec: curl …/v3.9.0/install.sh | sh          (v3.9.0 installs itself, its way)
  └─ newer?    → report both versions, do nothing
```

### Release workflow

Three additions to `release.yml`, all inside structures that already exist:

- `prepareCmd` gains a `baas-cli.jar.sha256` line mirroring the runner's — necessarily *inside*
  `prepareCmd`, after `versions:set` and the repackage, or the checksum would cover a
  placeholder-versioned JAR.
- `prepareCmd` gains the version bake:
  `sed -i "s/^BAAS_VERSION_DEFAULT=.*/BAAS_VERSION_DEFAULT=${nextRelease.version}/" scripts/install.sh`
- The assets list gains `baas-cli.jar.sha256` and `install.sh`.

`@semantic-release/git` is not in the plugin list, so neither mutation is committed: the repository
keeps the placeholder, the published asset carries the real version — exactly how `versions:set`
already treats `pom.xml`.

### CI guard

A workflow on `ubuntu-latest` and `macos-latest` that runs **this PR's** `install.sh` pinned to an
already-published release, asserts `baas --version` prints that version rather than the placeholder,
then runs `--uninstall` and asserts the shim is gone and `~/.baas/` survives.

Pinning to a published release breaks the chicken-and-egg: it exercises the script under change
against real assets. The version assertion covers the entire failure class this change exists to
prevent, and the macOS runner catches the `shasum -a 256` versus `sha256sum` split — stock macOS has
no `sha256sum`.

### Effect on comparability

This change touches no runner, image or user-data code, and alters no measurement semantics. It is
not neutral, though: because every `baas` today is an unreleased build, no run can currently pin
`releases/<version>/benchmark-runner.jar` at all. This does not change the pinning rule — it makes a
path that was previously unreachable reachable.

## Key Decisions

1. **The CLI version is baked into `install.sh` at release time, not resolved at run time.** It
   removes a request and removes a race (fetching the JAR and its checksum separately from `latest`
   can straddle a release and yield a false mismatch). The property that matters most is
   self-consistency: if a future release changes the install layout, that release's script installs
   that release's JAR, and the two cannot disagree.
2. **`releases/latest/download/` survives in exactly one place: fetching the installer itself.**
   One `latest`, resolved by GitHub's redirect, for the entry point only. Everything downstream is
   an explicit version — the same rule `RunnerJarResolver` already follows.
3. **The working-tree copy carries a placeholder default**, so `scripts/install.sh` run from a git
   checkout refuses and names the option to pass. This mirrors `pom.xml`'s
   `0.0.0-semantically-released` and `RunCommand`'s refusal on an unreleased build.
4. **`--update` never installs anything itself.** It decides, then hands off to the target version's
   own installer. Downloading a newer JAR with an older script is precisely the failure that baking
   the version was meant to prevent.
5. **No silent downgrade.** If the installed version is newer than the latest release, `--update`
   reports both and does nothing. The installed version determines which runner JAR executes the
   benchmarks; a command called "update" must not change that backwards by surprise. `--version` is
   the deliberate way back.
6. **`baas --version` is the authoritative installed version.** It reads the JAR manifest, which is
   the same value that pins the runner, so it cannot drift. A `VERSION` marker file would avoid
   parsing our own output format but would be a second source of truth that can lie about the JAR.
   The CI job asserts the format it parses.
7. **The installer installs a copy of itself** to `~/.local/share/baas/install.sh`. Without it,
   `--update` is unreachable for anyone who installed by piping `curl` into `sh`.
8. **Shim resolves `java` through `PATH`, with a `BAAS_JAVA` override**, rather than baking the
   validated JDK path. A baked path fails as "no such file" on a path the user never typed when a
   JDK is upgraded away; PATH lookup fails as "java not found", which people know how to fix. It also
   keeps the CLI and the user's build on the same JVM — a hidden divergence is the wrong thing to
   introduce into a tool whose product is comparability.
9. **Expensive prerequisite checks run once, at install time; the shim's guard is a builtin.**
   Spawning `java -version` per invocation would tax every `baas results` forever.
10. **`--uninstall` ships in the first pass.** It is ten lines, it is what a guest needs when
    finished — and it is the only way to *test* that install and uninstall never touch
    `~/.baas/config.yaml`, which is the load-bearing property of the chosen layout.
11. **`baas-cli.jar.sha256` must be published, and verification is mandatory.** The release
    currently emits a checksum for `benchmark-runner.jar` only. Shipping an installer that fetches
    the CLI unverified would be strictly weaker than the invariant the runner JAR already lives
    under, protecting the *more* privileged artifact — the CLI holds deployer credentials.
12. **The script lives at `scripts/install.sh`.** It is a release asset, so its repository path never
    appears in a URL anyone types. Consequence to handle inside this change: CLAUDE.md's
    "Nothing in CI invokes `scripts/`" becomes false — the release workflow will `sed` the script and
    the new CI job will execute it — and that line must be updated rather than left to rot.
13. **Developing and testing BaaS itself stays on an alias or an absolute path to the JAR.** The
    installer gets no `--from-source` mode; its job is to install a *released* artifact, and a
    source-built one would carry the placeholder and refuse to run anyway.
14. **Documentation (scope B) is the last work before the change closes**, so it describes what was
    actually built.

## Non-Goals

- Homebrew tap, jpackage, GraalVM native image, Docker image.
- `baas self-update` in Java. `baas upgrade` as a thin `ProcessBuilder` exec over the installed
  `install.sh` becomes cheap once decision 7 lands — recorded as a follow-up, not a dismissal.
- A "newer version available" hint on `baas --version`. Read-only and additive; separable from how
  upgrading works.
- Windows. The shim is POSIX `sh`.
- Removing `baas run`'s Maven build. Wanted, and largely a deletion given `--benchmark-jar` and
  `--skip-build` already exist, but it is its own change.
- Fixing the caller-ARN prefix instability (below). It belongs in `docs/review/`.

## Open Questions

All four were worked through in a second brainstorming pass and are resolved. The answers, with the
evidence that settled each, are in [`design.md`](design.md) under *Resolved in the second
brainstorming pass* — recorded there rather than restated here, so there is one place to read them.

1. **Ordering against the Maven-build removal** — resolved: folded into this change.
2. **`baas upgrade` as a follow-up change** — resolved: skipped, recorded as a later idea.
3. **The caller-ARN prefix** — resolved: a separate deferred change, `A10` filed, scope B warns.
4. **First-release bootstrap** — resolved: no interim URL; the feature ships with its first release.
