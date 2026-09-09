# Brainstorm — unified run prefix

## Design Summary

One run is one prefix, one id, one instant. `baas run` mints a globally unique run id and reads the
clock exactly once; both travel downstream unchanged, so S3 and DynamoDB agree on what a run is
called and when it happened. Everything *descriptive* — project, branch, benchmark type, commit —
is stored as data on the measurement rather than encoded into a path.

```
baas-<prefix>/
  runs/<project>/<runId>/
      input/benchmark.jar          uploaded by the CLI before launch
      input/runner.jar             only when --runner-jar overrides (dev builds)
      environment.json             written before the benchmark, survives a crash
      packages.txt
      jmh-output.txt | jmh-profiler-output.txt | jmh-with-async-output.txt | jcstress-output.txt
      jmh-result.json
      <fully.qualified.Benchmark-Mode>/…    profiler artifacts
      logs/*.log
      cloud-init-output.log
      run-status
  releases/<version>/benchmark-runner.jar
  image-builds/…
```

The run id is `20260820T174432812Z-a3f9c21b`: the run's UTC instant at millisecond precision, then
eight hex characters of entropy. Fixed width, 28 characters, drawn from an alphabet that cannot
contain `#` or `/`.

**The premise the design rests on.** The CLI is built to be distributed as a standalone
command-line tool — installed on PATH, invoked from any project directory, the way `grep` is.
Today `README.md:30` says the opposite ("no packaged binary yet — no install script, no Homebrew
tap"), and every `baas` in existence is an alias onto a reactor build. That is treated here as the
current state, not the target. A released CLI knows its own version, which is what lets it pin a
matching runner; a CLI that lives elsewhere must derive the project from where the *user* is
standing. A reactor checkout becomes the developer's special case.

**Comparability.** Re-keying moves where bytes land and changes nothing about what is measured.
Two things actively improve it: the runner JAR stops coming from `releases/latest`, removing a
drift axis nobody was tracking; and the measurement timestamp stops being read on the EC2
instance, removing its clock from the record entirely.

## What this supersedes from `assumptions.md`

That file was written before this session and several of its "Decided 2026-08-20" rows did not
survive contact with the code. It is kept as the record of what was believed; this brainstorm is
authoritative where they disagree.

| | `assumptions.md` | Decided here |
|---|---|---|
| Request id | `<branch>-<type>-<timestamp>` | `<timestamp>-<random>`, opaque |
| Normalisation | load-bearing, must be specified | unnecessary — nothing parses the id and its alphabet is fixed |
| Layout | `runs/<requestId>/` | `runs/<project>/<runId>/` |
| Runner JAR slot | `runner/<version>/` | `releases/<version>/` |
| History | nothing moves | migrated into the new layout |
| Lifecycle rule | open question | dropped entirely |
| Versioning | open question | suspended |
| Runner changes | "nothing in `benchmark-runner` has to change" | two changes: `--created-at`, and deriving the result path |

## Alternatives Considered

### Alternative A: a self-describing composite id

- **Approach**: `<project>-<type>-<timestamp>` (an evolution of `assumptions.md`'s
  `<branch>-<type>-<timestamp>`), millisecond UTC, with normalisation folding every character
  outside `[A-Za-z0-9._-]` to a dash.
- **Pros**: the bucket reads like an index — a listing tells you project, type and time with no
  lookup anywhere, and sorts chronologically. A failed run that wrote no measurements is still
  fully identified by its prefix name. Ids in logs explain themselves.
- **Cons**: normalisation becomes a correctness requirement rather than a nicety, because
  `requestId` is the last `#`-separated field of every sort key (`ResultKeys.java:44`) and the
  partition key of `requestId-index` (`:47`) — a `#` or `/` arriving from a branch or project name
  corrupts key parsing. Collisions are narrowed but never closed. The id repeats the project name
  inside a folder already named after it. At ~45 characters it overflows the `REQUEST_ID` column,
  which already truncates at 17 (`ResultsQueryService.java:101,109`), and truncation lands on the
  *shared* prefix so rows render identically.
- **Why not chosen**: it buys legibility with a permanent parsing hazard and leaves A9 open. The
  legibility it buys is recoverable more cheaply — see the agreed approach.

### Alternative B: a purely opaque id

- **Approach**: eight or more random characters, no structure at all. The table is the index; the
  bucket is storage.
- **Pros**: shortest to read, type and paste; fits the results column with no display change;
  closes every collision class; identity stops depending on meaning, so renaming a branch or a
  project never invalidates an id.
- **Cons**: an S3 listing becomes unordered and unreadable — S3 orders lexicographically and has no
  sort-by-date for prefixes, so "yesterday's jmh run" is only findable through DynamoDB. Worse, a
  run that dies before producing measurements writes S3 artifacts and *no items*, so its tags do
  not exist; its prefix would be an anonymous string at exactly the moment someone is debugging
  under pressure.
- **Why not chosen**: the failure case is the case that matters. A record of identity that only
  exists when the run succeeded is not identity.

### Alternative C: minimal re-key — keep two trees, tag the inputs

- **Approach**: leave results at `<branch>/<type>/<timestamp>/` and inputs at `runs/<requestId>/`,
  tag uploaded JARs `baas-artifact=input`, and re-filter the lifecycle rule on the tag so the
  30-day expiry survives.
- **Pros**: by far the smallest diff. No migration, no id change, no runner change, no release
  process work. It solves the one problem `export-before-teardown` actually tripped over.
- **Cons**: keeps a run split across two unrelated trees with two identifiers derived from the same
  two values (`RunCommand.java:212-213`), which is the thing this change exists to end. Needs an
  `s3:PutObjectTagging` grant on the operator and CI roles, and introduces a silent failure mode in
  both directions: an untagged input lives forever, a mistagged result is deleted at 30 days.
- **Why not chosen**: it preserves the split while adding a mechanism whose failure mode is silent
  data loss. Paying complexity to keep a structure we do not want is the worst of the three.

## Agreed Approach

**A time-ordered random id, one prefix per run, and metadata as data** — Alternative B's opacity
with Alternative A's navigability, which is the pattern ULID and KSUID exist for.

The id's leading component is the very same instant the measurement stores as `createdAt`, so the
bucket lists chronologically and every prefix says *when* even for a run that wrote nothing. The
trailing entropy closes the collisions that no naming structure can. Because nothing parses the id,
its format is a readability convention rather than a contract — which is what makes normalisation
unnecessary rather than merely deferred, and what lets a caller mint its own id in bash without any
risk of drift.

What the id no longer carries, tags carry. `TagKeys` already defines `PROJECT`, `TYPE` and
`COMMIT`, with `TYPE` machine-observed; **`branch` is the only new tag**. The metadata migration is
one entry in a vocabulary that already exists, not a redesign.

## Key Decisions

**Bucket layout**

- `runs/<project>/<runId>/` holds one run entirely — inputs under `input/`, results at the prefix
  root where the runner already writes them. The `input/` split keeps a 30 MB JAR out of the
  result listing and gives any consumer a prefix to skip rather than filenames to special-case.
- `<project>` stays in the path even though the id alone is unique. The bucket is genuinely
  multi-project — its name derives from a hash of the caller ARN, so one identity holds one bucket
  for every project it measures — and the segment is the one piece of identity a failed run keeps
  when it has written no tags. It also partitions the bucket the way `ResultKeys.partitionKey`
  partitions the table.
- `releases/<version>/benchmark-runner.jar`, not `runner/<version>/`. A prefix one character from
  `runs/` would need disambiguating in every listing and every sentence of the docs; `releases/`
  also states what the artifact is and that it is immutable.
- The `expire-uploaded-benchmark-jars` lifecycle rule (`cf-template-core.yaml:200-205`) is deleted
  rather than re-scoped. Its premise — that everything under `runs/` is re-creatable from source —
  is exactly what the unified layout falsifies, and `export-before-teardown` had already argued the
  opposite: the uploaded JAR is the only copy of what a measurement actually ran. Growth is
  ~30 MB per run, roughly $0.07/month per hundred runs.
- `ci/` is retired. A CI run is a run.

**Identity and time**

- `runId` = `<UTC instant, ISO basic, milliseconds>-<8 hex>`, e.g. `20260820T174432812Z-a3f9c21b`.
  Fixed 28 characters.
- **The CLI reads the clock once.** The instant is generated in `baas run`, embedded in the id, and
  passed to the runner as `--created-at`, which stores it as `createdAt` instead of reading its own
  clock. The id's timestamp and the sort key's timestamp become the same value.
- This extends a decision the runner already made deliberately — `JmhRunResults.java:47-50` captures
  one timestamp per run, not one per result, because "a per-result clock read would make two results
  from the same run differ by a stray millisecond". This moves that single read from the instance to
  the laptop.
- Millisecond precision is forced by that choice, not chosen for it: `StoredMeasurement.java:46`
  truncates `createdAt` to milliseconds, so a second-precision id would be a lossy view of the value
  it claims to be rather than the same value.
- `--created-at` defaults to `Instant.now()` when absent, so direct invocation of the runner keeps
  working. The trade is explicit: the runner trusts a caller-supplied clock.
- `RunId` (generation) and `RunLayout` (`runs/<project>/<runId>`) live in `baas-model` beside
  `ResultKeys`, for the reason that file already states — *"the only place a DynamoDB key is
  constructed. Encoding a key by hand anywhere else is how a query silently returns zero rows
  instead of failing to compile."* The runner's `--request-id` default uses `RunId` instead of
  `Instant.now().toString()`, and its result-path default becomes `RunLayout.of(project, runId)`
  instead of the bare request id. `--result-path` remains an override.
- The id being fixed-width removes truncation as a design question: `ResultsQueryService` widens
  `REQUEST_ID` to 28 and drops `truncate(…, 17)` entirely.

**Metadata**

- `TagKeys.BRANCH` is added, caller-supplied alongside `project` and `commit` rather than
  machine-observed. Branch is currently stored *nowhere* — `buildRunnerTags` records project, commit
  and type only, so it survives solely as a path segment today.
- The runner's silent `"unknown"` project fallback (`ApiCommonSharedOptions.java:77-83`) becomes a
  hard failure. It is not hypothetical: `exec-single-benchmark.yml` has no project input and CI
  passes no `project` tag, so CI runs are writing `RESULT#unknown` today — and under this layout
  they would also write into `runs/unknown/`.
- Project derivation becomes worktree-aware. `projectFromToplevel` uses `git rev-parse
  --show-toplevel`, which in a worktree returns the worktree directory, so a run launched from
  `.claude/worktrees/ddb-phase3` is attributed to project `ddb-phase3`. `--git-common-dir` resolves
  to the main repository in both cases and changes nothing for an ordinary clone.
- `--project` stays optional with a git-derived default. A required flag would be friction on every
  run to defend against a case the code already hard-fails on; what needed hardening was the silent
  path, not the convenient one.
- `environment.json` gains `project`, `branch`, `requestId` and `createdAt`. It is written before
  the benchmark, so it is what a run that dies early leaves behind — and it is what buys back the
  self-description given up by making the id opaque.

**Runner JAR and the release**

- One copy per version at `releases/<version>/benchmark-runner.jar`, uploaded if absent. The
  instance always `aws s3 cp`s it; the `curl api.github.com` branch and `releases/latest` both go
  away (`UserDataScriptBuilder.java:117-127`).
- When the slot is empty, the CLI fetches the asset from the GitHub release **tagged with its own
  version** and uploads it. The fetch moves from the EC2 instance to the laptop, which deletes the
  instance egress `private-runner-network` would otherwise have to tunnel and puts the download
  where a checksum can be verified.
- A CLI whose version is the `0.0.0-semantically-released` placeholder — i.e. any reactor build —
  hard-fails unless `--runner-jar` is passed, before the Maven build and before any upload. This
  matches the existing no-fallback stance (`CLAUDE.md:85`) and keeps `releases/` holding released
  artifacts only. The dev override stays per-run at `input/runner.jar`.
- **`release.yml` has to change first.** `baas-cli.jar` is not published as a release asset at all
  today, and the version has to be readable at runtime (nothing stamps `Implementation-Version`;
  only `META-INF/maven/.../pom.properties` carries a version). The Maven-deployed artifact is
  correctly versioned, because semantic-release runs `versions:set` in `prepare` before
  `mvn deploy` in `publish` — but `@semantic-release/github` uploads assets *between* those two, so
  the asset it picks up is the one built by the initial `mvn verify`, with the placeholder inside.
  The asset must come from the post-`versions:set` build.

**Infrastructure**

- Versioning is set to `Suspended` (`cf-template-core.yaml:183-184`). Both noncurrent rules stay:
  existing noncurrent versions and delete markers persist until reaped, so suspension is not the
  same as never having versioned, and `S3UploadService.deleteAllObjects` keeps its version-walking
  loop either way. `CoreTemplateTest`'s pinned facts change.
- The consequence is stated rather than implied: with versioning suspended there is no server-side
  recovery from an overwrite. That is consistent with the id change closing the collision class
  that could cause one.
- No new IAM. `RunnerRole` already holds bucket-wide access (`cf-template-core.yaml:284-285`), which
  covers `releases/*`. The CI role's `ci/*` `PutObject` grant (`cf-template-ci.yaml:116`) becomes
  dead and is removed; its `runs/*` grant already covers the new layout.

**History**

- Existing runs are migrated rather than left in place. This reverses `assumptions.md`. It became
  practical once `<project>` entered the path, because the mapping is fully derivable: project is
  the item's partition key.
- Each run keeps **its existing `requestId`**, landing at `runs/<project>/<existing-id>/`. The id is
  inside the sort key and *is* the GSI partition key, so minting new-shape ids for history would
  mean deleting and re-putting every item — re-creating history rather than relocating its files —
  for cosmetic uniformity. Two id shapes coexisting is the accepted cost.
- The migration server-side copies each tree and rewrites `resultPath`, `resultJsonKey`,
  `environmentJsonKey` and the profiler-output prefix with `UpdateItem`. Keys are never touched.
  Idempotent, dry-run first, following the Atlas migration precedent in the archived
  `dynamodb-results-store` change.
- Most historical *input* JARs are already gone — the 30-day expiry has been live throughout — so
  "every run prefix contains its input" is true going forward and patchy behind. No stored attribute
  references them; results are unaffected and all survive.

**CI**

- `e2e-cloud-test.yml`'s two benchmark jobs become two runs with two prefixes, which ends two jobs
  writing `run-status` to one key (`:96`, `:119`). The 2.8 MB fixture JAR is uploaded into each run's
  `input/`.
- CI mints ids in bash (`date -u` plus `openssl rand -hex 4`) and passes `--project` explicitly.
  There is no drift risk in minting it there, because nothing parses the id. It must also pass the
  *same* instant as `--created-at`, or the one-instant property holds for `baas run` and quietly
  fails for CI, whose id would then disagree with its own `createdAt` by the length of the queue
  wait.
- `exec-single-benchmark.yml` is **not** touched here; `gha-workflow-migration-to-dynamodb` owns
  that file, including the `project` input it needs.

**CLI surface**

- `baas download` accepts a run id and resolves the stored `resultPath` through `requestId-index`,
  since the id is what `baas run` prints and what `baas results` shows. An argument that looks like
  a prefix is still treated as one, so every historical path keeps resolving.
- Finding **A7** is closed rather than inherited: the source repository becomes a config key instead
  of `wsztajerowski/benchmark-as-a-service` hardcoded in a shell script.

## Stated limits, not solved

- **Unbounded growth.** With the expiry gone, the bucket only grows. At this cadence that is cents
  per year, and it is a deliberate trade for run prefixes that stay complete.
- **A corrupted `releases/<version>/` object never self-repairs.** Upload-if-absent will not
  overwrite it; the fix is deleting the key so the next run re-seeds it.
- **The runner trusts a caller-supplied `--created-at`.** A caller passing a bogus instant misdates
  its own results.
- **Pre-existing, out of scope, must be filed:** `JmhResult` does not parse JMH's `params` object at
  all, and the sort key is `class#method#mode#timestamp#requestId`. Because one timestamp is shared
  across a run, two `@Param` variants of the same benchmark method produce an identical sort key and
  the second `PutItem` silently overwrites the first. This is live today. It belongs in
  `docs/review/benchmark-runner-findings.md`, not in this change.

## Open Questions

1. **Ordering of the cutover and the migration.** Whether history moves before or after `baas run`
   starts writing the new layout, and whether the migration is a `scripts/` one-shot deleted after
   use (the `dynamodb-results-store` precedent) or a `baas admin` subcommand.
2. **Checksum verification of the downloaded release asset.** The download moving to the laptop is
   what makes verification *possible*; what it is verified against — a digest from the GitHub API, a
   checksum published as a second release asset, or nothing beyond TLS — is unsettled.
3. **What happens to the existing `RESULT#unknown` items** that CI has been writing. Migration has
   to put them somewhere: `runs/unknown/`, or a re-attribution to the real project.
4. **Entropy source** — `UUID.randomUUID()`'s first block versus `SecureRandom` — and whether the
   CLI pre-checks the GSI for an id collision before launching. Cheap either way; unspecified.

## Relationship to other changes

- **`private-runner-network`** — this change deletes the instance's `api.github.com` egress
  outright, so that change no longer has to tunnel it through a NAT or a VPC endpoint.
- **`export-before-teardown`** — waits on this. Its bundle layout is documented against the settled
  shape, and the versioning question it deferred here is now answered: suspended, so a bundle's
  "current versions only" limit applies to a bucket that no longer accumulates noncurrent versions.
- **`gha-workflow-migration-to-dynamodb`** — owns `exec-single-benchmark.yml`; this change owns
  `e2e-cloud-test.yml`. Both edit CI and must be sequenced to avoid colliding in the same files.
