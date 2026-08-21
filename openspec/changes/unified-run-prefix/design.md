# Design — unified run prefix

## Context

A benchmark run currently has two names. `RunCommand.java:212-213` computes both from the same two
values:

```java
String requestId  = benchmarkType + "-" + timestamp;
String resultPath = resolvedBranch + "/" + benchmarkType + "/" + timestamp;
```

The first keys uploaded inputs at `runs/<requestId>/`, the second keys results at
`<branch>/<type>/<timestamp>/`. Nothing joins them in S3; only the stored measurement does, and only
when the run got far enough to write one.

The runner itself does not have this problem. `ApiCommonSharedOptions` takes `--request-id` as an
opaque string and defaults the result path to it — one run, one prefix. The split is entirely the
CLI's doing, imposed by the explicit `--result-path` it passes through `UserDataScriptBuilder:164`.

Two other things are wrong independently. The instance fetches its runner JAR from GitHub
`releases/latest` (`UserDataScriptBuilder:117-127`), so two runs a week apart can execute different
runner code — the same class of drift as the `yum update -y` that finding **A8** removed from this
exact script. And `expire-uploaded-benchmark-jars` (`cf-template-core.yaml:200-205`) expires
everything under `runs/` at 30 days, which becomes a data-loss trap the moment results move there.

`export-before-teardown` is blocked on the resulting shape. `brainstorm.md` holds the exploration
and the three alternatives weighed; this document records the decisions and the four questions that
brainstorm left open.

Constraints that bound every decision below: the product is a *comparison* between measurements, so
a change that silently alters the measured environment is worse than one that fails loudly; real
AWS spend per run; and the project's standing cost was zero until the runner AMI snapshot, so a new
standing cost is a change in kind.

## Goals / Non-Goals

**Goals:**

- One run is one S3 prefix, one identifier and one instant, agreed on by S3 and DynamoDB.
- The runner JAR a run executes is determined by the CLI that launched it, not by the calendar.
- A run that dies before writing any measurement is still identifiable from S3 alone.
- Existing runs remain readable and downloadable throughout, and afterwards.
- No new AWS resource, no new IAM statement, no new standing service.

**Non-Goals:**

- Re-keying DynamoDB. Existing `requestId`s and both key shapes stay exactly as stored.
- Re-measuring or re-attributing history. Files move; measurements do not change.
- `exec-single-benchmark.yml` and the GHA benchmark path — `gha-workflow-migration-to-dynamodb`
  owns that file and this change must not touch it.
- Packaging and distribution of the CLI (install script, Homebrew, jpackage). This change requires
  the CLI's *version* to be readable and its JAR to be a release asset; it does not build a
  distribution channel.
- Fixing the `@Param` sort-key collision surfaced during brainstorming. Pre-existing and live
  today; it gets filed in `docs/review/benchmark-runner-findings.md`, not fixed here.

## Decisions

### The CLI names the run once, from a single clock read

`baas run` generates the instant, embeds it in the run id, and passes the same value to the runner
as `--created-at`. The runner stores it as `createdAt` rather than reading its own clock, so the
id's timestamp and the sort key's timestamp are the same value rather than two values that happen
to be close.

This extends a decision the runner already made deliberately: `JmhRunResults.java:47-50` captures
one timestamp per run rather than one per result, because a per-result clock read would make two
results from the same run differ by a stray millisecond. Moving that single read from the instance
to the laptop is the same argument one hop further out, and it removes the instance's clock from the
record entirely.

Millisecond precision is forced, not chosen: `StoredMeasurement.java:46` truncates `createdAt` to
milliseconds, so a second-precision id would be a lossy view of the value it claims to be.

`--created-at` defaults to `Instant.now()` when absent, so direct runner invocation keeps working.
The trade is explicit and stated rather than hidden: the runner trusts a caller-supplied clock, and
a caller passing a bogus instant misdates its own results.

*Rejected:* keeping the instance's clock and accepting two nearby timestamps. It leaves the id and
the measurement disagreeing about when a run happened, which is exactly the ambiguity this change
exists to remove, and it keeps a second clock in a system whose output is compared across time.

### The run id is time-ordered with random entropy, and nothing parses it

`runId` = `<UTC instant, ISO basic, milliseconds>Z-<8 hex>`, e.g. `20260820T174432812Z-a3f9c21b`.
Fixed 28 characters, from an alphabet that cannot contain `#` or `/`.

The leading component makes an S3 listing chronological — S3 orders lexicographically and offers no
sort-by-date for prefixes — so "yesterday's run" is findable without going through DynamoDB. That
matters most for a run that died before writing any measurement, where DynamoDB holds nothing at
all. The trailing entropy closes finding **A9**'s collision by removing the question rather than
narrowing it.

Because nothing parses the id, its format is a readability convention, not a contract. That is what
makes normalisation unnecessary rather than merely deferred, and what lets CI mint its own id in
bash without drift risk.

Fixed width also removes truncation as a design question: `ResultsQueryService.java:101,109` widens
`REQUEST_ID` to 28 and drops `truncate(…, 17)`, which today lands on the shared `<type>-<date>`
prefix and renders distinct rows identically.

*Rejected — a self-describing composite* (`<project>-<type>-<timestamp>`, brainstorm Alternative A):
the id is the last `#`-separated field of every sort key (`ResultKeys.java:35-44`) and the partition
key of `requestId-index` (`:47`), so a `#` or `/` arriving from a project or branch name corrupts
key parsing. That turns normalisation from a nicety into a permanent correctness requirement, and it
still leaves collisions merely narrowed. At ~45 characters it also overflows the results column.

*Rejected — purely opaque* (brainstorm Alternative B): an unordered, unreadable listing, and a
crashed run's prefix becomes an anonymous string at exactly the moment someone is debugging under
pressure.

### Entropy is `SecureRandom`, and the CLI does not pre-check the GSI

Four bytes from `java.security.SecureRandom`, hex-encoded. `UUID.randomUUID()`'s first block would
also work but arrives via a heavier construct whose remaining 120 bits are discarded.

No pre-flight query against `requestId-index`. A collision needs two runs in the same millisecond
*and* the same 32-bit draw; the query costs a round trip on every single run to defend against that,
and the failure it would prevent is one overwritten measurement, not a corrupted table.

### `RunId` and `RunLayout` live in `baas-model`, beside `ResultKeys`

`ResultKeys` already states the reason in its own header comment — it is the only place a DynamoDB
key is constructed, because hand-encoding one elsewhere is how a query silently returns zero rows
instead of failing to compile. An S3 run prefix is the same kind of value with the same failure mode:
a hand-built path does not fail to compile, it just points at nothing.

`RunLayout` owns `runs/<project>/<runId>` and the `input/` sub-prefix. The runner's `--request-id`
default becomes `RunId.generate()` instead of `Instant.now().toString()`, and its result-path default
becomes `RunLayout.of(project, runId)` instead of the bare request id. `--result-path` stays as an
override, which is what keeps every historical invocation working.

### The bucket is keyed `runs/<project>/<runId>/`, with inputs under `input/`

```
baas-<prefix>/
  runs/<project>/<runId>/
      input/benchmark.jar          uploaded by the CLI before launch
      input/runner.jar             only when --runner-jar overrides
      environment.json  packages.txt  <type>-output.txt  jmh-result.json
      <fully.qualified.Benchmark-Mode>/…   logs/*.log
      cloud-init-output.log  run-status
  releases/<version>/benchmark-runner.jar
  image-builds/…
```

`<project>` stays in the path even though the id alone is unique. The bucket is genuinely
multi-project — its name derives from a hash of the caller ARN, so one identity holds one bucket for
every project it measures — and the segment is the one piece of identity a failed run keeps when it
has written no tags. It partitions the bucket the way `ResultKeys.partitionKey` partitions the table.

The `input/` split keeps a 30 MB JAR out of the result listing and gives any consumer a prefix to
skip rather than filenames to special-case.

`releases/`, not `runner/`: a prefix one character from `runs/` would need disambiguating in every
listing and every sentence of the documentation, and `releases/` also states that the artifact is
immutable.

`ci/` is retired. A CI run is a run.

### The `runs/` expiry rule is deleted, not re-scoped

`expire-uploaded-benchmark-jars` rests on the premise that everything under `runs/` is re-creatable
from source. The unified layout falsifies that premise directly, and `export-before-teardown` had
already argued the opposite: the uploaded JAR is the only copy of what a measurement actually ran.

*Rejected — tag inputs and filter the rule on the tag* (brainstorm Alternative C): it needs an
`s3:PutObjectTagging` grant on the operator and CI roles and introduces silent failure in both
directions — an untagged input lives forever, a mistagged *result* is deleted at 30 days, server-side,
with nothing positioned to notice. Paying complexity for a mechanism whose failure mode is silent
data loss is worse than paying storage.

Cost of the deletion: ~30 MB per run, roughly $0.07/month per hundred runs. Argued rather than
absorbed, because the project's standing cost was zero until the AMI snapshot.

### Bucket versioning is suspended

With write-once results and an id that closes the collision class, versioning's only remaining value
is forensic. Suspending it ends noncurrent accumulation, which pushes back against the growth the
deleted expiry rule allows, and lets an export bundle claim completeness.

The consequence is stated rather than implied: **there is no server-side recovery from an
overwrite.** That is acceptable only because the entropy suffix removes the mechanism that could
cause one.

Both noncurrent lifecycle rules stay. Suspension is not the same as never having versioned — existing
noncurrent versions and delete markers persist until reaped — so `S3UploadService.deleteAllObjects`
keeps its version-walking loop either way. `CoreTemplateTest`'s pinned facts change with the
template.

### The runner JAR is pinned to the CLI's own version and fetched on the laptop

One copy per version at `releases/<version>/benchmark-runner.jar`, uploaded if absent. The instance
always `aws s3 cp`s it; the `curl api.github.com` branch and `releases/latest` both go away.

When the slot is empty, the CLI fetches the asset from the GitHub release tagged with **its own**
version and uploads it. Three things follow from moving the fetch to the laptop: the instance's
`api.github.com` egress disappears, which `private-runner-network` would otherwise have to tunnel
through a NAT or VPC endpoint; the source repository becomes a config key rather than a string
hardcoded in a shell script, closing finding **A7**; and the download lands somewhere a checksum can
be verified.

No new IAM. `RunnerRole` (`cf-template-core.yaml:278-285`) and the operator policy's
`S3WorkingBucketAccess` are both bucket-wide, so `releases/*` is already covered. The CI role's
`ci/*` `PutObject` grant (`cf-template-ci.yaml:116`) becomes dead and is removed; its `runs/*` grant
already covers the new layout.

### The release asset is verified against a published `.sha256`

`release.yml` publishes `benchmark-runner.jar.sha256` alongside the JAR; the CLI fetches both and
verifies before uploading to `releases/<version>/`. A mismatch is a hard failure with nothing
uploaded.

This closes CLAUDE.md's accepted *Runner JAR integrity* risk rather than merely relocating it. The
risk was accepted when verification was impossible — the download happened on a throwaway instance
mid-boot. Moving it to the laptop is new information that changes the trade-off, which is the bar
for reopening an accepted risk.

*Rejected — the GitHub API's asset digest field:* it couples the CLI to the presence and shape of an
API field, is still subject to unauthenticated rate limits, and verifies the transfer rather than
the build. *Rejected — nothing beyond TLS:* it lets the one moment verification becomes possible
pass without taking it, while `release.yml` is already open for editing.

### `release.yml` rebuilds after `versions:set`, and publishes the CLI JAR

This is a prerequisite, not a side effect. Today's workflow builds with `mvn -B verify`, then
semantic-release runs `versions:set` in `prepare` and `mvn deploy` in `publish` — but
`@semantic-release/github` publishes assets in that same `publish` phase, from `target/`, which
still holds the artifact built *before* `versions:set`. So the uploaded JAR carries
`0.0.0-semantically-released` inside while the Maven-deployed one is correctly versioned.

Three changes: `prepareCmd` gains a `package` step after `versions:set` (and the `sha256sum` that
feeds the decision above); `baas-cli/target/baas-cli.jar` joins the asset list; and `baas-cli`'s
shade `ManifestResourceTransformer` gains `Implementation-Version`, which currently carries only
`mainClass` (`baas-cli/pom.xml:146-148`). The CLI then reads its version from the manifest rather
than from `META-INF/maven/**/pom.properties`.

### A reactor build hard-fails without `--runner-jar`

A CLI whose version is the `0.0.0-semantically-released` placeholder cannot name a release, so it
fails before the Maven build and before any upload unless `--runner-jar` is passed. This matches the
existing no-fallback stance on the runner AMI — two provisioning paths produce silently incomparable
results — and keeps `releases/` holding released artifacts only. The development override stays
per-run at `input/runner.jar`.

The premise: the CLI is meant to be a standalone tool on PATH. Today `README.md:30` says the
opposite and every `baas` in existence is an alias onto a reactor build. That is the current state,
not the target; the reactor checkout becomes the developer's explicit special case rather than the
implicit default.

### Branch becomes a tag, and the `"unknown"` project fallback becomes a hard failure

What the opaque id stops carrying, tags carry. `TagKeys` already defines `PROJECT`, `TYPE` and
`COMMIT`; **`branch` is the only new key**, caller-supplied alongside project and commit rather than
machine-observed. Branch is currently stored nowhere — it survives solely as a path segment.

`ApiCommonSharedOptions.getProject()` (`:77-83`) falls back to `"unknown"` rather than failing. This
is not hypothetical: `exec-single-benchmark.yml` has no project input and CI passes no `project`
tag, so CI is writing `RESULT#unknown` today — and under this layout would also write into
`runs/unknown/`. The fallback becomes a hard failure.

`environment.json` gains `project`, `branch`, `requestId` and `createdAt`. It is written *before*
the benchmark, so it is what a run that dies early leaves behind, and it is what buys back the
self-description given up by making the id opaque.

### Project derivation resolves the main repository, not the worktree

`projectFromToplevel` uses `git rev-parse --show-toplevel`, which in a worktree returns the worktree
directory — so a run launched from `.claude/worktrees/ddb-phase3` is attributed to project
`ddb-phase3`. `--git-common-dir` resolves to the main repository in both cases and changes nothing
for an ordinary clone.

`--project` stays optional with a git-derived default. A required flag would be friction on every
run to defend against a case the code now hard-fails on; what needed hardening was the silent path,
not the convenient one.

### `baas download` accepts a run id or a literal prefix

The id is what `baas run` prints and what `baas results` shows, so it is what a user has in hand. An
argument matching the run-id shape resolves through `requestId-index` to the stored `resultPath`; an
argument that looks like a prefix is treated as one. That second branch is what keeps every
historical path resolving after the migration, and it is why nothing about the stored attributes
needs a compatibility shim.

### CI mints its own id and passes the same instant as `--created-at`

`e2e-cloud-test.yml`'s two benchmark jobs become two runs with two prefixes, which ends two jobs
writing `run-status` to one key (`:96`, `:119`). CI mints the id in bash (`date -u` plus
`openssl rand -hex 4`) and passes `--project` explicitly.

It must pass the *same* instant as `--created-at`. Otherwise the one-instant property holds for
`baas run` and quietly fails for CI, whose id would disagree with its own `createdAt` by the length
of the queue wait — a discrepancy that would only ever be noticed by someone comparing a prefix name
against a stored timestamp months later.

### History is migrated by a throwaway script, run after the cutover

Order is **cutover, then migrate**. New runs write the new layout immediately; old runs stay
resolvable the entire time, because `baas download` reads each item's stored `resultPath` rather
than reconstructing it. One idempotent pass at the end, with no second sweep.

*Rejected — migrate first:* every run launched between the migration and the deploy writes the old
shape, requiring a second pass, and the window is however long review and deploy take.

Mechanism is a `scripts/` one-shot, `--dry-run` first, deleted in the same PR that reports it ran —
the precedent set by the archived `dynamodb-results-store` change. *Rejected — a `baas admin`
subcommand:* a permanent CLI surface, with its own IAM question, for a job that runs once.

The migration server-side copies each run's tree to `runs/<project>/<existing-requestId>/` and
rewrites `resultPath`, `resultJsonKey`, `environmentJsonKey` and the profiler-output prefix with
`UpdateItem`. **Keys are never touched.**

Each run keeps its existing `requestId` rather than being given a new-shape id. The id is inside the
sort key and *is* the GSI partition key, so minting new ids for history would mean deleting and
re-putting every item — re-creating history rather than relocating its files — for cosmetic
uniformity. Two id shapes coexisting is the accepted cost.

Most historical *input* JARs are already gone, since the 30-day expiry has been live throughout. So
"every run prefix contains its input" is true going forward and patchy behind. No stored attribute
references them; every result survives.

### `RESULT#unknown` items migrate to `runs/unknown/` with their keys untouched

They are treated as the project they were stored under. One rule for all history, no special case,
and no key rewriting — re-attribution would require `DeleteItem` + `PutItem` per row, since `pk` is
part of the key, breaking the migration's own rule.

It is also the honest outcome: nobody recorded what those runs measured, and 36 of them are CI
fixture runs against `fake-jmh-benchmarks`. The hard failure above is what stops new ones appearing.

## Risks / Trade-offs

- **A caller passes a bogus `--created-at` and misdates its own results.** → The CLI and CI both
  pass the value they minted the id from, so the two agree by construction; a direct runner
  invocation that omits the flag gets `Instant.now()`. The failure is self-inflicted and local to
  one run.
- **No server-side recovery from an overwrite once versioning is suspended.** → Accepted only
  because the entropy suffix removes the mechanism that produced overwrites. The two changes ship
  together; suspending versioning without the id change would be a regression.
- **The bucket grows without bound.** → ~30 MB per run, cents per year at this cadence. The
  deliberate trade for run prefixes that stay complete. Suspended versioning offsets part of it.
- **A corrupted object at `releases/<version>/` never self-repairs**, because upload-if-absent will
  not overwrite it. → The checksum is verified before upload, so a corrupt object implies corruption
  after the fact; the fix is deleting the key so the next run re-seeds it. Documented, not automated.
- **The migration is destructive if it goes wrong** — it rewrites stored path attributes. → Copy
  before rewrite, `--dry-run` first printing every copy and every `UpdateItem`, idempotent so a
  partial run can simply be re-run, and keys never touched so the worst case is a stale path
  attribute rather than a lost row.
- **Two id shapes coexist forever.** → Accepted. Nothing parses the id, so no code needs to
  distinguish them; only a human reading a listing sees the difference.
- **`release.yml` must land and produce a real release before any pinned run can work.** → The
  reactor hard-fail makes this loud rather than silent: a developer without a release gets a clear
  failure naming `--runner-jar`, not a mystery run against an unexpected JAR.
- **This change and `gha-workflow-migration-to-dynamodb` both edit CI.** → Disjoint files by
  agreement: this change owns `e2e-cloud-test.yml`, that one owns `exec-single-benchmark.yml`.
  Sequencing matters only for merge order, not for correctness.

## Migration Plan

1. **`release.yml` first.** Rebuild after `versions:set`, publish `baas-cli.jar` and
   `benchmark-runner.jar.sha256`, stamp `Implementation-Version`. Nothing downstream works without
   a release that carries its own version.
2. **Code cutover.** `RunId`/`RunLayout` in `baas-model`; `baas run` mints the id and passes
   `--created-at`; user-data drops the GitHub branch; the runner gains `--created-at` and loses the
   `"unknown"` fallback; `baas download` accepts an id.
3. **Infrastructure.** `baas admin setup` to apply suspended versioning and the removed lifecycle
   rule; redeploy the CI stack for the dropped `ci/*` grant.
4. **First real run** against the new layout, verified by hand — no automated test drives
   `baas run` end to end.
5. **Migration dry-run**, reviewed by eye, then the real pass.
6. **Delete the script**, update CLAUDE.md's S3 layout section and `docs/review` status for A7/A9,
   and file the `@Param` collision.

**Rollback.** Steps 1-3 are ordinary reverts; a reverted CLI writes the old layout again and reads
old paths, because the stored `resultPath` is what `baas download` follows. Step 5 is the only
one-way door: the copies are additive and can be deleted, but the rewritten path attributes would
need a reverse pass. That is why the dry-run is mandatory and why the migration runs last.

## Resolved Questions

| Question from `brainstorm.md` | Resolution | Evidence |
|---|---|---|
| Ordering of cutover and migration; script or subcommand | Cutover first, then a `scripts/` one-shot deleted after use | `baas download` follows the stored `resultPath`, so old runs stay resolvable through the whole window — a single idempotent pass suffices. Migrating first needs a second pass for the deploy window. Precedent: archived `dynamodb-results-store` |
| What the downloaded release asset is verified against | A `.sha256` published as a second release asset | `release.yml` is already in scope, so the checksum is produced by the same build that produces the JAR. Verifies the build, not just the transfer, and depends on no API field |
| What happens to existing `RESULT#unknown` items | Migrated to `runs/unknown/`, `pk` untouched | `pk` is part of the key, so re-attribution means `DeleteItem` + `PutItem`, breaking the migration's own "keys are never touched" rule. 36 of the rows are CI fixture runs, so the real project is a guess |
| Entropy source; GSI pre-check | `SecureRandom`, 4 bytes; no pre-check | A collision needs the same millisecond *and* the same 32-bit draw; a pre-flight query costs a round trip on every run to prevent one overwritten measurement |

## Open Questions

None blocking implementation. Two things are deliberately left as stated limits rather than
questions: the bucket's unbounded growth, and a corrupted `releases/<version>/` object requiring
manual deletion to re-seed.
