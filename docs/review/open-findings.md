# Open findings

Every review finding that is **neither fixed nor accepted**, in one file, merged on 2026-10-05 from
the per-module review files (`baas-cli-findings.md`, `benchmark-runner-findings.md`,
`prebaked-runner-ami-review.md`) and the CLI usage analysis (`docs/analysis/cli-usage-analysis.md`).
Those files are gone; `git log -- docs/review docs/analysis` holds them.

- **IDs are kept from where each finding was filed** — `S`/`A`/`D`/`C` (module reviews), `P`
  (prebaked-image review), `U` (CLI usage analysis), `N` (new on 2026-10-05), `E` (live end-to-end
  test, 2026-10-10), and `<change>/W<n>` (a change's verify warning) — so commits, change artifacts
  and `openspec/config.yaml` rules that cite them still resolve.
- **Closing a finding deletes its entry here.** If the decision is worth keeping, it becomes an ADR
  in [`docs/adr/`](../adr/). ADRs 0002–0005 hold what was closed or declined up to 2026-10-05.
- **Excluded on purpose:** everything in CLAUDE.md's *Accepted risks*, and the hardenings and
  intended behaviour recorded in [ADR 0005](../adr/0005-declined-hardenings-and-intended-behaviour.md).
- State machines for the deployment, an operator machine and a job, which locate most `U`
  findings: `docs/diagrams/baas-states-*.mmd`.

## How findings are categorised

Each finding carries two grades, so the index reads as a work list.

- **Necessity.** *Must*: wrong output, lost data or a false green that someone will act on.
  *Should*: a security narrowing, a misleading step or a dead end a user meets in a normal workflow.
  *Could*: polish or tidying, safe to leave.
- **Effort.** *XS*: a message, a flag or a single line, plus its test, under an hour. *S*: one module
  and its tests, a few hours. *M*: an OpenSpec change of its own, about a day. *L*: a multi-day
  change touching several modules.

`Sev` is kept from the review that filed each finding. Where the two disagree, the necessity grade
decides what to pick up next.

## Index

### Must

| ID | Finding | Effort | Sev | Where it goes |
|---|---|---|---|---|
| E1 | `--async-output-type flamegraph,jfr`, which CI uses, writes empty flame graphs; CI checks only that an `.html` exists | S | Med | open |

### Should

| ID | Finding | Effort | Sev | Where it goes |
|---|---|---|---|---|
| S14 | `RunnerRole` can overwrite the pinned runner JAR every later job executes; the seed is a head-then-put race (seen live 2026-10-10) | S | Med | `narrow-bucket-grants` |
| S7 | `RunnerRole`'s bucket-wide `s3:DeleteObject`/`PutObject` (table half already fixed) | S | Med | `narrow-bucket-grants` |
| S15 | `OperatorRole`, and so every CI workflow, holds bucket-wide `s3:DeleteObject` that no command uses | S | Low | `narrow-bucket-grants` |
| U2 | Nothing exports a deployment's data before a teardown, which now deletes everything | M | Low | `export-before-teardown` |
| S2 | CI's OIDC trust is repo-wide and `pull_request` triggers it | M | High → reduced | open |
| S10 | Actions on mutable tags; dependabot covers only Maven | S | Low | open |
| E2 | Next-step hints omit `--deployment`, so with two deployments every one fails as printed | S | Low | open |
| pr83/W5 | A JCStress row's `mode` is the string `"null"` in CSV and JSON | XS | Low | open |
| pr83/W3 | `avgt` scores in `s/op` print as `0.000000` in JSON and CSV | S | Low | open |
| N2 | `3q7i7s65-operator-role` from the August deployment still exists | XS | Low | open, by hand |

### Could

| ID | Finding | Effort | Sev | Where it goes |
|---|---|---|---|---|
| jobs-command/W1 | `memory.totalKb` differs by a few KB between two instances of one type, so `jobs diff` never says "No differences" | S | Info | open, user's decision |
| E4 | The results table formats scores in the machine's locale (`10632008,341`) while JSON and CSV use `Locale.ROOT` | XS | Info | open |
| E5 | `jobs diff` columns shift when a value is wider than its column (CPU model names) | XS | Info | open |
| A4 | A store failure after the upload loses the measurement row | M | Med → reduced | open |
| N3 | A failed run-item reservation strands the uploaded `input/` | S | Low | open |
| C4 | MongoDB retirement; every service IT runs on the Mongo adapter | L | — | `retire-mongodb` |
| A13 | `LocalStorageService` reads every file as UTF-8 to trace-log it; binary output throws | XS | Low | `retire-mongodb` |

### Informational

| ID | Finding | Where |
|---|---|---|
| U14 | After a teardown and setup, the AWS CLI fails on the operator profile until its cached session is removed (recurred 2026-10-10) | last section |
| E6 | One instance type can land on different CPU generations, so `instanceType` alone does not make results comparable | last section |

IDs `E<n>` were filed by the live end-to-end test of v7.0.0 on 2026-10-10; its results are in
[`openspec/changes/pr83-review-fixes/verify.md`](../../openspec/changes/pr83-review-fixes/verify.md),
*Full end-to-end test*. `<change>/W<n>` are verify warnings from that change's `verify.md` that
describe product behaviour rather than a test gap, moved here so that this file stays the one list.

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
   **Seen live on 2026-10-10:** two `baas run` started in the same second on a freshly set-up
   deployment both logged `Seeding runner JAR releases/7.0.0/benchmark-runner.jar` (08:10:13 and
   08:10:21, the first upload still in progress), so the key was written twice. Harmless that time,
   since both copies were checksum-verified against the same release asset, but the "seeded once"
   rule held only by luck (`RunnerJarResolver.java:52-61`, `exists` then `putObject`).
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

**Seen live on 2026-10-10:** keeping `baas-development`'s history across the end-to-end test's
teardown took `aws s3 sync s3://baas-development … --exclude 'releases/*' --exclude '*/input/*'`
and `aws dynamodb scan --table-name baas-development-results` (46 items) under the operator profile.
That is the only way today, and the scan's JSON cannot be loaded back into a new deployment by any
`baas` command. Teardown's warning was accurate: everything was gone afterwards.

## Open, not scheduled

### E1 — `flamegraph,jfr` writes empty flame graphs, and CI passes them · Must · S

The runner passes `--async-output-type` straight into JMH's `-prof async:output=…`
(`JmhWithAsyncProfilerSubcommandService.createAsyncCommand`). With `flamegraph,jfr`, JMH starts
async-profiler with `file=…/jfr-wall.jfr`, so samples stream into the recording. The flame graphs
dumped at the end then find nothing in memory: async-profiler logs `[INFO] All samples were
filtered out` once per graph, and both HTML files hold the single frame `all` with 2 samples
(about 8.7 KB each). The JFR is intact: 344,047 `profiler.WallClockSample` events in 4.9 MB.

Seen live on 2026-10-10 in three jobs (`--async-event=wall --async-output-type=flamegraph,jfr
--async-additional-param threads=true`). The same job with `flamegraph` alone gave a full graph
(399 frames, per-thread roots, `incrementUsingSynchronized` present), as did an earlier
`cpu`/`flamegraph` job (3018 frames). So the cause is requesting `jfr` together with another
format, not wall-clock mode. Whether `cpu` + `flamegraph,jfr` fails the same way was not tested.

`e2e-cloud-test.yml` requests exactly that combination and asserts only that some `*.html` and
`*.jfr` exist (lines 187-190), so CI has been green on empty graphs. The CLAUDE.md claim that a bad
bake "fails CI rather than surviving it" holds for the profiler starting, not for it producing a
usable graph.

**Proposed:** (1) in CI, request `flamegraph` alone, or keep both formats and assert that the
graph's `cpool` holds more than `'all'`, so the check is about content and not existence; (2) in
the runner, when `jfr` is combined with another format, either refuse the combination before the
benchmark starts or render the other formats from the JFR afterwards (async-profiler's
`jfrconv`). Refusing is the smaller change and fails loudly; converting keeps both artifacts.

### E2 — next-step hints omit `--deployment` · Should · S

With two deployments configured, every command refuses without `--deployment`. Yet the hints the
CLI prints for the next step are bare, so each one fails exactly as printed:

- setup's closing text: `baas admin image build` (`SetupCommand.java:445`; its teammate step
  already carries `--deployment` since `derived-deployment-config`);
- the no-image refusal: `Build one:  baas admin image build` (`RunCommand.java:357`), which also
  says "for this account (us-east-1)" where a deployment is meant;
- the end of a run: `baas results query --job-id …` (`RunCommand.java:878`, and `:620` on failure).

Seen live on 2026-10-10: setup's printed `config set` was refused with "2 deployments are configured".
**Proposed:** one helper that renders a hint with `--deployment <name>` whenever more than one
deployment is configured (or always, which is simpler and never wrong), used at each site.

### pr83/W5 — a JCStress row's `mode` is the string `"null"` · Should · XS

Filed in `openspec/changes/pr83-review-fixes/verify.md`; seen again live on 2026-10-10 (`"mode":"null"`
in `results query --format json`). A JCStress job has no mode, and the literal text `null` reaches
CSV and a JSON string. **Proposed:** an empty CSV cell and a JSON `null`, as R9 did for the score.

### pr83/W3 — `avgt` results in `s/op` print as zero · Should · S

Filed in the same `verify.md` (live J2): `avgt` defaults to `s/op`, so a nanosecond-scale score prints
as `0.000000` at the fixed 6 decimals of JSON and CSV. A real measurement is shown as zero.
**Proposed:** format JSON and CSV by significant digits (or emit the full double), keeping
`Locale.ROOT`.

### S2 — CI's OIDC trust is repo-wide and `pull_request` triggers it · Should · M · reduced

`OperatorRole`'s federated statement matches `repo:<org>/<repo>:*` — every ref, PR branch and tag —
and `e2e-cloud-test.yml` runs on `pull_request`, so "can push a branch to the repository" means
"holds operator credentials". Reduced since filing: the job runs on `ubuntu-latest`, not a
self-hosted runner, fork PRs cannot obtain an `id-token`, and the operator role is the narrow one.
**Proposed:** pin `sub` to `repo:<org>/<repo>:ref:refs/heads/main` plus an
`environment:<name>` entry, put the E2E job behind an environment with required reviewers, and
replace the bare `pull_request` trigger with a label gate. S16 showed how this compounds with any
compromised step in a federated job.

### A4 — a store failure after the upload loses the measurement row · Could · M · reduced

All four services order S3 first, then the store, on purpose: the item points at `resultJsonKey`, so
writing it first could publish a row referencing an object never uploaded. Writes are idempotent and
batched, and a failed write exits non-zero with the S3 artifacts intact (asserted by an IT). What
remains: the row itself is lost and nothing re-derives it from `jmh-result.json`.

### S10 — supply chain · Should · S · reduced

`.github/dependabot.yml` covers only `maven`, so no action ever gets an update PR, and every action
is pinned to a mutable tag (`actions/*@v4|v5`, `aws-actions/configure-aws-credentials@v5`). The
third-party `machulav/ec2-github-runner` that made this sharper is gone. **Proposed:** add the
`github-actions` ecosystem; SHA-pin the actions in jobs that hold AWS credentials.

### N3 — a failed reservation strands the uploaded `input/` · Could · S

`baas run` uploads `benchmark.jar` (and a `--runner-jar`) before it reserves the job item, so a
reservation that cannot be written leaves `jobs/<project>/<jobId>/input/` with no job item: invisible
to `runs list` and never expired (no lifecycle rule under `jobs/`, by design). U25 removed the other
way into this state. Reserving first would instead leave a `launching` item for a failed upload,
which lists as `vanished` — arguably the better failure.

### N2 — the August deployment's operator role is still in the account · Should · XS

`3q7i7s65-operator-role`, from the caller-ARN naming that `account-derived-deployment-naming`
replaced, still exists: the `baas-operator` profile in `~/.aws/config` assumes it successfully
(2026-10-05). The deployer cannot remove it (outside its prefix). Delete it, and the profile, with an
identity above the deployer.

### jobs-command/W1 — `memory.totalKb` noise in `jobs diff` · Could · S

Filed in `openspec/changes/archive/2026-10-07-jobs-command/verify.md`, left for the user to decide.
Seen again on 2026-10-10: a re-run of the same job on the same instance type (`c5.2xlarge`) differed
in nothing but `memory.totalKb` (15882372 vs 15882380, 8 KB), so `jobs diff` reported `Differs in:
memory` for two environments that are equivalent for measurement purposes. Candidates are unchanged:
compare memory in MiB, or print `totalKb` but leave it out of the verdict.

### E4 — the results table formats scores in the machine's locale · Could · XS

`ResultsTable.java:97` uses `String.format("%.3f", value)` with the default locale, so on pl-PL the
table shows `10632008,341` while JSON and CSV (`Locale.ROOT`, a CLAUDE.md invariant) show
`10632008.341`. Not wrong for a human-read table, but the same number reads differently between
views and between machines. **Decide:** keep the locale on purpose (and say so in CLAUDE.md), or use
`Locale.ROOT` everywhere.

### E5 — `jobs diff` columns shift on a long value · Could · XS

`JobsDiffSubcommand` builds a fixed-width `Table` (91 columns). A value wider than its column, such as
`Intel(R) Xeon(R) Platinum 8124M CPU @ 3.00GHz`, pushes the next column right instead of being
clipped or wrapping, so that row no longer lines up with the header. Seen 2026-10-10 on c5 vs c6i.

## Informational

Kept for whoever next works in the area; none is a defect worth a change on its own.

- **U14.** After a teardown and setup, the AWS CLI (not `baas`) fails with `InvalidClientTokenId` on
  the operator profile until its cached session in `~/.aws/cli/cache` expires: the role was
  recreated with a new id. The SDK does not read that cache. **Recurred 2026-10-10** after the
  end-to-end test's teardown and setup: `aws --profile baas-operator-baas-dev sts get-caller-identity`
  failed while `baas` on the same profile worked; deleting the one cache file naming
  `baas-development-role-operator` fixed it. It matters only to someone using the AWS CLI beside
  `baas`, such as when backing up data (U2) or removing a leftover. A one-line note in teardown's or
  setup's output would save the search.
- **E6.** One instance type does not pin the CPU. On 2026-10-10, `c5.2xlarge` jobs ran on a
  Skylake `Platinum 8124M`, and the day before on a Cascade Lake `Platinum 8275CL`: same image, kernel,
  JDK and packages. `jobs diff` reports it (`cpu.model`), and `cpuModel` is a tag, so
  `--best-per cpuModel` or `--tag cpuModel=…` separates them. `--best-per instanceType` alone mixes
  CPU generations. Worth a sentence in the docs of `results query`, not a code change.

