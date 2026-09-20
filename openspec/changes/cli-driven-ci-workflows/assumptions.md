# Pre-brainstorm assumptions

> **This is not an artifact.** The schema's first artifact is `brainstorm`, which is deliberately
> still unstarted — this file exists so that session can begin from established facts instead of
> re-deriving them, and so the open questions are not silently resolved by whoever happens to
> write the proposal. Captured 2026-08-20, immediately after `dynamodb-results-store` §14.
>
> Extended 2026-09-10 after reading the java-wonderland and lynx-journal working copies and
> walking the current CI against `RunCommand`/`UserDataScriptBuilder`. Sections marked with
> that date are the second vintage; they add facts and reframe questions, and settle none.
>
> **Renamed 2026-09-17 from `gha-workflow-migration-to-dynamodb`.** DynamoDB stopped being what
> this change is about: option 4 below — CI stops provisioning and calls `baas run` — is the
> direction taken, and under it the connection string is not migrated but deleted along with the
> four workflows that read it. Archived changes still cite the old name
> (`2026-08-20-dynamodb-results-store`, `2026-09-09-unified-run-prefix`,
> `2026-09-17-installable-cli-command`); those are historical records and are deliberately not
> rewritten. This note is the trail.
>
> **Two corrections, 2026-09-20, from the explore session recorded in `explore.md`.** First, the
> opening sentence above is stale: `brainstorm` was the retired `superspec` schema's first
> artifact. Under the stock `spec-driven` schema this change now uses, the artifacts are
> `proposal`, `specs`, `design` and `tasks`, and explore replaces brainstorming — so `explore.md`,
> not a brainstorm, is what feeds `/opsx:propose`. Second, the claim under "Options" that an
> `exclude_from_results=true` run stays "reachable by `--request-id`/`--tag`" is **false against
> the code**: `ResultsQueryService` applies `EXCLUDE_FILTER` to `queryByRequestId` as well as
> `queryProject`. See `explore.md` F4. The decision taken (D7) makes the assumption true rather
> than working around it.

## Why this change exists

`dynamodb-results-store` cut **`baas run`** over to DynamoDB and then, in §14, removed MongoDB from
the infrastructure entirely: the `/<prefix>/mongo/connection-string` parameter, every IAM grant
naming it, and the runner's TCP 27017 egress.

The GitHub Actions path was never in that change's scope. It still writes to MongoDB, so it is
**broken as of §14** — `exec-single-benchmark.yml` reads the deleted parameter and exits 1.

The decision (2026-08-20) is to **keep the workflow**, because it serves automated benchmark runs
for other repositories (e.g. Java in Wonderland), and to point it at the same bucket and table the
CLI reads — without invoking `baas` inside GitHub Actions. The runner JAR stays the only BaaS
binary the workflow runs.

## How consumers actually integrate — verified 2026-09-10

Read out of the java-wonderland and lynx-journal working copies, not assumed.

**`benchmark-runner.yml` is a migrated java-wonderland workflow.** It was created there on
2024-05-27 with `request_id` / `result_prefix` / `s3_result_bucket` / `benchmark_path` /
`benchmark_type` / `parameters` — near-identical to the inputs it still carries here — removed
there on 2024-06-02, and now lives in this repo. The runner modules came the same way: the
`chore/benchmark-as-a-service` branch still holds `test-wrapper/`, `fake-jmh-benchmarks/`,
`fake-stress-tests/` and `pl.symentis.commands.ApiCommonSharedOptions`, followed by
`chore: remove migrated modules`. **The requirement this change serves predates this repository**;
it is the founding one, not a new ask.

**The integration model is one installation per consumer, sharing only the runner JAR.**
java-wonderland's `aws-tests.yml` on `main` is triggered by `pull_request` and `push: main` — the
automated-on-PR behaviour this change is meant to preserve — and runs entirely on its own
infrastructure:

| | java-wonderland | BaaS |
|---|---|---|
| AWS account | `653932013369` | `381492019823` |
| Region | `eu-north-1` | `eu-central-1` |
| Workflow role | `java-wonderland-github-actions-workflow-role` | `<prefix>-github-actions-workflow-role` |
| Runner role | `java-wonderland-github-actions-runner-role` | `<prefix>-runner-role` |
| AMI | `ami-099451d897f38cebe`, pinned by hand | `/<prefix>/runner/ami-id` |
| Bucket | `java-wonderland` | `baas-<prefix>` |
| Results store | its own Atlas, via `secrets.MONGO_CONNECTION_STRING` | DynamoDB |

The only thing it takes from BaaS is `benchmark-runner.jar`, via `release-downloader` — commit
`87760af`, "use benchmark runner from BaaS repo for all tests", which is also the last commit on
its `main`.

**So there is no multi-repo OIDC trust problem, because no consumer has ever assumed this repo's
`WorkflowRole`.** The single-repo `StringLike sub: repo:${Org}/${Repo}:*` scoping in
`cf-template-ci.yaml` is not a limitation anyone has hit. It only becomes one under a shared-tenant
model that has never existed — and the rest of the system is built the other way: `prefix =
lowercase(base32(sha256(caller-arn)))[0:8]`, stack and bucket both `baas-<prefix>`, derived from
caller identity and not user-selectable. Nothing in the design is multi-tenant.

**java-wonderland's `main` is frozen at 2024-06-22 and would fail against today's runner anyway.**
Its invocation is `java -jar benchmark-runner.jar jmh --s3-bucket java-wonderland
--s3-result-prefix … -id …`: `--s3-result-prefix` no longer exists (it is `--result-path`), no
`--project` is passed so `getProject()` throws, and no store is selected so the
exactly-one-of check fails. Whatever shape wins, that file is rewritten — which makes it a free
opportunity rather than a migration cost.

**java-wonderland is the standalone MongoDB user.** CLAUDE.md retains the Mongo path in
`benchmark-runner` "for standalone use only … against a user's own MongoDB" without naming who.
This is who: its own Atlas, its own secret, its own account, no BaaS infrastructure in the loop.
If java-wonderland moves onto a BaaS installation, that justification goes with it.

## Established facts — verified by reading the code, not assumed

**The stacks are already connected, and the connection was never Mongo-specific.**

- `start-ec2-runner.yml` launches the instance with `iam-role-name: ${{ secrets.runner-role-name }}`
  — the **core stack's `RunnerRole`**, the same role `baas run`'s instances use.
- After §14 that role holds `<prefix>-runner-dynamodb-policy` (`PutItem` + `BatchWriteItem` on the
  results table), `<prefix>-runner-s3-policy` (bucket-wide) and `<prefix>-runner-ec2-terminate-policy`.
- `ResultsStoreBuilder` builds its client with `DynamoDbClient.builder().build()` — the default
  credential chain, which on EC2 resolves the instance profile.

**Therefore the JAR can already write to the results table from a GHA-provisioned instance. No new
IAM, no new trust relationship, no cross-account anything.**

- `WorkflowRole` does **not** need DynamoDB. It provisions the instance; the JAR writes under the
  instance profile. That separation is already correct and should stay.
- `cf-template-ci.yaml` already takes `RunnerRoleArn` and `BucketName` as parameters from the core
  stack, so parameterising on core-stack outputs is an existing pattern, not new architecture.

**The change surface is small.**

| File | Change |
|---|---|
| `exec-single-benchmark.yml` | Drop the "Fetch MongoDB URI from SSM" step; add `--results-table` and `--project` to the `java -jar` invocation |
| `benchmark-runner.yml` | New `project` input; fix the `s3_result_bucket` default |
| `cf-template-ci.yaml` | Add a `ResultsTableName` parameter for symmetry with `BucketName` (documentation value; no grant needed) |
| `e2e-cloud-test.yml` | Replace the mongosh verification (~lines 170-192) with DynamoDB queries; drop `MONGOSH_VERSION` |

**Carries open finding A10.** `s3_result_bucket` defaults to `baas-lynx-main` and
`e2e-cloud-test.yml` hardcodes it in `env`. The ARN-hash prefix scheme can never generate that
name, so CI has been writing to a hand-built bucket `baas admin setup` cannot reproduce. Same files,
same change — fixing it here is natural, not scope creep.

## The orchestration ledger — added 2026-09-10

`baas run` and the GHA path are not "the CLI vs. CI". They are two *orchestrators* driving the same
`benchmark-runner.jar`, and they diverge because of one decision: GHA's instance has to be a
**GitHub Actions worker** (`machulav/ec2-github-runner`), so it cannot be the baked measurement AMI,
so it needs `yum update`, `setup-java`, docker/git/libicu and a PAT. Every environment divergence
below descends from that one choice. `machulav` is the root cause; the Mongo connection string is
a symptom.

Twelve orchestration responsibilities, and who discharges them today:

| # | Responsibility | `baas run` | GHA path |
|---|---|---|---|
| 1 | Resolve project | git repo name, throws if absent | `${GITHUB_REPOSITORY##*/}` (e2e only) |
| 2 | Resolve results store | fail-fast before build | SSM Mongo fetch → `exit 1` |
| 3 | Resolve the AMI | `/<prefix>/runner/ami-id`, no fallback | AL2023 latest + `yum update -y` |
| 4 | Build benchmark JAR | `mvn` in cwd | `mvn` (e2e) / caller's problem |
| 5 | Mint run identity | one clock read → id + `createdAt` + prefix | bash `date` + `openssl rand` |
| 6 | Stage benchmark JAR | `RunLayout.benchmarkJarKey` | hand-built `$path/input/…` |
| 7 | Pin + verify runner JAR | `releases/<v>/`, sha256-verified, seeded once | `release-downloader latest`, unpinned, unverified |
| 8 | Capture the environment | `environment.json` + `packages.txt`, before the run | **nothing** |
| 9 | Emit observed tags | 5 `--tag`s from observed shell variables | **nothing** |
| 10 | Termination safety | watchdog + `timeout` + shutdown hook | `stop-runner`, `if: always()` |
| 11 | Status sentinel | `run-status` + `cloud-init-output.log` | GHA job status |
| 12 | Report results | DynamoDB query by run id | mongosh (e2e only) |

Six re-implemented differently, four not done at all, two roughly equivalent. The "change surface
is small" table above is accurate *for option 3 below* and for nothing else.

## The load-bearing problem: two environments, one table

`baas run` boots the **baked runner AMI**: pinned parent AMI, pinned Corretto, perf, async-profiler,
declared kernel tunables. The GHA path does none of that:

- `start-ec2-runner.yml` resolves `/aws/service/ami-amazon-linux-latest/al2023-...` — whatever is
  latest that day
- its `pre-runner-script` runs `sudo yum update -y` — **exactly what finding A8 removed from the CLI
  path**, for exactly the reason that it unpins the OS under a benchmarking tool
- `actions/setup-java` installs Temurin; the AMI has Corretto
- async-profiler is downloaded per run rather than baked

So GHA results drift **against each other over time**, not merely against `baas run`. Filing them in
the same table means `baas results` — which groups by `(benchmark, branch)` and keeps the best score
— could let a lucky kernel outrank a real regression.

Per this change's schema rule, any proposal here must state explicitly what it does to comparability.

### Options (for the brainstorm to decide, not settled here)

1. **Boot the baked AMI in GHA too** — replace the `latest-ami-id` lookup with a read of
   `/<prefix>/runner/ami-id`. Makes results genuinely comparable across both paths, makes
   `imageVersion` meaningful, and extends A8's fix to the path it never covered. Cost: the image
   needs `docker`, `git` and `libicu` baked in for the GitHub runner agent — one `runner-image.yaml`
   edit and an `imageVersion` bump (~15-minute rebuild).
2. **Separate project partition** (e.g. `--project <repo>-ci`). Cheap and honest. Sufficient *if a
   repo is benchmarked only through GHA*, since its rows are then internally consistent. Does not
   address drift over time.
3. **Same partition, `exclude_from_results=true`.** Reachable by `--request-id`/`--tag`, excluded
   from grouping. Appropriate for the e2e *fixture* runs; wrong for real measurements.
4. **Added 2026-09-10 — GHA stops provisioning and calls `baas run`.** The workflow runs on
   `ubuntu-latest`, does `baas config sync --core-stack-name …` then `baas run`, and the benchmark
   instance goes back to being a plain batch executor driven by user-data. All twelve ledger
   responsibilities collapse onto code that already exists.

**Option 4 dominates option 1** and the doc did not see it, because option 1 priced in an
`imageVersion` bump to bake `docker`/`git`/`libicu` for the GitHub agent. That cost exists *only
because the instance is a GitHub runner*. Stop making it one and the baked AMI is used unmodified —
same comparability, no image edit, no rebuild, no PAT. Option 4 also deletes
`start-ec2-runner.yml`, `stop-ec2-runner.yml`, `exec-single-benchmark.yml`, the `machulav` action,
the `yum update`, the `setup-java`, the per-run async-profiler download and the bash id-minting.

Two facts that make option 4 cheaper than it sounds, both verified 2026-09-10:

- **`WorkflowRole` already holds most of `operator-policy.json`** — `ec2:RunInstances`,
  `DescribeInstances`, tag-scoped `TerminateInstances`, `iam:PassRole` on `RunnerRole`,
  `ec2:CreateTags`, and S3 on the bucket. It is missing four statements: `ssm:GetParameter` on
  `/<prefix>/runner/ami-id` (it holds only the public AL2023 path), `ec2:DescribeImages`,
  `cloudformation:DescribeStacks`, `dynamodb:Query`.
- **Or zero statements** — `OperatorRole`'s trust is the account root, so `WorkflowRole` can chain
  into `<prefix>-operator-role` (`configure-aws-credentials` supports `role-chaining`) and CI then
  runs under exactly the policy the CLI runs under, by construction, with no second copy to keep in
  sync. It would need `sts:AssumeRole` on that ARN in its own identity policy.

Costs of option 4, stated so the brainstorm weighs them rather than discovers them: `ubuntu-latest`
minutes spent polling (against which total *EC2* time falls, since the paid instance no longer sits
through `yum update`, `setup-java` and the downloads); loss of the `env.ACT`/LocalStack workflow
test, since `baas run` has no local mode; and `--benchmark-jar` takes a `Path`, not an `s3://` URL,
so a caller supplying a pre-built JAR needs an `aws s3 cp` step first.

One thing option 4 gains that is not about CI: CLAUDE.md records that `RunCommand.call()` is
executed by no test at all. Under option 4 the e2e workflow becomes its first real coverage, using
`--runner-jar ./benchmark-runner/target/benchmark-runner.jar` — already the documented escape hatch
for unreleased builds, so no new option is needed.

Whichever wins, the workflow should capture and pass `jdk`, `cpuModel`, `cpuArch` and `instanceType`
as `--tag`s the way `UserDataScriptBuilder` does — otherwise those rows record no environment at all
and `baas env diff` has nothing to work with.

## Open questions for the brainstorm

0. **Added 2026-09-10, and it comes first because it reframes the rest: one shared BaaS
   installation, or one per consumer?** The historical answer was per-consumer — java-wonderland
   ran its own account, roles, AMI, bucket and Atlas, and took only the JAR. Keeping that costs an
   AMI bake and a ~$0.20/month snapshot per installation and leaves results siloed, with no
   cross-project query from anywhere. Going shared makes `baas results` able to compare projects
   and gives one AMI to maintain, but needs multi-repo OIDC trust — and cross-account trust if
   consumers keep their own accounts — which is genuinely new work nothing has needed yet.
1. **Does anything else write to or read from `baas-lynx-main`?** Decides whether fixing A10 is a
   rename or a data migration.
2. **Will Java in Wonderland ever be benchmarked via `baas run` as well, or is GHA its only path?**
   If GHA is the only path, option 2 is sufficient and option 1 becomes a quality choice rather than
   a correctness one.
   **Revised 2026-09-10:** under question 0's per-consumer answer this largely dissolves — its rows
   would sit in its own table, internally consistent by construction. Under the shared answer it
   stays live and sharpens, since both paths would then write to one partition.
3. **Is an `imageVersion` bump to bake in `docker`/`git`/`libicu` acceptable?** This is the pivot for
   option 1.
4. **Must `benchmark-runner.yml` stay usable by repos with no BaaS stack of their own?** Determines
   whether the prefix is one fixed value or genuinely per-caller — and therefore whether the table
   name can be derived from `RESOURCE_NAME_PREFIX` or has to be resolved another way.
   **Revised 2026-09-10:** this is question 0 seen from the workflow's side, and it also decides
   what `benchmark-runner.yml` *is* — a reusable workflow other repos call, or a thing only this
   repo's e2e test uses. Note that under option 4 the resolution question disappears:
   `baas config sync --core-stack-name` reads the table from stack outputs, self-correcting, using
   the `DescribeStacks` grant the operator role already holds.
5. **Does the GHA path need `jcstress` and the profiler variants**, or only plain `jmh`? The e2e
   workflow exercises all of them; real automated runs may not need to.

## Proposed table-name resolution (weak preference, not decided)

Derive it as `baas-<prefix>-results` from the existing `RESOURCE_NAME_PREFIX` variable, mirroring
how the bucket is `baas-<prefix>`. That variable already exists, and the current workflow's own
error message already instructs the operator to set it to match the stack — so it stays one value
to keep in sync rather than two. Alternative worth weighing: read the core stack's
`ResultsTableName` output at runtime via `cloudformation:DescribeStacks`, which is self-correcting
but needs a new grant on `WorkflowRole`.

## Third blocker, observed 2026-09-08 — the deployed CI stack is out of sync

Found while closing `unified-run-prefix`, and moved here because it belongs to this change, not
that one. PR #53's `e2e-cloud-test` run failed *before* reaching the connection-string check:

```
User: arn:aws:sts::381492019823:assumed-role/baas-lynx-github-actions-workflow-role/GitHubActions
is not authorized to perform: s3:PutObject on resource:
"arn:aws:s3:::baas-lynx-main/runs/benchmark-as-a-service/20260904T200915130Z-ce8ee1d3/input/runner.jar"
because no identity-based policy allows the s3:PutObject action
```

**This is not fallout from the unified layout.** `cf-template-ci.yaml` has granted
`${BucketName}/runs/*` since `adfb448` (2026-07-22), the original core/CI split;
`unified-run-prefix` only deleted the retired `ci/*` sibling. So the template has been correct for
this the whole time and the *deployed* role has not.

`no identity-based policy allows` means the role holds no `PutObject` on that bucket at all — not
that a prefix condition failed. Three explanations fit, and they were not distinguished because the
`baas-lynx` environment is unreachable from the `3q7i7s65` deployer identity (prefix-exact policy;
even `cloudformation:DescribeStacks` on `baas-lynx-*` is refused):

1. the deployed stack predates the `runs/*` grant,
2. it was deployed with a `BucketName` parameter other than `baas-lynx-main` — note the workflow
   hard-codes `S3_BUCKET: baas-lynx-main` in its `env:` block rather than reading a stack output, so
   the two can drift silently, or
3. the role was not created from this template at all.

**Whoever picks this change up should check which, before assuming a redeploy fixes it** — under
explanation 2 a redeploy changes nothing.

### Known blockers on `e2e-cloud-test.yml`, in the order they fire

| # | Blocker | Status |
|---|---|---|
| 1 | `Start EC2 runner` — `GitHub Registration Token receiving error / Bad credentials` | `GHA_EC2_PAT` expired; needs a new classic token with `repo` scope |
| 2 | `Build` — `AccessDenied` on `s3:PutObject` to `runs/.../input/runner.jar` | This section |
| 3 | `exec-single-benchmark.yml` — SSM `mongo/connection-string` deleted, `exit 1` | The original reason for this change; not yet reached in any recent run |
| 4 | `Verify S3 results` — `environment.json` asserted but never written on this path | Found 2026-09-10; see below |
| 5 | `benchmark-runner.yml` — no `project` input, and `getProject()` now throws | Found 2026-09-10; e2e is unaffected, the dispatch path is not |

Blocker 3 has never actually fired in a recent run: its jobs report `skipping`, because 1 and 2 fail
upstream first. Fixing only the connection string would therefore not turn the workflow green, and a
green run is the only real proof this change works.

**Blocker 4 — `environment.json` is written only by `UserDataScriptBuilder`.** Nothing in
`benchmark-runner` writes it; the GHA path has no user-data at all. Yet `e2e-cloud-test.yml:214`
and `:219` assert it exists, added in `bc21b16` during the unified-run-prefix work. That assertion
is structurally unsatisfiable while GHA provisions its own runner, so clearing blockers 1-3 reaches
a *new* red rather than green. Two ways out, both for the brainstorm: option 4 above (user-data
writes it, as on `baas run`), or move the manifest into the runner JAR — which has an independent
argument for it, since the manifest is the observation of the machine the benchmark ran on and the
runner is the only thing executing on that machine in both paths. That would also make it testable
in Java rather than only through `UserDataScriptBuilderTest` string assertions.

**Blocker 5 — `benchmark-runner.yml` has no `project` input.** `ApiCommonSharedOptions.getProject()`
now throws instead of falling back to `"unknown"`, so the moment `--results-table` is added that
workflow fails on an unresolved project. `e2e-cloud-test.yml` passes `--project` inside its
`parameters` string and is therefore covered; the dispatch path is not. The "change surface" table
above already lists the new input — this records *why* it is load-bearing rather than cosmetic.
