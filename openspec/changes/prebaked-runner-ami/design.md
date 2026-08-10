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
- Every result is attributable to the exact image that produced it.
- A historical measurement environment can be reconstructed, and reconstruction fidelity is verifiable.
- Standing cost stays negligible (one or two AMI snapshots).

**Non-Goals:**

- Baking the `benchmark-runner` JAR into the image. It changes per release and is already staged through
  S3; coupling it to the image would force a rebuild per runner release.
- Multi-region image distribution. Single-region, matching the rest of the stack.
- A deep AMI history. Two slots, with the durable record held as templates in S3 rather than as AMIs.
- Automatic rebuild scheduling. Builds are operator-triggered.
- Any change to the results schema or the database.

## Decisions

### EC2 Image Builder, with the CLI owning recipe versioning

Image Builder matches the requirement that the repository hold a template which the CLI turns into an AMI
per account, and it does so as CloudFormation resources — the same pattern the CLI already uses for
`cf-template-core.yaml`. AWS owns build-instance provisioning, retries, and teardown.

Its cost is that `Component` and `ImageRecipe` are immutable and semver-versioned: changing content
requires a new version, and reusing a version fails the stack update. Left raw, "bump async-profiler to
4.1" would mean editing two numbers and getting a confusing CloudFormation error when you forget the
second. So the CLI renders the recipe from `infra/runner-image.yaml` and auto-bumps the patch version when
the rendered bytes differ from what is registered. The user edits one line.

*Alternatives considered.* A CLI-orchestrated build — launch, provision via user-data, `CreateImage`,
terminate — needs no new CF, no SSM, and no versioning ceremony, and updates are trivially one command.
It was rejected because it means hand-writing build-instance failure and cleanup handling, which the
existing three-layer termination design already demonstrates is easy to get subtly wrong. Packer was
rejected for putting a non-Java tool on the critical path of a CLI whose defining ADR is
`0001-self-contained-baas-cli`.

### `AWS::ImageBuilder::Image` is excluded from the stack

That resource performs a build during stack operations. Including it would add a ~15-minute image build to
every `baas admin setup`, including setups that changed nothing about the image. Builds are therefore
triggered out-of-band via `StartImagePipelineExecution`, and the stack holds only the durable
configuration.

### Two slots, and the durable record is the template rather than the AMI

`current` holds the newest build; `adhoc` holds at most one rebuild of a historical version. Retaining a
deep AMI history was rejected — but the justification is provenance, not the ~$0.20/month per snapshot,
which is too small to drive a design.

The real argument is that **a template outlives an AMI**. AMIs get deregistered, snapshots deleted, and
they are region-scoped. The results bucket is already `DeletionPolicy: Retain` and versioned, explicitly
outliving any stack, so a rendered template archived there is durable in a way the image is not.

Each slot prunes its own previous occupant at build *start*, not end, so pruning can never deregister an
AMI that a concurrent `baas run` has already resolved.

### Provenance archive shape

```
images/
  by-version/<imageVersion>/
      runner-image.yaml     rendered template
      component.yaml        rendered Image Builder component
      packages.txt          rpm -qa captured from the built instance
      build.json            amiId, exact parent AMI ID, checksums, region, builtAt
  by-ami/<amiId>            pointer object → imageVersion
```

`by-ami/` pointer objects make AMI-ID lookup a single `GetObject`. There is deliberately **no**
`index.json`: a mutable aggregate file would need read-modify-write, and two concurrent builds could
corrupt it. Listing-based lookup was also rejected as unnecessary when a pointer object costs nothing.

### Rebuild is approximate, and that is made explicit rather than hidden

A rebuild from an archived template does **not** reproduce the original AMI. `yum install` resolves
differently over time and a floating parent image moves underneath. Shipping "rebuild historical image"
without saying so would manufacture false confidence in comparisons — for this tool, worse than offering
nothing.

Two mitigations make it honest: the parent image is pinned by **exact AMI ID** rather than a `x.x.x`
selector, and `rpm -qa` is captured into `packages.txt` at build time. A rebuild diffs its manifest
against the archive, so drift is always detectable even when it cannot be prevented. A comparison you
cannot trust but *can* verify is acceptable; one you cannot verify is not.

### Adhoc builds bypass the pipeline

The `current` build uses the stack-managed pipeline. The `adhoc` build calls `imagebuilder:CreateImage`
directly against a recipe the CLI registers on demand from the archive.

Two code paths is a real cost, accepted for a specific reason: reproducing a historical benchmark must not
mutate infrastructure. A single pipeline would have to have its recipe swapped to the historical version
and back, so an investigation would leave footprints in the stack and race any concurrent build. Image
Builder recipes are free metadata that persist independently of AMIs, so an archived version is usually
already registered and gets reused.

### Image metadata reaches results through user-data

The runner cannot discover its own provenance — the drift verdict in particular is computed at build time,
not run time. So `baas run` resolves the selected AMI's tags and `build.json`, then passes `amiId`,
`imageVersion`, `imageSlot`, and any `imageDrifted` flag into user-data, which forwards them as ordinary
result tags. Because `BenchmarkMetadata.tags` is already a free-form `Map<String,String>`, this requires no
schema change now and none when the store becomes DynamoDB.

`baas results` surfaces drift but never auto-excludes. Whether a drifted number belongs in a comparison is
a judgement about the experiment, which is the operator's to make, not the tool's.

### No fallback to boot-time installation

`baas run` fails fast when no AMI exists. Keeping the yum path as a fallback would mean two provisioning
paths whose results are silently incomparable — precisely the problem this change exists to remove — and
change 3 would have to delete it regardless.

## Risks / Trade-offs

- **`java-25-amazon-corretto-headless` may not resolve from AL2023's regional repositories** → It installs
  successfully today from a public subnet, but whether that is the S3-hosted regional repo or an external
  Corretto repo is unverified and determines what the build step must reach. Verify before writing the
  component; if it is external, the build subnet needs that egress, which is available since builds stay
  public.
- **Setup now has two steps instead of one** → `baas admin setup` prints `baas admin build-image` as an
  explicit next step, and `baas run` fails with the same pointer. Rejected the alternative of building
  during setup because it makes every re-setup a 15-minute operation.
- **Image Builder adds a large block of CloudFormation and new IAM surface** → Offset partly by dropping
  `LifecyclePolicy` (the CLI prunes directly under the two-slot scheme) and by keeping every resource
  prefix-scoped so the deployer gains no cross-tenant reach.
- **Auto-bumping recipe versions could drift from the declared image version** → The image version in
  `runner-image.yaml` is the user-facing identity recorded in results and the archive; the recipe version
  is an Image Builder implementation detail. Keep them distinct in naming and tests so they are never
  conflated.
- **Concurrent adhoc builds contend for one slot** → Accepted at current single-operator scale; the second
  build wins and the first's AMI is pruned. Documented, not defended against.
- **A stale adhoc AMI accrues cost indefinitely** → `--clear-adhoc` and visibility via `baas admin images`.
  Auto-expiry was rejected as machinery disproportionate to ~$0.20/month.
- **Losing the boot-time install removes an escape hatch for ad-hoc tooling** → Adding a tool now means a
  recipe edit and a rebuild rather than a user-data tweak. This is the intended trade: ad-hoc tooling
  changes were exactly the drift being eliminated.

## Migration Plan

1. Verify the Corretto package source and finalize `infra/runner-image.yaml`.
2. Deploy the Image Builder resources via `baas admin setup` (no build occurs).
3. Run `baas admin build-image`; confirm the archive, the pointer, and a successful `baas run` on the new
   AMI.
4. Compare one benchmark's score on the AMI against a recent pre-change run to confirm no unexpected
   environment shift; investigate rather than accept a material difference.
5. Remove the installation block from `UserDataScriptBuilder` and make the AMI a hard precondition.

**Rollback:** revert the `UserDataScriptBuilder` and `RunCommand` changes to restore boot-time
installation. The Image Builder stack resources are inert without a build and can be left in place or
removed independently; the archived templates in S3 are retained regardless.

## Open Questions

- Does `java-25-amazon-corretto-headless` come from AL2023's regional repositories or an external Corretto
  repository? Determines the build subnet's required egress. Blocking for the component, not the design.
- Should `baas admin images` also list archived versions that no longer occupy a slot? Useful for
  discovering what `--from-version` can target; deferred until the archive has more than a few entries.
