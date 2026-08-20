# ADR 0001 — Self-contained `baas` CLI replaces the GitHub Actions dispatch path

- **Status:** Accepted — implemented and verified end-to-end against AWS
- **Date accepted:** 2026-05 (design), implemented through 2026-07
- **Amended by:** `openspec/changes/baas-cli-core-ci-split/`, `openspec/changes/baas-infra-hardening/`,
  `openspec/changes/dynamodb-results-store/` (measurements moved off MongoDB — see *Superseded*)
- **Supersedes:** `docs/redesign.md` (removed; this ADR is its distillation)

## Context

Running a benchmark required `scripts/run-remote-benchmark.zsh` to build a JAR, upload it to
S3, and trigger a GitHub Actions `workflow_dispatch`, which then provisioned an EC2 runner. A
developer could not run a benchmark without GitHub Actions being reachable, correctly
configured, and holding a valid MongoDB secret. The MongoDB connection string lived in two
unrelated places — a GHA secret for the runner, and a `BENCHMARK_DB_CONNECTION_STRING` env var
on the laptop for reading results. An S3-triggered Lambda added a third moving part.

## Decision

Replace that path with a self-contained Java CLI (`baas`) that provisions its own AWS
infrastructure, launches EC2 runners directly, and polls for results, with **no dependency on
GitHub Actions**. The GHA workflows remain, for automated CI only; the CLI neither dispatches
nor depends on them.

Five decisions shaped the design and still hold:

1. **MongoDB is connect-only.** The CLI never provisions a cluster. The user supplies a
   connection string; `baas` validates it and stores it in SSM as a SecureString.
2. **Public-egress networking, not a private subnet.** The runner sits in a public subnet with
   an internet gateway. This is what the GHA runner already required — it has to reach GitHub
   for async-profiler and the JDK — and it is how Atlas was already being reached.
3. **No GHA dependency in the application.** `baas` uses the operator's own AWS credentials.
   It never assumes the OIDC `WorkflowRole`.
4. **Scripts are removed only where `baas` replaces them.** Build and release helpers used by
   CI stay.
5. **Atlas IP allowlisting is manual.** No Atlas API integration.

## Consequences

**Gained:** benchmarks run without GitHub Actions; one source of truth for the MongoDB URI
(SSM SecureString, fetched at runtime, never written into user-data or CI logs); the Lambda and
its GitHub token are gone; IMDSv2 is enforced; the runner security group has no inbound rules;
instances self-terminate through three independent mechanisms.

**Accepted costs:** the runner has a public IP, which is a wider exposure than a private
subnet; `setup`/`teardown` need elevated IAM that day-to-day runs do not; and the Atlas access
list cannot be narrowed, because the runner's egress IP is ephemeral (see Risks).

## Rules that still bind

These are non-obvious invariants. Each exists because something broke without it, and the code
does not explain itself — change them only deliberately.

| Rule | Why |
|---|---|
| No `set -e` in the EC2 user-data script | If the IMDSv2 instance-id fetch fails under `set -e`, the script exits *before* starting the watchdog, orphaning the instance. Errors are handled by exit code and the `run-status` sentinel instead. |
| The watchdog starts immediately after `INSTANCE_ID` is resolved | Same reason: every later failure has to be covered by it. |
| S3 upload paths are request-ID-scoped (`runs/<requestId>/…`) | Two developers on the same branch otherwise overwrite each other's JARs mid-run. |
| Root volume is 30 GB gp3, not the AL2023 default | 8 GB is exhausted by profiling artifacts. |
| async-profiler is downloaded from GitHub on the instance | Public egress already exists; pre-staging it in S3 or adding a NAT gateway buys nothing. |
| The benchmark runs from `/app`, never from `/` | The runner scans below its working directory for `.log` files to upload. cloud-init starts user-data in `/`, so that walk covers the whole root filesystem and aborts on `/proc` entries that vanish mid-walk. |
| Three independent termination layers | Process `timeout`; a background shell watchdog that fires even if the JVM deadlocks; a CLI shutdown hook for Ctrl+C. Any one of them alone leaves a way to orphan an instance. |
| The Mongo URI is never placed in user-data | It is fetched from SSM at runtime so it stays out of instance metadata and CI logs. **Superseded** — there is no Mongo URI on the `baas run` path at all; the results table name travels in user-data, carrying no credentials. |
| An empty or unset Mongo URI selects `NoOpDatabaseService` | Benchmarks still run; measurements are discarded rather than failing the run. Useful for infrastructure testing, dangerous to hit unintentionally. **Superseded** — it was hit unintentionally, so absent store configuration is now a hard failure and discarding takes an explicit `--no-database`. |
| `benchmark-runner.jar` needs no code changes | It already reads `MONGO_CONNECTION_STRING`. The migration changed only where that value comes from. **Superseded** — the runner gained a storage-neutral `ResultsStore` port with DynamoDB, MongoDB and no-op adapters. |
| The CloudFormation template ships as a classpath resource | `baas admin setup` has no external file dependency. Only the core template ships; CI templates and policy JSONs are test fixtures. |
| `baas run` builds in the current working directory | That is the user's benchmark project, not this repo. |

## Risks

**Superseded since acceptance:** measurements no longer go to MongoDB. `openspec/changes/dynamodb-results-store/`
replaced it with a DynamoDB table reached over a gateway endpoint, one item per measurement, queried
by `baas results` without a driver on the CLI side. That closes the two database rows in the table
above and retires most of what this ADR assumed about an external database — a store selection is now
mandatory, and MongoDB survives only as a runner-local adapter for standalone use. Atlas is retained,
readable and unwritten until that change's §14 decommissions it.

**Closed since acceptance:**

- *Elevated IAM for setup/teardown* — resolved by splitting the elevated `BaasCliDeployerPolicy`
  from the narrow, stack-created `BaasCliOperatorRole`, with `aws.operatorProfile` deliberately
  not falling back to `aws.profile`.
- *SSM parameter collision during migration* — the migration is complete.
- *Private-subnet Atlas reachability* — resolved by reusing public egress.

**Still open:**

| Risk | Severity | Position |
|---|---|---|
| The runner's egress IP is ephemeral, so the Atlas access list has to be `0.0.0.0/0` | Medium | Accepted for v1; access is controlled by connection-string credentials rather than by network. A private subnet + NAT + Atlas PrivateLink is the alternative, needs a paid M10+ tier and ~$32/month standing cost. See `infra/README.md`. |
| The runner JAR is fetched from GitHub Releases without checksum verification | Low | Add SHA-256 verification from the release's checksum asset. |
| A shared `RunnerRole` can read any operator's Mongo URI — one SSM path | Low–Med | Per-operator SSM path prefixes if this ever becomes multi-tenant. Out of scope for v1. |
| The runner has a public IP | Low | Mitigated: no inbound security-group rules, IMDSv2 with hop limit 1, short-lived self-terminating instance. |
| `baas run` assumes a Maven project producing one JAR | Low | `--benchmark-jar` plus `--skip-build` covers other layouts. |

## Amendments since acceptance

The original design has been corrected in three material ways. Where this ADR and the current
code disagree, **the code wins**.

- **Single stack → core/CI split.** `baas admin setup` deploys only the core stack; the GitHub
  OIDC provider and `WorkflowRole` moved to a separately-deployed CI stack, so the local CLI's
  identity never needs `iam:CreateOIDCProvider`. Commands moved under `baas admin`.
  (`openspec/changes/baas-cli-core-ci-split/`)
- **Atlas is reached on TCP 27017, not 443.** The original security group allowed only 443/80,
  which meant every run failed at the database write. Now reverted along with everything else
  Mongo: the rule is gone, and a test pins its absence.
- **Bucket naming and retention.** The bucket is `baas-<prefix>`, declared
  `DeletionPolicy: Retain`. Because the prefix is a hash of the caller's ARN, a retained bucket
  blocks any later `setup` — so `baas admin teardown --delete-bucket` empties and deletes it
  explicitly (opt-in; the default retains), and setup detects and reports the collision.

Distribution beyond a shaded JAR — an install script, a Homebrew tap, jpackage bundles, a
native image, a Docker image — was specified but never built. It remains backlog, not a
decision this ADR records as made.
