# Tasks

## 1. Verify blocking assumptions

> 1.3–1.5 deferred by the user on 2026-10-04: no live probes before coding. Their assumptions are
> first exercised by section 8, so a failure there may point back here.

- [x] 1.1 Find the exact AMI name AL2023 publishes for release `2023.12.20260803.3`
  (`aws ec2 describe-images --owners amazon --filters Name=name,Values=…`), and confirm it resolves to
  exactly one `x86_64` image in `eu-central-1` (equal to `ami-070cc8ab883065d64`) and in one other
  region. Record the name and both IDs in design.md, Resolved Questions.
- [x] 1.2 Confirm Image Builder component and recipe versions accept only numeric `x.y.z`, and that
  CloudFormation replaces an `ImageRecipe` whose component list changed only when its `Version` also
  changes. Record the evidence. If either is false, revisit the versioning decision before section 3.
- [x] 1.3 On the dev installation, bake a throwaway recipe with a component holding a `test` phase.
  Confirm the steps run on an instance booted from the new AMI, see the applied `sysctl` values and the
  THP kernel argument, fail the build on a non-zero exit with the step's output in the build log, and
  need no IAM beyond the build-instance role. Record the minutes added to the bake.
  Answered by the live bakes instead of a throwaway probe: the test phase ran on a separate instance
  booted from the new AMI (8.2, `i-04d3f5fe925d7c271`) and saw the applied sysctls; a non-zero exit
  failed the build with the step's stderr in the S3 build log (8.4); no IAM beyond the build-instance
  role and the deployer's existing grants; TESTING added ~3 minutes (bakes took 11–13 min; one took 37).
- [x] 1.4 Confirm `UpdateStack` rejects `UsePreviousValue` for a parameter the deployed stack does not
  have, and accepts an omitted parameter with a non-placeholder supplied value. Record the error text.
  Answered live by 8.1 (design.md, Resolved Questions); the rejection path itself was not provoked.
- [x] 1.5 Confirm that a stack parameter value round-trips through `DescribeStacks` byte-exact,
  including comments, blank lines and a trailing newline.

## 2. Base definition and rendering

  Answered live by 8.3: NOT byte-exact. Trailing newline dropped, non-ASCII read back as `?`.
  Fixed by trimming and refusing non-ASCII (design.md, Resolved Questions).
- [x] 2.1 Change `infra/runner-image.yaml`: `parentImage.release` replaces `region`/`amiId`; `tools.perf`
  becomes the package name only; `tools.awsCli` is removed; bump `imageVersion` to `1.3.0`. Update
  `RunnerImageDefinition` and `requireComplete()` to match. Verify with the strict-load tests (unknown
  key, missing key) updated to the new shape.
  Done: the key is `parentImage.amiName` (it holds the AMI name), not `release`.
- [x] 2.2 Base component: install `perf` for `uname -r`, drop the awscli install and its downgrade
  fallback, and drop the `/etc/baas-image-version` write (it moves to the contract). Verify a rendering
  test that asserts the `perf` derivation, no `awscli` and no version-file line, and that the existing
  size test still passes with the margin recorded.
  Done: base 1290 bytes, contract 1999 (2026-10-04).
- [x] 2.3 Render the contract component: build phase writes the label to `/etc/baas-image-version`; test
  phase runs each check from design.md with a named failure. Filter `maven.compiler.target` into a
  resource for the Java floor. Verify rendering tests: the floor equals the POM's target, every check is
  present, the label is substituted, and the contract is under 4096 bytes.
- [x] 2.4 Resolve the parent: `DescribeImages` with owner `amazon`, name and `x86_64`, in the stack's
  region, requiring exactly one result. Remove the region check in `BuildImageCommand`. Verify unit
  tests for zero, one and two results, each failure naming release and region.

## 3. Extension, versions and label

- [x] 3.1 Extension file handling: read the leading `# baas-extension-base:` marker and strip it, treat
  comments-only as empty, refuse over 4096 bytes naming the size. Verify unit tests for each, including
  a file without a marker.
- [x] 3.2 Label: `<base>` or `<base>+ext.<sha256[0:8]>` over the stored extension with trailing whitespace
  stripped. Verify a test that the same content gives the same label, a trailing newline does not change
  it, and any other edit does.
- [x] 3.3 Version derivation from deployed parameters: extension previous + 1 when its content changed;
  recipe and contract previous + 1 when base, extension or contract changed; a pre-change stack's
  recipe previous is its `RunnerImageVersion`. Verify unit tests for unchanged, extension-only,
  base-only and pre-change parameter sets.
- [x] 3.4 Stale-push guard per the design table. Verify unit tests for the five rows, including a file
  kept across teardown (deployed none, marker `H`) accepted.
- [x] 3.5 Extend the base-version preflight so a hand-bumped base still fails fast, and the derived
  versions are never preflighted against themselves. Verify the existing preflight tests pass and a new
  one covers an extension-only change.
  Done without a code change to the preflight: `build-image` preflights the base alone, and the
  derived versions never reach it. An extension-only edit leaves the base identical, which the
  existing "identical content rebuilds" preflight test covers.

## 4. Template and stack updates

- [x] 4.1 `cf-template-core.yaml`: parameters `RunnerImageExtensionData` (default empty),
  `RunnerImageExtensionVersion`, `RunnerImageRecipeVersion`, `RunnerImageLabel`,
  `RunnerImageContractData`; condition `HasExtension`; extension and contract components (`!GetAtt
  …Arn`, per the cross-reference invariant); the recipe's ordered list with `AWS::NoValue` for an absent
  extension, versioned by `RunnerImageRecipeVersion`; the distribution's `baas-image-version` tag from
  `RunnerImageLabel`. Verify `CoreTemplateTest` cases for the ordering, the condition and the tag source.
- [x] 4.2 `CloudFormationService`: update with `UsePreviousValue` for named parameters the deployed stack
  has, and the supplied rendered value for those it lacks. Verify a unit test against a pre-change
  parameter set (only the original three present).
  Done without a change: `updateStackParameters` already carries forward every key not in
  `changed`, so setup sends `absentFrom(deployed)`. Covered by
  `RunnerImageParametersTest.aPreChangeStackMovesPastTheRecipeVersionItWasVersionedWith`.
- [x] 4.3 `SetupCommand`: render all image parameters on create; on update carry forward what exists and
  render what is absent. Verify tests that a second setup leaves an extension and a newer base unchanged,
  and that create registers no placeholder.
  Tested at the plan level (`RunnerImageParametersTest`): commands build their own AWS clients,
  so no command test reaches a stack. Section 8.1/8.3 cover the live path.
- [x] 4.4 `SetupCommand` writes `~/.baas/runner-image-extension.yaml` when absent, never overwrites it,
  and names it and the push command. Verify tests for absent, present (byte-identical afterwards) and
  the content equalling the pull's starter.
- [x] 4.5 `BuildImageCommand`: `--extension <file>` (size, guard, derived versions, label), extension
  carried forward without it, label in the success output. `ensureIdentityTags` tags the label. Verify
  command tests for push, plain rebuild, refused stale push and oversized file, each submitting nothing
  when refused.
  Command-level test covers only the oversized refusal, the one made before any AWS call; push,
  plain rebuild and stale push are covered where the logic lives (`RunnerImageExtensionTest`,
  `RunnerImageParametersTest`) and live in 8.3/8.5.

## 5. `baas admin image`

- [x] 5.1 Report the label, and when an extension is deployed, its hash, size against 4096 and step names
  per phase. Verify a test against an extension with two build steps and one validate step.
- [x] 5.2 `--extension` prints the marker and the deployed extension, or the starter marked `none`, as
  payload on `System.out`. Verify a pull → push round-trip test that the guard accepts.
- [x] 5.3 The drift warning compares base versions and names the bundled one, not a file path. Verify
  the existing warning test, reworded.
  No warning test existed; `baseOf` is tested and the warning text names the bundled base.

## 6. Manifest and tags

- [x] 6.1 `UserDataScriptBuilder`: one `java -XshowSettings:properties -version` capture into a variable;
  `JVM_VENDOR`, `JVM_VENDOR_VERSION`, `JVM_NAME` through `json_escape`; manifest fields `jvmVendor`,
  `jvmVendorVersion`, `jvmName`; `MANIFEST_SCHEMA_VERSION` 4; `--tag jvmVendor=${JVM_VENDOR}`. Verify
  rendering tests, `bash -n`, and `aLargeRunStaysWellUnderTheUserDataLimit`.
- [x] 6.2 `TagKeys.JVM_VENDOR` in `KNOWN` and `MACHINE_OBSERVED`. Verify a `RunCommand` test that
  `--tag jvmVendor=Acme` is refused before launch, naming the key.
- [x] 6.3 Verify an `EnvironmentManifest.diff` test that two manifests differing only in `jvmVendor`
  report that field.

## 7. Documentation

- [x] 7.1 CLAUDE.md: "the only place a tool version is declared" → a *base* tool version; "Git is the
  archive" → for the base, the extension's record is the stack; replace the `perf` pin invariant with
  the derivation and its contract check; add `jvmVendor` to the machine-observed keys (Invariants and
  Result tagging); add the extension's stack-parameter record and its stale-push guard under the
  runner-image invariants; note the extension is readable by `DescribeStacks`.
- [x] 7.2 `infra/README.md`: the extension workflow (starter, pull, edit, push, guard), the 4096-byte
  limit, the no-secrets rule, and that async-profiler is only partly supported on OpenJ9.
- [x] 7.3 Update the `baas admin build-image` and `baas admin image` sequence diagrams in
  `docs/diagrams/` and check they render with `mmdc`.
  Done: `baas-build-image.mmd` and `baas-setup.mmd` (no `admin image` diagram exists); both render
  with `@mermaid-js/mermaid-cli` 11.17.0.
- [x] 7.4 Note on `openspec/changes/private-runner-network` that a private build would also cut extension
  downloads.
- [x] 7.5 Mark U20 **Fixed** in `docs/review/baas-cli-findings.md`'s status table and remove
  `custom-runner-image` from `openspec/changes/QUEUE.md`. Delete the change's `README.md` stub and
  `assumptions.md` once the design supersedes them.
  Done 2026-10-04, together with U37 (found by the concurrent analysis refresh: the parent lookup
  omitted deprecated AMIs), fixed in `ParentImageResolver` with a test. U21's row and its deferred
  live check in `QUEUE.md` were left as that refresh wrote them.

## 8. End-to-end verification (manual: no automated test covers the `baas run` path)

- [x] 8.1 On the dev installation, `baas admin setup` with the new CLI. Verify the starter file is
  written, the stack's new parameters hold rendered values, and `baas admin image` still reports the
  `1.2.0` image.
  Done 2026-10-04 on `baas-381492019823`: starter written with marker `none`; the five new parameters
  got rendered values (recipe `1.2.1`, label `1.2.0`, extension version `1.0.0`, extension empty,
  contract rendered); base `1.2.0` and parent carried forward; `admin image` still reports `1.2.0`.
- [x] 8.2 `baas admin build-image` with no extension. Verify label `1.3.0`, the contract passes in the
  build log, and a `baas run jmh-with-async` against `fake-jmh-benchmarks` completes with
  `imageVersion=1.3.0` and a `jvmVendor` tag, and an `environment.json` holding the vendor fields.
  Done 2026-10-04: `ami-0c9fae7941f27c50a`, recipe `1.2.2`, ~13 min (TESTING ~3 min). Test-phase
  console: `BaaS contract: every check passed` on test instance `i-04d3f5fe925d7c271`. Run
  `20261004T140751111Z-a0a8f178`: `imageVersion=1.3.0`, `jvmVendor=Amazon.com Inc.`, manifest
  schema 4 with `Corretto-25.0.4.7.1` / `OpenJDK 64-Bit Server VM`, perf = kernel
  `6.1.177-224.371`, parent's awscli `2.33.15` (the old pin's version).
- [x] 8.3 Push an extension that installs a small extra package. Verify label `1.3.0+ext.<hash>`,
  `baas admin image` lists the step, the package is in the run's `packages.txt`, and a plain `baas admin
  setup` followed by `baas admin build-image` keeps the extension.
  Done 2026-10-04, after two fixes it exposed. First push (pre-fix CLI): label `+ext.a8df0fc0`,
  but `admin image` hashed the stack's copy as `da9ee39b` — `DescribeStacks` had returned the
  starter's em dash as `?` (design.md, Resolved Questions). Fixed (ASCII only, trimmed), re-pulled,
  re-pushed: `ami-04fd4d2e9ebde3816`, label `1.3.0+ext.32a691b6`, and `admin image` agrees (1656
  bytes, `build: InstallStrace`, `validate: CheckStrace`). Run `20261004T143713386Z-68a2b8ec`:
  `imageVersion=1.3.0+ext.32a691b6`, `strace-6.12-1.amzn2023.0.1` in `packages.txt`. Plain setup:
  "already up to date", all eight image parameters identical. Plain build-image: same label on
  `ami-09d00661be7a636c6` — but the recipe moved 1.2.4 → 1.2.5 with nothing changed, the second
  fix (documents compared ignoring trailing whitespace; unit test, not yet re-baked live).
- [x] 8.4 Push an extension that removes `/app/async-profiler`. Verify the build fails naming the
  async-profiler check and the pointer still names the previous image.
  Done 2026-10-04: label `1.3.0+ext.9cf16166`, recipe 1.2.6, failed in TESTING: "Document
  …component-contract/1.2.6/1 failed!", console `BaaS contract FAILED: no async-profiler library at
  …` and `…no asprof at…` (every check reported in one bake). Pointer still `ami-09d00661be7a636c6`,
  `available`. **Found:** the failed build's own AMI `ami-032267ff39b91f5fe` was left registered
  with its snapshot — fixed (`build()` retires a failed build's output AMI, unit-tested) and that
  one removed by hand.
- [x] 8.5 With a stale file (marker from before 8.3), verify the push is refused before any stack
  change; pull, re-apply, and verify it is accepted.
  Done 2026-10-04: a file marked `none` pushed while `da9ee39b` was deployed was refused, naming
  both and the pull command; the stack's `LastUpdatedTime` did not move. The pulled, edited file was
  then accepted (the 8.3 re-push).
- [x] 8.6 Compare 8.2's `fake-jmh-benchmarks` score with recent `1.2.0` runs in `baas results`. Record
  the spread, and investigate any difference outside the recorded CI range rather than accepting it:
  only the AWS CLI version should differ on the measurement host.
  Done 2026-10-04 (`Incrementing_Synchronized`, c5.2xlarge, `-wi 1 -i 1`), grouped by host CPU since
  that varies per launch: 1.2.0 on 8124M 9.42–13.12M (n=5, mean 11.08), on 8275CL 9.44–10.87M (n=3,
  mean 10.03); stock 1.3.0 on 8124M 11.62M (n=1), on 8275CL 11.19 and 12.00M (n=2); 1.3.0+ext on
  8124M 10.73M. All inside the overall 1.2.0 spread and the recorded CI history; both 8275CL samples
  sit above that subgroup's 1.2.0 maximum. Investigated, not explained: `env diff` shows no
  JVM/kernel/perf/awscli/async-profiler/tunable difference; the one change on the measurement path is
  user-data's extra `java -XshowSettings` call before the benchmark. Carried as verify.md W1 for
  more samples, not accepted. Runs `20261004T140751111Z-a0a8f178`, `20261004T160248400Z-226c05b4`,
  `20261004T162557253Z-1cf464a0` (the last misreported as failed by the CLI — see verify.md).
- [x] 8.7 Run `baas env diff` between a `1.2.0` run and 8.2's run. Verify the vendor fields appear on one
  side under the schema-version warning, and the AWS CLI version is the only tool-version difference.

  Done 2026-10-04: `env diff 20261004T110628590Z-67619f66 20261004T140751111Z-a0a8f178` (1.2.0 vs
  1.3.0). Schema 3-vs-4 warning; vendor fields on the 1.3.0 side only. **No tool version differs at
  all** — awscli included, the parent ships the old pin's 2.33.15. Remaining differences are the
  instance's (CPU 8124M vs 8275CL, memory) and the run's identity.
## 9. Verify

- [x] 9.1 Run `/opsx:verify` and record the result in `verify.md` in this change directory: a
  requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation
  from design or tasks.
