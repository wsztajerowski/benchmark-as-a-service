# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Benchmark as a Service (BaaS)** — a cloud-native benchmark orchestration system that runs JMH and JCStress benchmarks on EC2 instances, stores results in S3 and MongoDB, and exposes everything through a picocli CLI.

## Build & Test Commands

```bash
# Build all modules (unit tests only)
mvn clean package

# Build + run integration tests (requires Docker for TestContainers)
mvn clean verify

# Run a single test class
mvn -pl benchmark-runner test -Dtest=MyTestClass

# Run a single integration test
mvn -pl benchmark-runner verify -Dit.test=MyIT

# Build without tests
mvn clean package -DskipTests
```

Integration tests (`*IT.java`) spin up LocalStack (S3) and MongoDB via TestContainers. Running `mvn clean verify` copies `fake-jmh-benchmarks.jar` and `fake-stress-tests.jar` into `benchmark-runner/target/` during the pre-integration-test phase.

## Local Development Environment

```bash
# Start LocalStack (S3, SSM) + MongoDB + MongoDB Express
docker-compose up

# Run a local benchmark against LocalStack S3 + local MongoDB (local_test db)
./jmh-with-profiler.sh   # JMH's own profilers
./jmh-with-async.sh      # async-profiler; needs ASYNC_PATH set to a local libasyncProfiler
```

Both use the `fake-jmh-benchmarks` fixture. `docker-compose` has no init container, so create the
bucket first: `aws --endpoint-url=http://localhost:4566 --profile localstack s3 mb s3://baas`.

The `.env` file contains LocalStack credentials used by Docker Compose and tests.

## Running Benchmarks Remotely

`baas` is the supported path. It provisions its own infrastructure and needs no GitHub Actions.

```bash
# One-time: deploy the core stack and store the MongoDB URI in SSM (needs deployer credentials)
baas admin setup --mongo-uri "mongodb+srv://user:pass@host/db"

# Provision EC2, run, poll for results
baas run jmh -- MyBenchmark -f 1 -wi 1 -i 3

# Query aggregated results from MongoDB
baas results
```

The zsh helpers that used to drive this (`run-remote-benchmark.zsh`, `wait-for-gha-run.sh`,
`benchmark_overview.sh`, plus the `logger.sh`/`git_helpers.sh`/`aws_helpers.sh` they sourced)
have been **removed** — `baas run` and `baas results` replace them. `scripts/` now holds only
`get-version-property.sh`, `get-version-property-simple.sh`, and `update-dependencies.sh`:
standalone developer utilities for POM version inspection and dependency bumps. Nothing in CI
invokes them; `release.yml` builds its semantic-release config inline and shells out only to `mvn`.

**`--` is required before benchmark parameters.** Everything after it is forwarded verbatim to
`benchmark-runner.jar`. Without it, picocli parses JMH flags as `baas` options and fails with
`Unknown options: '-f', '-wi', '-i'`. `baas` options must come before the `--`:

```bash
baas run --instance-type c6i.4xlarge jmh -- MyBenchmark -f 1 -wi 1 -i 3
```

## Module Structure

This is a Maven multi-module project with 4 modules:

| Module | Purpose |
|--------|---------|
| `benchmark-runner` | Fat JAR that runs on the EC2 runner — executes benchmarks, saves results to S3 & MongoDB |
| `baas-cli` | The `baas` CLI developers use locally — provisions infrastructure, launches runners, polls for results. Main class `pl.wsztajerowski.baas.BaasApp` |
| `fake-jmh-benchmarks` | Minimal JMH JAR used as test fixture |
| `fake-stress-tests` | Minimal JCStress JAR used as test fixture |

The `s3-hook-lambda` module has been removed; the S3-upload-triggers-GHA path no longer exists.

## Architecture (benchmark-runner)

**Entry point**: `pl.wsztajerowski.commands.TestWrapper` (picocli root command)

**Subcommands**: `jmh`, `jmh-with-async`, `jmh-with-prof`, `jcstress`

**Key packages**:
- `commands/` — picocli `@Command` classes; each subcommand wires together options + service
- `services/` — one `*SubcommandService` per subcommand; orchestrates process execution and storage
- `services/options/` — immutable records (Java records) for CLI options (`ApiJmhOptions`, `ApiJCStressOptions`, etc.)
- `infra/` — `StorageService` (S3 or local filesystem) and `DatabaseService` (MongoDB or no-op)
- `entities/` — Morphia-mapped MongoDB documents for JMH and JCStress results
- `process/` — `BenchmarkProcessBuilder` for launching the benchmark JAR as a subprocess

## Architecture (baas-cli)

**Entry point**: `pl.wsztajerowski.baas.BaasApp` (picocli root command `baas`)

**Subcommands**: `admin setup`, `admin teardown`, `config set`, `config show`, `run <type>`, `results`.
`setup`/`teardown` live under `admin` because they need the elevated `BaasCliDeployerPolicy`;
day-to-day `run`/`results` use the narrow, stack-created `BaasCliOperatorRole`
(`aws.operatorProfile` in `~/.baas/config.yaml`, which deliberately does **not** fall back to
`aws.profile`).

**End-to-end flow** — no GitHub Actions involved:
1. `baas run <type>` builds the benchmark JAR in the **current working directory** (the user's
   project, not this repo) and uploads it to `s3://{bucket}/runs/{requestId}/benchmark.jar`
2. The CLI looks up the latest AL2023 AMI via SSM and calls `ec2:RunInstances` directly with a
   generated user-data script
3. User-data installs Amazon Corretto 25, fetches the runner JAR (S3 or GitHub Releases), reads
   `MONGO_CONNECTION_STRING` from SSM, and runs `benchmark-runner.jar` from `/app`
4. `benchmark-runner.jar` launches the benchmark JAR as a subprocess → parses results → uploads
   output to S3 → saves documents to MongoDB
5. User-data writes the `run-status` sentinel to S3 and self-terminates the instance; the CLI
   polls that sentinel every 15s, then prints results from MongoDB

The GHA workflows still exist, but for automated CI only — the CLI neither dispatches nor
depends on them.

> Design rationale, and the non-obvious invariants the runner depends on (no `set -e` in
> user-data, `/app` as working directory, request-ID-scoped S3 paths, three termination layers):
> [`docs/adr/0001-self-contained-baas-cli.md`](docs/adr/0001-self-contained-baas-cli.md).
> Read it before changing user-data generation or the teardown path.

## Infrastructure & CI

- **CloudFormation**: two independently-deployed stacks in `infra/` — see [`infra/README.md`](infra/README.md)
  for the deploy procedure:
  - `cf-template-core.yaml` — networking (VPC, public subnet, IGW, S3 gateway endpoint,
    security group), the `baas-{prefix}` S3 bucket, `RunnerRole` + instance profile, and
    `OperatorRole`. This is what `baas admin setup` deploys, bundled in the CLI as the
    classpath resource `/templates/cf-template-core.yaml`.
  - `cf-template-ci.yaml` — GitHub OIDC provider + `WorkflowRole`, for the GHA CI path only.
    Split out so the local CLI's identity never needs `iam:CreateOIDCProvider`.
  - `deployer-policy.json` / `operator-policy.json` — the two IAM policies; test fixtures, not
    bundled into the shipped JAR.
- **GitHub Actions** in `.github/workflows/`:
  - `benchmark-runner.yml` — `workflow_dispatch` orchestration: EC2 provisioning + benchmark execution
  - `exec-single-benchmark.yml` — reusable benchmark executor (called by the main workflow)
  - `start-ec2-runner.yml` / `stop-ec2-runner.yml` — EC2 lifecycle via `machulav/ec2-github-runner@v2`
  - `release.yml` — semantic release; sets the actual version (pom.xml has `0.0.0-semantically-released`)
  - `ci-pr-build.yml` — `mvn clean verify` on PRs
  - `e2e-cloud-test.yml` — full cloud E2E against real AWS
- EC2 instances self-terminate through **three** independent mechanisms: the benchmark process
  `timeout`, a background shell watchdog that fires even if the JVM deadlocks, and a CLI JVM
  shutdown hook for Ctrl+C. Any one of them alone leaves a way to orphan an instance.

## Storage Layout

**Measurements go to MongoDB, not to S3.** S3 holds process output and artifacts. There is no
`result.json` — the machine-readable JMH file is parsed locally and persisted as documents; if
the database is the no-op implementation, the numbers are not stored anywhere.

- **S3**, under `{resultPath}` (= `{branch}/{type}/{timestamp}`):
  - `jmh-output.txt` / `jmh-with-async-output.txt` / `jmh-profiler-output.txt` / `jcstress-output.txt` — captured process stdout
  - `logs/*.log` — log files found under the runner's working directory
  - `{benchmark}.{mode}/*` — async-profiler artifacts (flame graphs), `jmh-with-async` only
  - `run-status` — sentinel written by user-data: `completed` or `failed:{exitCode}`
  - `cloud-init-output.log` — the runner's boot log, uploaded before the instance self-terminates
  - `runs/{requestId}/{benchmark,runner}.jar` — uploaded inputs (separate top-level prefix)
- **MongoDB**: `jmh_benchmarks` and JCStress collections; Morphia ORM. The connection string
  reaches the runner as `MONGO_CONNECTION_STRING` (env var) or `--mongo-connection-string`.
  Empty or unset selects `NoOpDatabaseService` — benchmarks still run, results are discarded.

## Java Version

Java 25 (set in root `pom.xml`). Fat JARs built with `maven-shade-plugin`.
