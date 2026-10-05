# ADR 0002 — The region is chosen once, at setup, and found everywhere else

- **Status:** Accepted — implemented and verified live (PR #77, 2026-10-05)
- **Date accepted:** 2026-10-04
- **Closes findings:** U5, U18, U22, U23 (CLI usage analysis, 2026-10-01 → 2026-10-04)

## Context

An installation lives in exactly one region: its stack, its results table, its AMI pointer and its
runner subnet are all regional. Three earlier states got this wrong in different directions:

- **U5.** The region came only from `~/.baas/config.yaml`, defaulting to `eu-central-1`, so a CI job
  whose fresh config named no region was right only because the installation happened to be there.
  Fixed (F9) by falling back to `AWS_REGION` before the default.
- **U22.** `config set --region` re-aimed a machine at any region unchecked — the same defect class
  as the removed `config set --prefix`. Moved away from its installation, `baas run` reported "No
  runner image is published … Build one: `baas admin build-image`", advice that sends a deployer to
  build in a region with no stack. `config sync` checked the stack, but only in whatever region it
  already resolved, so in the wrong one it reported the operator role's `AccessDenied` (U18).
- **U23.** `baas admin setup --region B` on an account installed in A found the bucket (names are
  global), took any non-404 for "exists", and blamed a *retained* bucket, advising
  `aws s3 rb --force` — on the live installation's bucket, holding every run's artifacts. With the
  real, region-scoped deployer it never even got that far: the preflight ran first and printed a
  deployer policy for region B, inviting a grant for a second installation that cannot exist.

## Decision

1. **`baas admin setup --region` is the only place a region is chosen.** Setup records it.
2. **Everything else finds it from the bucket.** The bucket carries the prefix's name and bucket
   names are global, so `HeadBucket` answers from a client in any region: the right region returns
   it in the response, a wrong one is refused with 301 or 400 that still carries
   `x-amz-bucket-region`, and a missing bucket is a plain 404. Probed live on 2026-10-04 from
   `eu-central-1`, `us-east-1` and `us-west-2` against the account's bucket. It needs only
   `s3:ListBucket`, which both the operator and the deployer already hold — no IAM change.
3. **`config sync --name` finds the region, verifies the stack there, and stores it** — CI included,
   so a job follows the installation rather than its `AWS_REGION`, and a machine re-pointed at a
   rebuilt installation is re-adopted with the same command.
4. **`config set --region` is removed**, as `--prefix` was. Released as a minor, by decision, as F14
   was.
5. **`admin setup` checks the bucket's region before its preflight.** The stack creates the bucket in
   its own region, so a bucket elsewhere means the installation is elsewhere; no stack lookup is
   needed. It refuses naming that region and `--region`, with no delete advice. Only a bucket in the
   target region gets the retained-bucket recovery text.

## Consequences

- No user ever types a region after setup; there is no state in which a machine's region disagrees
  with its installation's.
- Moving an installation is a rebuild in the new region (export, teardown, setup), after which every
  machine re-runs `config sync --name`. There is no in-place move, and none is wanted.
- `AWS_REGION` and the `eu-central-1` default now matter only before any installation is adopted:
  where `setup` and `deployer-policy` deploy or render, and where `config sync` starts looking.
- F9's "resolved, never stored" rule is partly reversed: a synced config stores the region. What it
  stores is the installation's own region, never the environment's.

## Known edges, accepted

- A bucket a teardown retained in region A, with setup aimed at B, is reported as "the installation
  lives in A". Setup aimed at A then says the bucket is a retained leftover.
- `config sync --name` of a name whose bucket exists in *another* account (the namespace is global)
  finds a region, then no stack, and reports a retained bucket. The name is the one `baas admin
  setup` printed, so this needs a typo that happens to hit someone else's bucket.
