# `prebaked-runner-ami` — change review

Review of the OpenSpec change `prebaked-runner-ami` and its implementation in commit `e65e251`
(54 files, +4269/-558), recorded 2026-08-13.

Companion files: [`baas-cli-findings.md`](./baas-cli-findings.md),
[`benchmark-runner-findings.md`](./benchmark-runner-findings.md).

**Line numbers are as of this commit and will drift.** Each entry names a logical anchor (method,
resource, statement `Sid`) — trust that over the line number.

## Scope

Three axes:

1. **OpenSpec artifacts** — `proposal.md`, `design.md`, the three delta specs, `tasks.md`:
   internal coherence, whether the deltas describe what was built, readiness to archive.
2. **Implementation code** — the `e65e251` diff, for correctness defects and violations of the
   invariants CLAUDE.md records.
3. **Documentation accuracy** — claims the change added to `CLAUDE.md`, `README.md`,
   `infra/README.md`, `docs/diagrams/` and `docs/review/`, checked against the code.

Live AWS re-verification of the §11 end-to-end results was **out of scope**; read-only AWS calls
were used only where they settle a documentation claim (they settle P3).

Excluded and not re-raised: everything in CLAUDE.md's *Accepted risks* table, and open finding
**D1** (`baas results` filtering/grouping documented but not implemented), which is already
tracked and is scheduled for `dynamodb-results-store`.

## Verification performed

| Check | Result |
|---|---|
| `mvn clean verify`, full reactor, `ASYNC_PATH` exported | **Green** — 160 tests in `baas-cli`, 0 failures, 0 skipped; `JmhWithAsyncProfilerSubcommandServiceIT` *ran* rather than silently skipping |
| Task completion vs commit message claim | 79/79 checked — matches `Refs: … (79/79 tasks)` |
| Deployed image vs declared image (read-only AWS) | **Mismatch** — see P3 |
| `|| echo absent` fallback behaviour | Reproduced in a real shell — see P4 |
| `ListComponentsIterable` exists in SDK 2.41.19 | Confirmed — makes P1 a one-line fix |

## Status

| # | ID | Finding | Sev | Status |
|---|-----|---------|-----|--------|
| 1 | P1 | Version preflight reads one `listComponents` page | ~~Med~~ Low | **Withdrawn as filed; hardened anyway** |
| 2 | P2 | A failed retirement fails a command whose real work already succeeded | Med | **Fixed** |
| 3 | P3 | Declared image `1.2.0` was never built; `1.0.0`/`1.1.0` are not in git | Med | Open |
| 4 | P5 | CLAUDE.md's "`!GetAtt`, never `!Ref`" invariant is false, and following it breaks the stack | Med | **Fixed** |
| 5 | P6 | `RunCommand` comment asserts the exact mechanism CLAUDE.md records as a past bug | Med | **Fixed** |
| 6 | P4 | `\|\| echo absent` never fires — a missing tool records as empty string | Low | Open |
| 7 | P7 | "comparison group" wording assumes grouping `baas results` does not do | Low | Open |
| 8 | P8 | `tasks.md` §11.3 contradicts the delta spec it verifies | Low | Open |
| 9 | P9 | Change widened open finding S5 without updating it | Low | Open |
| 10 | P10 | A corrupt manifest stack-traces where a missing one is handled | Low | Open |
| 11 | P11 | `benchmarkType` in the manifest makes every cross-type `env diff` report a difference | Low | Open |
| 12 | P12 | Kernel tunables silently default to `0`/`null` on a mistyped key | Low | Open |
| 13 | P13 | async-profiler install path hardcoded a third time | Low | Open |
| 14 | P14 | `build()` polls without an upper bound | Low | Open |
| 15 | P15 | `deployer-policy.json` is partition-hardcoded; the template beside it is not | Low | Open |

**P2, P5 and P6 were fixed on 2026-08-13.** P2's fix is pinned by a test confirmed to fail against
the pre-fix code. **P1 was withdrawn the same day** — its premise did not survive checking against
the deployed stack (see the correction at the head of that entry); the pagination change was kept
as hardening only.

`P3` and `P8` remain the two worth clearing before archiving: the first is a verification gap in
the change itself, the second a record that contradicts the spec it verifies. Neither is a code
defect.

---

## 1. P1 — the version preflight reads one `listComponents` page · ~~Med~~ Low · **Withdrawn as filed**

> **Correction (2026-08-13).** As originally filed this finding claimed component versions
> accumulate without bound. **That premise is wrong**, and the severity with it. `Version` is a
> replacement property on `AWS::ImageBuilder::Component`, so every bump has CloudFormation create
> the new version and *delete* its predecessor. The account holds exactly one registered version
> (`3q7i7s65-runner-toolchain 1.1.0`), and the stack events show the pattern on every bump:
> *"Requested update requires the creation of a new physical resource; hence creating one"*
> followed by `DELETE_COMPLETE`. The page limit is therefore unreachable in this design, and the
> preflight is correct as it stands — it works precisely because the one surviving version is the
> current one.
>
> The pagination change was kept as hardening, not as a fix. It costs nothing at one version. The
> original analysis is left below so the reasoning can be checked rather than taken on trust.


`ImageBuilderService.registeredComponentData` issues a single `listComponents` call and searches
only that page:

```java
var versions = imageBuilder.listComponents(r -> r
        .owner("Self")
        .filters(Filter.builder().name("name").values(componentName).build()))
    .componentVersionList();
```

`ListComponents` caps `maxResults` at 25 and paginates with `nextToken`. Component versions
accumulate without bound — `imagebuilder:Delete*` is granted in `deployer-policy.json` but nothing
in the codebase ever calls it, and the design's "one image, rebuilt in place" rule governs *AMIs*,
not component versions. Once the account holds more than a page of them, the declared version can
fall outside the page, `filter(version -> imageVersion.equals(...))` matches nothing,
`preflightVersion` concludes the version is unregistered, and `baas admin build-image` proceeds
into a stack update that Image Builder then rejects.

That is precisely the failure the method's own javadoc says it exists to prevent, and the same
class of silent blindness as the `byName` defect fixed and pinned directly above it
(`preflightQueriesComponentsInAWayThatReturnsVersions`). The existing test uses a fake that returns
every version in one response, so it cannot see this.

**Applied as hardening.** `registeredComponentData` now calls `listComponentsPaginator`.
`FakeImageBuilder` grew a `pageSize` honouring `nextToken` (0 = one page, what every other test
wants), and `preflightFindsAVersionBeyondTheFirstPage` registers 31 versions at page size 25.
The test guards the API contract against the day components stop being deleted; it does not pin a
reachable defect. Drop both if you would rather not carry a test for an unreachable state — the
single-page read is not wrong today.

## 2. P2 — a failed retirement fails a command whose real work already succeeded · Med · **Fixed**

`ImageBuilderService.retire` guards only the snapshot deletions:

```java
List<String> snapshots = ec2.describeImages(r -> r.imageIds(amiId))...   // uncaught
ec2.deregisterImage(r -> r.imageId(amiId));                              // uncaught
for (String snapshotId : snapshots) {
    try { ec2.deleteSnapshot(...); } catch (Ec2Exception e) { logger.warn(...); }
}
```

`retire` is called from `publish` *after* the pointer has been repointed. If the replaced AMI is
already gone — deregistered by hand, or cleaned up out of band — `describeImages` throws
`InvalidAMIID.NotFound`, which propagates out of `publish` and out of `call()`. The build
succeeded, the AMI is correct, the pointer is correct, and the command still exits non-zero. The
operator's natural response to a failed `build-image` is to run it again: another ~15 minutes and
another AMI, for a cleanup step that had nothing left to clean.

The `deleteSnapshot` loop already carries the right reasoning — *"failing the whole command here
would strand a build that has already succeeded"* — it is just applied one level too deep.

**Fixed.** `publish` now calls a private `retireQuietly`, which wraps `retire` in the same
catch-and-warn the snapshot loop already used, naming the AMI that may need removing by hand.
`retire` itself still throws, so a direct caller keeps the honest signal. Pinned by
`aReplacedImageThatIsAlreadyGoneDoesNotFailTheBuild`, which errored with
`InvalidAMIID.NotFound` against the old code.

## 3. P3 — the declared image was never built, and the two that were are not in git · Med

Verified against the live account (read-only):

```
/3q7i7s65/runner/ami-id → ami-0a89e2bd4bf6f208a
  baas-image-version = 1.1.0
  baas-parent-ami    = ami-070cc8ab883065d64
  created            = 2026-08-12T21:58:34Z
```

`infra/runner-image.yaml` in the working tree declares `imageVersion: "1.2.0"`. Commit `e65e251`
(2026-08-13T00:51) introduced that file whole, in one commit. Two consequences:

**The shipped declaration has no end-to-end verification.** Every §11 result — the async-profiler
kernel-stack check, the tunables check, the score comparison in 11.7 — was gathered on 1.0.0 and
1.1.0. Whatever changed between 1.1.0 and 1.2.0 has been through the unit tests and nothing else.
Since CI cannot catch a bad bake (CLAUDE.md, *What isn't there*), nothing else will.

**`git log -p` is not yet the archive it is advertised as.** design.md rests the removal of the S3
image archive on git being the durable record, with
`git checkout <sha> -- infra/runner-image.yaml && baas admin build-image` as the reproduction
path. Because the file arrived in a single squashed commit, git holds no revision at 1.0.0 or
1.1.0 — so the two environments that produced the measurements recorded in §11.7 are exactly the
ones that cannot be reconstructed. The claim becomes true from 1.2.0 forward; it is not true
retrospectively, and §11.7's own numbers are the counter-example.

**Fix.** Either build 1.2.0 and record it, or set the declaration back to the version that was
actually verified. Then soften design.md's archive claim to "from this change forward", since the
`by-version/` archive it replaced would have held 1.0.0 and 1.1.0.

## 4. P5 — the "`!GetAtt`, never `!Ref`" invariant is false, and following it breaks the stack · Med · **Fixed**

CLAUDE.md states:

> **The Image Builder wiring uses `!GetAtt <X>.Arn`, never `!Ref`.**

The Image Builder block of `cf-template-core.yaml` (lines 277-400) contains **13 `!Ref` uses and 4
`!GetAtt .Arn` uses**. The `!Ref`s are not oversights — they are required:

| Line | Use | Why `!Ref` |
|---|---|---|
| 356 | `InstanceProfileName: !Ref ImageBuildInstanceProfile` | the property takes a **name**; `!Ref` on `AWS::IAM::InstanceProfile` returns exactly that |
| 361, 363 | `SubnetId` / `SecurityGroupIds` | ids, not ARNs |
| 324-325, 333-334 | `Version`, `Data`, `ParentImage` | template parameters |

The true invariant is narrower: the four **cross-resource references** (`ComponentArn`,
`ImageRecipeArn`, `InfrastructureConfigurationArn`, `DistributionConfigurationArn`) must use
`!GetAtt .Arn`. Applied as written, the rule tells a maintainer to change line 356 to
`!GetAtt ImageBuildInstanceProfile.Arn`, which fails the stack update.

The in-template comment at lines 336-339 is better scoped ("every one of these resources exposes a
distinct `Arn` attribute") but "throughout the Image Builder wiring" overreaches the same way.

**Fixed.** CLAUDE.md's bullet now names the four cross-resource references explicitly and states
that the rule does *not* generalise, calling out `InstanceProfileName` as the one `!GetAtt …Arn`
would break. The in-template comment at `RunnerImageRecipe.Components` was narrowed the same way
and now says which `!Ref`s must stay.

## 5. P6 — `RunCommand`'s comment asserts the exact mechanism CLAUDE.md records as a past bug · Med · **Fixed**

`RunCommand.call()`, step 7:

```java
// 7. Launch instance. imageVersion and instanceType ride along as result tags so that
//    `baas results` can spot a comparison group whose rows sat on different environments
//    without fetching a single S3 object.
Map<String, String> tags = new LinkedHashMap<>(extraTags);
tags.putIfAbsent("instanceType", resolvedInstanceType);
if (runnerImage.imageVersion() != null) {
    tags.putIfAbsent("imageVersion", runnerImage.imageVersion());
}
```

That map goes to `Ec2ProvisioningService.runInstance`, which turns it into a `TagSpecification` on
`RunInstances` — **EC2 instance tags**. They never reach `benchmarkMetadata.tags`. The result tags
come from user-data:

```
--tag "imageVersion=${IMAGE_VERSION_ACTUAL}" \
--tag "instanceType=${INSTANCE_TYPE}"
```

This is the defect the commit message lists as one of the five found by running it, and CLAUDE.md
records it as an invariant:

> tagging the *instance* leaves every stored result with a null `imageVersion` and the comparison
> silently never fires

`UserDataScriptBuilderTest.passesEnvironmentTagsToTheRunnerNotJustToTheInstance` pins the correct
behaviour with a javadoc that says the opposite of this comment. A maintainer who trusts the
comment could delete the user-data `--tag` lines as redundant and reintroduce the fixed bug — one
that fails silently, in the database, with no error anywhere.

The EC2 tags themselves are harmless (console visibility, and the `baas-role` scoping
`RunnerRole`'s `TerminateInstances` condition depends on). Only the comment is wrong.

**Fixed.** The comment now says these are instance tags, states plainly that
`ResultsQueryService` reads `benchmarkMetadata.tags` and that only the runner's own `--tag`
options populate it, records that instance-tagging is how results ended up with a null
`imageVersion` once already, and points at
`UserDataScriptBuilderTest#passesEnvironmentTagsToTheRunnerNotJustToTheInstance`.

## 6. P4 — `|| echo absent` never fires; a missing tool records as an empty string · Low

`UserDataScriptBuilder`, lines 67 and 69:

```bash
PERF_VERSION=$(json_escape "$(perf --version 2>/dev/null | head -1 || echo absent)")
ASYNC_PROFILER_VERSION=$(json_escape "$(/app/async-profiler/bin/asprof --version 2>&1 | head -1 || echo absent)")
```

A pipeline's exit status is that of its **last** command. `head -1` succeeds on empty input, so the
`||` branch is unreachable without `pipefail`. Reproduced:

```
$ PERF_VERSION=$(nonexistent-cmd --version 2>/dev/null | head -1 || echo absent)
$ echo "[$PERF_VERSION]"
[]
```

So a runner whose AMI is missing `perf` records `"perfVersion": ""` — indistinguishable from a
capture that failed for some other reason, in the artifact whose entire job is saying what the run
measured on. Given that baking `perf` is one of this change's headline fixes, "absent" is
information worth not losing.

`set -o pipefail` is **not** the fix here — the no-`set -e` invariant exists for good reason and
`pipefail` would interact with it. Capture first, then default:

```bash
PERF_VERSION=$(perf --version 2>/dev/null | head -1)
PERF_VERSION=$(json_escape "${PERF_VERSION:-absent}")
```

## 7. P7 — "comparison group" assumes grouping `baas results` does not do · Low

The delta spec, design.md and the commit message all describe `baas results` flagging *a
comparison group* whose rows disagree. `ResultsQueryService.environmentWarning` computes distinct
`imageVersion` / `instanceType` values across **every row returned by the query** — there is no
grouping and no `exclude_from_results` filter anywhere in the results path (open finding **D1**,
which `dynamodb-results-store` will implement).

The behaviour over-reports rather than under-reports, which is the safe direction, but two
unrelated benchmarks that happen to straddle an image bump will trigger it. Once any account has
built a second image, the warning fires on almost every `baas results` invocation, and a warning
that always fires stops being read.

Not re-raising D1. Flagging that this change's artifacts read as though it were already fixed.
When D1 lands, `environmentWarning` should move inside the group.

## 8. P8 — `tasks.md` §11.3 contradicts the delta spec it verifies · Low

Delta spec, *The image version is bumped by hand and validated before building*:

> **Scenario: Unchanged content rebuilds without a version bump** — WHEN `baas admin build-image`
> runs twice with no edit between runs — THEN the second run reuses the registered recipe version
> and completes

`tasks.md` §11.3:

> Confirm a second `build-image` without a version bump **fails the preflight and starts no
> build**

The code implements the spec: `preflightVersion` throws only when the registered document
*differs*, and `CloudFormationService.updateStackParameters` swallows "No updates are to be
performed". Task 11.3 as worded describes the *edited-content* case, which is the scenario above
it. Both are marked `[x]`, so whichever was actually run, one of the two records is wrong.

**Fix.** Reword 11.3 to "confirm an edited tool version without an `imageVersion` bump fails the
preflight", and add the unchanged-content case if it was not exercised.

## 9. P9 — the change widened open finding S5 without updating it · Low

S5 (*user-data built by concatenation, one value of eleven escaped*) is the next item in the
walkthrough. `UserDataScriptBuilder.build` now interpolates **thirteen** values into single-quoted
shell assignments, still escaping only `BENCHMARK_PARAMETERS`. The two new ones are `imageVersion`
and `amiId`, and `imageVersion` is read back from the `baas-image-version` **EC2 tag** — a value
that can be edited in the console, not a compile-time constant.

Not a new finding; S5's entry and count should be updated so the walkthrough sees the current
surface when it reaches it.

## 10. P10 — a corrupt manifest stack-traces where a missing one is handled · Low

`EnvDiffSubcommand.fetch` handles an absent `environment.json` with a clear, actionable message.
`EnvironmentManifest.parse` throws `UncheckedIOException` on malformed JSON, and nothing catches
it — the user gets a Java stack trace. The delta spec covers only the missing case
(*"Missing manifest fails clearly"*), so this is a gap in both the spec and the code. Since the
manifest is assembled by shell heredoc on a remote host, malformed is a state worth handling.

## 11. P11 — `benchmarkType` in the manifest makes every cross-type diff report a difference · Low

`environment.json` carries `"benchmarkType"`, which is a property of the *run*, not of the
environment, and `EnvironmentManifest.diff` compares every key. `baas env diff` between a `jmh`
run and a `jmh-with-async` run therefore always reports a difference even when the two sat on
byte-identical environments — noise in a command whose output is meant to be read as
"these are/aren't comparable".

## 12. P12 — kernel tunables silently default to `0`/`null` on a mistyped key · Low

`RunnerImageDefinition.Kernel` uses `int` primitives under
`@JsonIgnoreProperties(ignoreUnknown = true)`. A mistyped key in `runner-image.yaml` (say
`perf_event_paranoid` instead of `perfEventParanoid`) is dropped silently and the field defaults:

- `perfEventParanoid` → `0`, *more* permissive than the declared `1`, and the bake succeeds
- `transparentHugepages` → `null` → `transparent_hugepage=null` on the kernel command line, which
  the kernel ignores without complaint

The delta spec says these values "SHALL NOT be left to the base image's defaults". A typo does
exactly that, and every downstream artifact — component, manifest, `env diff` — reports the wrong
value as though it were chosen. Boxed types plus a null check at load, or a required-field
assertion in `RunnerImageRenderer`, would make it loud.

## 13. P13 — the async-profiler install path is hardcoded a third time · Low

`runner-image.yaml` declares `installPath: /app/async-profiler`; `RunnerImageDefinition` derives
`libraryPath()` from it; `UserDataScriptBuilder` line 69 hardcodes
`/app/async-profiler/bin/asprof`. Changing the declared path leaves that capture pointing at
nothing — and because of P4, the result is an empty string rather than a failure. The invariant
that `installPath` must track `JmhWithAsyncProfilerSubcommand`'s default is documented; this third
copy is not.

## 14. P14 — `build()` polls without an upper bound · Low

`ImageBuilderService.build` loops `while (true)` on a 30-second poll. `SUCCEEDED` holds
`AVAILABLE`; `FAILED` holds `FAILED`, `CANCELLED`, `DELETED`. `DEPRECATED` and
`UNKNOWN_TO_SDK_VERSION` are in neither, so either would spin forever. It is an interactive
command and Ctrl+C works, so the practical impact is small, but there is no deadline.

## 15. P15 — `deployer-policy.json` is partition-hardcoded; the template beside it is not · Low

`cf-template-core.yaml` uses `!Sub arn:${AWS::Partition}:...` throughout, including the two managed
policy ARNs this change added. The statements this change added to `deployer-policy.json`
(`ImageBuilder`, `ImageBuilderRead`, `ImageBuilderServiceLinkedRole`, `SsmRunnerAmiPointer`, the
widened `PassRolesToServices`) use a literal `arn:aws:`, as does the rest of that file, and
`DeployerPolicyRenderer` substitutes only `${ACCOUNT_ID}` / `${REGION}` / `${PREFIX}`.

Consistency only — no partition other than `aws` is a stated target. Noted because commit
`743a3b7` is titled "partition-agnostic ARNs" and the two files now disagree about what that
means.

---

## What held up

Worth recording, because most of this change is careful work and a findings list reads as though
it were not:

- **Every CLAUDE.md user-data invariant survives the rewrite.** No `set -e`; the watchdog still
  starts immediately after `INSTANCE_ID` resolves; nothing is installed; the manifest is written
  and uploaded before the benchmark; every manifest value is captured into a variable first and the
  heredoc body is pure `${VAR}` references; the run still executes from `/app`; the mongo URI still
  comes from SSM. `UserDataScriptBuilderTest` pins the ordering ones rather than trusting them.
- **The ordering that costs money is right and tested.** Pointer written before deregister, snapshot
  IDs collected before deregister, preflight before the stack update, AMI resolution before the
  Maven build and before any upload. `repointsBeforeRetiringTheImageItReplaces` and
  `failedBuildLeavesThePointerAndThePreviousImageUntouched` are the two tests worth having.
- **The five defects the commit says were found by running it are each pinned by a test**, including
  the `byName` one, which no amount of reading would have caught.
- **`manifestIsValidJsonForARepresentativeCapture`** parses the heredoc body with a real JSON parser
  and asserts the body contains no `$(` — the right test for a file assembled by shell.
- **Quoting every manifest value except `schemaVersion`** means an empty capture yields `""` rather
  than invalid JSON. Deliberate and correct; P4 is about the *value*, not the syntax.
- **The build-instance role is genuinely minimal** — two managed policies plus `s3:PutObject` on
  `image-builds/*`, and a template test asserts the absence of DynamoDB, `ec2:RunInstances` and
  `iam:*`.
- **The `Locale.ROOT` fix is complete** — `printJson`, `printCsv` and the shared number formatter,
  with non-finite scores emitted as JSON `null`, and tests that set the locale explicitly.
- **Delta specs match the implementation** everywhere except the two wording problems in P7 and P8.
  The operator/deployer split, the `env` command's top-level placement, the credential resolution
  for each command, and the S3 layout additions all check out line by line.
