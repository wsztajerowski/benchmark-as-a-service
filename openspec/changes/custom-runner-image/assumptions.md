# Pre-explore assumptions

> **This is not an artifact.** No artifact exists yet; `/opsx:explore` is the first step. This file
> exists so that session starts from established facts instead of re-deriving them, and so the open
> questions below are not silently resolved by whoever writes the proposal. Captured 2026-10-02 from
> the CLI usage analysis.

**Priority 2** of the queue in [`../QUEUE.md`](../QUEUE.md).

## Why this change exists

Review finding **U20** (`docs/review/baas-cli-findings.md` row 34, `docs/analysis/cli-usage-analysis.md`
§3): *an installed CLI cannot bake a changed runner image.* What the user meant to do was install
baas, edit the runner image definition, and rebuild with admin rights. That path has never worked
for an installed CLI. `scripts/install.sh` made the installed CLI the normal case, so it is no longer
an edge case.

Commit `1aba0c5` corrected the README to say an edit needs a checkout and a rebuilt CLI. That
documents the gap; it does not close it.

## Established facts — verified in the code on 2026-10-02

- **The definition is read from the classpath only.** `RunnerImageRenderer` loads
  `/templates/runner-image.yaml` (`RunnerImageRenderer.java:23`, `:99`). It has done so since
  `e65e251`. No file is read from disk, and the installer ships only the JAR.
- **Three commands consume it, and each builds its own renderer:**
  - `BuildImageCommand.java:52` renders and then calls `updateStack`, which sends only the three image
    parameters (`RunnerImageVersion`, `RunnerParentAmiId`, `RunnerImageComponentData`) and carries
    everything else forward (`BuildImageCommand.java:122`).
  - `SetupCommand.java:204` sends the same rendering on **every** create and update. This is a CLAUDE.md
    invariant: letting the template's placeholder stand would register a no-op component at the
    declared version, and Image Builder would then refuse the real one at that same version.
  - `ImageCommand.java:60` compares the deployed image against the bundled definition, but its warning
    at `:76` says "infra/runner-image.yaml declares …", naming a file it never reads.
- **Constraints the definition already carries:**
  - Components are immutable at a version, so any edit needs `imageVersion` bumped.
  - The preflight refuses a version that is already registered.
  - The rendered component travels as a stack parameter and must stay under CloudFormation's
    4096-byte cap. A unit test guards this, and `infra/runner-image.yaml` is 3985 bytes today.
  - `parentImage.region` must match the stack's region (`BuildImageCommand.java:57`).
  - `perf`'s pinned version must match the parent AMI's kernel.
- **PR #73, merged 2026-10-02 (finding P12), makes `RunnerImageDefinition` reject a mistyped or missing key.** That
  validation is what makes an operator-supplied file safe to accept. Build on it rather than adding
  another.

## Decided 2026-10-02

- U20 becomes its own OpenSpec change, and this is it. It starts with `/opsx:explore`.

## Things the explore session has to settle

1. **Where a custom definition comes from.** A `--definition <file>` on `build-image`? A file at a
   conventional path? Something stored in the installation itself?
2. **`admin setup` reverts a custom component.** It re-submits the *bundled* rendering on every update
   (above). Whatever the fix is, a later plain `setup` must not silently put the stock image back. One
   option is to stop sending the image parameters on update. That would change the setup-renders-the-
   same-parameters invariant, which exists for the create path.
3. **Which copy is the record.** A definition kept on one admin's laptop lets another admin's plain
   `build-image` revert the installation without anyone noticing. The installation is account-shared
   since `account-derived-installation-naming`, so this is reachable. The stack's
   `RunnerImageComponentData` parameter could be the record instead. That raises a follow-on
   question: can a definition be recovered from the stack, or only the rendered component?
4. **A starting point.** Operators need a way to export the shipped definition, for example
   `baas admin image --export` or a `print-definition` command, so they can edit a copy rather than
   write one from scratch.
5. **`admin image`'s drift warning** has to name what it actually compared against: the bundled copy,
   a supplied file, or the stack's record.
6. **Comparability.** A custom image changes what results measure. `imageVersion` already reaches every
   result's tags and `environment.json`. Does a custom definition need a distinguishable version (a
   suffix, a separate tag) so its results are never confused with a stock image of the same number?
7. **CLAUDE.md wording to revisit** when this lands: "`infra/runner-image.yaml` is the only place a
   tool version is declared", and "Git is the archive". Neither holds for a definition that never
   lived in this repository.
