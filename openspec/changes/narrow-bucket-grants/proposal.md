# Proposal

## Why

Both roles that touch the working bucket hold more of it than any of their code uses, and the excess
is reachable by code nobody reviews. `RunnerRole` runs an arbitrary benchmark JAR under
`PutObject`/`DeleteObject` on the whole bucket, so one job can replace the pinned runner every later
job of that version executes (S14) or delete every job's artifacts (S7). `OperatorRole`, which every
CI workflow federates into on `pull_request`, holds a bucket-wide `DeleteObject` that no command calls
(S15). Versioning is `Suspended`, so none of these is recoverable. Separately, the runner seed is a
head-then-put: two first jobs of a version both wrote the key on 2026-10-10, and "seeded once" held
only because both copies were the same verified bytes.

## What Changes

- `RunnerRole`'s S3 grant narrows to `s3:PutObject` + `s3:GetObject` on `jobs/*` and `s3:GetObject`
  on `releases/*`. `s3:DeleteObject` and `s3:ListBucket` go; nothing on the instance lists or deletes.
- `OperatorRole` loses `s3:DeleteObject`, in `cf-template-core.yaml` and in its reference copy
  `infra/operator-policy.json`. It keeps `PutObject`/`GetObject`/`ListBucket` on the whole bucket:
  it seeds `releases/` and uploads and downloads under `jobs/`.
- The runner-JAR seed becomes a conditional `PutObject` with `If-None-Match: *`. A lost race — the key
  appeared after the existence check — is the slot being seeded, not an error; the existing object is
  used, as on any later job.
- Existing deployments take the new grants on their next `baas admin deployment setup`. No migration,
  no CLI command or option changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `core-stack-provisioning`: *RunnerRole S3 policy matches the bucket name* becomes a prefix-scoped
  grant with no delete and no list; *Operator role permissions* gains that its S3 access excludes
  deletion.
- `runner-jar-distribution`: *The runner artifact slot is seeded once per version, and never silently
  replaced* gains that the write itself refuses to replace an object, so concurrent first jobs cannot
  both write it.

## Impact

- **Closes** S14 and S15. **Reduces** S7: what remains is a runner overwriting another job's objects
  under `jobs/*`, which has no per-job IAM scope — a runner's key would have to be bound to a session
  tag the instance profile cannot carry.
- **Code:** `infra/cf-template-core.yaml` (two policies), `infra/operator-policy.json`,
  `RunnerJarResolver`; tests in `CoreTemplateTest`, `OperatorPolicyDriftTest` (unchanged, keeps the two
  copies in step) and a LocalStack IT for the conditional seed.
- **Deployments:** a stack update replaces two inline policies — no resource replacement, no
  `GroupDescription` touched, no downtime. A deployment not re-set-up keeps the old grants and keeps
  working, since only permissions are removed and the seed code works under either.
- **Diagnostics:** without `s3:ListBucket`, a runner `GetObject` on a missing key answers 403, not 404.
  The only such reads are user-data's two `aws s3 cp` downloads, both of keys the CLI wrote just
  before launching.
- **Forecloses** fixing N3 (stranded `input/` after a failed reservation) by having the operator
  delete the upload; N3's other fix, reserving before uploading, is unaffected.
- **Cost:** none. No resource is added or removed.
- **Not changed:** the deployer's bucket rights (teardown's `deleteAllObjects` runs under them);
  `releases/<version>/` stays upload-if-absent and is never overwritten; versioning stays `Suspended`
  and no lifecycle rule is added under `jobs/`; no bucket policy is introduced — enforcing `releases/*`
  immutability in a bucket policy was considered and left out as extra mechanism; the runner's
  DynamoDB grants are untouched.
