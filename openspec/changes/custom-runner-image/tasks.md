# Tasks

## 1. Verify blocking assumptions

- [ ] 1.1 Find the exact AMI name AL2023 publishes for release `2023.12.20260803.3`
  (`aws ec2 describe-images --owners amazon --filters Name=name,Values=…`), and confirm it resolves to
  exactly one `x86_64` image in `eu-central-1` (equal to `ami-070cc8ab883065d64`) and in one other
  region. Record the name and both IDs in design.md, Resolved Questions.
- [ ] 1.2 Confirm Image Builder component and recipe versions accept only numeric `x.y.z`, and that
  CloudFormation replaces an `ImageRecipe` whose component list changed only when its `Version` also
  changes. Record the evidence. If either is false, revisit the versioning decision before section 3.
- [ ] 1.3 On the dev installation, bake a throwaway recipe with a component holding a `test` phase.
  Confirm the steps run on an instance booted from the new AMI, see the applied `sysctl` values and the
  THP kernel argument, fail the build on a non-zero exit with the step's output in the build log, and
  need no IAM beyond the build-instance role. Record the minutes added to the bake.
- [ ] 1.4 Confirm `UpdateStack` rejects `UsePreviousValue` for a parameter the deployed stack does not
  have, and accepts an omitted parameter with a non-placeholder supplied value. Record the error text.
- [ ] 1.5 Confirm that a stack parameter value round-trips through `DescribeStacks` byte-exact,
  including comments, blank lines and a trailing newline.

## 2. Base definition and rendering

- [ ] 2.1 Change `infra/runner-image.yaml`: `parentImage.release` replaces `region`/`amiId`; `tools.perf`
  becomes the package name only; `tools.awsCli` is removed; bump `imageVersion` to `1.3.0`. Update
  `RunnerImageDefinition` and `requireComplete()` to match. Verify with the strict-load tests (unknown
  key, missing key) updated to the new shape.
- [ ] 2.2 Base component: install `perf` for `uname -r`, drop the awscli install and its downgrade
  fallback, and drop the `/etc/baas-image-version` write (it moves to the contract). Verify a rendering
  test that asserts the `perf` derivation, no `awscli` and no version-file line, and that the existing
  size test still passes with the margin recorded.
- [ ] 2.3 Render the contract component: build phase writes the label to `/etc/baas-image-version`; test
  phase runs each check from design.md with a named failure. Filter `maven.compiler.target` into a
  resource for the Java floor. Verify rendering tests: the floor equals the POM's target, every check is
  present, the label is substituted, and the contract is under 4096 bytes.
- [ ] 2.4 Resolve the parent: `DescribeImages` with owner `amazon`, name and `x86_64`, in the stack's
  region, requiring exactly one result. Remove the region check in `BuildImageCommand`. Verify unit
  tests for zero, one and two results, each failure naming release and region.

## 3. Extension, versions and label

- [ ] 3.1 Extension file handling: read the leading `# baas-extension-base:` marker and strip it, treat
  comments-only as empty, refuse over 4096 bytes naming the size. Verify unit tests for each, including
  a file without a marker.
- [ ] 3.2 Label: `<base>` or `<base>+ext.<sha256[0:8]>` over the stored extension with trailing whitespace
  stripped. Verify a test that the same content gives the same label, a trailing newline does not change
  it, and any other edit does.
- [ ] 3.3 Version derivation from deployed parameters: extension previous + 1 when its content changed;
  recipe and contract previous + 1 when base, extension or contract changed; a pre-change stack's
  recipe previous is its `RunnerImageVersion`. Verify unit tests for unchanged, extension-only,
  base-only and pre-change parameter sets.
- [ ] 3.4 Stale-push guard per the design table. Verify unit tests for the five rows, including a file
  kept across teardown (deployed none, marker `H`) accepted.
- [ ] 3.5 Extend the base-version preflight so a hand-bumped base still fails fast, and the derived
  versions are never preflighted against themselves. Verify the existing preflight tests pass and a new
  one covers an extension-only change.

## 4. Template and stack updates

- [ ] 4.1 `cf-template-core.yaml`: parameters `RunnerImageExtensionData` (default empty),
  `RunnerImageExtensionVersion`, `RunnerImageRecipeVersion`, `RunnerImageLabel`,
  `RunnerImageContractData`; condition `HasExtension`; extension and contract components (`!GetAtt
  …Arn`, per the cross-reference invariant); the recipe's ordered list with `AWS::NoValue` for an absent
  extension, versioned by `RunnerImageRecipeVersion`; the distribution's `baas-image-version` tag from
  `RunnerImageLabel`. Verify `CoreTemplateTest` cases for the ordering, the condition and the tag source.
- [ ] 4.2 `CloudFormationService`: update with `UsePreviousValue` for named parameters the deployed stack
  has, and the supplied rendered value for those it lacks. Verify a unit test against a pre-change
  parameter set (only the original three present).
- [ ] 4.3 `SetupCommand`: render all image parameters on create; on update carry forward what exists and
  render what is absent. Verify tests that a second setup leaves an extension and a newer base unchanged,
  and that create registers no placeholder.
- [ ] 4.4 `SetupCommand` writes `~/.baas/runner-image-extension.yaml` when absent, never overwrites it,
  and names it and the push command. Verify tests for absent, present (byte-identical afterwards) and
  the content equalling the pull's starter.
- [ ] 4.5 `BuildImageCommand`: `--extension <file>` (size, guard, derived versions, label), extension
  carried forward without it, label in the success output. `ensureIdentityTags` tags the label. Verify
  command tests for push, plain rebuild, refused stale push and oversized file, each submitting nothing
  when refused.

## 5. `baas admin image`

- [ ] 5.1 Report the label, and when an extension is deployed, its hash, size against 4096 and step names
  per phase. Verify a test against an extension with two build steps and one validate step.
- [ ] 5.2 `--extension` prints the marker and the deployed extension, or the starter marked `none`, as
  payload on `System.out`. Verify a pull → push round-trip test that the guard accepts.
- [ ] 5.3 The drift warning compares base versions and names the bundled one, not a file path. Verify
  the existing warning test, reworded.

## 6. Manifest and tags

- [ ] 6.1 `UserDataScriptBuilder`: one `java -XshowSettings:properties -version` capture into a variable;
  `JVM_VENDOR`, `JVM_VENDOR_VERSION`, `JVM_NAME` through `json_escape`; manifest fields `jvmVendor`,
  `jvmVendorVersion`, `jvmName`; `MANIFEST_SCHEMA_VERSION` 4; `--tag jvmVendor=${JVM_VENDOR}`. Verify
  rendering tests, `bash -n`, and `aLargeRunStaysWellUnderTheUserDataLimit`.
- [ ] 6.2 `TagKeys.JVM_VENDOR` in `KNOWN` and `MACHINE_OBSERVED`. Verify a `RunCommand` test that
  `--tag jvmVendor=Acme` is refused before launch, naming the key.
- [ ] 6.3 Verify an `EnvironmentManifest.diff` test that two manifests differing only in `jvmVendor`
  report that field.

## 7. Documentation

- [ ] 7.1 CLAUDE.md: "the only place a tool version is declared" → a *base* tool version; "Git is the
  archive" → for the base, the extension's record is the stack; replace the `perf` pin invariant with
  the derivation and its contract check; add `jvmVendor` to the machine-observed keys (Invariants and
  Result tagging); add the extension's stack-parameter record and its stale-push guard under the
  runner-image invariants; note the extension is readable by `DescribeStacks`.
- [ ] 7.2 `infra/README.md`: the extension workflow (starter, pull, edit, push, guard), the 4096-byte
  limit, the no-secrets rule, and that async-profiler is only partly supported on OpenJ9.
- [ ] 7.3 Update the `baas admin build-image` and `baas admin image` sequence diagrams in
  `docs/diagrams/` and check they render with `mmdc`.
- [ ] 7.4 Note on `openspec/changes/private-runner-network` that a private build would also cut extension
  downloads.
- [ ] 7.5 Mark U20 **Fixed** in `docs/review/baas-cli-findings.md`'s status table and remove
  `custom-runner-image` from `openspec/changes/QUEUE.md`. Delete the change's `README.md` stub and
  `assumptions.md` once the design supersedes them.

## 8. End-to-end verification (manual: no automated test covers the `baas run` path)

- [ ] 8.1 On the dev installation, `baas admin setup` with the new CLI. Verify the starter file is
  written, the stack's new parameters hold rendered values, and `baas admin image` still reports the
  `1.2.0` image.
- [ ] 8.2 `baas admin build-image` with no extension. Verify label `1.3.0`, the contract passes in the
  build log, and a `baas run jmh-with-async` against `fake-jmh-benchmarks` completes with
  `imageVersion=1.3.0` and a `jvmVendor` tag, and an `environment.json` holding the vendor fields.
- [ ] 8.3 Push an extension that installs a small extra package. Verify label `1.3.0+ext.<hash>`,
  `baas admin image` lists the step, the package is in the run's `packages.txt`, and a plain `baas admin
  setup` followed by `baas admin build-image` keeps the extension.
- [ ] 8.4 Push an extension that removes `/app/async-profiler`. Verify the build fails naming the
  async-profiler check and the pointer still names the previous image.
- [ ] 8.5 With a stale file (marker from before 8.3), verify the push is refused before any stack
  change; pull, re-apply, and verify it is accepted.
- [ ] 8.6 Compare 8.2's `fake-jmh-benchmarks` score with recent `1.2.0` runs in `baas results`. Record
  the spread, and investigate any difference outside the recorded CI range rather than accepting it:
  only the AWS CLI version should differ on the measurement host.
- [ ] 8.7 Run `baas env diff` between a `1.2.0` run and 8.2's run. Verify the vendor fields appear on one
  side under the schema-version warning, and the AWS CLI version is the only tool-version difference.

## 9. Verify

- [ ] 9.1 Run `/opsx:verify` and record the result in `verify.md` in this change directory: a
  requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation
  from design or tasks.
