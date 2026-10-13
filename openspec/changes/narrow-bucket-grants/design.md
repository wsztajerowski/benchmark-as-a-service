# Design

## Context

See proposal.md — Why. The bucket has two top-level prefixes (`jobs/`, `releases/`) plus
`image-builds/`, which the build instance writes under its own role and this change does not touch.

What each identity actually does to the bucket, read from the code on 2026-10-13:

| Identity | Calls | Keys |
|---|---|---|
| Runner, user-data | `aws s3 cp` download ×2, upload of `environment.json`, `packages.txt`, `cloud-init-output.log` | runner JAR at `releases/<v>/…` or `jobs/…/input/runner.jar`; benchmark JAR and every upload under `jobs/<project>/<jobId>/` |
| Runner, JVM (`S3StorageService`) | `PutObject` only | under the job's result prefix, `jobs/…` |
| Operator (`baas run`, `jobs download`, `jobs show/diff`) | `HeadObject`, `PutObject`, `GetObject`, `ListObjectsV2` | `releases/` (seed), `jobs/` |
| Deployer (teardown) | `ListObjectVersions`, `DeleteObjects` | whole bucket |

Nothing under the runner role lists or deletes, and nothing under the operator role deletes. The
only delete in the code is `S3UploadService.deleteAllObjects`, called from `TeardownCommand` with
deployer credentials.

## Goals / Non-Goals

**Goals:**
- Each role's S3 grant is the table above, and no more.
- `releases/<version>/benchmark-runner.jar` is written at most once by construction, not by the
  ordering of two calls.

**Non-Goals:**
- Isolating one job's objects from another job's runner (the S7 remainder; see Risks).
- Making `releases/` immutable against the deployer or the operator. Both are trusted, and the
  operator's write is the seed itself.
- Recovering from a corrupted seed. That stays "delete the key, the next job re-seeds it".

## Decisions

### The runner's grant is an allow-list of two prefixes, not the bucket minus `releases/`

Two statements: `s3:PutObject` + `s3:GetObject` on `<bucket>/jobs/*`, and `s3:GetObject` on
`<bucket>/releases/*`. Rejected: keeping the bucket-wide allow and adding a `Deny` on `releases/*`
writes — it leaves the next top-level prefix anyone adds writable by default, and a deny is one more
statement to reason about. The allow-list fails safe: a new prefix the runner should write to fails
the first job loudly.

`aws s3 cp` uploads above its multipart threshold (8 MB — profiling artifacts and logs can pass it)
authorise `CreateMultipartUpload`/`UploadPart`/`CompleteMultipartUpload` as `s3:PutObject`, so no
multipart action needs naming. `s3:AbortMultipartUpload` was never granted and is not added.

### The runner loses `s3:ListBucket`

Nothing on the instance lists. Its only effect was diagnostic: S3 answers a `GetObject` on a missing
key with 404 to a caller holding `ListBucket` and with 403 otherwise. The instance's only reads are of
keys the CLI wrote seconds before launch, so the case barely arises, and when it does
`cloud-init-output.log` still shows which `aws s3 cp` failed. Rejected: keeping it for the clearer
error — a listing right exposes every project's key names to benchmark code for one message.

### The operator keeps bucket-wide `PutObject`/`GetObject`/`ListBucket` and loses only `DeleteObject`

Its writes are the seed (`releases/`) and job inputs (`jobs/`), its reads and listings span both, so
narrowing by prefix would name every prefix there is and buy nothing. Rejected: scoping the operator's
`releases/*` write to non-existing objects — IAM has no condition for it, which is why the seed itself
carries the guard (next decision). `infra/operator-policy.json` changes with the template;
`OperatorPolicyDriftTest` already compares the action sets and needs no change.

### The seed is `PutObject` with `If-None-Match: *`, and a refusal means "already seeded"

`RunnerJarResolver.resolve` keeps its `HeadObject` first: it is what spares every later job the
GitHub download. Only the put changes. S3 answers a conditional put whose key now exists with
`412 PreconditionFailed`, and one that loses to a concurrent in-flight conditional put with
`409 ConditionalRequestConflict`. Both mean another launch is seeding the same verified release
asset, so both are logged and treated like the head finding the object: the key is returned and the
launch proceeds. Every other error still fails the launch, as now.

The put is extracted into a package-private `seed(S3Client, bucket, key, bytes)` returning whether it
wrote, so it can be exercised against LocalStack without the GitHub fetch.

Rejected:
- **Dropping the `HeadObject`** and relying on the conditional put alone: every job would download
  the runner JAR and its checksum from GitHub to discover the slot is full.
- **A bucket policy denying writes to existing `releases/*` objects:** S3 bucket policies cannot
  condition on whether the object exists, and a blanket deny on `releases/*` would also refuse the
  seed. Left out as mechanism (decided 2026-10-04).
- **Re-enabling versioning** to make an overwrite recoverable: reverses a settled invariant
  (versioning `Suspended`) to protect a write that the grant now forbids.

### A 409 that hides a failed concurrent seed is left to fail the job

If the other launch's upload ultimately fails, this launch proceeds with no object at the key, and
its instance's download is denied. The job fails with its boot log uploaded, and the next job re-seeds.
Rejected: retrying the head after a 409 — it needs a wait and a bound for a case that takes two first
jobs of a new version in the same seconds *and* a failing upload, and its failure mode is already a
recorded, diagnosable failed job.

## Risks / Trade-offs

- [A runner can still overwrite another job's objects under `jobs/*`] → Accepted as the S7 remainder.
  A per-job scope would need the instance's credentials tagged with its job id, which an instance
  profile cannot carry; same trust position as *Runners can terminate each other* in CLAUDE.md's
  Accepted risks. Recorded on S7 in `open-findings.md` rather than closed.
- [A deployment not re-set-up keeps the old grants] → Harmless: the new CLI's conditional put needs
  only `PutObject`, which both grant sets hold. The protection arrives with the next
  `baas admin deployment setup`, which the `feat`/`fix` commit body names so it reaches the release notes.
- [LocalStack 4.14 may ignore `If-None-Match`] → The IT asserts the second write is refused and the
  first object survives. If LocalStack does not honour the header, the IT fails rather than passing
  vacuously, and the refusal handling is then covered by a unit test with a stubbed `S3Client`
  throwing 412/409, with the real behaviour checked live (task in tasks.md).
- [A grant too narrow surfaces only on a real instance] → The live check runs a JMH job with
  async-profiler (largest uploads, `logs/async-output/`) and a JCStress job on the re-set-up
  `baas-development` deployment before merge.

**Comparability:** none affected. The runner, the image, user-data and every key a job writes are
unchanged; only who may write where changes.

## Migration Plan

1. Merge into `next-release`; it ships with the next release.
2. Each deployment runs `baas admin deployment setup` once. The stack update replaces two inline
   policies on `RunnerRole` and `OperatorRole` — no resource replacement, no `GroupDescription` edit,
   no in-flight job affected beyond its role's next policy evaluation (an in-flight runner keeps
   writing under `jobs/`, which stays allowed).
3. Rollback: re-run setup from the previous CLI release; the old template restores the old grants.
