# Pre-brainstorm assumptions

> **This is not an artifact.** The schema's first artifact is `brainstorm`, which is deliberately
> still unstarted — this file exists so that session can begin from established facts instead of
> re-deriving them, and so the open questions are not silently resolved by whoever writes the
> proposal. Captured 2026-08-20, during the `export-before-teardown` brainstorm, which is what
> forced a look at the bucket.

## Why this change exists

`export-before-teardown` set out to back up the data plane and immediately hit the question of what
a "run" is in S3. Today it is two unrelated trees: results under `<branch>/<type>/<timestamp>/` and
uploaded inputs under `runs/<requestId>/`. Backing that up is possible either way — a mirror copies
prefixes and does not care — but freezing a bundle layout against a shape that is about to move
means writing it twice.

Looking closely turned up two things that are wrong independently of any backup, and one that is
about to become wrong the moment the layout unifies.

## Established facts — verified by reading the code, not assumed

**The CLI already computes both identifiers from the same two values.** `RunCommand.java:212-213`:

```java
String requestId  = benchmarkType + "-" + timestamp;
String resultPath = resolvedBranch + "/" + benchmarkType + "/" + timestamp;
```

**The runner already models one run as one prefix.** `ApiCommonSharedOptions.java:26` takes
`--request-id` as an opaque string defaulting to `Instant.now().toString()`, and
`getRequestOptions()` defaults the result path to it:

```java
Path nonNullResultPath = Optional.ofNullable(resultPath).orElse(Path.of(nonNullRequestId));
```

The split is entirely the CLI's doing — it passes an explicit `--result-path` through
`UserDataScriptBuilder.java:164`. Nothing in `benchmark-runner` has to change to unify this; `baas
run` has to stop disagreeing with the runner's own default.

**The per-run runner JAR copy is the exception, not the rule.** `RunCommand.java:218` always uploads
the user's `benchmark.jar` to `runs/<requestId>/`, but `:222-228` uploads `runner.jar` only when
`--runner-jar` was passed. The default path is `UserDataScriptBuilder.java:118-122`:

```bash
if [[ -n "${RUNNER_JAR_S3_KEY}" ]]; then
  aws s3 cp "s3://${S3_BUCKET}/${RUNNER_JAR_S3_KEY}" /app/benchmark-runner.jar
else
  RELEASE_URL=$(curl ... "https://api.github.com/repos/wsztajerowski/benchmark-as-a-service/releases/latest" ...)
```

So by default every instance calls the GitHub API and pulls **`releases/latest`** — unpinned. Two
runs a week apart can execute different runner code. This is the same class of drift as the
`yum update -y` that finding **A8** removed from this exact script, and it is the mechanism behind
both the accepted "runner JAR downloaded without checksum verification" risk and finding **A7**
(runner-JAR discovery hardcodes the upstream repo).

**A lifecycle rule will eat the results the moment they move.** `infra/cf-template-core.yaml:200-205`:

```yaml
- Id: expire-uploaded-benchmark-jars
  Status: Enabled
  Prefix: runs/
  ExpirationInDays: 30
```

Its own comment reads *"Uploaded benchmark JARs are re-creatable from source; results under
`<branch>/<type>/<timestamp>/` are untouched by these rules."* Re-key results under `runs/` and that
rule deletes every one of them 30 days after it is written — server-side, silently, with nothing in
the CLI positioned to notice. **This is the blocking sub-decision of the change.** S3 lifecycle
prefixes are literal, so `runs/*/input/` cannot express the exception; the filter has to key on
something other than the prefix, or the inputs have to live elsewhere.

**Bucket versioning is on.** `cf-template-core.yaml:183-184`, with noncurrent versions expiring at
30 days and delete markers reaped (`:186-197`). `S3UploadService.deleteAllObjects` walks version
pages because of it.

**CI has its own scheme and shares one prefix across two jobs.** `e2e-cloud-test.yml:26-28` builds
`ID=CI_E2E_$(date -u +'%Y%m%d_%H%M%S')` and `result_path=ci/$ID`, and that single `result_path` is
consumed by two benchmark jobs (`:96`, `:119`) — so two runs already write `run-status` to the same
key. Note `gha-workflow-migration-to-dynamodb` also edits this file, in different regions.

## Decided 2026-08-20

| | |
|---|---|
| Request id | `<branch>-<type>-<timestamp>`, computed in `baas run` |
| Normalisation | every character outside `[A-Za-z0-9._-]` becomes `-`, runs of dashes collapse, leading/trailing trimmed |
| Layout | one run, one prefix: `runs/<requestId>/`, holding inputs and results together |
| Runner JAR | a single copy at `runner/<version>/benchmark-runner.jar`, uploaded if absent; the instance always `aws s3 cp`s it, and the GitHub branch of the script goes away |
| CI | same rule per benchmark job (`<ref_name>-<type>-<timestamp>` → `runs/<id>`), which also ends two jobs sharing one `run-status`. Edit lands here |
| Finding A9 | accepted as filed at Low. Second granularity is fine for human-paced runs, and folding branch into the id removes the cross-branch collision |
| History | nothing moves. Existing objects stay where they are and existing items keep their stored `resultPath` / `resultJsonKey`, so `baas download` on an old run keeps resolving |

**Why normalisation is load-bearing, not cosmetic.** Branch names carry `/`, which would otherwise
nest a prefix level per path segment. More seriously, `ResultKeys` builds the sort key as
`<class>#<method>#<mode>#<timestamp>#<requestId>` — a `#` reaching the request id would corrupt key
parsing. Today it cannot, because the id is `<type>-<timestamp>`; folding the branch in is exactly
what makes it reachable.

**What this does to comparability.** Re-keying moves where bytes land and changes nothing about what
is measured or how. Pinning the runner JAR *improves* comparability: `releases/latest` means the
runner version is whatever existed on the day of the run, and pinning it to the CLI that launched
the run removes a drift axis nobody was tracking. Removing the `api.github.com` call also deletes an
egress dependency the queued `private-runner-network` change would otherwise have to solve with a
NAT or a VPC endpoint.

## Open questions for the brainstorm

1. **How is the `runs/` lifecycle rule re-scoped?** Options weighed and not settled: tag uploaded
   JARs on upload (`baas-artifact=input`) and filter the rule on the tag — needs an
   `s3:PutObjectTagging` grant; drop the expiry entirely and accept unbounded growth; or keep inputs
   outside the run prefix, which costs the one-prefix-per-run property this change exists to get.
2. **Does versioning stay enabled?** Results are write-once, so its only remaining value is forensic
   — surviving an A9 collision that overwrites a `run-status`, which is now accepted at its current
   level. Suspending it removes noncurrent storage and lets an export bundle claim completeness.
   Note suspension is not the same as never having versioned: existing noncurrent versions persist
   until the lifecycle rule reaps them, so `deleteAllObjects` keeps its version-walking loop either
   way, and `CoreTemplateTest` pins template facts that would need updating.
3. **Where does the CLI get the runner JAR it uploads?** Shaded into the CLI JAR as a resource, the
   way `runner-image.yaml` already ships as `/templates/runner-image.yaml` (exact version match,
   larger CLI); downloaded from GitHub Releases by the CLI on the laptop pinned to its own version
   (small CLI, moves the unverified download somewhere it can be checksummed); or built from the
   reactor for development.
4. **What seeds `runner/<version>/`?** `baas admin setup`, or `baas run` uploading if absent on first
   use. The second needs no new command but puts a conditional upload in the run path.
5. **What happens to `--runner-jar`?** The development override still needs somewhere to put a local
   build — per-run under the run prefix as today, or a version-keyed slot of its own.
6. **Does `baas download <result-path>` keep accepting old-layout paths?** Nothing moves, so old
   paths must keep working; whether the argument is documented as "the path `baas results` printed"
   rather than a constructed shape is a docs decision.
