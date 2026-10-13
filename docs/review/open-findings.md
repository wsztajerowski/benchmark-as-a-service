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
| E4 | The results table formats scores in the machine's locale (`10632008,341`) while JSON and CSV use `Locale.ROOT` | XS | Info | open |
| E5 | `jobs diff` columns shift when a value is wider than its column (CPU model names) | XS | Info | open |
| A4 | A store failure after the upload loses the measurement row | M | Med → reduced | open |
| S7 | A runner can overwrite another job's objects under `jobs/*` | M | Med → reduced | open |
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

### S7 — a runner can overwrite another job's objects under `jobs/*` · Could · M · reduced

Reduced by `narrow-bucket-grants` (2026-10-13): `RunnerRole` holds `PutObject`/`GetObject` on
`jobs/*` and `GetObject` on `releases/*` only — no delete, no listing, no write to the pinned runner.
What remains is that benchmark code can overwrite any job's objects under `jobs/*`, including its
`jmh-result.json`, since IAM has no per-job scope for an instance profile. Versioning is `Suspended`,
so an overwrite is unrecoverable. The same trust position as *Runners can terminate each other*
(CLAUDE.md, Accepted risks): only an operator supplies a benchmark JAR. **Would need:** per-job
credentials — e.g. the CLI vending a session-tagged role session to the instance — which is its own
change.

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

