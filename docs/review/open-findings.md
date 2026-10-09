# Open findings

Every review finding that is **neither fixed nor accepted**, in one file, merged on 2026-10-05 from
the per-module review files (`baas-cli-findings.md`, `benchmark-runner-findings.md`,
`prebaked-runner-ami-review.md`) and the CLI usage analysis (`docs/analysis/cli-usage-analysis.md`).
Those files are gone; `git log -- docs/review docs/analysis` holds them.

- **IDs are kept from where each finding was filed** — `S`/`A`/`D`/`C` (module reviews), `P`
  (prebaked-image review), `U` (CLI usage analysis), `N` (new on 2026-10-05) — so commits, change
  artifacts and `openspec/config.yaml` rules that cite them still resolve.
- **Closing a finding deletes its entry here.** If the decision is worth keeping, it becomes an ADR
  in [`docs/adr/`](../adr/). ADRs 0002–0005 hold what was closed or declined up to 2026-10-05.
- **Excluded on purpose:** everything in CLAUDE.md's *Accepted risks*, and the hardenings and
  intended behaviour recorded in [ADR 0005](../adr/0005-declined-hardenings-and-intended-behaviour.md).
- State machines for the deployment, an operator machine and a job, which locate most `U`
  findings: `docs/diagrams/baas-states-*.mmd`.

## Index

| ID | Finding | Sev | Where it goes |
|---|---|---|---|
| S14 | `RunnerRole` can overwrite the pinned runner JAR every later job executes | Med | `narrow-bucket-grants` |
| S7 | `RunnerRole`'s bucket-wide `s3:DeleteObject`/`PutObject` (table half already fixed) | Med | `narrow-bucket-grants` |
| S15 | `OperatorRole` — so every CI workflow — holds bucket-wide `s3:DeleteObject` no command uses | Low | `narrow-bucket-grants` |
| C4 | MongoDB retirement; every service IT runs on the Mongo adapter | — | `retire-mongodb` |
| A13 | `LocalStorageService` reads every file as UTF-8 to trace-log it; binary output throws | Low | `retire-mongodb` |
| U2 | Nothing exports a deployment's data before a teardown, which now deletes everything | Low | `export-before-teardown` |
| DR1 | `run --detach` with today's shutdown hook would cancel and terminate the job it just launched | High if shipped naively | `detached-run` |
| DR4 | The archived non-goal rejecting `--detach` cites a reason job items removed | Info | `detached-run` |
| S2 | CI's OIDC trust is repo-wide and `pull_request` triggers it | High → reduced | open |
| A4 | A store failure after the upload loses the measurement row | Med → reduced | open |
| S10 | Actions on mutable tags; dependabot covers only Maven | Low | open |
| N3 | A failed run-item reservation strands the uploaded `input/` | Low | open |
| N2 | `3q7i7s65-operator-role` from the August deployment still exists | Low | open, by hand |
| U8 | `runner.sourceRepo` can only be set by editing YAML | Info | open |
| U14 | Informational, see the last section | Info | — |

## Queued as OpenSpec changes

Order and state are in [`openspec/changes/QUEUE.md`](../../openspec/changes/QUEUE.md). Each design
below is what the proposal starts from.

### `narrow-bucket-grants` — S14, S7, S15

**S14.** `${prefix}-policy-runner-s3` grants `s3:PutObject` on `${ResourceNamePrefix}/*`, which
includes `releases/<version>/benchmark-runner.jar`. CLAUDE.md rests the pinning argument on that
object being "seeded once and never overwritten", but that holds only by CLI convention:
`RunnerJarResolver.resolve` treats any object already present as trusted and never re-verifies it.
So code in one benchmark JAR can replace the runner that **every later job of that version
executes**, for as long as the version is in use. Versioning is `Suspended`, so the original cannot
be recovered.

**S7.** The runner executes an arbitrary user JAR under that same grant, which also carries
`DeleteObject` bucket-wide. Its table access is already minimal — `PutItem`/`BatchWriteItem` on
`RESULT#*` and `UpdateItem` on `JOB` only (`dynamodb-results-store`, `run-status-in-dynamodb`) —
but `BatchWriteItem` still carries `DeleteRequest`s within `RESULT#`.

**S15.** `${prefix}-policy-operator-s3` and `operator-policy.json` grant `s3:DeleteObject` on the
whole bucket. Nothing under the operator role deletes an object (the only delete is teardown's
`deleteAllObjects`, under deployer credentials). CI federates into this role on `pull_request`, so
any same-repository branch's workflow can wipe every job's artifacts, unrecoverably.

**Decided (2026-10-04), one small change, one `baas admin deployment setup` re-run per deployment:**
1. The runner's S3 grant: `PutObject` and `GetObject` on `jobs/*`, `GetObject` on `releases/*`
   (user-data's `aws s3 cp` of the runner), no `DeleteObject`. Pin it in `CoreTemplateTest`. What
   remains of S7 is overwriting another job's objects under `jobs/*`, which has no per-job IAM scope.
2. The seed itself becomes non-overwriting: `PutObject` with `If-None-Match: *` in
   `RunnerJarResolver`, which also removes the head-then-put race between two first jobs of a new
   version. A bucket-policy enforcement on `releases/*` is left out as extra mechanism.
3. `s3:DeleteObject` leaves both operator documents; `OperatorPolicyDriftTest` keeps them in step.

### `retire-mongodb` — C4, A13

**C4.** The largest removal left: `MongoResultsStore`, `MongoMeasurementDocument`,
`ResultsStoreBuilder.mongoStore`, `--mongo-connection-string`/`-m` and its `MONGO_CONNECTION_STRING`
default, the `mongodb-driver-sync`, `morphia-core` and `testcontainers-mongodb` dependencies, the
`mongo` service in `docker-compose.yaml`. CLAUDE.md's *Accepted risks* already records that no live
user is known. **Every service IT stores through `MongoResultsStore`** (via
`TestcontainersWithS3AndMongoBaseIT`), so the DynamoDB adapter — the only one BaaS uses — is
exercised by the store contract suite but by no end-to-end runner test.

**A13.** `LocalStorageService.saveFile` evaluates `Files.readString` as a `logger.trace` argument
whatever the level, so a binary file (`profile.jfr`, a `.bin.gz`) throws and fails the job. Local mode
only (no `--s3-bucket`), which no script, test or README section uses.

**Decided (2026-10-04), as its own change:** first move the service ITs onto LocalStack DynamoDB —
so the production path is tested end to end. Then delete the Mongo adapter,
`-m`/`MONGO_CONNECTION_STRING`, Morphia, the driver, the Mongo testcontainer, the compose service and
local storage mode, so `--results-table` and `--s3-bucket` become required. Update CLAUDE.md: the
*Accepted risks* MongoDB row, the Morphia and connection-string gotchas, *Adding a benchmark type*.

### `export-before-teardown` — U2

**U2.** Since `jobs-command`, a teardown deletes the bucket and the results table with the stack
(nothing is retained), so the only way to keep a deployment's history is to export it first — and no
`baas` command does that yet. The image part was already fixed: teardown retires it.

The design lives in
[`openspec/changes/export-before-teardown/brainstorm.md`](../../openspec/changes/export-before-teardown/brainstorm.md)
(its dated 2026-10-02 block overrides the text above it): an export command decoupled from teardown,
then a teardown that deletes the bucket and table behind a second typed confirmation.

### `detached-run` — DR1, DR4

Not yet in `QUEUE.md`: a stub change, explored on 2026-10-05, that comes after `jcstress-e2e` and
`jobs-command`. Its full exploration, with every decision, is
[`openspec/changes/detached-run/exploration.md`](../../openspec/changes/detached-run/exploration.md).

**DR1.** The shutdown hook is registered before `RunInstances` (`RunCommand` l.443), and it calls
`session.stop(CANCELLED)` whenever the session has not ended. A detached `run` returning after
`confirmLaunched` would therefore cancel and terminate its own instance at JVM exit. **Decided:**
keep the hook armed through the launch, then disarm it. An ADR amends CLAUDE.md's
three-termination-layers rule: for a detached job, `baas jobs terminate` replaces the CLI's Ctrl+C
layer.

**DR4.** The archived `run-status-in-dynamodb` design lists `--detach`/`attach` as a non-goal,
"rejected in the usage analysis §5", whose reason (it would need discovery by tag) the job items
removed. The `detached-run` ADR supersedes it explicitly.

## Open, not scheduled

### S2 — CI's OIDC trust is repo-wide and `pull_request` triggers it · reduced

`OperatorRole`'s federated statement matches `repo:<org>/<repo>:*` — every ref, PR branch and tag —
and `e2e-cloud-test.yml` runs on `pull_request`, so "can push a branch to the repository" means
"holds operator credentials". Reduced since filing: the job runs on `ubuntu-latest`, not a
self-hosted runner, fork PRs cannot obtain an `id-token`, and the operator role is the narrow one.
**Proposed:** pin `sub` to `repo:<org>/<repo>:ref:refs/heads/main` plus an
`environment:<name>` entry, put the E2E job behind an environment with required reviewers, and
replace the bare `pull_request` trigger with a label gate. S16 showed how this compounds with any
compromised step in a federated job.

### A4 — a store failure after the upload loses the measurement row · reduced

All four services order S3 first, then the store, on purpose: the item points at `resultJsonKey`, so
writing it first could publish a row referencing an object never uploaded. Writes are idempotent and
batched, and a failed write exits non-zero with the S3 artifacts intact (asserted by an IT). What
remains: the row itself is lost and nothing re-derives it from `jmh-result.json`.

### S10 — supply chain · reduced

`.github/dependabot.yml` covers only `maven`, so no action ever gets an update PR, and every action
is pinned to a mutable tag (`actions/*@v4|v5`, `aws-actions/configure-aws-credentials@v5`). The
third-party `machulav/ec2-github-runner` that made this sharper is gone. **Proposed:** add the
`github-actions` ecosystem; SHA-pin the actions in jobs that hold AWS credentials.

### N3 — a failed reservation strands the uploaded `input/` · Low

`baas run` uploads `benchmark.jar` (and a `--runner-jar`) before it reserves the job item, so a
reservation that cannot be written leaves `jobs/<project>/<jobId>/input/` with no job item: invisible
to `runs list` and never expired (no lifecycle rule under `jobs/`, by design). U25 removed the other
way into this state. Reserving first would instead leave a `launching` item for a failed upload,
which lists as `vanished` — arguably the better failure.

### N2 — the August deployment's operator role is still in the account · Low

`3q7i7s65-operator-role`, from the caller-ARN naming that `account-derived-deployment-naming`
replaced, still exists: the `baas-operator` profile in `~/.aws/config` assumes it successfully
(2026-10-05). The deployer cannot remove it (outside its prefix). Delete it, and the profile, with an
identity above the deployer.

### U8 — `runner.sourceRepo` can only be set by editing YAML · Info

`RunnerJarResolver` names it in its error message as the thing to change, but `config set` has no
option for it. A fork that releases its own runner edits `~/.baas/config.yaml` by hand.

## Informational

Kept for whoever next works in the area; none is a defect worth a change on its own.

- **U14.** After a teardown and setup, the AWS CLI (not `baas`) fails with `InvalidClientTokenId` on
  the operator profile until its cached session in `~/.aws/cli/cache` expires: the role was
  recreated with a new id. The SDK does not read that cache.

