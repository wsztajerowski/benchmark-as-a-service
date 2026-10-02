# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 With the deployer profile, delete the two live image records of
  `baas-381492019823-recipe-runner/1.2.0` whose AMIs `build-image` already deregistered (build
  versions `/1` and `/2`). Verify both succeed and `list-image-build-versions` then shows only `/3`.
  That confirms `imagebuilder:DeleteImage` is permitted on the installation's records, and that a
  record pointing at a deregistered AMI can be deleted.
- [x] 1.2 Confirm the deployer is allowed `imagebuilder:ListImages` and `ListImageBuildVersions` by
  running both with the deployer profile. Verify neither returns AccessDenied.

## 2. Retirement in ImageBuilderService

- [x] 2.1 Extend `FakeImageBuilder` with image versions and build versions (listing, with
  pagination, and deletion), and `FakeSsm` with `deleteParameter`. Verify the existing
  `ImageBuilderServiceTest` still passes.
- [x] 2.2 Add `retireInstallation(parameterName, recipeName)`, following design.md:
  1. read the pointer;
  2. retire its AMI when it still exists;
  3. delete the pointer unconditionally;
  4. delete every build version of every image version named after the recipe.

  Each step catches its own failure, logs it as a warning naming the leftover and the `aws`
  command that removes it, and continues. Verify with tests for:
  - a full retirement (the pointer, AMI, snapshot and every record are gone);
  - no pointer (no error; the records are still deleted);
  - a pointer naming a deregistered AMI (the pointer is deleted);
  - a failing deregister (warned, and the pointer and records are still deleted);
  - records spread over more than one page.
- [x] 2.3 Verify `ImageBuilderService.retire` and `publish` are unchanged:
  `git diff main -- …ImageBuilderService.java` shows only additions, and the build-path tests pass.

## 3. Teardown wiring

- [x] 3.1 Call `retireInstallation("/" + resolvedStack + "/runner/ami-id",
  resolvedStack + "-recipe-runner")` in `TeardownCommand`, after `deleteStack` returns and never
  before the gates. Verify with a test that the paths derive from `--stack-name` when it is given,
  and from the configured prefix otherwise.
- [x] 3.2 Make the closing notices say the image was retired, still naming the retained table (and
  the bucket when kept). Verify the existing `TeardownNoticeTest`, extended to cover the new text.

## 4. Documentation and trackers

- [ ] 4.1 Update CLAUDE.md:
  - the runner-image invariants, to say teardown retires the image;
  - the *Runner AMI snapshot cost* row of *Accepted risks*, to say a torn-down installation has
    no standing cost.

  Verify by `grep -n "teardown" CLAUDE.md` showing the new statements.
- [ ] 4.2 Update `docs/diagrams/baas-teardown.mmd` and `baas-lifecycle.mmd`: retirement after the
  stack deletion, and the Retire block no longer listing image leftovers. Verify both render with
  `npx @mermaid-js/mermaid-cli`.
- [ ] 4.3 Update `docs/analysis/cli-usage-analysis.md`:
  - U1 → Fixed;
  - U2's image part → Fixed;
  - in the installation state graph, *Residue* no longer lists image items, and the *Inherited*
    state is removed;
  - U10 → "won't fix (decided 2026-10-02: `admin image` stays as is)".

  Verify the three state graphs still render.
- [ ] 4.4 Mark rows 16 (U1) and 20 (U2, image part) in `docs/review/baas-cli-findings.md` Fixed, and
  re-run `openspec validate --specs`.

## 5. End-to-end verification — MANUAL, paid

No automated test covers teardown against AWS. This costs one image build and one runner launch.

- [ ] 5.1 Back up `~/.baas/config.yaml` outside the repository, and verify the copy is
  byte-identical.
- [ ] 5.2 Run `baas admin teardown --yes --delete-bucket` with the change built. Verify:
  - the pointer parameter is gone (`aws ssm get-parameter` returns ParameterNotFound);
  - `describe-images --owners self` lists no BaaS AMI;
  - `describe-snapshots --owner-ids self` lists no BaaS snapshot;
  - `list-images --owner Self` lists no `baas-381492019823-recipe-runner` record. This is the
    check for the design's open risk: a record deleted after its recipe is gone;
  - the command exits 0.
- [ ] 5.3 Delete the retained table by hand, run `baas admin setup` (with the federation flags),
  then `baas run`. Verify the run is refused for want of a runner image: the *Inherited* state
  can no longer be reached.
- [ ] 5.4 Run `baas admin build-image`, then one `baas run jmh` against `fake-jmh-benchmarks`.
  Verify the run completes, leaving the installation as it was before 5.2. Then delete the backup
  from 5.1, recording which assertion showed the config survived.

## 6. Verification record

- [ ] 6.1 Run `/opsx:verify` and record the result in `verify.md` in this change directory:
  a requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any
  deviation from design.md or this task list.
