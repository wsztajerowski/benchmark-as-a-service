# Proposal — unified run prefix

## Why

A benchmark run has no single name. Results land under `<branch>/<type>/<timestamp>/` and the run's
uploaded inputs under `runs/<requestId>/` — two trees, two identifiers, both computed from the same
two values (`RunCommand.java:212-213`). Separately, every instance pulls its runner JAR from GitHub
`releases/latest`, so two runs a week apart can execute different runner code under a tool whose
entire product is comparability.

`export-before-teardown` is blocked on this: it cannot freeze a bundle layout against a shape that
is about to move. Unifying now makes one run one prefix, one id and one instant; pins the runner to
the CLI that launched it; and deletes the instance's `api.github.com` egress before
`private-runner-network` has to tunnel it through a NAT or a VPC endpoint.

## What Changes

**Run identity**

- From: `requestId = <type>-<yyyyMMdd_HHmmss>`, and a separate `resultPath = <branch>/<type>/<ts>`.
- To: a single `runId` = `<UTC instant, ISO basic, milliseconds>-<8 hex>`, e.g.
  `20260820T174432812Z-a3f9c21b`. Fixed 28 characters, time-ordered, with entropy closing the
  collision.
- Reason: two identifiers derived from the same inputs is the split this change exists to end, and
  second granularity collides for two developers starting the same type in the same second.
- Impact: **BREAKING** for anything constructing a run path by hand. Nothing parses the id, so its
  shape stays a readability convention rather than a contract.

**One clock read**

- From: `createdAt` is read on the EC2 instance, independently of the id minted on the laptop.
- To: `baas run` reads the clock once, embeds that instant in the `runId`, and passes it to the
  runner as `--created-at`; the id's timestamp and the sort key's `createdAt` become the same value.
- Reason: extends the runner's existing per-run (not per-result) timestamp decision one hop
  further, and removes the instance's clock from the record entirely.
- Impact: non-breaking. `--created-at` defaults to `Instant.now()`, so direct runner invocation is
  unaffected. The runner now trusts a caller-supplied clock — stated, not hidden.

**Bucket layout**

- From: `<branch>/<type>/<timestamp>/` for results, `runs/<requestId>/` for inputs, `ci/` for CI.
- To: `runs/<project>/<runId>/` holding the whole run — inputs under `input/`, results at the
  prefix root. `releases/<version>/benchmark-runner.jar` for the pinned artifact. `ci/` retired,
  because a CI run is a run.
- Reason: the prefix becomes the run. `<project>` stays in the path because the bucket is genuinely
  multi-project and it is the one piece of identity a run that wrote no measurements keeps.
- Impact: **BREAKING** for the S3 layout. Existing runs are migrated rather than left behind.

**Runner JAR provenance**

- From: the instance curls `api.github.com/.../releases/latest` and `wget`s whatever it finds.
- To: the CLI uploads the asset for **its own version** to `releases/<version>/` if absent; the
  instance always `aws s3 cp`s it. The GitHub branch of the user-data script is deleted.
- Reason: `releases/latest` is an unpinned drift axis, the same class as the `yum update -y` that
  A8 removed from this exact script. Moving the fetch to the laptop is also what makes a checksum
  possible at all.
- Impact: **BREAKING** for reactor builds. A CLI whose version is the
  `0.0.0-semantically-released` placeholder hard-fails unless `--runner-jar` is passed, before the
  Maven build and before any upload — matching the existing no-fallback stance on the runner AMI.
  `--runner-jar` still writes per-run to `input/runner.jar`.

**Metadata as data, not as path**

- From: `branch` survives only as a path segment and is stored nowhere; the runner silently falls
  back to project `"unknown"`.
- To: `TagKeys.BRANCH` is added, caller-supplied alongside `project` and `commit`; the `"unknown"`
  fallback becomes a hard failure; `environment.json` gains `project`, `branch`, `requestId` and
  `createdAt`; project derivation becomes worktree-aware.
- Reason: what the opaque id stops carrying, tags and the manifest must carry — and
  `environment.json` is written before the benchmark, so it is what a run that dies early leaves.
- Impact: **BREAKING** for any caller relying on the `"unknown"` fallback. CI is writing
  `RESULT#unknown` today and must start passing `--project`.

**Infrastructure**

- The `expire-uploaded-benchmark-jars` lifecycle rule is **deleted**, not re-scoped. Its premise —
  everything under `runs/` is re-creatable from source — is exactly what the unified layout
  falsifies.
- Bucket versioning moves to `Suspended`. **BREAKING** in the sense that there is no longer any
  server-side recovery from an overwrite; consistent with the id change closing the collision class
  that could cause one. Both noncurrent rules stay, since existing versions persist until reaped.
- No new IAM. The CI role's now-dead `ci/*` `PutObject` grant is removed.

**CI**

- `e2e-cloud-test.yml`'s two benchmark jobs become two runs with two prefixes, ending two jobs
  writing `run-status` to one key. CI mints its own id in bash and passes the same instant as
  `--created-at`, or the one-instant property would hold for `baas run` and quietly fail for CI.
- `exec-single-benchmark.yml` is **not** touched here — `gha-workflow-migration-to-dynamodb` owns it.

**History**

- Existing runs are migrated into the new layout, each keeping **its existing `requestId`**. The
  migration server-side copies each tree and `UpdateItem`s `resultPath`, `resultJsonKey`,
  `environmentJsonKey` and the profiler-output prefix. DynamoDB keys are never touched.

## Capabilities

### New Capabilities

- `run-identity`: how a run is named and when it happened — `runId` format and generation, the
  single clock read, `--created-at` propagation to the runner, and `RunId`/`RunLayout` living in
  `baas-model` beside `ResultKeys` so a path is never hand-encoded elsewhere.
- `run-artifact-layout`: the S3 shape of a run — `runs/<project>/<runId>/` with `input/`,
  `releases/<version>/` for the runner artifact, the retirement of `ci/`, the removal of the
  `runs/` expiry rule, suspended versioning, and the one-shot migration of existing runs.
- `runner-jar-distribution`: where the runner JAR comes from — version-pinned to the CLI,
  upload-if-absent, fetched on the laptop rather than the instance, with the reactor build's
  hard-fail and the release-process prerequisites that make a CLI version readable at runtime.

### Modified Capabilities

- `results-store-schema`: `branch` joins the known-key tag vocabulary; the runner's silent
  `"unknown"` project fallback becomes a hard failure; `createdAt` becomes caller-supplied rather
  than read on the instance.
- `benchmark-results-query`: the download command accepts a run id, resolved through
  `requestId-index`, while still accepting a literal prefix so every historical path resolves.
- `cli-command-structure`: project derivation becomes worktree-aware (`--git-common-dir`, not
  `--show-toplevel`, so a run from `.claude/worktrees/x` is not attributed to project `x`), and the
  download argument's accepted shapes are stated.
- `core-stack-provisioning`: bucket versioning suspended, the `runs/` expiry rule removed, and the
  CI role's dead `ci/*` grant removed.
- `runner-image-provisioning`: `environment.json` gains `project`, `branch`, `requestId` and
  `createdAt`; `<result-path>` becomes the unified run prefix.

## Impact

**Code** — `baas-model` (`RunId`, `RunLayout`, `TagKeys.BRANCH`); `baas-cli` (`RunCommand` id and
upload paths, `UserDataScriptBuilder`'s JAR fetch and `--created-at`, `DownloadCommand`'s argument
resolution, `ResultsQueryService`'s `REQUEST_ID` column — fixed width removes the `truncate(…, 17)`
that today lands on the shared prefix and renders rows identically); `benchmark-runner`
(`ApiCommonSharedOptions` `--created-at`, the result-path default, the removed `"unknown"`
fallback).

**Infrastructure** — `infra/cf-template-core.yaml` (versioning, one lifecycle rule),
`infra/cf-template-ci.yaml` (one grant removed), `CoreTemplateTest`'s pinned facts.

**Release process** — this is a **prerequisite, not a side effect**. `baas-cli.jar` is not published
as a release asset today, and nothing stamps a version readable at runtime. `@semantic-release/github`
uploads assets *between* `versions:set` and `mvn deploy`, so the asset it currently picks up is the
pre-`versions:set` build carrying the placeholder. `release.yml` has to change first.

**CI** — `.github/workflows/e2e-cloud-test.yml` only. Must be sequenced against
`gha-workflow-migration-to-dynamodb`, which owns `exec-single-benchmark.yml`.

**Data** — a one-shot migration of existing S3 trees plus `UpdateItem` on the four path attributes.
Idempotent, dry-run first, following the Atlas migration precedent in the archived
`dynamodb-results-store` change. Most historical *input* JARs are already gone to the 30-day expiry,
so "every run prefix contains its input" is true going forward and patchy behind; no stored
attribute references them and no result is affected.

**Cost** — with the `runs/` expiry deleted the bucket only grows: ~30 MB per run, roughly
$0.07/month per hundred runs. This is a deliberate trade for run prefixes that stay complete, and it
is argued rather than absorbed because the project's standing cost was zero until the runner AMI
snapshot. Suspending versioning pushes the other way by ending noncurrent accumulation. No new AWS
resource, no new IAM, no new standing service.

**Comparability** — re-keying moves where bytes land and changes nothing about what is measured. Two
things actively improve it: the runner JAR stops coming from `releases/latest`, and the measurement
timestamp stops being read on the instance.

**Findings closed** — **A7** (runner-JAR discovery hardcodes the upstream repo: the source
repository becomes a config key and the instance stops calling GitHub at all) and **A9**
(`requestId` collides at second granularity: closed by the entropy suffix rather than by narrowing).

**Deliberately not changed** — existing `requestId`s and DynamoDB keys are never rewritten, so two
id shapes coexist and `baas download` on an old path keeps resolving; that is the accepted cost of
relocating history rather than re-creating it. `exec-single-benchmark.yml` stays broken here, as its
own change owns it. The runner keeps MongoDB as a standalone adapter. The accepted checksum risk is
narrowed in *location* — the download moves somewhere it can be verified — but verifying it is a
design question, not a decision made here.

**Filed elsewhere, not fixed here** — `JmhResult` does not parse JMH's `params` object, and the sort
key carries one timestamp per run, so two `@Param` variants of the same benchmark method produce an
identical sort key and the second `PutItem` silently overwrites the first. Live today, pre-existing,
belongs in `docs/review/benchmark-runner-findings.md`.

**Open, deferred to design** — migration ordering versus cutover and whether it is a `scripts/`
one-shot or a `baas admin` subcommand; what the release asset is verified against; what happens to
the existing `RESULT#unknown` items; and the entropy source.
