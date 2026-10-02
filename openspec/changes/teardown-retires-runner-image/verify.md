# Verification — teardown-retires-runner-image

Verified 2026-10-02 at `8e789d6` plus the task-5 run, against proposal.md, design.md, tasks.md and
`specs/core-stack-provisioning/spec.md`. Paths below are relative to
`baas-cli/src/{main,test}/java/pl/wsztajerowski/baas/`.

## Summary

| Dimension | Status |
|---|---|
| Completeness | 16/16 tasks (this record is 6.1); 2/2 requirements implemented |
| Correctness | 9/9 scenarios covered: 6 by unit tests, 3 only by the manual run of section 5 |
| Coherence | Design followed; one log line reads wrongly in the teardown context (W1) |

No critical issues. Five warnings below.

## Requirement → code → test

| Requirement / scenario | Code | Test | Gap |
|---|---|---|---|
| **Teardown safety gates** | | | |
| Abort when a run is in flight | `TeardownCommand.java:67` (gate 1) | — (pre-existing behaviour) | W2 |
| Abort when a run is still booting (`pending`) | `Ec2ProvisioningService.java:125` | Live only: 2026-10-01 the gate named a runner 6 s after launch | W2 |
| Both gates before anything is deleted | `TeardownCommand.java:67`, `:76`, before `:116` and `:125` | Live only: task 5.2 log order | W3 |
| Bucket retained unless explicitly deleted | `TeardownCommand` `deleteBucket` branch (unchanged) | — (pre-existing) | — |
| **Teardown retires the runner image** | `ImageBuilderService.retireInstallation` (`:253`), `deleteImageRecords` (`:293`); called at `TeardownCommand.java:125` after `deleteStack` at `:116` | | |
| A torn-down installation leaves no image behind | as above | `ImageBuilderServiceTest.retiringAnInstallationRemovesThePointerTheAmiItsSnapshotsAndEveryRecord`; live 5.2 (pointer `ParameterNotFound`, no AMI, no snapshot, no recipe record) | — |
| A later setup cannot inherit the image | pointer deleted at `:278` | Live 5.3: `baas run` refused "No runner image is published", nothing uploaded | W3 |
| Nothing to retire | `readPointer` empty → skip | `noPointerIsNotAnErrorAndTheRecordsStillGo` | — |
| The pointer names an AMI already gone | `describeImage(amiId).isPresent()` at `:265` | `aPointerNamingAnAmiThatIsAlreadyGoneIsStillDeleted` | — |
| A failed retirement is reported, not fatal | per-step `SdkException` catches; `TeardownCommand` warns and returns 0 | `aFailedDeregisterIsALeftoverAndThePointerAndRecordsStillGo`; `TeardownNoticeTest.theImageNoticesSayWhatWasRetiredOrWhatIsLeft` | W4 |
| Another installation's image | `resolveInstallation` (`:149`), `pointerPath`/`recipeName` (`:158`) | `TeardownNoticeTest.theImageRetiredIsTheTornDownInstallations`; the record-isolation assertion in the first retirement test | — |
| Records over several pages | SDK paginators | `recordsSpreadOverSeveralPagesAreAllDeleted` | — |

## Warnings

- **W1 — "Retiring replaced image" during a teardown.** `retire` logs "Retiring replaced image
  ami-…", which reads wrongly when nothing replaced it (task 5.2 log). It was left as is because
  task 2.3 required `retire` to stay unchanged. Fix: a neutral "Retiring image" in a later change;
  it touches `build-image`'s log only cosmetically.
- **W2 — the `pending` filter has no unit test.** The gate's state filter is checked only by a
  live run. `Ec2ProvisioningService.listRunningBenchmarkInstances` is untested because no fake
  covers `describeInstances` filters. Fix: a capturing EC2 double asserting the filter values,
  the same shape as `Ec2ProvisioningServiceTest`.
- **W3 — `TeardownCommand.call()`'s ordering is verified only manually.** Gates, then bucket,
  then stack, then image: no JVM test drives `call()`, since every step needs AWS. The same gap
  the CLAUDE.md note records for `RunCommand.call()`. Task 5 is the evidence: its log shows the
  order, and its residue checks show the effect.
- **W4 — a snapshot that fails to delete is logged but not returned as a leftover.** `retire`
  warns per snapshot, as before, but `retireInstallation`'s returned list does not include it, so
  teardown's closing notice can still say the snapshots were retired when one was not. The
  warning naming the snapshot does appear just above it. Fix together with W1 by having `retire`
  return its failures.
- **W5 — the old caller-ARN installation's Image Builder records remain.** Three
  `3q7i7s65-runner` image versions survive in the account: they are outside this deployer's
  `${PREFIX}-*` scope, the same reason that installation's component could not be deleted on
  2026-10-01. They are not this installation's and cost nothing; removing them needs an identity
  above the deployer.

## Deviations from design and tasks

- **Task 2.1:** the fake's name filter first matched `/image/<name>/`; real ARNs read
  `…:image/<name>/…`. Every record test failed until the fake was corrected. The service was right
  throughout, and an isolation assertion was added so the filter is now pinned in both directions.
- **Design open risk resolved:** Image Builder **does** allow deleting a record after its recipe
  was deleted with the stack (task 5.2).
- **Observed effect, not planned:** with the records gone, the rebuild in 5.4 was numbered
  `1.2.0/1` rather than continuing the old sequence, which is the numbering effect design.md gave
  as a reason to delete them.
- **Task 5.4 also confirmed U13 from PR #70 in a real run:** no "Terminating instance" message
  after a successful run, and `cloud-init-output.log` arrived (13 KB).

## Paid run (section 5), 2026-10-02

| Step | Result |
|---|---|
| 5.1 | `~/.baas/config.yaml` backed up outside the repository, byte-identical |
| 5.2 | `admin teardown --yes --delete-bucket` exit 0 in 68 s. Then: pointer `ParameterNotFound`; no AMI; no snapshot; no `baas-381492019823-recipe-runner` record; table `ACTIVE` (retained) |
| 5.3 | Table deleted by hand; `admin setup` with the federation flags created the stack (3.5 min); `baas run` refused before any upload, and the bucket stayed empty |
| 5.4 | `admin build-image` exit 0 in 9 min 10 s (`ami-0ae60d9d990dc00df`, build `1.2.0/1`); `baas run jmh` completed (`20261002T120522922Z-f9e0c166`); no live instances afterwards. Config identical to the backup, which was then deleted |

## Assessment

No critical issues; five warnings, none blocking. Ready for archive.
