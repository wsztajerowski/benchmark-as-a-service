# Design

## Context

See proposal.md — Why. Current state:
- `TeardownCommand` runs four steps in order: the live-runner gate (`pending`/`running` since F3),
  the confirmation, the optional bucket deletion, then `deleteStack`, which waits for completion.
- `build-image` writes everything image-related outside the stack:
  - the SSM pointer `/<prefix>/runner/ami-id`, through `ImageBuilderService.publish`;
  - the AMI and its snapshot, through Image Builder distribution;
  - one Image Builder image record per build, `image/<prefix>-recipe-runner/<version>/<build>`.

  On a rebuild, `publish` retires the replaced AMI through `ImageBuilderService.retire`: it collects
  snapshot ids, deregisters, then deletes each snapshot, warning on a snapshot failure. It does not
  touch image records, so they accumulate: the live installation holds three, two pointing at
  deregistered AMIs.
- `SsmService.deleteParameter` already treats "not found" as success.
- The deployer policy already grants every call this needs (proposal.md — Impact).

## Goals / Non-Goals

**Goals:**
- After a teardown, nothing `build-image` created remains for that installation.
- A partial failure never leaves the stack half-deleted because of the image.

**Non-Goals:**
- Changing `build-image`'s own leftover image records.
- Guarding a `build-image` that is running concurrently with a teardown. CLAUDE.md records
  concurrent image operations as deliberately unguarded, and this change does not alter that.
- Any change to what the image contains.

**Comparability:** none. This changes only how an installation is removed. No runner, image content,
user-data or measurement path is touched, so existing and future results compare exactly as before.

## Decisions

### Retirement runs after the stack is deleted, not before

Teardown's order becomes: gate → confirmation → bucket (with `--delete-bucket`) → stack → image.

If the stack deletion fails (`DELETE_FAILED`, a permission error, an interrupted waiter), the
installation keeps a usable image. The operator fixes the cause and retries, and nothing has to be
rebuilt. Retiring first would leave a stack that still exists but has no image, so every
`baas run` fails until a 10-minute rebuild, all for a teardown that did not happen.

*Rejected — retire first:* its only advantage is that a failed retirement would stop the
teardown before the stack goes. But a retirement failure is a cost leak, not a correctness problem,
and stopping the teardown over it would be the wrong trade.

### Retirement never fails the command

By the time retirement runs, the stack is gone. Exiting non-zero would tell the operator the
teardown failed when the expensive, consequential part succeeded. Every retirement step therefore
reports its failure as a warning naming the leftover, with the `aws` command that removes it, and
the command exits 0. This is the same stance `ImageBuilderService.retireQuietly` takes after a
successful build.

### The pointer is deleted even when the AMI could not be retired

The pointer is what makes a later setup inherit the image (the *Inherited* state in the analysis).
An AMI left behind without a pointer is only a cost leak, and the warning names it. So the order is:
1. read the pointer;
2. retire its AMI, if it still exists;
3. delete the pointer, unconditionally;
4. delete the image records.

A pointer naming an AMI that is already gone is not an error: `describeImage` already returns empty
for `InvalidAMIID.*` (fix F5). Retirement skips straight to deleting the pointer.

### Image records are deleted too, found by the recipe name

Records cost nothing. But they are the last trace of the installation, and they shape a future
installation with the same prefix: after the 2026-10-01 wipe, the first rebuild was numbered
`1.2.0/2` because a record survived. Teardown lists the image versions named
`<prefix>-recipe-runner` (owner: self), lists each version's build versions, and deletes each
build version. That's three paginated read calls and one delete per record. The read calls are
covered by `imagebuilder:List*`; the deletes by `imagebuilder:Delete*` on `${PREFIX}-*`, which
matches the record ARNs.

*Rejected — leave the records:* that would leave U2 half open for no saving: the calls are cheap
and already permitted.

### The prefix comes from the stack being torn down

`TeardownCommand` already derives the bucket and table names from `resolvedStack`, which is
`--stack-name` or the configured prefix. The pointer path and the recipe name follow the same rule:
`/<resolvedStack>/runner/ami-id` and `<resolvedStack>-recipe-runner`. Teardown already calls the
stack the installation, so this needs no new resolution.

### One service method, built from existing pieces

`ImageBuilderService` gains a teardown-time `retireInstallation(parameterName, recipeName)` that
composes the existing `readPointer` and `retire`, `SsmService.deleteParameter`, and the new record
deletion, catching per step. `retire` itself is unchanged, so `build-image`'s retirement behaviour
does not move. Tests use the existing `FakeEc2`/`FakeSsm`/`FakeImageBuilder`, extended with record
listing and deletion.

## Risks / Trade-offs

- [A `build-image` running during teardown publishes a new pointer after retirement] → The new AMI
  survives the teardown. This is the same unguarded concurrency CLAUDE.md already accepts for two
  builds. Teardown's warning lists what is left, and a second teardown removes it.
- [Deleting an image record whose recipe the stack already deleted is rejected by Image Builder] →
  It would surface as a per-record warning, not a failure. Task 1.2 checks it against a real
  teardown before relying on it.
- [An operator expected teardown to keep the image] → Decided against (2026-10-01): rebuilding takes
  about ten minutes from the definition in git. The closing notice says what was retired.

## Migration Plan

Nothing to migrate. The first teardown after release retires the current image. To roll back,
revert the change; a teardown then leaves the image behind again, as it does today.

## Open Questions

None.

## Resolved Questions

- *Does the deployer policy need new grants?* No. `ec2:DeregisterImage`, `ec2:DeleteSnapshot`,
  `ssm:DeleteParameter` on the exact pointer path, `imagebuilder:Delete*` on `${PREFIX}-*` and
  `imagebuilder:List*` are all present. Checked against `infra/deployer-policy.json` on 2026-10-02.
- *Are Image Builder records left by teardown real?* Yes. After the 2026-10-01 teardown, three
  `3q7i7s65-runner` records and the current installation's own survived, and
  `list-image-build-versions` shows three records for `baas-381492019823-recipe-runner/1.2.0` today.
