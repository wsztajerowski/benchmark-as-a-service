# Design

## Context

See `proposal.md` for the motivation, and `assumptions.md` for the code facts this was explored from
(2026-10-04 explore session).

The image definition reaches AWS only as stack parameters: `RunnerImageRenderer` renders the bundled
`runner-image.yaml` into `RunnerImageVersion`, `RunnerParentAmiId` and `RunnerImageComponentData`.
`baas admin setup` submits that rendering on every create **and** update (`SetupCommand.java:204`);
`baas admin build-image` submits it, bakes, and repoints. `Version` is a replacement property on both
`AWS::ImageBuilder::Component` and `AWS::ImageBuilder::ImageRecipe`, so exactly one of each is
registered at a time and CloudFormation is the only writer. The rendered base component is 3985 bytes
against CloudFormation's 4096-byte parameter cap.

What the runner relies on from the image, read from `UserDataScriptBuilder` and the runner:
`java` (runs `benchmark-runner.jar`, compiled for Java 25 by the root `pom.xml`), `aws` (every S3 copy,
the run-status `update-item`, self-termination), `/app/async-profiler/{lib/libasyncProfiler.so,
bin/asprof}`, `perf`, `/etc/baas-image-version`, and `perf_event_paranoid`/`kptr_restrict` for
async-profiler's kernel stacks.

## Goals / Non-Goals

**Goals:**
- An operator of an installed CLI can add anything to the runner image without a checkout.
- Nothing a BaaS workflow depends on can be removed by an extension without the bake failing.
- No command other than an explicit push changes or loses an installation's extension.
- Results from an extended image are never confused with a stock image's.

**Non-Goals:**
- Operator override of the parent AMI. Accounts restricted to their own AMIs (Allowed AMIs, SCPs on
  `ec2:ImageId`) will need it; it then needs a `+parent.<…>` label part and carry-forward. Deriving
  `perf` from the kernel, done here, is its main prerequisite.
- ARM/Graviton: a different base (async-profiler build, package architecture), not a parameter.
- Verifying that async-profiler works with the chosen JVM. Presence is checked; on OpenJ9 it only
  partly works, and that is documented rather than tested.
- Guarding two `build-image` runs started within seconds of each other. Same window as the existing,
  accepted concurrent-`build-image` race (CLAUDE.md).
- Keeping secrets in an extension (see Risks).

## Decisions

### The image is three components in one recipe: base, extension, contract

`ImageRecipe.Components` is an ordered list, which is the layering Image Builder already offers. The
base is rendered from the bundled definition; the extension is the operator's raw AWSTOE document; the
contract runs last. A CLI upgrade re-renders base and contract and never touches the extension, so the
upgrade problem a fork has — a three-way merge per release — does not arise.

*Rejected:* shipping the whole definition as an operator-owned file (fork model). Every BaaS release
would need a manual merge, and the guarantee that async-profiler is present would rest on that merge
being done right. *Rejected:* a BaaS schema for the extension (a list of packages, say). It would limit
what an operator can install, which is the problem being solved.

### The contract runs in the AWSTOE `test` phase, on the booted image

A build-phase check on the build instance sees the files, not the effect: the base writes
`/etc/sysctl.d/99-baas-benchmark.conf` but never applies it, and transparent hugepages come from a
kernel command-line argument that needs a reboot. Image Builder runs `test`-phase steps on a fresh
instance launched from the new AMI — what a runner boots. That is also the only place two checks are
meaningful at all: `perf` matching `uname -r` after an extension upgraded the kernel, and a later
`sysctl.d` file overriding the base's.

The contract checks: `java` on `PATH` with `java.specification.version` ≥ the runner's target (read
from `-XshowSettings:properties`, not the `java -version` banner, whose format varies by vendor);
`aws --version`; both async-profiler files; `rpm -q perf` against `uname -r`; `/etc/baas-image-version`;
`sysctl -n kernel.perf_event_paranoid` ≤ 1; `sysctl -n kernel.kptr_restrict` = 0. Each failure prints
the check's name.

The Java floor is rendered into the contract from the build's own `maven.compiler.target` (resource
filtering), so it has one source and moves when the runner's target does.

*Rejected:* build-phase file checks (cheaper, fooled by a later file). *Rejected:* guarding everything
the base declares (exact Corretto NVR, THP, swap). That makes overriding impossible and the extension
additive in name only, failing 15 minutes into a bake. *Rejected:* an exact Java major. Measuring on a
newer JDK is a legitimate reason to write an extension; the runner only needs to load its classes.

### The contract also writes the label, and its version moves with the recipe

`/etc/baas-image-version` must hold the label, which depends on the extension. If the base wrote it,
every extension edit would change the base's content and force a base version bump, which is the one
version still bumped by hand. So the contract's build phase writes the label and its test phase checks
it. The contract's content therefore changes exactly when the label does, and its version is derived
together with the recipe's (next decision).

### Only the base version is bumped by hand; the rest are derived from the deployed stack

| Version | Source |
|---|---|
| base component | `imageVersion` in `runner-image.yaml`, by hand, preflighted as today |
| extension component | previous + 1 (patch) when the extension's content changed, else unchanged |
| contract component and recipe | previous + 1 (patch) when the base, extension, or contract changed, else unchanged |

"Previous" is read from the deployed stack's parameters (`RunnerImageExtensionVersion`,
`RunnerImageRecipeVersion`). For a stack predating this change, the recipe's previous version is its
`RunnerImageVersion`, the value the recipe was versioned with until now. These are internal: only the
label is shown to anyone. Because CloudFormation replaces the component or recipe and deletes the old
one, the deployed parameter is always the one registered version, so previous + 1 cannot collide.

*Rejected:* asking the operator to version the extension. "Edited, forgot to bump" is today's most
frequent preflight failure, and the version carries no meaning an operator would choose.

### The label is `<base version>+ext.<sha256[0:8]>`, carried by a parameter

Without an extension the label is the base version, so a stock image's results keep today's tag
shape. With one, the suffix is the first eight hex characters of the SHA-256 of the stored extension
with trailing whitespace stripped (Image Builder drops a trailing newline when it stores a document; the
preflight already forgives exactly that). The same content gives the same label in every installation.

A new parameter `RunnerImageLabel` feeds the distribution configuration's `baas-image-version` AMI tag,
replacing `!Ref RunnerImageVersion` there, and `ImageBuilderService.ensureIdentityTags` tags the label.

*Rejected:* a per-installation counter (`+ext.4` means different content in two installations).
*Rejected:* an operator-chosen name in the label. More to supply, and the hash would still be needed to
tell two edits apart. The steps' names, shown by `baas admin image`, give readability.

### The extension lives verbatim in the stack parameter `RunnerImageExtensionData`

Read back with `DescribeStacks`, it is exactly what was pushed, comments included, so a pull returns the
operator's own text. An empty value sets `HasExtension` false, and the extension component and its
recipe entry are omitted (`AWS::NoValue`). A file of only comments and blank lines is sent as empty.

The parameter's 4096-byte cap is checked by `build-image` before anything is submitted. A few hundred
bytes installs an agent or a profiler; longer logic fits as a step that downloads and runs a script.

*Rejected:* an S3 object referenced by the component's `Uri`. No size cap, but the deployer would need
`s3:PutObject`, which it deliberately lacks (CLAUDE.md, deployer policy). The move is one property
(`Data` → `Uri`) if the cap ever bites. *Rejected:* an SSM parameter. 8 KB needs the paid advanced
tier, and it adds a second record of the image beside the stack.

### Pull and push, with a base marker that refuses a stale push

- **Pull:** `baas admin image --extension` prints `# baas-extension-base: <hash|none>`, then the
  deployed extension, or the starter when none is deployed.
- **Push:** `baas admin build-image --extension <file>` removes a leading marker line, and checks it
  against the deployed extension:

| Deployed | Marker | Result |
|---|---|---|
| none | anything, or absent | accept |
| `H` | `H` | accept |
| `H` | other, or absent | refuse, naming `H` and the pull command |

Accepting anything when none is deployed is what lets a file kept across a teardown and setup be pushed
again. Its consequence is accepted: a file whose extension a teammate deliberately removed brings it
back. That is visible in `baas admin image` and in every result's label, and one push undoes it.

No `--force`: a deliberate overwrite is a pull, a replacement of the content, and a push.

*Rejected:* no guard. An overwritten extension is unrecoverable from AWS — the stack keeps only the
current value, the replaced component is deleted, and the image built from it is retired — and the
long-lived local file `setup` writes is exactly the stale copy that would do it. *Rejected:* a
confirmation prompt showing old and new step names. Every push differs from the deployed one, so it
would fire every time and not tell stale apart from intended.

### Setup writes the starter, and carries image parameters forward on update

`baas admin setup` writes `~/.baas/runner-image-extension.yaml` when absent and never otherwise; its
content is the pull's output for an installation with no extension. `~/.baas/config.yaml` is
untouched, so the "names, region, prefix, preferences only" rule for that file holds.

On update, setup sends `UsePreviousValue` for every image parameter the deployed stack already has, and
the rendered value for one it lacks. The second half is what upgrades a pre-change installation: the new
parameters get real values instead of template placeholders. That keeps the invariant that a template
placeholder is never registered, which is why setup renders at all. `build-image` without `--extension`
does the same for the extension parameter.

### The parent is resolved from a pinned AL2023 release name

`runner-image.yaml` names the release (`parentImage.release`). `build-image` and `setup` (on create)
call `DescribeImages` with owner `amazon`, filtered by that name and `x86_64`, in the stack's region,
and require exactly one result. The resolved ID goes to `RunnerParentAmiId` and the `baas-parent-ami`
tag as now. The deployer already holds `ec2:Describe*`. The region check at `BuildImageCommand.java:57`
goes away.

The release stays `2023.12.20260803.3`, the one `ami-070cc8ab883065d64` already is, so the stock image's
kernel, `perf` and Corretto are unchanged by this change.

*Rejected:* an operator `--parent-ami` (Non-Goals). *Rejected:* `/aws/service/ami-amazon-linux-latest`
SSM aliases. They are selectors, the drift the pinned parent exists to prevent.

### The base derives `perf` and takes the AWS CLI from the parent

`dnf install -y "perf-$(uname -r | sed 's/\.x86_64$//')"`: the build instance runs the parent's
kernel, so this installs the matching build for any parent. The awscli install and its `dnf downgrade`
fallback are removed, and the base shrinks. The AWS CLI version stays observed per run in
`environment.json`.

### JVM vendor is observed once and reaches both the manifest and the tags

One `java -XshowSettings:properties -version` call, captured into a variable, yields `java.vendor`,
`java.vendor.version` and `java.vm.name`, each through `json_escape` (the manifest invariant). They
become `jvmVendor`, `jvmVendorVersion` and `jvmName` in `environment.json` (`MANIFEST_SCHEMA_VERSION`
3 → 4). `JVM_VENDOR` is also passed as the runner's `--tag jvmVendor=…`, from the same variable, so a
tag cannot disagree with its manifest. `TagKeys.JVM_VENDOR` joins `KNOWN` and `MACHINE_OBSERVED`, which
`RunCommand.RESERVED_TAG_KEYS` already refuses as caller tags. `baas env diff` needs no change: it diffs
the union of keys.

`java.vm.name` alone does not separate Corretto, Temurin and GraalVM (all report `OpenJDK 64-Bit Server
VM`), and the vendor alone does not separate OpenJ9. So all three are recorded, and only the vendor is
a tag, the dimension someone comparing JVMs groups by.

## Comparability

- **Stock image:** the base version moves (to `1.3.0`) because the base's content changes, but the
  parent release, kernel, `perf` build, Corretto, async-profiler and tunables are identical to `1.2.0`.
  The AWS CLI version may differ: it is not on the measurement path. A task compares a benchmark against
  a `1.2.0` run.
- **Extended image:** its label differs from every stock label, and `environment.json` and
  `packages.txt` record what it changed. Results tagged `1.3.0` are never from an extended image.
- **Runs before this change:** no `jvmVendor` tag (grouped into the no-tag bucket, as for any absent
  group tag) and no vendor fields (`env diff` shows them on one side, under the schema-version warning).

## Risks / Trade-offs

- [The `test` phase adds a test instance per bake] → Measured in task 1; cents per build, a few builds a
  year. If unexpectedly long, the fallback is build-phase checks of the files, with the weaker guarantee
  stated.
- [An extension can contact any host while baking] → Unchanged from today's bake, which already
  downloads async-profiler. `ImageBuildSecurityGroup` allows 443 and 80. Noted on the parked
  `private-runner-network` change, whose private build would cut extension downloads too.
- [The extension is readable by anyone who can `DescribeStacks`] → Stated in the starter's comments and
  `infra/README.md`: never put a credential in an extension. A secret belongs in Secrets Manager, read
  by a step under a build-instance role grant — out of scope.
- [4096 bytes is too small for some extension] → Refused with a byte count before submitting; the S3
  `Uri` path is a one-property follow-up.
- [A removed extension is re-added by a stale file] → Accepted (see the push decision); visible in the
  label.
- [The base component nears its cap] → It shrinks here (awscli install removed; label moved to the
  contract). The existing size test stays and gains one for the contract.
- [`UsePreviousValue` on a parameter the deployed stack lacks is rejected by CloudFormation] → That is
  the "render what is absent" rule; a unit test covers a pre-change parameter set.

## Migration Plan

1. Release the CLI. Existing installations keep working: `baas run` reads only the pointer.
2. On each installation, `baas admin setup` (renders the new parameters, carries the old ones) and
   then `baas admin build-image` (base `1.3.0`, recipe version derived from the old `RunnerImageVersion`,
   contract on the booted image).
3. Rollback: an older CLI submits its own template, which lacks the new parameters and components, so
   the extension and contract components are deleted and the extension text with them. Pull it first.
   Whether the older recipe version can replace a newer derived one cleanly is untested. Rollback is
   best-effort, not a supported path; the published image keeps working throughout, because `baas run`
   reads only the pointer.

## Open Questions

- The exact AL2023 AMI name for release `2023.12.20260803.3` and that it resolves to one image per
  region (task 1). It changes the value written in `runner-image.yaml`, not the approach.
- Whether the `test` phase needs any IAM beyond the build-instance role (task 1). Expected not: it reuses
  the infrastructure configuration.
