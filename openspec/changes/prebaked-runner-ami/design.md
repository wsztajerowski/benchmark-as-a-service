## Context

`UserDataScriptBuilder` currently installs the entire runner toolchain at boot: `yum update -y`, then
`java-25-amazon-corretto-headless`, then — for `jmh-with-async` — an async-profiler tarball from GitHub
Releases. Every run therefore measures on a slightly different machine than the last, and every run pays
several minutes of provisioning before the first warmup iteration.

Three constraints shape the design:

1. **Measurement integrity is the primary goal.** A benchmarking tool that lets its own substrate drift
   between runs is producing numbers nobody should trust. This outranks the cost and latency wins.
2. **The image must be built in the caller's own account.** AMIs are account- and region-scoped, and
   stack/bucket names are already derived from caller identity, so a shared published AMI would break the
   isolation model.
3. **Change 3 (`private-runner-network`) depends on this one.** A runner in a subnet with no internet
   route cannot install anything, so whatever it needs must already be in the image.

This change deliberately does not anticipate change 3 beyond that: it uses the public subnet that exists
today, and change 3 later adds a private subnet and moves only the runner.

## Goals / Non-Goals

**Goals:**

- The runner boots from a pinned image and installs nothing.
- Tool versions are declared in one repository file and changing one is a one-line edit.
- Every result is attributable to the exact environment that produced it, including the parts the image
  does not control (instance type, CPU model).
- Two results can be shown to be comparable, or shown not to be, without re-running anything.
- Standing cost stays negligible (one AMI snapshot).

**Non-Goals:**

- Baking the `benchmark-runner` JAR into the image. It changes per release and is already staged through
  S3; coupling it to the image would force a rebuild per runner release.
- Multi-region image distribution. Single-region, matching the rest of the stack.
- Retaining more than one AMI, or any command that rebuilds a historical image.
- Automatic rebuild scheduling. Builds are operator-triggered.
- Any change to the results schema, the database, or `benchmark-runner`.

## Decisions

### One image, rebuilt in place — and git is the archive

There is exactly one runner AMI, published at `/<prefix>/runner/ami-id`. `baas admin build-image` replaces
it. There are no slots, no AMI history, and no command that reconstructs a historical image.

The earlier draft of this change kept two AMI slots and archived every rendered template, component,
package manifest, and `build.json` to `images/by-version/` in the results bucket, so that
`--from-version` could rebuild a historical environment. That machinery is removed, because
`infra/runner-image.yaml` is a version-controlled file and therefore already the durable record:
`git log -p infra/runner-image.yaml` is the image history, with better tooling than S3 will ever have, and
reconstruction is `git checkout <sha> -- infra/runner-image.yaml && baas admin build-image`. Manual, rare,
and it clobbers the current image — the right cost for a capability nobody has asked for.

*Alternatives considered.* An S3 archive of rendered templates duplicates what git already stores, and its
`by-version/` + `by-ami/` layout, absent-index reasoning, and fetch-by-version path are all machinery
serving that duplicate. Building a historical image on demand inside `baas run --from-version` was
rejected for two independent reasons: `baas run` executes under `aws.operatorProfile`, and image building
needs the deployer's `imagebuilder:*`, `ec2:CreateImage`, `ssm:PutParameter`, and a widened `iam:PassRole`
— so it would collapse the credential split that `RunCommand` documents and `operator-policy.json`
enforces; and an image built per run resolves `yum` afresh each time, so two runs of the "same" historical
version would sit on different substrates, reintroducing exactly the drift this change exists to remove.

### The declaration and the observation are separate artifacts

Two files, deliberately not one schema:

```
  infra/runner-image.yaml          <result-path>/environment.json
  ────────────────────────         ─────────────────────────────
  DECLARATION                      OBSERVATION
  "install Corretto 25"            "Corretto 25.0.3+11-LTS ran"
  in git, one per repo             in S3, one per run
  input to the build               output of the run
  what was asked for               what was got, including what
                                   the template cannot control
                                   (instance type, CPU model,
                                    actual patch levels)
```

The observation is strictly richer, and it is the one that answers "did these two runs measure the same
thing?". Recording it removes the need to reconstruct anything: the question an operator actually asks is
"did the environment change between these results", not "let me re-measure on last quarter's machine".

### Kernel tunables belong in the image

`infra/runner-image.yaml` declares `perf_event_paranoid`, `kptr_restrict`, transparent hugepages, and swap
alongside the tool versions, and the Image Builder component applies them.

These are not cosmetic. async-profiler needs the first two to walk kernel stacks, and transparent hugepages
and swap materially move benchmark numbers. Today they are whatever AL2023 defaults to on the day the
instance booted — uncontrolled variance sitting directly under a profiler, in a tool whose purpose is
eliminating uncontrolled variance. Baking them makes them a declared, reviewable, diffable part of the
environment.

### The environment is captured in user-data, before the benchmark

The capture is a block of shell in `UserDataScriptBuilder` that writes `<result-path>/environment.json` and
`<result-path>/packages.txt` and uploads both *before* `java -jar benchmark-runner.jar` starts.

Placing it in user-data rather than in the runner keeps `benchmark-runner` completely untouched by this
change, and the data is shell-native anyway (`/proc/cpuinfo`, `lscpu`, `uname -r`, `/etc/os-release`,
`java -version`, `sysctl`, IMDS, `rpm -qa`). Capturing before the benchmark means a run that crashes still
leaves a record of what it crashed on — the same reasoning that already uploads `cloud-init-output.log`.
The block roughly replaces the install block being deleted, so the script does not grow materially.

`packages.txt` is kept separate from `environment.json` because `rpm -qa` is several hundred lines that
would drown the ~30 high-signal fields.

### Two-tier environment comparison

```
  TIER 1 — result tags, in the database     "did anything change?"
  ─────────────────────────────────────      zero extra I/O
  imageVersion, instanceType                 baas results flags a group
                                             whose rows disagree

  TIER 2 — environment.json, in S3          "what exactly changed?"
  ────────────────────────────────           one GetObject per run
  full manifest + packages.txt               baas env diff <a> <b>
```

The common case — every row on the same `imageVersion` — costs nothing, because `baas results` already
reads those rows from the database and `BenchmarkMetadata.tags` is a free-form `Map<String,String>`. Only
when versions disagree does anyone pay for a fetch. Keeping the fat manifest out of the database also means
the DynamoDB migration inherits no new schema question.

Recording `instanceType` as a tag additionally makes the existing `exclude_from_results=true` convention
largely unnecessary: a comparison can see that the hardware differed instead of relying on someone to
remember a flag.

### Image version is hand-edited, and a preflight check replaces auto-bumping

Image Builder's `Component` and `ImageRecipe` are immutable and semver-versioned, so reusing a version with
changed content fails the stack update. The earlier draft solved this by rendering the recipe
deterministically, hashing it, and auto-bumping the patch version when the bytes differed.

That subsystem is removed. `imageVersion` in `infra/runner-image.yaml` is edited by hand — it sits in the
same file, two lines from the tool version being changed — and `baas admin build-image` preflights it:

> `Recipe version 1.0.0 is already registered and its content differs. Bump imageVersion in
> infra/runner-image.yaml.`

A few lines of check instead of a content-hashing subsystem, and the file stays the single source of truth
for an identity that is recorded on every result.

### Repoint before deregister

`baas admin build-image` writes the new AMI ID to `/<prefix>/runner/ami-id` **first**, then deregisters the
AMI it replaced and deletes that AMI's snapshots.

This reverses the earlier draft, which pruned at build *start* to avoid racing a concurrent `baas run`.
With a single image that ordering is actively wrong: deregistering at build start leaves the pointer aimed
at a deregistered AMI for the whole ~15-minute build, so every run launched during a build fails. Pruning
after the repoint shrinks the window to the gap between the two calls, and any run resolving the pointer
after the write gets the new AMI. The remaining race — a run that read the old ID before the write and
calls `RunInstances` after the deregister — is accepted: single-operator scale, and the failure is a loud
`InvalidAMIID.NotFound` rather than silent corruption.

### EC2 Image Builder, not a hand-rolled build

Image Builder matches the requirement that the repository hold a template which the CLI turns into an AMI
per account, and it does so as CloudFormation resources — the same pattern the CLI already uses for
`cf-template-core.yaml`. AWS owns build-instance provisioning, retries, and teardown.

*Alternatives considered.* A CLI-orchestrated build — launch, provision via user-data, `CreateImage`,
terminate — needs no new CloudFormation and no recipe versioning, but means hand-writing build-instance
failure and cleanup handling, which the existing three-layer termination design demonstrates is easy to get
subtly wrong. Packer was rejected for putting a non-Java tool on the critical path of a CLI whose defining
ADR is `0001-self-contained-baas-cli`.

### `AWS::ImageBuilder::Image` is excluded from the stack

That resource performs a build during stack operations. Including it would add a ~15-minute image build to
every `baas admin setup`, including setups that changed nothing about the image. Builds are therefore
triggered out-of-band via `StartImagePipelineExecution`, and the stack holds only the durable
configuration.

### No fallback to boot-time installation

`baas run` fails fast when no AMI exists. Keeping the yum path as a fallback would mean two provisioning
paths whose results are silently incomparable — precisely the problem this change exists to remove — and
change 3 would have to delete it regardless.

## Risks / Trade-offs

- **A historical environment can no longer be re-measured by command.** When a diff shows
  `jdk: 25.0.1 → 25.0.3`, the operator learns the environment moved but cannot isolate whether it caused a
  score change without reverting `infra/runner-image.yaml` and rebuilding, which clobbers the current
  image. Accepted deliberately: the question actually asked is "did it change", which the manifest answers
  directly, and the reconstruction path still exists through git for the rare case.
- **~~`java-25-amazon-corretto-headless` may not resolve from AL2023's regional repositories~~** →
  Resolved: it ships in AL2023's own `core` repository on the regional S3 mirror. See *Resolved Questions*.
- **`perf_event_paranoid: 1` and `kptr_restrict: 0` weaken kernel isolation** on an instance that runs
  arbitrary benchmark JARs → Accepted: single-tenant, throwaway, already terminated within
  `timeout + 300 s`. Record it in *Accepted risks* rather than leaving it implicit, since previously these
  were AL2023 defaults and nobody chose them.
- **Setup now has two steps instead of one** → `baas admin setup` prints `baas admin build-image` as an
  explicit next step, and `baas run` fails with the same pointer. Rejected the alternative of building
  during setup because it makes every re-setup a 15-minute operation.
- **Image Builder adds a large block of CloudFormation and new IAM surface** → Offset by dropping the
  archive, drift, and adhoc subsystems, and by keeping every resource prefix-scoped so the deployer gains
  no cross-tenant reach.
- **The environment manifest adds two small objects per run** → Kilobytes against the profiling artifacts
  already uploaded per run; and `packages.txt` is split out so the high-signal file stays readable.
- **`baas env diff` is a new command whose value depends on the manifest being stable** → Its output is
  keyed on `schemaVersion` in `environment.json`, so a later field addition is detectable rather than
  silently changing diff output.

## Migration Plan

1. Verify the Corretto package source, and whether the AL2023 base image supplies `perf`; finalize
   `infra/runner-image.yaml`.
2. Deploy the Image Builder resources via `baas admin setup` (no build occurs).
3. Run `baas admin build-image`; confirm the pointer value and the AMI tags.
4. Run a benchmark and confirm `environment.json`, `packages.txt`, and the `imageVersion` /`instanceType`
   tags are present.
5. Compare one benchmark's score on the AMI against a recent pre-change run to confirm no unexpected
   environment shift; investigate rather than accept a material difference.
6. Remove the installation block from `UserDataScriptBuilder` and make the AMI a hard precondition.

**Rollback:** revert the `UserDataScriptBuilder` and `RunCommand` changes to restore boot-time
installation. The Image Builder stack resources are inert without a build and can be left in place or
removed independently. Manifests already written to the results bucket are harmless.

## Resolved Questions

Verified 2026-08-11 against the live AL2023 repository metadata and `eu-central-1`.

- **`java-25-amazon-corretto-headless` comes from AL2023's own `core` repository**, served from the
  regional S3-hosted mirror (`al2023-repos-eu-central-1-de612dc2.s3.dualstack.eu-central-1.amazonaws.com`,
  reached through the mirror list at `cdn.amazonlinux.com`). No external Corretto repository is involved,
  so the build step needs S3 and the mirror-list host, not a third-party endpoint — and change 3's private
  subnet inherits a problem the existing S3 gateway endpoint already mostly solves.
- **The AL2023 base image does not supply `perf`.** `perf` is a separately installable package and appears
  in *no* comps group — not `core`, not `base`, and not `ami`, which is the group defining what the AL2023
  AMI ships. The current boot-time-install path installs only Corretto, so JMH's `-prof perf`, `perfnorm`
  and `perfasm` fail today for want of the binary. Baking `perf` fixes them rather than keeping them
  working.
- **`perf` must match the running kernel**, and does: the pinned parent AMI (os-images release
  `2023.12.20260803.3`) carries kernel `6.1.177-224.371.amzn2023`, and the `perf` package is built from the
  same kernel build at the identical version. The two are pinned together in `infra/runner-image.yaml`, and
  the component smoke-tests `perf` so a mismatched pair fails the build rather than a benchmark.
- **`imagebuilder` resolves in `eu-central-1`**: the service authorised the call and denied it on a
  region-qualified ARN (`arn:aws:imagebuilder:eu-central-1:…:image-pipeline/*`), which an unavailable
  service would not produce. The two managed policy names are taken from AWS documentation rather than
  probed — neither the deployer nor the operator identity holds `iam:GetPolicy` — and a wrong name fails
  `baas admin setup` loudly at stack create, so it is self-reporting.
- **Pinned versions** as of the parent AMI: parent `ami-070cc8ab883065d64` (eu-central-1), Corretto
  `25.0.4+7-1.amzn2023.1`, `perf` `6.1.177-224.371.amzn2023`, `awscli-2` `2.33.15-1.amzn2023.0.1`,
  async-profiler `4.0` (unchanged from the value user-data used, so the profiler under a measurement does
  not move as part of this change).

## Open Questions

- Should `baas env diff` accept result identifiers (branch / type / timestamp) rather than raw S3 result
  paths? Paths are unambiguous and available from `baas results`, but identifiers would be friendlier.
  Deferred until the command has been used.
