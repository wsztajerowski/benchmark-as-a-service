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

# Run a local benchmark (uses LocalStack for storage, skips MongoDB)
./jmh-with-async.sh
```

The `.env` file contains LocalStack credentials used by Docker Compose and tests.

## Running Benchmarks Remotely

```bash
# Provision EC2, run, poll (baas-cli)
baas run jmh -- MyBenchmark -f 1 -wi 1 -i 3

# Build, upload to S3, and trigger a GitHub Actions workflow on EC2 (legacy path)
scripts/run-remote-benchmark.zsh -t=jmh-with-async -- MyBenchmark -f 1 -wi 1 -i 3

# Query aggregated results from MongoDB
scripts/benchmark_overview.sh
```

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
| `benchmark-runner` | Main fat JAR — runs benchmarks, saves results to S3 & MongoDB |
| `s3-hook-lambda` | **DEPRECATED** — AWS Lambda triggered by S3 uploads (being removed) |
| `fake-jmh-benchmarks` | Minimal JMH JAR used as test fixture |
| `fake-stress-tests` | Minimal JCStress JAR used as test fixture |

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

**End-to-end flow**:
1. Developer runs `scripts/run-remote-benchmark.zsh` → builds JAR → uploads to S3 → dispatches `benchmark-runner.yml` GHA workflow
2. GHA provisions an EC2 instance → downloads the runner and benchmark JARs → executes the CLI
3. CLI launches the benchmark JAR as a subprocess → parses stdout results → uploads JSON to S3 → saves to MongoDB

## Infrastructure & CI

- **CloudFormation**: Two stacks in `infra/` — bootstrap (OIDC, IAM, S3 bucket) and main (EC2, SSM parameters)
- **GitHub Actions** in `.github/workflows/`:
  - `benchmark-runner.yml` — main orchestration: EC2 provisioning + benchmark execution
  - `exec-single-benchmark.yml` — reusable benchmark executor (called by the main workflow)
  - `release.yml` — semantic release; sets the actual version (pom.xml has `0.0.0-semantically-released`)
  - `ci.yml` — build + test on push/PR
- EC2 instances self-terminate after benchmark completes via a shell watchdog

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
