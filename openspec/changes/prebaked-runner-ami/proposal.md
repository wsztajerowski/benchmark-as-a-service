## Why

Every benchmark run currently executes `yum update -y` and installs Corretto and async-profiler before
measuring anything. That means the operating system, the JDK patch level, and the profiler underneath a
benchmark drift between measurements — for a tool whose entire purpose is holding the environment
constant while the code varies, this quietly invalidates comparisons (open finding A8). It also adds
minutes of paid boot time to every run, and it makes the runner dependent on reaching yum repositories
and GitHub, which blocks any move to a private subnet.

Baking a pinned image turns the measurement environment into a versioned, attributable artifact.

## What Changes

- New `infra/runner-image.yaml` declares the image version, pinned tool versions (Corretto,
  async-profiler, AWS CLI), and the exact parent AL2023 AMI ID. It ships as a CLI classpath resource
  alongside `cf-template-core.yaml`.
- `cf-template-core.yaml` gains EC2 Image Builder resources — `Component`, `ImageRecipe` (30 GB gp3
  root, preserving the existing volume invariant), `InfrastructureConfiguration` on the public subnet,
  `DistributionConfiguration`, `ImagePipeline` — plus a build-instance role carrying
  `AmazonSSMManagedInstanceCore` and `EC2InstanceProfileForImageBuilder`. Deliberately **no**
  `AWS::ImageBuilder::Image`, which would perform a ~15-minute build inside every `baas admin setup`.
- New `baas admin build-image` renders the recipe, auto-bumps its patch version when content changes,
  triggers the pipeline, archives the rendered template to S3, and writes the AMI ID to SSM.
- Two AMI slots: `current` (newest build) and `adhoc` (at most one image rebuilt from an archived
  template, for reproducing a historical environment). Pointers at `/<prefix>/runner/ami-id` and
  `/<prefix>/runner/adhoc-ami-id`. Each slot prunes its own previous occupant at build *start*, so
  pruning never races an in-flight run.
- Rendered templates, component YAML, an `rpm -qa` manifest, and a `build.json` recording the exact
  parent AMI and artifact checksums are archived to `images/by-version/<v>/` in the results bucket, with
  `images/by-ami/<amiId>` pointer objects. A rebuild compares its manifest against the archived one, so
  environment drift is always *detectable* even when it cannot be prevented.
- New `baas admin images` lists both slots with version, AMI ID, build time, and drift status.
- `baas run` gains `--image-version` (resolves against either slot) and keeps `--ami-id` as an explicit
  override.
- Results gain `amiId`, `imageVersion`, `imageSlot`, and — when a rebuild's manifest diverges —
  `imageDrifted` tags. `tags` is already a free-form `Map<String,String>`, so this needs no schema
  change.
- `UserDataScriptBuilder` loses `yum update`, the Corretto install, and the async-profiler download.
  The three-layer termination design and the `/app` working-directory invariant are untouched.
- **BREAKING**: `baas run` fails fast when no AMI has been built, pointing at `baas admin build-image`.
  There is no fallback to AL2023 + yum — two provisioning paths would produce silently incomparable
  results, and the private-network change would have to delete the fallback anyway.

## Capabilities

### New Capabilities
- `runner-image-provisioning`: building, versioning, archiving, slotting, and pruning the runner AMI;
  rebuilding a historical image from an archived template and detecting drift.

### Modified Capabilities
- `core-stack-provisioning`: adds Image Builder stack resources, the build-instance role, the two AMI
  pointer parameters, and the deployer/operator permissions they require.
- `cli-command-structure`: adds `baas admin build-image` and `baas admin images`; adds
  `baas run --image-version`; makes a built AMI a precondition of `baas run`.

## Impact

- **Infra**: `infra/cf-template-core.yaml`, `infra/deployer-policy.json`, `infra/operator-policy.json`,
  new `infra/runner-image.yaml`, `infra/README.md`.
- **Code**: new `commands/admin/BuildImageCommand.java`, `commands/admin/ImagesCommand.java`, new
  `infra/ImageBuilderService.java`, `infra/RunnerImageArchive.java`, `infra/RunnerImageRenderer.java`;
  modified `commands/RunCommand.java`, `infra/UserDataScriptBuilder.java`,
  `infra/Ec2ProvisioningService.java`, `infra/SsmService.java`, `config/BaasConfig.java`,
  `baas-cli/pom.xml` (imagebuilder SDK dependency).
- **Docs**: `README.md` first-run flow gains a build step; `CLAUDE.md` invariants for user-data and the
  AMI precondition; `docs/diagrams/` sequence diagrams; `docs/review/baas-cli-findings.md` (A8).
- **Cost**: ~$0.20/month per retained AMI snapshot, ~$0.40 worst case with both slots occupied.
- **No changes** to `benchmark-runner`, the DynamoDB/Mongo store, the ARN-derived prefix scheme, or the
  public-subnet networking model.
- **Open implementation risk**: whether `java-25-amazon-corretto-headless` resolves from AL2023's
  regional repositories or an external Corretto repository determines what the build step must reach.
  Must be verified before the Image Builder component is written.
