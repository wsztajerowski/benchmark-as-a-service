# Design: DynamoDB results store, prebaked runner AMI, private runner network

Date: 2026-08-10
Status: Approved for OpenSpec proposal

Replaces MongoDB Atlas with a DynamoDB table created by the BaaS CloudFormation stack, and takes the
opportunity to move benchmark runners into a private subnet at no standing cost.

## Roadmap

Three OpenSpec changes, strictly ordered:

```
1. prebaked-runner-ami    ──┐
                            ├──→  3. private-runner-network
2. dynamodb-results-store ──┘
```

Changes 1 and 2 are independent of each other and each delivers value alone. Change 3 requires both:
a private subnet cannot reach yum (needs 1) and cannot reach Atlas (needs 2). Change 1 requires no
foresight about change 3 — it uses the subnet that exists today.

The AMI is a prerequisite for the *network* change, not for the database change. Both are
prerequisites for going private.

---

## Change 1 — `prebaked-runner-ami`

### Intent

The runner boots from a pinned image and installs nothing at run time. This is a
**measurement-integrity** change first and a networking prerequisite second: `yum update -y` on every
run (open finding A8) means the OS underneath a benchmark drifts between measurements, which is worse
for a benchmarking tool than most security findings.

### Image definition

`infra/runner-image.yaml` is the single file a user edits. It declares the image version and pinned
tool versions (Corretto, async-profiler, AWS CLI) and the exact parent AL2023 AMI ID. It ships as a
classpath resource alongside `cf-template-core.yaml`.

### Build mechanism: EC2 Image Builder, CLI owns versioning

Image Builder's `Component` and `ImageRecipe` are immutable and semver-versioned, so changing content
requires a new version. Rather than making the user bump two numbers, `baas admin build-image` renders
the recipe from `runner-image.yaml` and auto-bumps the recipe patch version when the rendered content
changes. The user edits one line.

New stack resources in `cf-template-core.yaml`: `Component`, `ImageRecipe` (AL2023 parent, 30 GB gp3
root volume preserving the existing volume invariant), `InfrastructureConfiguration` pointed at the
public subnet, `DistributionConfiguration`, and `ImagePipeline`. Plus a build-instance role carrying
`AmazonSSMManagedInstanceCore` and `EC2InstanceProfileForImageBuilder`.

Deliberately **absent**: `AWS::ImageBuilder::Image`. That resource performs a build during stack
operations, which would pull a ~15-minute rebuild into every `baas admin setup`.

Also absent: `AWS::ImageBuilder::LifecyclePolicy`. With the two-slot scheme below, the CLI prunes
directly and the CF resource is unnecessary.

### Two AMI slots

| Slot | Pointer | Contents |
|---|---|---|
| `current` | `/<prefix>/runner/ami-id` | Newest image built from `infra/runner-image.yaml` |
| `adhoc` | `/<prefix>/runner/adhoc-ami-id` | At most one image rebuilt from an archived template |

AMI tags (`baas-image-version`, `baas-slot`, `baas-parent-ami`) are the descriptive source of truth;
the SSM parameters exist so resolution needs no `DescribeImages` call.

Each slot prunes its own previous occupant — deregister AMI, delete snapshot — at the **start** of a
build, so pruning never races an in-flight `baas run`. Worst-case standing cost is ~$0.40/month for
two snapshots. `--clear-adhoc` empties the second slot.

### Template archive in S3

Keeping one AMI per slot rather than a deep history is justified by provenance, not by the ~$0.20/month
per snapshot. A rendered template in the results bucket — already `DeletionPolicy: Retain`, versioned,
and deliberately outliving any stack — is durable in a way an AMI is not: AMIs get deregistered,
snapshots deleted, and they are region-scoped.

```
images/
  by-version/<imageVersion>/
      runner-image.yaml     rendered template
      component.yaml        rendered Image Builder component
      packages.txt          rpm -qa captured from the built instance
      build.json            amiId, exact parent AMI ID, artifact checksums, region, builtAt
  by-ami/<amiId>            tiny pointer object → imageVersion
```

`by-ami/` pointer objects make an AMI-ID lookup a single `GetObject`. There is deliberately no mutable
`index.json`, so concurrent builds cannot corrupt a shared file.

### Rebuild fidelity

**A rebuild from an archived template does not reproduce the original AMI.** Package resolution moves,
and a floating parent image moves with it. Left unaddressed, this creates a false sense of
reproducibility, which for a benchmarking tool is worse than promising nothing.

Mitigation, decided: pin the parent image by **exact AMI ID** in `build.json`, record artifact
checksums, and capture `rpm -qa` into `packages.txt` during the build. A rebuild pins what it can and
**compares its manifest against the archived one**, so drift is always detectable even when it is not
preventable. A comparison you cannot trust but can verify is acceptable; one you cannot verify is not.

### Commands

| Command | Effect |
|---|---|
| `baas admin build-image` | Renders + version-bumps, updates stack, `StartImagePipelineExecution`, archives to S3, writes `current` pointer |
| `baas admin build-image --from-version <v>` | Fetches archived template, builds into `adhoc` via direct `imagebuilder:CreateImage` |
| `baas admin build-image --clear-adhoc` | Deregisters the adhoc AMI and deletes its snapshot |
| `baas admin images` | Lists both slots: slot, version, AMI ID, build time, drift status |
| `baas run --image-version <v>` | Resolves against either slot; fails with a precise pointer if neither matches |
| `baas run --ami-id <id>` | Explicit override; means "use this AMI if it still exists" |

The `current` build uses the stack-managed pipeline. The `adhoc` build instead calls
`imagebuilder:CreateImage` directly against a recipe the CLI creates on demand from the archive. Two
code paths, accepted deliberately: reproducing a historical benchmark must never mutate the
CloudFormation stack, which swapping a single pipeline's recipe back and forth would require. Image
Builder recipes are free metadata that persist independently of AMIs, so an archived version usually
still exists and is reused rather than recreated.

All `build-image` variants run under **deployer** credentials, consistent with the existing rule that
`baas admin *` is the elevated path.

### Runner changes

`UserDataScriptBuilder` loses `yum update`, the Corretto install, and the async-profiler download. The
three-layer termination design and the `/app` working-directory invariant are untouched.

`RunCommand` reads `/<prefix>/runner/ami-id` and **fails fast** when it is absent, pointing at
`baas admin build-image`. There is no fallback to AL2023 + yum: two provisioning paths would produce
silently incomparable results, and change 3 would have to delete the fallback anyway.

### Result tagging

New tags `amiId`, `imageVersion`, and `imageSlot`. When an adhoc rebuild's captured manifest diverges
from the archived one, results also carry `imageDrifted=true`.

The runner cannot determine any of these itself. `baas run` resolves the AMI's tags and its
`build.json` when selecting the image, then passes all four values into user-data, which forwards them
to the runner as ordinary result tags — the same path existing tags already take.

Because `tags` is already a free-form `Map<String,String>` on `BenchmarkMetadata`, this requires **no
schema change** in Mongo now or DynamoDB later.

`baas results` surfaces drift rather than auto-excluding it. Whether drifted numbers belong in a
comparison is the user's decision, not the tool's. This closes the provenance chain: archive →
rebuild → detect drift → tag → visible at query time.

### IAM

Deployer gains prefix-scoped `imagebuilder:*` (including `CreateImage`, `CreateImageRecipe`,
`GetImage`, `ListImageRecipes`), `iam:PassRole` for the build instance profile, `ec2:CreateImage`,
`DeregisterImage`, `DeleteSnapshot`, `DescribeImages`, `CreateTags`, and `ssm:PutParameter` on the two
AMI pointer paths. Operator gains read on those paths and `ec2:DescribeImages`.

### Open implementation risk

Whether `java-25-amazon-corretto-headless` resolves from AL2023's regional repos or from an external
Corretto repo determines what the build step must reach. It does not change the design, but it must be
verified before the component is written. Carried as an explicit task, not an assumption.

---

## Change 2 — `dynamodb-results-store`

### Table

One table `baas-<prefix>-results`. On-demand billing. `DeletionPolicy: Retain` +
`UpdateReplacePolicy: Retain`, mirroring `S3MainBucket` — the results table is *more* the benchmark
history than the bucket is. Keys `pk`/`sk`, both `S`. **No GSIs.** No TTL: history is the point.

| Item | pk | sk |
|---|---|---|
| JMH result | `RUN#<requestId>` | `BENCH#<fqcn>#<method>#<type>` |
| JCStress result | `RUN#<requestId>` | `JCSTRESS` |
| tag index | `TAG#<key>#<value>` | `<createdAt>#<requestId>#<name>` |
| benchmark index | `BENCH#<fqcn>` | `<method>#<createdAt>#<requestId>` |
| time index | `ALL#<yyyy-mm>` | `<createdAt>#<requestId>#<name>` |

Partitioning the benchmark index on the **class** with the method in the sort key answers both
questions from one partition: all methods of a class is a plain `Query`, one method over time is
`begins_with(sk, "<method>#")`. A flat `BENCH#<fqcn>.<method>` key would answer only the second.

The time index is month-partitioned to bound partition size while still serving "recent N" without a
`Scan`.

### Why inverted index items instead of GSIs

`branch` and `project` are already tags, so index-backed tag search subsumes both as special cases —
`Query(TAG#branch#main)` descending. Arbitrary tag search is unreachable under a GSI design; it
degrades to `Scan` + `FilterExpression` permanently, and each new indexed dimension needs a new GSI
plus a backfill.

The cost objection does not hold: a GSI replicates writes automatically, so writing index items
manually costs the same order of WCUs with a projection under our control. At ~6–8 tiny items per
benchmark result and on-demand pricing, this is a fraction of a cent per run.

Accepted cost: the runner owns index consistency, and deleting a run means deleting its derived items.
Results are write-once and keys are deterministic, so this is bounded.

### Item contents

The result item holds the queryable summary: score, scoreError, scoreUnit, mode, threads, forks,
jdkVersion, vmName, vmVersion, warmup/measurement counts, `createdAt` (ISO-8601), `tags`,
`profilerOutputPaths`, and `resultJsonKey`.

`resultJsonKey` points at the **verbatim JMH result JSON**, now uploaded to the run's S3 prefix.
`rawData` (`List<List<Double>>`, the only field that can approach DynamoDB's 400 KB item limit) and
`scorePercentiles` live only there; `secondaryMetrics` is reduced to name → {score, unit}.

This also closes the long-documented "measurements live only in MongoDB, there is no `result.json`"
gap as a side effect.

Index items carry a denormalized projection — name, type, mode, score, scoreError, scoreUnit,
createdAt, requestId, branch, amiId, excludeFromResults — so every query is one round trip.

### Negation is not indexable

`exclude_from_results=true` must be applied *negatively*, and no index answers "not tagged X". So
`excludeFromResults` is projected onto index items as a boolean and applied via `FilterExpression`.
Index items are written for all tags except a small reserved set.

### Writes

One `BatchWriteItem` per result: 1 result + 1 benchmark + 1 time + N tag items, roughly 6–8, well
under the 25-item limit. Keys are fully deterministic, so a retried batch is idempotent rather than
duplicative.

Ordering: S3 JSON first (it is the durable record and the item references its key), then DynamoDB with
backoff. If the DynamoDB write ultimately fails, the runner exits non-zero so `run-status` reports
failure and the S3 artifacts survive for re-import. This partially addresses open finding A4, where a
transient store error silently discarded a paid run.

### Code structure

**New reactor module `baas-model`**, depended on by both `benchmark-runner` and `baas-cli`: the stored
`ResultItem`/`IndexItem` shapes, key encoding, attribute-name constants, and the
`Map<String,AttributeValue>` mapper. This is the structural fix for finding A3 — today both modules
read the same documents through independently duplicated raw string paths, so a key-encoding change
silently returns zero rows instead of failing to compile.

**SDK: low-level `DynamoDbClient` plus an explicit mapper.** The Enhanced Client's one-class-per-table
model fights a single-table design with heterogeneous items — each item type needs its own
`TableSchema` and manual key composition anyway — and Java records need hand-written builders for
`@DynamoDbImmutable` regardless. The explicit mapper doubles as the schema contract.

`JmhResult`/`Metric` stay in `benchmark-runner` as *parsing* types for JMH's JSON output and map into
`ResultItem`. All `dev.morphia` annotations disappear, and with them the
`mapPackage("pl.wsztajerowski.entities")` constraint on where entity classes may live.

`DatabaseService` becomes `ResultsStore` with a single `put(ResultItem)`; the store derives and writes
its own index items. **`upsert` is deleted** — it is dead code today, called by none of the four
subcommand services, and porting a Mongo-shaped update-operator API to DynamoDB for zero callers is
pure cost.

`NoOpResultsStore` survives but is reachable **only via an explicit `--no-database` flag** on
`benchmark-runner` (surfaced as a pass-through on `baas run`). A missing table name is a hard failure.
Inferring no-op from absence would preserve today's documented silent-data-loss footgun, where a run
reports success and the paid measurements are discarded.

The JCStress item keeps one row per request, matching today's `_id = requestId`. Two JCStress
executions sharing a request ID would overwrite each other, exactly as they do now — behaviour
preserved deliberately rather than fixed here.

### Query layer

`ResultsQueryService` is rewritten against the table using `baas-model`:

| Invocation | Query |
|---|---|
| `--request-id X` | `Query(pk=RUN#X)` |
| `--branch main` | `Query(pk=TAG#branch#main)` descending |
| `--benchmark <fqcn>[#method]` | `Query(pk=BENCH#<fqcn>)`, optional `begins_with` on method |
| `--tag k=v` | `Query(pk=TAG#k#v)` descending |
| no filter | `Query(pk=ALL#<yyyy-mm>)` walking back months until the limit fills |

**Behaviour change to document prominently:** `--benchmark` is today a *regex* match. DynamoDB cannot
regex a key, so it becomes exact-or-prefix. This is a genuine capability loss.

Open finding **D1 is implemented in this change**: filter `exclude_from_results`, group by
`(benchmark, branch)`, keep the highest-scoring run per group. The query layer is being rewritten from
scratch anyway, and shipping a second version that still contradicts its own documentation is worse
than the first.

The exclusion filter is a `FilterExpression` applied server-side; the grouping and best-score selection
are applied **client-side** over the returned rows. DynamoDB has no aggregation, and at the row counts
`baas results` deals with, pulling a bounded query result and reducing it locally is the correct
trade — not a limitation being worked around.

### Everything else this change drags with it

- `/<prefix>/mongo/connection-string` is deleted. The table name is a stack output carried in
  `config.yaml` and user-data; it is not a secret, so the "never put it in user-data" invariant does
  not apply.
- `--mongo-uri` leaves `admin setup` and `config set`. `validateMongoUri` — all three copies, finding
  A5 — is deleted.
- IAM: runner gains `dynamodb:PutItem`/`BatchWriteItem` on the table ARN (no read). Operator gains
  `Query`/`GetItem`. Deployer gains `CreateTable`/`DescribeTable`/`UpdateTable`/`TagResource`/
  `DeleteTable`. Every Mongo SSM grant is removed from deployer, operator, CI, and runner policies.
- Networking, already in this change: a **DynamoDB gateway endpoint** (free) so database traffic never
  leaves the VPC, and **27017 egress removed** from `RunnerSecurityGroup`.
- `SetupCommand`'s retained-resource pre-check gains a twin for the table. The documented S3 trap — a
  retained resource blocking the next setup with a CloudFormation error that never names the culprit —
  now applies to the table too.
- `TeardownCommand` messaging updated: the table is retained.
- Tests collapse to a single LocalStack container (`s3` + `dynamodb`) instead of LocalStack plus a
  Mongo container. `MongoDbTestHelpers` and `TestcontainersWithS3AndMongoBaseIT` are deleted or
  renamed. New: key-encoding unit tests, mapper round-trip tests, query-service integration tests.
- `docker-compose.yaml` loses `mongo` and `mongo-express`; LocalStack gains `dynamodb`. The documented
  manual setup step now also creates the table.
- `jmh-with-profiler.sh` and `jmh-with-async.sh` swap `--mongo-connection-string` for the table name
  or `--no-database`.

### Data migration

`scripts/migrate-atlas-to-dynamodb` — a throwaway script, idempotent, with a dry-run. Reads Atlas,
writes result items plus their derived index items, then is deleted once complete. This keeps
`mongodb-driver-sync` out of the shipped CLI permanently.

Accepted: CLAUDE.md records that nothing in CI exercises `scripts/`, so it is unprotected by tests.

### Accepted-risks changes

Three rows leave the table entirely: the Atlas IP allowlist (`0.0.0.0/0` gated by credentials),
"MongoDB — connect-only", and the shared `RunnerRole` SSM Mongo path.

The stale `openspec/changes/atlas-service-account-credentials` change (empty spec directories) becomes
obsolete and should be removed.

---

## Change 3 — `private-runner-network`

### Topology

A new private `RunnerSubnet` (`MapPublicIpOnLaunch: false`, no IGW route) with its own route table
carrying the S3 and DynamoDB gateway endpoints. The existing `PublicSubnet` is **retained but demoted**
to Image Builder build instances only. `Ec2ProvisioningService` moves to the private subnet.

```
BaasVpc
├─ PublicSubnet   → IGW route.  Image Builder build instances only. Rare, short-lived.
└─ RunnerSubnet   → no IGW.  S3 + DynamoDB gateway endpoints. Every benchmark run.
```

The builder and the runner have opposite network requirements by nature: the runner needs no internet
*because* the builder had internet. A build instance with no egress cannot build the thing that makes
egress unnecessary. Building in a private subnet would additionally need `ssm`, `ssmmessages`, and
`ec2messages` interface endpoints (~$7/month each, since Image Builder orchestrates through SSM) and
would still fail on its own terms. So: **builds stay public, permanently.**

### BYO-VPC escape hatch removed

`UseExistingVpc`, `ExistingVpcId`, `ExistingSubnetId`, `ExistingSecurityGroupId`, and the
`CreateNetworking` condition are deleted; every networking resource becomes unconditional. The CLI
could never validate that a foreign VPC provided the required gateway endpoints, and the failure mode
— runs that hang and time out — is miserable to diagnose from a terminated instance.

### Egress

Security group egress narrows to the two AWS-managed prefix lists (`com.amazonaws.<region>.s3` and
`com.amazonaws.<region>.dynamodb`) on 443. No `0.0.0.0/0`, no port 80, no 27017. Egress now enumerates
exactly what the runner may reach.

### Self-termination changes mechanism

`InstanceInitiatedShutdownBehavior: terminate` on `RunInstances` lets the watchdog call
`shutdown -h now` instead of `ec2:TerminateInstances`. This avoids a ~$7/month EC2 interface endpoint
*and* removes the `ec2:TerminateInstances` grant from `RunnerRole`, closing open finding S8 where a
shared-tag condition let any runner terminate any other.

**All three termination layers survive** — this is a load-bearing invariant, so explicitly:

1. The watchdog subshell still fires under a deadlocked JVM: `shutdown` is a separate process.
2. The process `timeout` around the runner JAR is unchanged.
3. The CLI's JVM shutdown hook still calls `TerminateInstances` from the laptop under operator
   credentials, which a private subnet does not affect.

New failure mode introduced: a hung `shutdown`. Mitigated with a `halt -f -p` fallback if the instance
is still alive 120 s later.

Consequence for a documented invariant: the `baas-role` tag key exists in its current dash form
*because* `RunnerRole`'s `ec2:TerminateInstances` condition is scoped to it. Once that grant is removed
the tag no longer gates self-termination, so the CLAUDE.md invariant must be rewritten rather than
simply carried forward — the tag remains useful for identification and cost attribution, but the reason
it is load-bearing changes.

### Runner JAR staging becomes mandatory

GitHub Releases is unreachable from a private subnet, so `baas run` always stages the JAR into S3,
cached under `runners/<version>/` so repeated runs do not re-upload. The laptop verifies a checksum
before upload, which is a partial answer to the standing accepted risk that the runner JAR is fetched
with no integrity verification.

### What already works unchanged

async-profiler and the JDK are baked in by change 1. The IMDSv2 token fetch is link-local.
Instance-profile credentials come from IMDS with no STS call. The `cloud-init-output.log` upload — the
primary artifact for diagnosing a run that died before producing output — goes over the S3 gateway
endpoint.

### Cost

**$0 standing.** Gateway endpoints and the IGW are free; there is no NAT and no interface endpoint. The
only recurring charge is one or two AMI snapshots at ~$0.20/month each.

This obsoletes the *Accepted risks* position that a private subnet needs "a paid tier and ~$32/month
standing cost". That row is rewritten rather than deleted, recording that gateway endpoints plus a
prebaked AMI achieved it for free.

### New user-facing constraint

**A benchmark that makes outbound network calls of its own will now fail.** Maven runs on the laptop
and only the built JAR is uploaded, so the build path is unaffected — but a benchmark reaching an
external service is no longer possible without deliberately re-opening egress. This must be
documented, not discovered.

---

## Decision log

| # | Decision | Rationale |
|---|---|---|
| 1 | One-off export/import script for Atlas history | History matters; a throwaway script keeps the Mongo driver out of the shipped CLI |
| 2 | Index-backed patterns: requestId, branch, benchmark-over-time, arbitrary tag | Branch and project are tags, so tag search subsumes them |
| 3 | Summary in DynamoDB, verbatim JMH JSON in S3 | Keeps items ~1–2 KB under the 400 KB limit; closes the "no result.json" gap |
| 4 | Three changes, ordered AMI → DynamoDB → private network | AMI and DynamoDB are independent siblings; both gate the network move |
| 5 | Single table, inverted index items, no GSIs | Same write cost as a GSI design, strictly more query power |
| 6 | Low-level `DynamoDbClient` + explicit mapper | Enhanced Client fights heterogeneous single-table items |
| 7 | New shared `baas-model` module | Structural fix for the duplicated-string-path problem (A3) |
| 8 | `upsert` deleted | Dead code; zero callers |
| 9 | Explicit `--no-database` required | Removes a silent-data-loss footgun |
| 10 | Table `DeletionPolicy: Retain` | Mirrors the bucket; history outlives any stack |
| 11 | EC2 Image Builder, CLI owns version bumping | Matches "template in repo, CLI builds per account"; keeps updates a one-line edit |
| 12 | Separate `build-image` command, AMI ID in SSM | An AMI ID is shared account state, not per-laptop state |
| 13 | Hard fail when no AMI exists | One provisioning path; deletes the yum-at-runtime path (A8) |
| 14 | BYO-VPC escape hatch dropped | Unvalidatable for a foreign VPC; awful failure mode |
| 15 | Two AMI slots (`current`, `adhoc`) + S3 template archive | Templates outlive AMIs; historical environments rebuildable on demand |
| 16 | Pin parent AMI + capture `rpm -qa` manifest | Makes rebuild drift detectable, so comparisons are verifiable |
| 17 | D1 implemented in change 2 | The query layer is being rewritten anyway |
| 18 | `shutdown -h now` instead of `ec2:TerminateInstances` | Avoids an interface endpoint; closes finding S8 |

## Open risks

| Risk | Status |
|---|---|
| `java-25-amazon-corretto-headless` source repo (AL2023 regional vs external Corretto) | Verify before writing the Image Builder component |
| Rebuild from archive is approximate, not exact | Mitigated by parent-AMI pinning + manifest drift detection; surfaced as `imageDrifted` tag |
| `--benchmark` loses regex matching | Documented behaviour change |
| Concurrent adhoc builds by two developers contend for one slot | Accepted at current single-operator scale |
| Deleting a run requires deleting its derived index items | Results are write-once; keys are deterministic |
| `scripts/` is not exercised by CI | Pre-existing, accepted; migration script is throwaway |
