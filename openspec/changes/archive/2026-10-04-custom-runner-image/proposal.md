# Proposal

## Why

An installed `baas` cannot bake a changed runner image (finding **U20**,
`docs/review/baas-cli-findings.md` row 34): `RunnerImageRenderer` reads `runner-image.yaml` only from
the JAR, and `scripts/install.sh` ships only the JAR. So a team that needs an observability agent,
another profiler or a native library on its runners has no supported way to put it there, and an
installation outside `eu-central-1` cannot build an image at all, because the bundled definition pins
a parent AMI in that region and `build-image` refuses any other. The installer made the installed CLI
the normal case, so this is no longer a developer edge case.

## What Changes

- The runner image becomes **three Image Builder components in one recipe**, run in order:
  - **base** — BaaS-owned, rendered from the bundled `runner-image.yaml`, moves with the CLI version;
  - **extension** — user-owned, a raw AWSTOE document with no BaaS schema; optional;
  - **contract** — BaaS-owned, runs last in AWSTOE's `test` phase on an instance booted from the new
    image, and fails the bake when anything a BaaS workflow depends on is missing.
- The **contract** asserts: a `java` on `PATH` whose `java.specification.version` is at least the
  runner's compile target (25 today); a working `aws`; `libasyncProfiler.so` and `asprof` at the
  path the runner defaults to; a `perf` matching the running kernel; `/etc/baas-image-version`;
  `perf_event_paranoid ≤ 1` and `kptr_restrict = 0`. Everything else — JDK vendor and version above the
  floor, transparent hugepages, swap — is the extension's to change, and is observed per run.
- The **base** changes what it pins:
  - the parent AMI is resolved from a pinned **AL2023 release name** in the stack's own region,
    replacing a region-bound AMI ID — **the `eu-central-1` lock is removed**;
  - `perf` is installed for the running kernel instead of pinned to an NVR, so `tools.perf` loses its
    version and `kernelRelease` (internal: only the bundled copy is ever read);
  - the AWS CLI is no longer installed or pinned; the parent's own copy is used;
  - Corretto, async-profiler and the kernel-tunable defaults stay pinned.
- The **extension** is stored as written in a fourth stack parameter, `RunnerImageExtensionData`
  (≤ 4096 bytes, ASCII only, trailing whitespace dropped — the stack reads nothing else back as
  written). `baas admin setup` writes a starter `~/.baas/runner-image-extension.yaml` (never
  overwriting one); `baas admin image --extension` pulls the deployed copy; `baas admin build-image
  --extension <file>` pushes one. A file of comments only means no extension.
- **A stale push is refused.** A pulled file carries `# baas-extension-base: <hash|none>`. When the
  stack holds no extension, any file is accepted; when it holds extension `H`, the file's marker must
  be `H`.
- **Nothing reverts an image by accident.** A plain `baas admin setup` (update) or `baas admin
  build-image` carries all four image parameters forward unchanged; only `build-image` changes them,
  and only `--extension` changes the extension.
- **Image identity** becomes a label: `1.3.0` for the stock image, `1.3.0+ext.<sha256[0:8]>` with an
  extension. It reaches `/etc/baas-image-version`, the AMI tag, the `imageVersion` result tag and
  `environment.json`. Image Builder's numeric versions become internal and CLI-derived.
- `baas admin image` reports the extension: its hash, size against the cap, and its step names per
  phase.
- `environment.json` gains `jvmVendor`, `jvmVendorVersion` and `jvmName` (`schemaVersion` 3 → 4), and
  `jvmVendor` becomes a **machine-observed result tag**, rejected as a caller `--tag` like `jdk`
  (**BREAKING** for any caller already passing `--tag jvmVendor=…`).

**Not in scope, recorded as non-goals:** an operator override of the parent AMI (needed one day by
accounts restricted to their own AMIs; it would need a `+parent.` label part and carry-forward),
ARM/Graviton runners, guarding two `build-image` runs in the same seconds, and checking that
async-profiler works with a non-HotSpot JVM.

## Capabilities

### New Capabilities

_None._ The extension, its sync and the contract are requirements of the image the system already
specifies.

### Modified Capabilities

- `runner-image-provisioning`: the image definition becomes a base plus an optional user extension
  verified by a contract; the parent is resolved by release name; `perf` and the AWS CLI stop being
  pinned; the image version is a derived label rather than hand-bumped for the extension; the manifest
  gains JVM vendor fields; results carry `jvmVendor`.
- `cli-command-structure`: `baas admin build-image` takes `--extension` with the stale-push guard;
  `baas admin image` pulls and reports the extension.
- `core-stack-provisioning`: the stack declares the extension and contract components and a fourth
  image parameter; `baas admin setup` carries the image parameters forward on update and writes the
  starter file.
- `results-store-schema`: `jvmVendor` joins the machine-observed tag vocabulary.

## Impact

- **Code:** `RunnerImageRenderer`, `RunnerImageDefinition`, `infra/runner-image.yaml` (schema change
  and `imageVersion` bump), `BuildImageCommand`, `ImageCommand`, `SetupCommand`, `ImageBuilderService`
  (preflight across three components, label derivation), `CloudFormationService`
  (`UsePreviousValue`), `UserDataScriptBuilder` (manifest fields, `jvmVendor` tag),
  `baas-model` `TagKeys`, `infra/cf-template-core.yaml` (two components, a conditional one, a fourth
  parameter, the recipe's component list).
- **IAM:** none expected. The new components fall under the deployer's existing
  `imagebuilder:…:*/<prefix>-*` scope; the extension rides a stack parameter, so the deployer gains no
  `s3:PutObject`. Confirmed live: the `test` phase needed no IAM change.
- **Cost:** no new standing cost — the extension is a stack parameter, and the one-image rule still
  bounds snapshots to one. Each bake gains the `test` phase's instance time (minutes of a `c5.large`,
  cents per build; to be measured). `build-image` runs a few times a year.
- **Comparability:** a stock image's results stay comparable with today's; the label changes only when
  the base or an extension does. An extended image's results never share an `imageVersion` tag with a
  stock image's. Runs before this change have no JVM vendor fields; `baas env diff` against them shows
  the fields on one side and its existing schema-version warning.
- **Deliberately unchanged:** exactly one image and one pointer, repointed before the old AMI is
  retired; user-data installs nothing; the image is built by `build-image`, never by the stack; the
  runner's 443-only egress; teardown retiring the image; the deployer's exclusion of `s3:PutObject`.
- **Docs:** CLAUDE.md — "the only place a tool version is declared" becomes the only place a *base*
  tool version is declared, "Git is the archive" holds for the base only, the `perf` pin invariant is
  replaced by the derivation and its contract check, and the machine-observed tag list gains
  `jvmVendor`. `infra/README.md` gains the extension workflow. Closes **U20**.
