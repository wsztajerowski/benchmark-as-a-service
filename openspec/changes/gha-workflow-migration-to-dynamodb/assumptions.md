# Pre-brainstorm assumptions

> **This is not an artifact.** The schema's first artifact is `brainstorm`, which is deliberately
> still unstarted — this file exists so that session can begin from established facts instead of
> re-deriving them, and so the open questions are not silently resolved by whoever happens to
> write the proposal. Captured 2026-08-20, immediately after `dynamodb-results-store` §14.

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

Whichever wins, the workflow should capture and pass `jdk`, `cpuModel`, `cpuArch` and `instanceType`
as `--tag`s the way `UserDataScriptBuilder` does — otherwise those rows record no environment at all
and `baas env diff` has nothing to work with.

## Open questions for the brainstorm

1. **Does anything else write to or read from `baas-lynx-main`?** Decides whether fixing A10 is a
   rename or a data migration.
2. **Will Java in Wonderland ever be benchmarked via `baas run` as well, or is GHA its only path?**
   If GHA is the only path, option 2 is sufficient and option 1 becomes a quality choice rather than
   a correctness one.
3. **Is an `imageVersion` bump to bake in `docker`/`git`/`libicu` acceptable?** This is the pivot for
   option 1.
4. **Must `benchmark-runner.yml` stay usable by repos with no BaaS stack of their own?** Determines
   whether the prefix is one fixed value or genuinely per-caller — and therefore whether the table
   name can be derived from `RESOURCE_NAME_PREFIX` or has to be resolved another way.
5. **Does the GHA path need `jcstress` and the profiler variants**, or only plain `jmh`? The e2e
   workflow exercises all of them; real automated runs may not need to.

## Proposed table-name resolution (weak preference, not decided)

Derive it as `baas-<prefix>-results` from the existing `RESOURCE_NAME_PREFIX` variable, mirroring
how the bucket is `baas-<prefix>`. That variable already exists, and the current workflow's own
error message already instructs the operator to set it to match the stack — so it stays one value
to keep in sync rather than two. Alternative worth weighing: read the core stack's
`ResultsTableName` output at runtime via `cloudformation:DescribeStacks`, which is self-correcting
but needs a new grant on `WorkflowRole`.
