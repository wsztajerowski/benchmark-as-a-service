---
paths:
  - "infra/runner-image.yaml"
  - "baas-cli/src/*/java/pl/wsztajerowski/baas/image/**"
---

# The runner image (`infra/runner-image.yaml`, `baas admin image build`)

The measurement environment's base: base version, pinned parent
release, Corretto (the full JDK, the default under `/opt/jdks`) and async-profiler versions, kernel
tunables, and the `baas-install-jdk` helper extensions add JDKs with. Ships in the JAR as
`/templates/runner-image.yaml` because both `setup` and `admin image build` render it at runtime.

- **`baas run` has no fallback.** No AMI at `/<prefix>/runner/ami-id` → the runner-image lookup
  fails there, before any upload. Two provisioning paths would produce silently incomparable
  results.
- **Exactly one image, rebuilt in place.** No slots, no AMI history, no second pointer. The base's
  archive is git: `git log -p infra/runner-image.yaml`, and `git checkout <sha> -- …` to
  reconstruct — from 1.2.0 onward; 1.0.0 and 1.1.0 predate the file and cannot be rebuilt. An
  extension has no archive: the stack holds only the current one.
- **The pointer is repointed *before* the replaced AMI is deregistered.** Retiring first aims the
  pointer at a deleted AMI for the whole ~15-minute build, failing every job launched in that window.
- **Teardown retires the image, always, and only after the stack is gone.** The pointer, the AMI,
  its snapshot and the recipe's Image Builder records live outside the stack, so deleting the stack
  left them, and a later setup could launch that inherited AMI without any `admin image build`. Retiring
  after the stack means a failed stack deletion still leaves a deployment with an image to run
  on. The pointer is deleted even when its AMI cannot be: the pointer is what a later setup would
  inherit, while a leftover AMI is only a cost leak. No step fails the teardown; leftovers are named
  with the command that removes each. `--deployment` retires *that* deployment's image.
  `admin image build` itself still leaves one Image Builder record per build, which cost nothing.
  Teardown's AWS calls go through `TeardownAws`, and `TeardownSequenceTest` pins this order and the
  gates before it; a build's go through `ImageBuildAws`, pinned by `RunnerImageBuildSequenceTest`.
- **The image is three components: base, extension, contract — in that order.** The base is
  rendered from the bundled `infra/runner-image.yaml`, the only place a *base* tool version is
  declared; any edit needs `imageVersion` bumped, because Image Builder components are immutable at
  a version. The extension is an operator's raw AWSTOE document; the contract runs last and fails
  the bake when a BaaS workflow would break. Only the base's version is hand-bumped: the extension's
  and the recipe's are derived from the deployed stack (`RunnerImageParameters`), and the contract
  shares the recipe's because it carries the label.
- **The contract lives in the AWSTOE `test` phase, so `ImageTestsEnabled` must stay `true`.** With
  tests off the checks never run and an image that broke BaaS is published silently. A build-phase
  check would be cheaper and wrong: sysctls are applied, and THP and an upgraded kernel take effect,
  only on the booted image. A test-stage failure comes *after* Image Builder registered the AMI, and
  Image Builder keeps it, so `build()` retires a failed build's output AMI — without that, every
  contract failure leaked a snapshot no command would ever name.
- **The base writes nothing that depends on the extension** — the label in
  `/etc/baas-image-version` included, which the contract writes. Otherwise every extension edit
  would change the base and demand a hand-bumped version.
- **The extension is stored verbatim in the stack parameter `RunnerImageExtensionData`, and only
  `admin image build --extension` changes it.** It is the only copy — overwritten, it cannot be recovered
  from AWS — so a push whose `# baas-extension-base:` marker no longer names the deployed extension
  is refused. With none deployed, any file is accepted (a file kept across a teardown), and a file a
  teammate deliberately emptied can come back that way: accepted. The copy that outlives a teardown
  is the export's `extension` part, marker included; teardown itself saves nothing, and `import`
  only prints the push, so this same guard decides it. Two pushes in the same seconds are
  not guarded, as for concurrent builds below. Anyone with `DescribeStacks` can read it, so it must
  never hold a credential. **ASCII only, trailing whitespace dropped:** `DescribeStacks` returns
  each non-ASCII character as `?` (the component itself gets the real bytes) and drops the trailing
  newline, so anything else makes the stack's copy — which the pull, the guard, the change detection
  and `admin image` all hash — differ from what was baked. Found live: an em dash in the starter's
  own comment.
- **`baas admin deployment setup` submits rendered image parameters only for what the stack lacks.** On create
  that is all of them — letting the template's placeholders stand would register a no-op at a
  version and Image Builder would then refuse the real one at that same version. On update every
  deployed image parameter is carried forward, so a plain setup can neither revert the base nor drop
  an extension; a parameter a pre-change stack lacks still gets a real value.
- **The preflight must not query components with `byName`.** That collapses every version into one
  row with no version field and an ARN ending in a literal `x.x.x`, so the version filter matches
  nothing, the preflight concludes the version is free, and a doomed build proceeds. Cost a real
  9-minute build to find.
- **`perf` is never pinned; the base installs the running kernel's build.** The RPM is built from one
  kernel build, the build instance boots the parent, so `uname -r` names the only right one and a pin
  could only disagree with it. The contract checks it still matches on the booted image, which is
  what catches an extension that upgraded the kernel.
- **The parent is an AL2023 release named by AMI name (`parentImage.amiName`), never an AMI ID.**
  An ID binds the definition to one region; the name resolves to the same release — kernel and
  repository — in every region, and exactly one Amazon-owned match is required.
- **Image Builder authorises reads against the collection, writes against the named resource.**
  `GetComponent`/`List*` cannot be prefix-scoped (they evaluate against `component/*`); pipeline
  writes can. Hence the split `ImageBuilderRead` (`Resource: "*"`) / `ImageBuilder` statements in
  `deployer-policy.json`.
- **Creating the first pipeline in an account needs `iam:CreateServiceLinkedRole`** for
  `imagebuilder.amazonaws.com`. Appears in no SDK call and no resource schema; only a real deploy
  finds it.
- **The four Image Builder *cross-resource* references use `!GetAtt <X>.Arn`, never `!Ref`** —
  `ImageRecipe.Components[].ComponentArn`, and the pipeline's `ImageRecipeArn`,
  `InfrastructureConfigurationArn` and `DistributionConfigurationArn`. Each of those resources
  exposes a distinct `Arn` attribute — the shape where `Ref` is liable to return the name — and the
  properties, plus `StartImagePipelineExecution`, reject a name. This does **not** generalise to the
  block as a whole, where every other reference is correctly a `!Ref`: `InstanceProfileName` wants a
  *name* (`!Ref` on an `AWS::IAM::InstanceProfile` returns exactly that, and `!GetAtt …Arn` breaks
  it), `SubnetId`/`SecurityGroupIds` want ids, and the version/data/parent-image properties are
  template parameters.
- **No `AWS::ImageBuilder::Image` in the template.** That resource builds during stack operations,
  adding ~15 minutes to every `baas admin deployment setup`.
- **Every JDK a job can select is a directory `/opt/jdks/<id>`, and only the base and the extension
  put one there.** The base installs Corretto as `java-25-amazon-corretto-devel` — `-headless` has no
  `jcmd`, which JMH's `-prof jfr` runs from the benchmark JVM's own `java.home` ("Cannot find jcmd
  anywhere", the closed PT1) — and links it as `/opt/jdks/corretto-25`, the default. An extension adds more with
  the base's `baas-install-jdk <id> <url> <sha256>`: download, `sha256sum -c`, unpack into a temporary
  directory under `/opt`, refuse an existing id or an archive without `bin/jcmd`, then rename into
  place, so a failed install leaves nothing a contract would trip over. `java` on `PATH` and
  `alternatives` stay Corretto: they run the runner, which needs its compile release; `--jdk` changes
  only the benchmark process's `java`. Ids, the root and the default come from `baas-model`'s `Jdks`,
  which the renderer substitutes into the helper and the contract.
- **The image's JDK list is what the contract observed, never what the extension declares.** The
  contract checks every `/opt/jdks` entry (id `[a-z0-9][a-z0-9.-]*`, at most 32 characters, `bin/java`
  runs, `bin/jcmd` exists, the default present, ids joined within the 256 characters an AMI tag value
  holds) and writes `image-builds/<label>/jdks.json` (`[{id, release, vendor, default}]`, default first)
  through the build role's existing `image-builds/*` grant — the bucket and region are rendered into the
  contract, so it differs per deployment, which is fine: its version is the recipe's, derived per stack.
  Publishing (build and adoption alike) reads that list and tags the AMI `baas-jdks=<ids>` *before*
  the pointer moves; a missing or malformed list retires the image like a contract failure. `baas run`
  reads the tag in its existing image lookup and refuses an unknown `--jdk` before any upload; an image
  without the tag (built before 1.5.0) accepts no `--jdk` and runs on `java` from `PATH`. Parsing the
  extension for `baas-install-jdk` lines was rejected: a loop or a variable defeats it.
- **A build is waited for or adopted, never repeated — only two starts in the same seconds race.**
  The CLI runs the whole build, so Ctrl+C or a closed lid mid-build used to leave Image Builder
  finishing an AMI no pointer named and nothing retired, and a re-run started a second build.
  `ImageBuilderService.publish` now looks at the pipeline's newest build first: still running, it
  is waited for; finished but unpublished, it is adopted (tags, pointer, retire the replaced AMI).
  Only a build of the recipe version the stack declares qualifies — one from before an extension
  push is retired instead, or the runners would get the superseded extension. Two
  `baas admin image build` runs started in the same seconds can still both start a build and have
  the second deregister what the first published: deliberately not guarded.
