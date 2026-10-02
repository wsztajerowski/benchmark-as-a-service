# Proposal

## Why

`baas admin teardown` deletes the core stack but not what `baas admin build-image` created outside
it:
- the AMI pointer `/<prefix>/runner/ami-id`
- the runner AMI and its 30 GB snapshot
- the Image Builder image records

The paid lifecycle test of 2026-10-01 found all of them still present after a teardown, plus an AMI
the old caller-ARN installation had left seven weeks earlier. The snapshot keeps costing money
(~$0.20/month) after BaaS is gone. And once the retained results table is cleared, a later
`baas admin setup` reaches a state where `baas run` works **without any `build-image`**, launching
an inherited AMI whose component the deleted stack took with it. Closes finding **U1** and most of
**U2** (`docs/analysis/cli-usage-analysis.md`, rows 16 and 20 of
`docs/review/baas-cli-findings.md`).

## What Changes

- **Teardown always retires the installation's runner image**, after the stack is deleted:
  - the AMI the pointer names is deregistered and its snapshots deleted, through the
    `ImageBuilderService.retire` that `build-image` already uses;
  - the pointer parameter is deleted;
  - every Image Builder image record of the installation's recipe is deleted.

  There is no flag and no opt-out, unlike `--delete-bucket`: the image is rebuilt from
  `infra/runner-image.yaml` in ~10 minutes, and git is its archive. That was decided on 2026-10-01.
- **Retirement never fails a teardown.** A missing pointer, a pointer naming an AMI that is already
  gone, or a failed delete is reported as a warning naming what is left and the `aws` command that
  removes it. The stack is already gone by then, and leaving an image behind is the state
  teardown produces today.
- **`--stack-name` retires that installation's image**: the pointer path is derived from the stack
  being torn down, not from this machine's configured prefix.
- Teardown's closing notice no longer implies the image survives. The retained table, and the
  bucket without `--delete-bucket`, are still named.
- The live-runner gate's requirement is corrected to say what the code has done since fix F3: a
  `pending` runner blocks teardown, as well as a `running` one.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `core-stack-provisioning`:
  - "Teardown safety gates" now covers `pending` runners, and says teardown retires the image.
  - A new requirement covers what retirement removes, its order after the stack deletion, and that
    it never fails the teardown.

## Impact

- **Code:** `TeardownCommand` (the retirement step, closing notices);
  `ImageBuilderService` (a teardown-time retire that also deletes the pointer and the image
  records); tests for both.
- **IAM:** none. The deployer policy already grants `ec2:DeregisterImage`, `ec2:DeleteSnapshot`,
  `ssm:DeleteParameter` on the exact pointer path, `imagebuilder:Delete*` on `${PREFIX}-*`, and
  `imagebuilder:List*`. The rendered policy's size is unchanged.
- **Cost:** removes the one standing cost a torn-down installation still had, the ~$0.20/month
  snapshot. No new resource and no new call during runs.
- **Docs:**
  - CLAUDE.md (the runner-image invariants, and the *Runner AMI snapshot cost* row of *Accepted
    risks*);
  - `docs/diagrams/baas-teardown.mmd` and `baas-lifecycle.mmd`;
  - `docs/analysis/cli-usage-analysis.md` (U1, U2, and the *Residue* / *Inherited* states of the
    installation graph);
  - `docs/review/baas-cli-findings.md`.
- **Deliberately not changed:**
  - The **results table** is still retained, with no flag to delete it, and the **bucket** is still
    retained unless `--delete-bucket` is passed. Deleting both on every teardown, behind a second
    typed confirmation that recommends `baas admin export`, is decided (2026-10-02) but belongs to
    `export-before-teardown`. It ships only together with the export command, so the destructive
    behaviour never exists without its safety net.
  - The **live-runner gate and the confirmation still come first**, so nothing is retired while a
    run is in flight or before the stack name is retyped.
  - **"Exactly one image, rebuilt in place"** and "repoint before retire" are untouched: teardown
    retires an image that nothing will launch again.
  - **`build-image` still leaves its own image records behind** after retiring an AMI. They cost
    nothing, and changing the build path is a separate concern.
  - **No new IAM grant.**
