# AGENTS.md – Benchmark as a Service (BaaS)

## Architecture Overview

BaaS is a **cloud-native benchmark orchestration system** composed of five Maven modules in a single multi-module build:

| Module | Role |
|---|---|
| `benchmark-runner` | Fat JAR CLI (picocli) that runs JMH/JCStress benchmarks, uploads results to S3, and persists data in MongoDB |
| `baas-cli` | Self-contained Java CLI replacing the zsh helper scripts; main class `pl.wsztajerowski.baas.BaasApp` |
| `s3-hook-lambda` | ~~AWS Lambda triggered by S3 object-create events; translates the JSON request file into a GitHub Actions `workflow_dispatch` call~~ **DEPRECATED – scheduled for removal** |
| `fake-jmh-benchmarks` | Minimal JMH benchmark JAR used as test fixture in integration tests |
| `fake-stress-tests` | Minimal JCStress JAR used as test fixture in integration tests |

---

## Full System Flow

### ~~Trigger path A – S3 upload (production)~~ [DEPRECATED – pending removal]

> **This trigger path is deprecated and will be removed.** The `s3-hook-lambda` module, its CloudFormation resources, and all associated SSM parameters are being decommissioned. Do not build new features that rely on this path.

```
[DEPRECATED]
User
 └─ aws s3 cp request.json  →  s3://<prefix>-main/requests/<id>/request.json
                                        │ S3 ObjectCreated event (prefix=requests/, suffix=.json)
                                        ▼
                              s3-hook-lambda (Java 21, AWS Lambda)  ← DEPRECATED
                                ├─ reads JSON body from S3
                                ├─ reads GitHub config from SSM Parameter Store
                                │   /<prefix>/github/{org,repo,workflowid,workflowbranch,token}
                                └─ POST https://api.github.com/repos/<org>/<repo>/actions/workflows/<id>/dispatches
                                           (forwards S3 JSON verbatim as "inputs")
                                        │ workflow_dispatch
                                        ▼
                              benchmark-runner.yml  (GHA)
```

### Trigger path B – CLI helper (`scripts/run-remote-benchmark.zsh`) — **current recommended path**
```
scripts/run-remote-benchmark.zsh -t=<type> [options] -- [JMH params]
 │
 ├─ 1. get current git branch name
 ├─ 2. mvn clean package  (skip with -sb)
 ├─ 3. aws s3 cp jmh-benchmarks/target/jmh-benchmarks.jar
 │        → s3://<bucket>/<branch>/jmh-benchmarks.jar
 ├─ 4. gh workflow run benchmark-runner.yml  (with request_id, result_path, tags, …)
 │        waits via scripts/wait-for-gha-run.sh
 └─ 5. [if BENCHMARK_DB_CONNECTION_STRING set]
          mongosh → db.jmh_benchmarks.find({'_id.requestId': '<id>'})
          prints: BENCHMARK  SCORE  UNIT
```

### GitHub Actions orchestration (benchmark-runner.yml)
```
benchmark-runner.yml
 ├─ start-ec2-runner.yml
 │    ├─ configure-aws-credentials (OIDC → WorkflowRole)
 │    ├─ lookup latest Amazon Linux 2023 AMI via SSM
 │    └─ machulav/ec2-github-runner@v2 mode=start
 │         pre-runner-script: yum install docker git libicu
 │         → outputs: label, ec2-instance-id
 │
 ├─ exec-single-benchmark.yml  (runs on EC2 label)
 │    ├─ [jmh-with-async only] download async-profiler tar.gz → /app/async-profiler/
 │    ├─ setup-java (Temurin, Java 25)
 │    ├─ fetch MONGO_CONNECTION_STRING from SSM /<prefix>/mongo/connection-string (exits 1 if missing)
 │    ├─ download benchmark-runner.jar
 │    │     from GitHub Releases (latest)  OR  aws s3 cp <runner-path>  (if runner-path set)
 │    ├─ aws s3 cp <benchmark-path>  →  benchmark-under-test.jar
 │    └─ java -jar benchmark-runner.jar <type> \
 │             --request-id ... --result-path ... --s3-bucket ...
 │             --benchmark-path benchmark-under-test.jar <parameters>
 │
 └─ stop-ec2-runner.yml  (always – even on failure)
      └─ machulav/ec2-github-runner@v2 mode=stop
```

### benchmark-runner.jar execution (per subcommand)
```
TestWrapper (picocli)
 └─ subcommand: jmh | jmh-with-async | jmh-with-prof | jcstress
      ├─ ApiCommonSharedOptions + ApiJmhOptions/ApiJCStressOptions  →  immutable options records
      ├─ StorageServiceBuilder  →  S3StorageService (bucket set)  OR  LocalStorageService (no bucket)
      ├─ DatabaseServiceBuilder →  DocumentDbService (mongo URI set)  OR  NoOpDatabaseService
      └─ *SubcommandService.executeCommand()
           ├─ BenchmarkProcessBuilder  →  java -jar <benchmark.jar> [all JMH/JCStress flags]
           │     stderr merged into stdout → redirected to processOutput file
           ├─ storageService.saveFile(resultPath/jmh-output.txt, processOutput)
           ├─ [jmh-with-async] storageService.saveFile per flamegraph/JFR per benchmark
           ├─ [jmh] parse jmh-results.json → JmhBenchmark records → databaseService.save()
           └─ [jcstress] parse HTML output → JCStressResult → databaseService.save()
```

---

## Developer Scripts

### `scripts/run-remote-benchmark.zsh` — main action entry point

**The primary way to trigger a remote benchmark run.** Handles the full lifecycle: build → upload → dispatch → wait → preview results.

**Prerequisites:** `mvn`, `aws` CLI (profile `lynx` by default), `gh` CLI (authenticated).

```zsh
scripts/run-remote-benchmark.zsh -t=<type> [options] -- [JMH params forwarded verbatim]
```

| Option | Default | Description |
|---|---|---|
| `-t`, `--benchmark-type` | **required** | `jmh`, `jmh-with-async`, or `jmh-with-prof` (not `jcstress`) |
| `-w`, `--workflow-branch` | `main` | Git branch used to load the workflow |
| `-p`, `--aws-profile` | `lynx` | AWS CLI profile |
| `-sb`, `--skip-build` | `false` | Skip `mvn clean package` and S3 upload |
| `-wf`, `--worker-family` | _(default EC2)_ | EC2 family override (`c5`, `c6i`, `c7i`) |
| `-ws`, `--worker-size` | _(default EC2)_ | EC2 size override (`2xlarge`, `4xlarge`, `8xlarge`) |
| `--` | — | Everything after `--` is forwarded as JMH benchmark parameters |

**Behaviour notes:**
- Benchmark JAR is read from `jmh-benchmarks/target/jmh-benchmarks.jar` and uploaded to `s3://<bucket>/<branch>/jmh-benchmarks.jar`.
- Request ID format: `<type>-<YYYYMMDD_HHMMSS>`; result path: `<branch>/<type>/<YYYYMMDD_HHMMSS>`.
- Auto-tags every run with `branch`, `type`, `project=lynx-journal`, `options=<JMH params>`.
- When `-wf` or `-ws` is supplied, the tag `exclude_from_results=true` is automatically added (non-standard hardware runs are excluded from `benchmark_overview.sh` default aggregation).
- After the workflow completes it prints a quick result preview via `mongosh` if `BENCHMARK_DB_CONNECTION_STRING` is set, otherwise prints the equivalent query to stdout.
- The S3 console link to result artifacts is printed at the end regardless.

```zsh
# Examples
scripts/run-remote-benchmark.zsh -t=jmh-with-async -- MyBenchmark -f 1 -wi 1 -i 3
scripts/run-remote-benchmark.zsh -t=jmh -sb -wf=c7i -ws=4xlarge -- -f 2 -wi 3 -i 5
```

---

### `scripts/benchmark_overview.sh` — result viewer

**The primary way to inspect aggregated benchmark results** stored in MongoDB. Requires `BENCHMARK_DB_CONNECTION_STRING` env var; when unset it prints the generated `mongosh` query to stdout instead of running it.

```bash
BENCHMARK_DB_CONNECTION_STRING=mongodb://... scripts/benchmark_overview.sh [options]
```

| Option | Description |
|---|---|
| `-b`, `--benchmark-name` | Filter by benchmark name (MongoDB `$regex`) |
| `-l`, `--living-branches` | Restrict to currently active remote git branches |
| `-a`, `--all` | Print every result row; skip grouping/deduplication |

**Default behaviour (no `-a`):**
- Filters out documents with `benchmarkMetadata.tags.exclude_from_results = true`.
- Hard-coded match on `benchmarkMetadata.tags.project: 'lynx-journal'` and `benchmarkMetadata.tags.type: 'jmh'`.
- Groups by `(benchmark, branch)`, keeping only the **highest-scoring run** per group.
- Output columns: `BENCHMARK  SCORE  UNIT  BRANCH  REQUEST_ID  OPTIONS`

```bash
# Examples
BENCHMARK_DB_CONNECTION_STRING=mongodb://... scripts/benchmark_overview.sh --living-branches
BENCHMARK_DB_CONNECTION_STRING=mongodb://... scripts/benchmark_overview.sh -b Synchronized -l
BENCHMARK_DB_CONNECTION_STRING=mongodb://... scripts/benchmark_overview.sh --all
```

---

## AWS Deployment Topology

### CloudFormation stack (`infra/cf-template-main.yaml`)

`cf-template-main.yaml` is a **single unified stack** — it has already been rewritten. The bootstrap stack (`cf-template-bootstrap.yaml`) is obsolete; `infra/README.md` is outdated and references removed parameters. Trust the template file, not the README.

The current main stack provisions:
- VPC (`10.0.0.0/16`), public subnet, Internet Gateway, S3 gateway endpoint
- `<prefix>-main` S3 bucket (DeletionPolicy: Retain)
- `RunnerSecurityGroup` — no inbound rules; outbound HTTPS + HTTP (for yum)
- `WorkflowRole` – assumed by GHA via OIDC; manages EC2 + `s3:PutObject` on `ci/*`
- `RunnerRole` + `RunnerInstanceProfile` – assumed by EC2; full S3 CRUD + `ec2:TerminateInstances` (tag-scoped to `baas:role=benchmark-runner`) + `ssm:GetParameter` for mongo and AMI paths
- `GithubOidc` – OIDC provider (conditional)
- GitHub SSM params inlined: `/<prefix>/github/{org,repo,workflowid,workflowbranch}`
- **No Lambda function, no S3 event trigger** — all Lambda resources have been removed

`UseExistingVpc` / `ExistingVpcId` / `ExistingSubnetId` / `ExistingSecurityGroupId` parameters allow reusing existing network infrastructure.

> **GitHub token** (`/<prefix>/github/token`) must still be created manually via `aws ssm put-parameter --type SecureString` — CloudFormation does not support SecureString parameters.

### GHA required secrets/variables
| Name | Type | Source |
|---|---|---|
| `WORKFLOW_ROLE_ARN` | Secret | CF main stack output `WorkflowRoleArn` |
| `RUNNER_ROLE_NAME` | Secret | CF main stack output (role name, not ARN) |
| `GHA_EC2_PAT` | Secret | GitHub classic token with `repo` scope (for `machulav/ec2-github-runner`) |
| `SUBNET_ID` | Variable | AWS subnet for EC2 (or use CF output `SubnetId`) |
| `SECURITY_GROUP_ID` | Variable | AWS security group for EC2 (or use CF output `SecurityGroupId`) |
| `AWS_REGION` | Variable | e.g. `eu-central-1` |
| `ASYNC_PROFILER_VERSION` | Variable | e.g. `4.0` |
| `RESOURCE_NAME_PREFIX` | Variable | SSM/S3 prefix; defaults to `baas` if unset |

> **`MONGO_CONNECTION_STRING` is NOT a GHA secret.** `exec-single-benchmark.yml` fetches it from SSM at `/<RESOURCE_NAME_PREFIX>/mongo/connection-string` (SecureString). The workflow exits with code 1 if the parameter is absent or empty.

### EC2 runner configuration
- AMI: latest Amazon Linux 2023 (looked up via SSM at runtime, not hardcoded)
- Default type: `c5.2xlarge`; configurable: `c5|c6i|c7i` × `2xlarge|4xlarge|8xlarge`
- Pre-runner script: installs `docker`, `git`, `libicu`
- Instance profile: `RunnerInstanceProfile` (inherits `RunnerRole` S3 permissions)
- Tags (YAML map, converted to `[{Key, Value}]` JSON via `yq`): `project=baas`, `runner=request-benchmark-runner`
- Lifecycle: created on `start-runner`, **always terminated** by `stop-runner` (even on failure)

---

## Data Model

### MongoDB collections
| Collection | Entity class | Key fields |
|---|---|---|
| `jmh_benchmarks` | `JmhBenchmark` | `_id`: `{requestId, benchmarkName, mode}` · `jmhResult` (raw JMH JSON) · `benchmarkMetadata.{tags, createdAt}` |
| (JCStress – no explicit `@Entity("…")`) | `JCStressResult` | `totalTests`, `passedTests`, `testsWithFailedResults`, `testsWithInterestingResults` |

- Tags are a free-form `Map<String,String>` on `BenchmarkMetadata` — used by scripts for filtering (e.g. `branch`, `type`, `source`, `exclude_from_results`, `project`)
- `benchmark_overview.sh` groups by `(benchmark, branch)`, keeping the highest-scoring run; excludes documents where `benchmarkMetadata.tags.exclude_from_results = true`

### S3 result layout
```
<result-path>/
 ├─ jmh-output.txt            (process stdout for plain jmh)
 ├─ jmh-profiler-output.txt   (process stdout for jmh-with-prof)
 ├─ jmh-with-async-output.txt (process stdout for jmh-with-async)
 ├─ logs/*.log                 (any .log files found in working directory)
 └─ <fully.qualified.BenchmarkName-Mode>/
      ├─ flame-<event>-forward.html   (async profiler flamegraph)
      ├─ jfr-<event>.jfr             (async profiler JFR)
      └─ profile.jfr                  (JMH profiler output)
```

---

## CI / Release Workflows

| Workflow | Trigger | Purpose |
|---|---|---|
| `ci-pr-build.yml` | PR | `mvn -B clean verify` (unit + integration tests) |
| `e2e-cloud-test.yml` | PR / manual | Full cloud E2E: builds JAR, uploads to S3, runs `jmh-with-async` + `jmh-with-prof` on EC2, verifies S3 artifacts and MongoDB documents |
| `release.yml` | push to `main` | `mvn verify` → semantic-release → GitHub Release with `benchmark-runner.jar` asset → `mvn deploy` to GitHub Packages |
| `benchmark-runner.yml` | `workflow_dispatch` ~~/ Lambda~~ | Production benchmark execution |

### Release mechanics
- Semantic versioning via `@semantic-release/commit-analyzer` (Angular preset)
- `prepareCmd`: `mvn versions:set -DnewVersion=${nextRelease.version}` (updates pom.xml version at release time only)
- `publishCmd`: `mvn deploy -DskipTests` (publishes to GitHub Packages)
- Normal development version in pom.xml stays `0.0.0-semantically-released`

---

## Module: benchmark-runner

### Entry point
`pl.wsztajerowski.commands.TestWrapper` is the `main` class (set in the Shade manifest). Subcommands: `jmh`, `jmh-with-async`, `jmh-with-prof`, `jcstress`.

### Key package structure
- `commands/` – picocli `@Command` classes (`ApiJmhOptions`, `ApiCommonSharedOptions`, etc.) that translate CLI flags into service options records
- `services/` – business logic per subcommand (`JmhSubcommandService`, etc.) built via dedicated `*Builder` classes
- `services/options/` – immutable Java records for all option groups (`JmhOptions`, `JmhBenchmarkOptions`, `S3Options`, …)
- `infra/` – `StorageService` and `DatabaseService` abstractions with S3/LocalStorage and MongoDB/NoOp implementations
- `entities/` – Morphia-mapped records (`JmhBenchmark`, `JCStressResult`) persisted to MongoDB
- `process/` – `BenchmarkProcessBuilder` wraps `ProcessBuilder` to launch the benchmark JAR as a subprocess

### Graceful degradation pattern
Both storage and database are **optional at runtime**:
- Omit `--s3-bucket` → `LocalStorageService` (writes to local filesystem)
- Omit `--mongo-connection-string` / `MONGO_CONNECTION_STRING` env var → `NoOpDatabaseService` (no persistence)
- `AWS_ENDPOINT_URL_S3` env var (or `--s3-service-endpoint`) overrides the S3 endpoint (used for LocalStack)

### MongoDB connection string requirement
The connection string **must include the database name**: `mongodb://host:port/database_name`. This is enforced in `DatabaseServiceBuilder` with a `requireNonNull` check.

Morphia auto-maps all classes under `pl.wsztajerowski.entities` – keep entity classes in that package.

---

## Module: s3-hook-lambda

> **DEPRECATED – scheduled for removal.** Do not add new features or dependencies on this module.

Lambda reads GitHub config from **SSM Parameter Store** using a prefix from the `SSM_PARAM_PREFIX` env var:
- `/<prefix>/github/org`, `/<prefix>/github/repo`, `/<prefix>/github/workflowid`, `/<prefix>/github/workflowbranch`, `/<prefix>/github/token`

The S3 request body JSON is forwarded verbatim as `inputs` inside the GHA `workflow_dispatch` payload. The Lambda runtime is **Java 21** (one version behind the build toolchain which targets Java 25).

---

## Build & Test

```bash
# Build all modules (includes integration tests)
mvn clean verify

# Build without integration tests
mvn clean package

# Build a single module
mvn clean verify -pl benchmark-runner
```

Integration tests (`*IT.java`) use **Testcontainers** (LocalStack for S3 + MongoDB containers) and are run via `maven-failsafe-plugin`. The `fake-jmh-benchmarks.jar` and `fake-stress-tests.jar` shaded artifacts are copied into `benchmark-runner/target/` during `pre-integration-test` phase by `maven-dependency-plugin`.

**Single-module build caveat:** `mvn -pl benchmark-runner verify` requires the `fake-jmh-benchmarks` and `fake-stress-tests` shaded JARs to already exist in the local Maven repository (built via `classifier=shaded`). Run the full reactor first if you haven't already.

**JUnit 6** is used (`junit.bom.version=6.0.2`) — the API differs from JUnit 5. Testcontainers **2.x** is used; integration tests pin `mongo:7.0.5`.

**JCStress result files** (`jcstress-results-*.bin.gz`) are written to the **module root directory** (not `target/`). `mvn clean` removes them via an extra configured fileset.

---

## Local Development

```bash
# Start LocalStack + MongoDB (required for local runs)
docker-compose -f docker-compose.yaml up

# Run a benchmark locally against LocalStack
./jmh-with-async.sh       # jmh-with-async type
./jmh-with-profiler.sh    # jmh-with-prof type

# Run local E2E test (requires act, docker-compose, aws cli, mongosh)
/bin/bash .github/test/exec-single-benchmark-e2e-test.sh

# ── Remote benchmarks (see Developer Scripts section for full option reference) ──

# Trigger a remote benchmark run and wait for results
scripts/run-remote-benchmark.zsh -t=jmh-with-async -- MyBenchmark -f 1 -wi 1 -i 3

# View aggregated results from MongoDB (filtered to active branches)
BENCHMARK_DB_CONNECTION_STRING=mongodb://... scripts/benchmark_overview.sh --living-branches
```

**`docker-compose.yaml` services** (note: `.yaml` extension, not `.yml`):
- `localstack-baas` — LocalStack with `SERVICES=s3,lambda,ssm`
- `localstack-baas-init` — creates S3 bucket `baas` and SSM params `/baas/github/{org,repo,workflowid,token}`
- `mongo` — MongoDB at port 27017
- `mongo-express` — MongoDB UI at port 8081

**`.env` at root** supplies `AWS_ACCESS_KEY_ID=test` / `AWS_SECRET_ACCESS_KEY=test` for LocalStack. Do not store real credentials here.

**Local act E2E prerequisite:** `docker-compose.yaml` does NOT create the `/baas/mongo/connection-string` SSM param that `exec-single-benchmark.yml` requires. Create it manually before running the E2E test:
```bash
aws --endpoint-url=http://localhost:4566 ssm put-parameter \
  --name /baas/mongo/connection-string \
  --value "mongodb://host.docker.internal:27017/local_test" \
  --type SecureString --profile localstack
```

Local S3 browser: `http://localhost:4566/<bucket>` (browser only; SDK/CLI endpoint is `https://s3.localhost.localstack.cloud:4566`) | MongoDB UI: `http://localhost:8081`

---

## GitHub Actions Workflows

| File | Purpose |
|---|---|
| `benchmark-runner.yml` | Main entry – provisions EC2, calls exec workflow, tears down EC2 |
| `exec-single-benchmark.yml` | Reusable – downloads runner + benchmark JARs, runs `benchmark-runner.jar` |
| `start-ec2-runner.yml` / `stop-ec2-runner.yml` | EC2 lifecycle management via `machulav/ec2-github-runner@v2` |
| `release.yml` | On push to `main`: semantic-release, publishes `benchmark-runner.jar` as a GitHub Release asset |
| `ci-pr-build.yml` | On PR: `mvn clean verify` |
| `e2e-cloud-test.yml` | On PR / manual: full cloud E2E test against real AWS infrastructure |

---

## Project Conventions

- Java **25**, compiled with `maven-compiler-plugin` 3.14+; target Java version is set in root `pom.xml` `<maven.compiler.source>` / `<maven.compiler.target>`.
- All JARs are assembled as **shaded fat JARs** (maven-shade-plugin); artifact name is always `${project.artifactId}` (no version suffix).
- Version string is `0.0.0-semantically-released` – actual versioning is managed by the release workflow, not manually.
- Shell scripts in `scripts/` and `.github/test/testing-scripts/` use a shared `logger.sh` providing `log INFO|SUCCESS|WARNING|ERROR "message"` — always source it with `LOGGER_NAME="..."` set first.
- New benchmark types need: a subcommand class in `commands/`, a service + builder in `services/`, an options record in `services/options/`, and registration in `TestWrapper`'s `subcommands` list.
- `scripts/update-dependencies.sh` contains a hardcoded `dependencies=()` array — edit it to perform semi-automated POM version bumps with build verification.

---

## Module: baas-cli

Self-contained Java CLI that replaces `run-remote-benchmark.zsh` and `benchmark_overview.sh`. **Fully implemented** — not a planned feature.

- Main class: `pl.wsztajerowski.baas.BaasApp` (picocli root command `"baas"`)
- Config stored at `~/.baas/config.yaml` (Jackson YAML)
- CF template is bundled as a classpath resource (`/templates/cf-template-main.yaml`) — `baas setup` reads it directly, no local `infra/` file needed
- Does **not** use Morphia; uses raw `mongodb-driver-sync`

### Subcommands

```
baas setup        # deploy/update CloudFormation stack; write config to ~/.baas/config.yaml
baas teardown     # tear down the CloudFormation stack
baas config set   # store MongoDB URI in SSM SecureString; write non-sensitive config
baas config show  # print current config (MongoDB URI masked)
baas run <type>   # build → upload → provision EC2 → poll → show results
                  # types: jmh | jmh-with-async | jmh-with-prof | jcstress
baas results      # query MongoDB; mirrors benchmark_overview.sh aggregation logic
```

### `baas run` execution flow

1. Runs `mvn clean package -q -DskipTests` in the **current working directory** (the project being benchmarked, not the BaaS repo)
2. Uploads JARs to S3
3. Looks up latest Amazon Linux 2023 AMI via SSM
4. Calls `ec2:RunInstances` directly (no GHA); EC2 user-data installs **Amazon Corretto 25** (`yum install -y java-25-amazon-corretto-headless`)
5. User-data fetches MongoDB URI from SSM, runs `benchmark-runner.jar`, writes `run-status` sentinel to S3, then self-terminates
6. CLI polls S3 every 15 seconds for `run-status` sentinel
7. JVM shutdown hook calls `ec2:TerminateInstances` on `Ctrl+C`

### Two independent EC2 termination safety layers
- **Process timeout**: Linux `timeout` kills `java -jar benchmark-runner.jar` after `--timeout` seconds
- **Shell watchdog**: background `sleep N && ec2:TerminateInstances` fires `timeout + 300 s` after launch

### Result output columns
`baas results` outputs: `BENCHMARK | REQUEST_ID | TYPE | MODE | SCORE | ±ERROR | UNIT`
(differs from `benchmark_overview.sh` which outputs `BENCHMARK | SCORE | UNIT | BRANCH | REQUEST_ID | OPTIONS`)

> Full design document: [`docs/redesign.md`](docs/redesign.md) | Migration plan: [`docs/aws-migration-plan.md`](docs/aws-migration-plan.md)

---

## Known Constraints

| Area | Notes |
|---|---|
| SecureString in CF | Not supported — GitHub token (`/<prefix>/github/token`) must be created manually via `aws ssm put-parameter --type SecureString` |
| GHA as compute orchestrator | `benchmark-runner.yml` EC2 lifecycle is still managed by `machulav/ec2-github-runner@v2`; `baas run` bypasses GHA entirely |
| Runner source | Downloaded from GitHub Releases at runtime; `runner-path` input on `exec-single-benchmark.yml` overrides with an S3 URL (used in E2E tests) |
| `jcstress` not in zsh helper | `run-remote-benchmark.zsh` only accepts `jmh`, `jmh-with-async`, `jmh-with-prof`; use `baas run jcstress` or manual `workflow_dispatch` |
| MongoDB queries in shell | `scripts/benchmark_overview.sh` and `run-remote-benchmark.zsh` embed raw `mongosh` queries — no API layer |
| Tags as metadata | Free-form `Map<String,String>` on `BenchmarkMetadata`; `exclude_from_results` tag is a convention, not a first-class field |
| `CLAUDE.md` at root | Contains several errors (wrong env var names, wrong workflow names, wrong module count) — do not rely on it; use AGENTS.md |
