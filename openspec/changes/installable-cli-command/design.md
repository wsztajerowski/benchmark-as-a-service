# installable-cli-command — design

## Context

`baas` today is an alias onto a reactor build. That build carries no `Implementation-Version`, so
`BaasVersion.current()` returns `0.0.0-semantically-released`, and `RunCommand` refuses to launch —
by design, since there is no released runner JAR to pin to. The consequence is that the
version-pinning path shipped by `unified-run-prefix` exists in code and has never once executed.

The release workflow already publishes `baas-cli.jar` as an asset and stamps its version into the
shaded manifest. What is missing is entirely on the user's side of the wire: nothing puts that
published JAR onto a machine.

This change also removes `baas run`'s Maven build, and the two belong together rather than in
sequence. `RunCommand:410` shells out to `mvn clean package` in the current working directory — a
sane default only while `baas` *is* an alias into a checkout. An installed `baas` is invoked from
anywhere, so building whatever happens to be underfoot stops being coherent. The installer is
precisely what makes the build wrong, so shipping one without the other would leave a command whose
default behaviour contradicts how it is now reached.

Constraints that shape the design:

- **Audience of roughly one.** The author for >90% of usage; occasionally a developer friend
  following a README. Maintenance surface is the scarce resource, not polish.
- **The prerequisites are already met.** Anyone benchmarking a Java project has a JDK. The installer
  has nothing to bundle.
- **The repo already has a discipline for fetching release artifacts**, in `RunnerJarResolver`:
  explicit version, published checksum, verify before writing, never `latest`. The installer is the
  same problem one layer out, and deviating from that discipline would need justifying.
- **A `baas run` can be in flight while an install happens**, holding a paid EC2 instance for up to
  `timeout + 300 s`. Whatever the installer does to the JAR must not disturb a running JVM.

## Goals / Non-Goals

**Goals:**

- A released `baas` on `PATH`, installed by one command, reporting a real version.
- The installer and the JAR it installs always originate from the same release.
- Verification before anything is written, matching the runner JAR's existing standard.
- Update and uninstall that a guest can run without reading the source.
- CI that executes the installer, so it cannot break silently on someone else's machine.
- `baas run` consumes a benchmark JAR named on the command line and builds nothing, retiring Maven as
  a prerequisite of the tool.
- No tag ever carries a placeholder value standing in for something unknown.

**Non-Goals:**

- Homebrew tap, jpackage, GraalVM native image, Docker image.
- `baas self-update` implemented in Java.
- `baas upgrade` as a thin exec over the installed script — **a recorded idea for later, not a
  rejected one.** If it is built, it should locate the installer relative to its own JAR
  (`jar.resolveSibling("install.sh")`) rather than hardcoding `~/.local/share/baas/`, so it survives
  a layout change or a custom prefix and does not recreate the two-places-must-agree coupling that
  sank `self-update`. Left out here only to keep an already-widened change from widening further.
- A "newer version available" hint on `baas --version`.
- Windows support. The shim is POSIX `sh`.
- Any `--from-source` mode. Developing BaaS itself stays on an alias or an absolute JAR path.
- Fixing the caller-ARN prefix instability. It gets its own OpenSpec change, deferred; `A10` is filed
  in `docs/review/baas-cli-findings.md` meanwhile, and the scope-B documentation warns about it while
  the hazard is live.

## Decisions

### The installer is a POSIX shell script published as a release asset

Rejected: a **Homebrew tap** — a second repository plus per-release formula bumping, to spare a
competent friend one `curl` line. Rejected: **jpackage / native-image / Docker** — per-OS builds,
macOS notarisation and AWS SDK v2 reflection configuration, all spent removing a JDK dependency the
user necessarily already has.

### The CLI version is baked into the script at release time, not resolved at run time

`prepareCmd` rewrites a `BAAS_VERSION_DEFAULT=` line before the asset is uploaded. This removes a
request, removes a race, and buys the property that matters: **the script that installs version X is
always version X's own script.** If a later release moves the install layout, its own installer
carries the matching logic, and the two cannot disagree.

Rejected: **resolving `latest` at run time** and then fetching the pinned artifacts. It works, but it
lets an old cached script install a JAR built for a layout it does not know about, and it fetches the
JAR and its checksum in two requests that can straddle a release and produce a false mismatch.

### `releases/latest/download/` is used in exactly one place — fetching the installer

One `latest`, resolved by GitHub's own redirect, for the entry point. Every artifact request
downstream of it names an explicit version, which is the rule `RunnerJarResolver` already follows.

### The working-tree copy carries a placeholder and refuses to install

`scripts/install.sh` in git has `BAAS_VERSION_DEFAULT=0.0.0-semantically-released`. Run from a
checkout with no `--version`, it exits non-zero naming the option — mirroring `pom.xml`'s placeholder
and `RunCommand`'s refusal on an unreleased build. The developer's checkout is the special case, and
it says so.

### Nothing is written until the checksum verifies, and every write is a rename

Order: download JAR and `.sha256` into a temp directory → verify → `mv` into place → write shim to a
temp path → `mv` into place.

The temp directory must sit **on the same filesystem as the destination** (e.g.
`~/.local/share/baas/.tmp.$$`), because `mv` across devices degrades to copy-and-unlink and stops
being atomic.

This is not fastidiousness. `rename(2)` swaps the inode, so a `baas run` already in flight keeps
executing the JAR it started with. Writing in place would corrupt a JVM that is currently babysitting
a paid EC2 instance, and the crash would arrive later, looking unrelated.

### A missing checksum *tool* is a failure, not a skip

Linux has `sha256sum`; macOS was believed to have only `shasum -a 256`. The script detects whichever
exists and exits non-zero if neither does, rather than installing unverified. This mirrors the
existing requirement that a missing published checksum is a hard failure.

**Correction (2026-09-12, first CI run).** The premise about macOS is false. `macos-latest` reports
`/sbin/sha256sum`, and so does stock macOS: it is Apple's own `com.apple.md5sum` multi-call binary
in SIP-restricted `/sbin`, hard-linked alongside `md5sum`, `sha1sum` and `sha512sum`. The classic
"macOS has only `shasum -a 256`" portability split is therefore gone, and the preferred branch is
the only one that runs on any platform this project supports.

The fallback is kept anyway — two lines insuring a macOS old enough to predate those binaries — but
deliberately **not** tested: a harness case for a branch no supported platform executes costs more
than it protects. Its correctness was confirmed by hand once, under a PATH resolving only `shasum`
and `cut`, where `sha256_of` returned a digest identical to `sha256sum`'s.

The published `.sha256` is a bare hash — `release.yml` already pipes through `cut -d" " -f1` — so the
comparison is against a bare hash with trailing whitespace stripped, not against either tool's native
`<hash>  <file>` output format.

### The self-copy is downloaded at the pinned version, never `cp "$0"`

`--update` needs an installer on disk, so one is placed at `~/.local/share/baas/install.sh`.

It cannot be a copy of the running script: in the dominant install path the script arrives on stdin
through `curl … | sh`, where `$0` is `sh` or `-` and there is no file to copy. Downloading
`releases/download/v<version>/install.sh` is correct in every path, costs one request, and guarantees
the stored installer is the released one for exactly the version installed — even when the install
was launched from a locally modified checkout.

### `--update` decides, then hands off; it never installs

```
install.sh (v3.2.0) --update
  ├─ resolve latest tag        HEAD /releases/latest → v3.9.0
  ├─ read installed version    `baas --version` → 3.2.0
  ├─ equal    → "already on 3.9.0", exit 0        (no JAR download)
  ├─ older    → exec  curl …/v3.9.0/install.sh | sh
  └─ newer    → report both versions, change nothing
```

Handing off is what preserves the self-consistency property; downloading a newer JAR with an older
script is exactly the failure the version bake prevents.

**No silent downgrade.** The installed version determines which runner JAR executes the benchmarks,
so a command called "update" must never move it backwards. `--version <v>` is the deliberate way back.

Rejected: **`baas self-update` in Java** — it overwrites the JAR its own JVM has open, forces the CLI
to learn the installation layout so shim and lib paths live in two places that must agree, introduces
the CLI's first `latest` lookup, and can only be tested by mutating the developer's own installation.

### Version comparison is field-wise numeric, not string comparison

`3.10.0` must rank above `3.9.0`; a string compare ranks it below. The script splits on `.` and
compares numerically, in pure `sh`, with no dependency on `sort -V` (GNU-only in practice).

The release configuration declares `branches: ['main']` with no prerelease channel, so versions are
`X.Y.Z`. If a field is non-numeric, the comparison bails and tells the user to pass `--version`
explicitly rather than guessing an ordering.

### `baas --version` is the authoritative installed version

It reads the JAR manifest — the same value that pins the runner JAR — so it cannot drift from what is
actually installed. The cost is that the installer parses our own output format, `baas <version>`,
which the CI job therefore asserts.

Rejected: a **`VERSION` marker file** written at install time. It avoids the parsing, but it is a
second source of truth that can disagree with the JAR beside it.

If `baas --version` cannot be run at all (no shim, broken Java), `--update` reports that and stops,
rather than assuming a version.

### The shim resolves `java` through `PATH`, guarded by a builtin

```sh
#!/bin/sh
command -v "${BAAS_JAVA:-java}" >/dev/null 2>&1 || {
  echo "baas needs Java 25 on PATH (or set BAAS_JAVA). See <link>." >&2; exit 1; }
exec "${BAAS_JAVA:-java}" -jar "<absolute JAR path>" "$@"
```

`command -v` is a shell builtin, so the guard forks nothing on any invocation. Spawning
`java -version` here would tax every `baas results` forever.

Rejected: **baking the validated JDK path into the shim.** It survives `PATH` changes but fails as
"no such file or directory" on a path the user never typed once that JDK is upgraded away. It also
lets the CLI and the user's build silently run on different JVMs — the wrong divergence to introduce
into a tool whose product is comparability.

### Expensive prerequisite checks run once, at install time

| Check | Severity |
|---|---|
| `java` present and major ≥ 25 | hard fail |
| `~/.local/bin` on `PATH` | warn, printing the `export` line |
| `git` | warn — project, commit and branch derivation |
| AWS credentials; the AWS CLI **only** for SSO users, since `aws sso login` writes the token cache the SDK reads | informational |

There is deliberately no Maven check. It was going to be a warn-only row, and removing the build in
this same change retires the prerequisite before the check is ever written — the cheapest possible
resolution of the ordering question, since neither the check nor a later deletion of it is needed.

`baas-cli` shells out to `git` (`GitProject:65`, `RunCommand:419`, `ResultsCommand:146`) and never to
`aws`. The AWS CLI in `infra/runner-image.yaml` is baked into the *runner image* for user-data's
`aws s3 cp` — on the instance, not the laptop.

Note for implementation: `java -version` writes to **stderr**, and the version string is `1.8.0_x`
for 8 and `25.0.x` for modern releases, so major-version extraction must handle both shapes.

### The installer prints the `PATH` line; it never edits shell rc files

Editing dotfiles varies by shell, is easy to do twice, and `--uninstall` could not cleanly reverse it.
Printing the `export` line leaves the user in control of their own configuration.

### `--uninstall` ships in the first pass

Ten lines, and it is what a guest needs when finished. The load-bearing reason is different: it is the
only way to *test* that install and uninstall never touch `~/.baas/config.yaml`, which is the property
justifying the split layout. Without it that separation is an assertion nobody can verify.

### `baas run` builds nothing; the benchmark JAR is named on every invocation

`runMavenBuild()` and `--skip-build` are deleted, and `--benchmark-jar` becomes required.

The `benchmark.jarPath` config key goes with them, not just its default. Removing the build promotes
that default from cosmetic to load-bearing — it points at `jmh-benchmarks/target/jmh-benchmarks.jar`,
a path from an older project layout, already filed as part of **A6**. Keeping it would mean a
forgotten flag resolves to a path the user has never heard of. Deleting the key instead makes an
unnamed JAR a hard failure that names the option, which is the stance the repo already takes on store
configuration: absent configuration is a failure, not a silent default.

The failure occurs before the AMI lookup and before any upload, matching every other precondition
`baas run` checks.

### `commit` and `branch` are absent rather than `unknown`

Both stay derived from the working directory's git repository and stay overridable by `--commit` and
`--branch`. What changes is the failure case: `currentGitCommit()` and `currentGitBranch()` currently
fall back to the literal string `"unknown"`, and that value is now simply not recorded.

This is the same junk the project already refused one layer up. The runner throws on an unresolved
project precisely because `RESULT#unknown` had accumulated as a partition nobody queries, 36 rows of
it. A `commit=unknown` tag is that same non-answer wearing a value's clothing, and it pollutes the
only query surface the tool has.

Derivation is kept rather than dropped because running from the benchmark project's checkout stays
the normal workflow even without the build — `mvn package && baas run --benchmark-jar target/x.jar`
happens in one directory — and `branch` is the default grouping tag for `baas results`. Removing
derivation entirely would cost the tool's main view to guard against a case the override already
covers.

### Layout separates the binary from the configuration

```
~/.local/bin/baas                 # shim, absolute JAR path baked in
~/.local/share/baas/baas-cli.jar  # the artifact
~/.local/share/baas/install.sh    # the pinned installer, for --update
~/.baas/config.yaml               # owned by `baas admin setup`; never read or written by the installer
```

### Three additions to `release.yml`, inside structures that already exist

Ordering inside `prepareCmd` is load-bearing — `versions:set`, then package, then checksum, then bake:

1. `sha256sum baas-cli/target/baas-cli.jar | cut -d" " -f1 > …/baas-cli.jar.sha256`, mirroring the
   runner's line. Computing it before the repackage would checksum a placeholder-versioned JAR.
2. `sed -i "s/^BAAS_VERSION_DEFAULT=.*/BAAS_VERSION_DEFAULT=${nextRelease.version}/" scripts/install.sh`
3. The assets list gains `baas-cli.jar.sha256` and `install.sh`.

`@semantic-release/git` is not among the plugins, so neither mutation is committed — the repository
keeps the placeholder and the published asset carries the real version, exactly as `versions:set`
already treats `pom.xml`.

The release job runs on `ubuntu-latest`, so GNU `sed -i` and `sha256sum` are safe *there*. Portability
is a constraint on the installer, which runs on the user's machine, not on the workflow.

### CI executes the installer against a fixture release it builds itself

> **Correction.** This section first specified running the installer pinned to an already-published
> release. That cannot work. `gh release view v2.1.0` lists exactly `baas-cli.jar`,
> `benchmark-runner.jar` and `benchmark-runner.jar.sha256` — **no published release carries
> `baas-cli.jar.sha256`**, because this change is what starts publishing it. The installer would
> correctly refuse to install unverified, and the job would fail on every run until after the first
> release. The chicken-and-egg is one layer deeper than the original approach assumed.

Matrix `ubuntu-latest` × `macos-latest`:

1. Build a fixture release inside the job: set a test version, package, compute
   `baas-cli.jar.sha256`, and serve both files from a local HTTP server.
2. Run **this PR's** `scripts/install.sh`, pointed at that server through the configurable source
   repository — the same seam that lets a fork retarget its releases.
3. Assert `baas --version` prints exactly the fixture version, not the placeholder.
4. Assert a corrupted checksum fails the install and writes nothing.
5. Seed a sentinel `~/.baas/config.yaml`, run `--uninstall`, assert the launcher and share directory
   are gone and the sentinel survives.

A fixture is not a workaround for the missing asset — it is the stronger test. Pinning to a published
release would only prove the installer can fetch bytes that already exist. Building the artifact in
the job proves the whole chain the change depends on: that `versions:set` reaches the shaded manifest,
that a checksum computed after the repackage matches, and that `BaasVersion.current()` therefore
reports a real version. Step 3 is the single assertion covering the failure class this change exists
to prevent, and it also pins the output format `--update` parses.

The macOS leg was expected to catch the `sha256sum` / `shasum -a 256` split. The first CI run showed
it does not — stock macOS ships `sha256sum` (see the correction above) — so that split is exercised
neither by CI nor, by decision, by a harness case. The two legs still earn their place on BSD-vs-GNU
userland differences (`mktemp`, `sed`, `install`) and on Java resolution. A published release remains
worth installing from by hand after the fact, which is what the migration plan's verification step
is.

This also makes CLAUDE.md's "Nothing in CI invokes `scripts/`" false, which the change corrects.

## Risks / Trade-offs

- **A saved `install.sh` silently reinstalls its own version instead of upgrading.** → `--update`
  exists precisely for this, the installer stores itself so `--update` is reachable, and every run
  prints the version it is pinned to.
- **`curl … | sh` asks the user to trust a script over the wire.** → The script comes from a release
  asset rather than a moving branch, and everything it installs is verified against a published
  checksum before a byte is written. Accepted: this is the same trust model the audience already
  extends to the tool itself.
- **The installer parses `baas --version` output.** → A format change would break `--update` quietly;
  the CI job asserts the exact format, so it breaks loudly instead.
- **Merging is not shipping.** The documented URL 404s until the first release that carries the
  installer asset. → Sequenced explicitly in the migration plan; no interim URL is documented.
- **An upgrade during an in-flight `baas run`.** → Atomic rename means the running JVM keeps its own
  inode. The already-launched instance is unaffected, and the run it is polling completes normally.
- **`~/.local/bin` may not be on `PATH`.** → Detected and reported with the exact `export` line; the
  installer does not edit rc files on the user's behalf.
- **A second person will hit the unnormalised caller-ARN prefix before they hit anything else.** →
  Out of scope to fix; `A10` filed, its fix deferred to a separate change, and scope B warns while the
  hazard is live.
- **A supplied JAR can be stale relative to the `commit` and `branch` derived beside it.** Today the
  build ties them together by construction: `baas run` compiles the working tree it reads git from.
  Once the JAR is named explicitly, a week-old artifact can be stored under today's commit, and the
  result is queryable and wrong with nothing to signal it. → **Accepted and recorded, not solved.**
  Detecting it would mean comparing JAR mtime against commit time, which is a heuristic that is wrong
  in both directions — a rebuild with no source change looks stale-free, a touched file looks fresh.
  `--commit` and `--branch` exist for callers who know better than the working directory.
- **With `branch` absent, `baas results` loses its default distinction.** Rows collapse into one
  untagged group and `--living-branches` silently filters nothing. → Both are already specified to
  degrade this way rather than error (`benchmark-results-query`: "collected into a single untagged
  group rather than dropped"; "a no-op rather than an error"), and derivation is retained precisely
  so this stays the exception rather than the default.

## Migration Plan

1. **Remove the Maven build first.** Delete `runMavenBuild()`, `--skip-build` and
   `BenchmarkConfig.jarPath`; make `--benchmark-jar` required; stop recording `"unknown"` tags. Doing
   this before anything else means the installer's prerequisite checks and every documentation line
   are written once, against the final behaviour, rather than written and then corrected.
2. Land `scripts/install.sh`, the `release.yml` additions, and the CI workflow. Nothing about
   installation is user-visible yet.
3. **The next release publishes the installer.** Until it exists, no install URL is documented and no
   raw-branch URL is offered as a stand-in — the working-tree script would refuse anyway, by design.
4. Verify against that first release on both platforms: install, `baas --version`, `--update` as a
   no-op, `--uninstall`.
5. Do the documentation pass (scope B) last, describing what was actually built: README's install
   section, the alias demoted to the documented developer path, CLAUDE.md's `scripts/` line and its
   *Distribution* accepted-risk row, and the ADR amendment that calls distribution backlog.

**Rollback.** Nothing to undo on the server side: previously published releases keep their assets, and
the alias never stopped working. Reverting the `release.yml` change stops publishing the installer;
already-published installers keep working, since each one names an explicit, immutable version.

## Resolved Questions

- **Shim JDK resolution** — `PATH` with a `BAAS_JAVA` override. A baked path fails as "no such file"
  on a path the user never typed, and splits the CLI from the JVM that builds the benchmark.
- **Script location** — `scripts/install.sh`. It is a release asset, so its repository path never
  appears in any URL a user types, and `scripts/` already holds this kind of utility.
- **`--uninstall` in the first pass** — yes. It is the only way to test that `~/.baas/config.yaml`
  survives, which is what justifies the layout.
- **Downgrade on `--update`** — report both versions and do nothing. The installed version selects the
  runner JAR that executes the benchmarks.
- **Reading the installed version** — parse `baas --version`; the JAR manifest cannot drift from the
  JAR. CI asserts the format.
- **First-release bootstrap** *(was Open Question 4)* — no interim URL. The feature ships with its
  first release. A raw-branch URL would serve the placeholder copy, which refuses to install without
  `--version`, so documenting one would be actively misleading.
- **The stored installer copy** — always downloaded at the pinned version. `cp "$0"` is impossible in
  the `curl … | sh` path, where `$0` is `sh` and no file exists.

### Resolved in the second brainstorming pass

- **Ordering against the Maven-build removal** — folded into this change as its first task. The
  removal was never a proposed change: `openspec list` showed only `installable-cli-command`,
  `gha-workflow-migration-to-dynamodb`, `export-before-teardown` and `private-runner-network`, so
  there was nothing to sequence against, and holding a finished change's documentation against an
  unstarted one is how change directories rot.
- **Where the benchmark JAR comes from** — `--benchmark-jar`, required, with the config key deleted
  rather than merely defaulted differently.
- **How far "optional" goes for `commit` and `branch`** — derivation retained, `"unknown"` dropped.
  Evidence: `benchmark-runner/src/main` references neither, so only `results-store-schema`'s SHALL and
  two query features depend on them, and both features are already specified to degrade rather than
  error.
- **`baas upgrade`** — skipped, recorded as a later idea. Two of the three original arguments against
  it died when the Maven removal was folded in (the change no longer has a zero-line Java diff, and
  `cli-command-structure` is being modified anyway); the surviving reason is scope control on a change
  that just doubled, plus the fact that nothing has yet shown the `curl` one-liner to be irritating.
- **The caller-ARN finding** — its fix becomes a separate, deferred OpenSpec change rather than work
  here. `A10` is filed as the record, since an unproposed change tracks nothing, and scope B warns.
- **The installer's version flag** — `--version <v>` selects, and its missing-argument error reports
  the baked default, removing any need for a flag that only prints a version.

## Open Questions

None. Every question this design opened has been answered and recorded above; the heading is kept so
a later reader can tell "resolved" apart from "never asked".
