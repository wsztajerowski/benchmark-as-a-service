# ADR 0004 — The runner image only moves forward, and its extension is never lost

- **Status:** Accepted — implemented (PR #77, 2026-10-05); U39 verified live, U40's non-empty path
  pending a second installation
- **Date accepted:** 2026-10-04
- **Builds on:** `openspec/changes/archive/2026-10-04-custom-runner-image/` (bundled base +
  installation extension + BaaS contract, the parent resolved per region — not repeated here)
- **Closes findings:** U20, U21 (code; live check deferred), U37, U39, U40

## Context

The image is built from three parts: the **base**, bundled in the CLI JAR and versioned by hand;
the installation's **extension**, an AWSTOE document held as the stack parameter
`RunnerImageExtensionData`; and the contract checks. Two gaps survived the change that introduced
this split:

- **U39.** `build-image` always submits the base the running CLI bundles. From a CLI older than the
  installation's base it replaced the newer component — whose version CloudFormation deleted on
  replacement, so nothing refused the older one — and silently moved every later result onto the
  older environment. `admin image`, run from that CLI, even advised the build. The extension had a
  stale-push guard for exactly this "another admin reverts it unnoticed" risk; the base did not.
- **U40.** The extension's only copy is the stack parameter, so `admin teardown` deleted it with the
  stack and said nothing.

## Decision

1. **A base moves only forward, and only by upgrading the CLI.** `build-image` compares the bundled
   base with the deployed `RunnerImageVersion` numerically (`1.10.0` after `1.9.0`) before any stack
   change and refuses a lower one, naming both versions and the CLI upgrade. **No override flag:**
   re-measuring a historical environment stays the accepted-risk path in CLAUDE.md — a checkout of
   the old commit and a deliberate rebuild.
2. **`admin image`'s drift warning splits by direction:** bundled newer → "run `baas admin
   build-image`"; bundled older → "upgrade the CLI", never a build.
3. **Teardown saves a deployed extension before deleting anything**, with the marker a pull prints,
   to `runner-image-extension.<prefix>.yaml` beside the config file, and names it. A later setup's
   stack holds no extension, so `build-image --extension <file>` accepts it as is. A teardown that
   cannot read or write it deletes nothing — a re-run costs a minute, a lost extension is gone.
4. **The parent AMI is resolved per region, deprecated releases included** (U21, U37 — implemented by
   the change itself, recorded here because they were findings first).

## Consequences

- Several admins on one installation with different CLI versions can no longer step its measurement
  environment backwards; the oldest CLI is told to upgrade.
- The by-hand second-installation procedure must override `RunnerParentAmiId` outside
  `eu-central-1`: the template's default is the eu-central-1 AMI, which `setup` resolves per region
  but a direct `aws cloudformation deploy` does not (`infra/README.md`).
- Live verification of U21 and of U40 with a non-empty extension needs an installation outside the
  account's own prefix and region; see `docs/review/open-findings.md`.
