# Verify — custom-runner-image

Run 2026-10-04 on branch `custom-runner-image`, after the live end-to-end checks of section 8 against
`baas-381492019823`. Tests: `baas-model` 73, `baas-cli` 583, all passing. `openspec validate --strict`
passes. The installation was left on stock `1.3.0` (`ami-09d0ca3128f2792c0`), no extension, one AMI.

## Summary

| Dimension | Status |
|---|---|
| Completeness | 38/38 tasks (1.3–1.5 answered by the live run, not by probes — see tasks.md) |
| Correctness | 19/19 requirements implemented; every scenario covered by a unit test or a live check, gaps below |
| Coherence | Design followed; four live-found deviations folded back into design, spec and CLAUDE.md |

## Requirement → code → test

`ext` = `RunnerImageExtension`, `params` = `RunnerImageParameters`, `render` = `RunnerImageRenderer`.

| Requirement | Code | Tests | Live |
|---|---|---|---|
| **runner-image-provisioning** | | | |
| Base, optional extension, contract | `cf-template-core.yaml` recipe `Components`; `render.renderBase/renderContract` | `CoreTemplateTest.theRecipeRunsBaseThenExtensionThenContract` | 8.2 stock, 8.3 extended, CLI upgrade = 8.1→8.2 |
| Contract fails a bake that breaks a workflow | `CONTRACT_TEMPLATE` test phase; pipeline `ImageTestsEnabled: true` | `RunnerImageRendererTest.theContractChecks…`, `…ScriptsAreValidBash`, `CoreTemplateTest.imageTestsRun…` | 8.2 pass, 8.4 fail (async-profiler) — **W2** for the other checks |
| Parent resolved from a pinned release | `ParentImageResolver` (`includeDeprecated`) | `ParentImageResolverTest` (4) | eu-central-1 in 8.2; other region **W3** |
| Label identifies base and extension | `params.label`, contract writes `/etc/baas-image-version`, `RunnerImageLabel` → AMI tag | `RunnerImageParametersTest.theLabel…`, `CoreTemplateTest.theAmiIsTaggedWithTheLabel` | `1.3.0`, `1.3.0+ext.32a691b6` on AMI, manifest and tags |
| Extension held by the installation, explicit push only | `ext.parse/requireStorable`, `BuildImageCommand`, `SetupCommand.imageParameters` | `RunnerImageExtensionTest` (17), `RunnerImageExtensionCommandsTest.anOversized…` | 8.3: pull→push, plain setup and plain rebuild keep it; removal by comments-only |
| Stale push refused | `ext.requireCurrent` | `RunnerImageExtensionTest` guard cases (4) | 8.5 refused, stack untouched; re-pull accepted |
| Base definition ships, pins only what it must | `infra/runner-image.yaml`, `RunnerImageDefinition` | `RunnerImageRendererTest` (definition + strict-parse) | 8.2 manifest: perf = kernel, parent's awscli |
| Kernel tunables (modified) | base `ApplyKernelTunables` | `…kernelTunablesAreAppliedByTheBase` | 8.2 manifest `1 / 0 / [always]` |
| Runner installs no tooling (modified) | unchanged user-data | existing `UserDataScriptBuilderTest` | 8.2 |
| Exactly one image (modified) | `ImageBuilderService.publish`, `retireFailedOutput` | `ImageBuilderServiceTest` (26, incl. `anImageThatFailedItsTests…`) | one AMI after every step; 8.4 orphan removed by hand before the fix existed |
| Base version by hand, rest derived (modified) | `params.plan/next/sameDocument`, base-only preflight | `RunnerImageParametersTest` (8) | recipe 1.2.1→1.2.7 across 7 submissions; no-op plan against the live stack |
| Every run records its environment (modified) | `UserDataScriptBuilder` `JVM_PROPS`, schema 4 | `UserDataScriptBuilderTest.theManifestRecordsWhoBuiltTheJvm`, `…jvmPropertiesArePickedOutByTheirExactKey`, `EnvironmentManifestTest.sameVersionFromAnotherVendor…` | 8.2, 8.7 |
| **cli-command-structure** | | | |
| `build-image` (modified) | `BuildImageCommand` | `RunnerImageExtensionCommandsTest` | 8.2–8.4, restore |
| `admin image` (modified) | `ImageCommand` | `RunnerImageExtensionCommandsTest` report cases, `baseOf` | drift warning before 8.1; extension report in 8.3 |
| **core-stack-provisioning** | | | |
| Setup carries image parameters forward | `SetupCommand` update path, `params.absentFrom` | `RunnerImageParametersTest.aPreChangeStack…` | 8.1 (pre-change stack), 8.3 (plain setup: identical) |
| Setup writes a starter | `SetupCommand.writeExtensionStarter` | `RunnerImageExtensionCommandsTest` (2) | 8.1 written; later setups left it |
| Pipeline declares three components (modified) | template | `CoreTemplateTest` (5 new) | every bake |
| **results-store-schema** | | | |
| Tags vocabulary incl. `jvmVendor` (modified) | `TagKeys`, `--tag jvmVendor=` in user-data | `TagKeysTest`, `RunCommandTest.rejectsACallerTagForTheJvmVendor` | 8.2 tags |

## Found live and fixed in this change

1. **`ImageTestsEnabled: false`** in the pipeline — the contract would never have run. Enabled.
2. **The parent lookup omitted deprecated AMIs** (U37, from the concurrent analysis refresh); the pinned
   parent deprecates 2026-11-01. `includeDeprecated(true)`.
3. **`DescribeStacks` is not byte-exact**: non-ASCII returns as `?`, the trailing newline is dropped.
   The first push labelled `+ext.a8df0fc0` while every reader saw `da9ee39b`. Extensions are now
   ASCII-only and stored trimmed; the starter lost its em dash.
4. **Every rebuild looked changed** (recipe 1.2.4→1.2.5 with no edit) from the same trailing newline.
   Documents are compared ignoring trailing whitespace; confirmed no-op against the live stack.
5. **A contract failure leaked its AMI** (`ami-032267ff39b91f5fe`, removed by hand). `build()` now
   retires a failed build's output AMI and snapshots.

## Warnings

- **W1 — Stock 1.3.0 scores higher on 8275CL hosts, unexplained.** 11.19 and 12.00M vs 1.2.0's
  9.44–10.87M on the same CPU (n=2 vs n=3, single-iteration). Inside the overall 1.2.0 spread and CI
  history; `env diff` shows no tool, kernel or tunable difference. The one change on the measurement
  path is user-data's extra `java -XshowSettings` JVM start before the benchmark. Recommendation: let
  CI accumulate 1.3.0 samples per CPU model, and if the gap holds, test the hypothesis by moving the
  properties capture after the benchmark on a branch.
- **W2 — Most contract failure paths never ran on an image.** Only the async-profiler checks failed
  live (8.4). The Java floor, `perf` vs an upgraded kernel, the sysctl bounds, and the "another
  vendor's JVM succeeds" / "THP override succeeds" scenarios are covered by rendering tests and
  `bash -n` only. Recommendation: one extension bake that swaps Corretto for Temurin 25 (should pass,
  `jvmVendor=Eclipse Adoptium`) when the next image work is done.
- **W3 — No installation outside eu-central-1 was built.** The release resolves to
  `ami-07a5b367e8dc8bd92` in us-east-1 (task 1.1), but no bake ran there. Tracked as the U21 deferred
  check in `openspec/changes/QUEUE.md`.
- **W4 — Image Builder on a deprecated parent is untested.** The lookup now finds it; whether a recipe
  accepts it is first shown by a bake after 2026-11-01, or avoided by moving the pin to a newer
  release first.
- **W5 — Out of scope, observed: the CLI reports a run that finished before its `launched` write as
  failed.** On a very slow connection `RunInstances` answered five minutes late; the instance had
  already recorded `completed` and stored its measurement, and `RunSession.confirmLaunched` read the
  terminal status as "cancelled while launching" (exit 1, a redundant terminate). Run
  `20261004T162557253Z-1cf464a0`. Belongs to `run-status-in-dynamodb`'s logic. **Filed** as U38 in
  `docs/review/baas-cli-findings.md` (§55, with a proposed fix), not fixed here.
- **W6 — Fixed in the spec: the core-stack delta contradicted the code.** Found by the re-verify. *Setup
  carries the image parameters forward on an existing installation* said setup submits rendered
  values "only when it creates the stack", and listed four of the eight parameters. Setup also
  submits the rendered value for a parameter the deployed stack lacks (`absentFrom`), which design.md
  states and 8.1 relied on. The requirement now says that, names all eight, and has a scenario for an
  installation from before this change. No code change.

## Deviations from design and tasks

- `parentImage.amiName`, not `release` (design updated).
- `CloudFormationService` needed no change; setup sends `absentFrom(deployed)` (task 4.2 note).
- Command-level tests cover only refusals made before AWS; the push paths are tested where the logic
  lives and live in section 8 (tasks 4.3, 4.5 notes).
- Tasks 1.3–1.5 were deferred by the user and answered by section 8 rather than by probes.
- No `admin image` sequence diagram exists; `baas-setup.mmd` was updated instead (task 7.3 note).

## Re-verify (2026-10-04)

No code had changed since the first verify. Tests re-run: `baas-model` 73, `baas-cli` 583, none
failed or skipped. `openspec validate --strict` passes. Every MODIFIED and REMOVED heading matches
its main-spec requirement exactly, so the archive will not skip one. Found W6 (fixed in the spec)
and filed W5 as U38.

## Assessment

No critical issues. W6 is fixed in the spec and W5 is filed as U38. W1–W4 remain, none blocking: W1
needs more samples rather than a code change. Ready to commit and archive once the code is reviewed.
