## Why

Every benchmark run currently executes `yum update -y` and installs Corretto and async-profiler before
measuring anything. The operating system, the JDK patch level, and the profiler underneath a benchmark
therefore drift between measurements — for a tool whose entire purpose is holding the environment constant
while the code varies, this quietly invalidates comparisons (open finding A8). It also adds minutes of paid
boot time to every run, and it makes the runner dependent on reaching yum repositories and GitHub, which
blocks any move to a private subnet.

Two things fix this together: bake the toolchain into a pinned image so nothing installs at run time, and
record what the environment actually was on every run so any two results can be compared on equal terms —
or shown not to be.

## What Changes

- New `infra/runner-image.yaml` — the single declaration of the measurement environment: image version,
  pinned Corretto / async-profiler / `perf` / AWS CLI versions, the exact parent AL2023 AMI ID, and the
  kernel tunables that make profiling reproducible (`perf_event_paranoid`, `kptr_restrict`, transparent
  hugepages, swap). Ships as a CLI classpath resource alongside `cf-template-core.yaml`.
- `cf-template-core.yaml` gains EC2 Image Builder resources — `Component`, `ImageRecipe` (30 GB gp3 root,
  preserving the existing volume invariant), `InfrastructureConfiguration`, `DistributionConfiguration`,
  `ImagePipeline` — plus a build-instance role carrying `AmazonSSMManagedInstanceCore` and
  `EC2InstanceProfileForImageBuilder`. Deliberately **no** `AWS::ImageBuilder::Image`, which would perform
  a ~15-minute build inside every `baas admin setup`.
- New `baas admin build-image` renders the recipe, triggers the pipeline, publishes the AMI ID to
  `/<prefix>/runner/ami-id`, then deregisters the AMI it replaced. **One image, rebuilt in place** — no
  slots, no AMI history, no rebuild-from-archive. `infra/runner-image.yaml` is version-controlled, so
  `git log -p infra/runner-image.yaml` is the image history and
  `git checkout <sha> -- infra/runner-image.yaml && baas admin build-image` is the reproduction path.
- New `baas admin image` reports the current image: version, AMI ID, build time, and parent AMI.
- **Every run records its environment.** User-data captures `<result-path>/environment.json` (image
  version and AMI ID, instance type, CPU model and topology, memory, OS and kernel, JVM version, tool
  versions, kernel tunables) and `<result-path>/packages.txt` (`rpm -qa`) *before* the benchmark starts, so
  the record survives a run that fails.
- **Two-tier environment comparison.** `imageVersion` and `instanceType` become result tags, so
  `baas results` can flag a comparison group whose rows disagree using the database alone. New
  `baas env diff <resultPathA> <resultPathB>` fetches both manifests from S3 for the field-level
  difference. `BenchmarkMetadata.tags` is already a free-form `Map<String,String>`, so this needs no schema
  change and survives the DynamoDB migration untouched.
- `UserDataScriptBuilder` loses `yum update`, the Corretto install, and the async-profiler download, and
  gains the environment-capture block. The three-layer termination design and the `/app`
  working-directory invariant are untouched.
- Baking `perf` also **fixes** JMH's `-prof perf`, `perfnorm`, and `perfasm`: the AL2023 base image ships no
  `perf` binary (it belongs to no comps group, including the `ami` group) and no user-data path installs
  one, so those profiler names are broken today.
- **BREAKING**: `baas run` fails fast when no AMI has been built, pointing at `baas admin build-image`.
  There is no fallback to AL2023 + yum — two provisioning paths would produce silently incomparable
  results, and the private-network change would have to delete the fallback anyway.

## Capabilities

### New Capabilities
- `runner-image-provisioning`: the runner's measurement environment — declaring and building the single
  pinned AMI, and recording per run what that environment actually was so two results can be compared.

### Modified Capabilities
- `core-stack-provisioning`: adds Image Builder stack resources, the build-instance role, the AMI pointer
  parameter, and the deployer/operator permissions they require.
- `cli-command-structure`: adds `baas admin build-image`, `baas admin image`, and `baas env diff`; makes a
  built AMI a precondition of `baas run`.

## Impact

- **Infra**: `infra/cf-template-core.yaml`, `infra/deployer-policy.json`, `infra/operator-policy.json`,
  new `infra/runner-image.yaml`, `infra/README.md`.
- **Code**: new `commands/admin/BuildImageCommand.java`, `commands/admin/ImageCommand.java`,
  `commands/EnvCommand.java` + `commands/EnvDiffSubcommand.java`, new `infra/ImageBuilderService.java`,
  `infra/RunnerImageRenderer.java`, `results/EnvironmentManifest.java`; modified
  `commands/RunCommand.java`, `infra/UserDataScriptBuilder.java`, `infra/Ec2ProvisioningService.java`,
  `results/ResultsQueryService.java`, `config/BaasConfig.java`, `baas-cli/pom.xml` (imagebuilder SDK).
- **Docs**: `README.md` first-run flow gains a build step; `CLAUDE.md` invariants for user-data, the AMI
  precondition, and the environment manifest; `docs/diagrams/`; `docs/review/baas-cli-findings.md` (A8).
- **Cost**: ~$0.20/month for the single retained AMI snapshot.
- **No changes** to `benchmark-runner` — the environment capture lives entirely in user-data. Also no
  changes to the DynamoDB/Mongo store, the ARN-derived prefix scheme, or the public-subnet networking
  model.
- **Verified before implementation**: `java-25-amazon-corretto-headless` ships in AL2023's own `core`
  repository on the regional S3 mirror, so the build step reaches no third-party endpoint. See
  `design.md` *Resolved Questions* for the pinned parent AMI and package versions.
