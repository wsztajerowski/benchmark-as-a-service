# AGENTS.md – Benchmark as a Service (BaaS)

## Architecture Overview

BaaS is a **cloud-native benchmark orchestration system** composed of four Maven modules in a single multi-module build:

| Module | Role |
|---|---|
| `benchmark-runner` | Fat JAR CLI (picocli) that runs JMH/JCStress benchmarks on the EC2 runner, uploads results to S3, and persists data in MongoDB |
| `baas-cli` | Self-contained Java CLI replacing the zsh helper scripts; main class `pl.wsztajerowski.baas.BaasApp` |
| `fake-jmh-benchmarks` | Minimal JMH benchmark JAR used as test fixture in integration tests |
| `fake-stress-tests` | Minimal JCStress JAR used as test fixture in integration tests |

`s3-hook-lambda` has been **removed** — the module, its CloudFormation resources, the
`<prefix>-lambda` bucket, and the S3-object-create trigger path are all gone. If you find a
reference to it anywhere, it is stale.

---

## Full System Flow

### Trigger path A – `baas run` — **the supported path**

No GitHub Actions involved. See [Module: baas-cli](#module-baas-cli) for the full flow.

```
baas run <type> [baas options] -- [benchmark params]
 │
 ├─ 1. resolve config from ~/.baas/config.yaml; get current git branch
 ├─ 2. mvn clean package -q -DskipTests  in the CURRENT working directory (skip with --skip-build)
 ├─ 3. aws s3 putObject → s3://<bucket>/runs/<requestId>/benchmark.jar
 ├─ 4. SSM lookup: latest AL2023 AMI  →  ec2:RunInstances with generated user-data
 │        user-data: install Corretto 25 → fetch runner JAR → read mongo URI from SSM
 │                 → run benchmark-runner.jar from /app → write run-status → self-terminate
 ├─ 5. poll s3://<bucket>/<resultPath>/run-status every 15 s
 └─ 6. read mongo URI from SSM, query jmh_benchmarks, print results table
```

### Trigger path B – GitHub Actions `workflow_dispatch` — **CI only**

`benchmark-runner.yml` still exists and still works, but it is for automated CI (`e2e-cloud-test.yml`)
rather than for developers. `baas` neither dispatches nor depends on it. The zsh helper that used
to drive it (`scripts/run-remote-benchmark.zsh`) is superseded — see [Developer Scripts](#developer-scripts).

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

### Superseded by `baas` — do not build on these

These still exist on disk and still run, but `baas-cli` replaced them and they are slated for
removal. Use `baas run` / `baas results` instead, and don't add features here.

| Script | Replaced by |
|---|---|
| `scripts/run-remote-benchmark.zsh` | `baas run <type> -- <params>` |
| `scripts/wait-for-gha-run.sh` | `baas run`'s built-in `run-status` polling |
| `scripts/benchmark_overview.sh` | `baas results` |
| `scripts/logger.sh`, `git_helpers.sh`, `aws_helpers.sh` | (support code for the three above) |

Two behaviours from the old scripts still shape the data model and are mirrored by `baas results`:

- Runs are tagged with `branch`, `type`, `project`, and `options=<params>`; non-standard hardware
  runs get `exclude_from_results=true`.
- Default aggregation filters out `benchmarkMetadata.tags.exclude_from_results = true`, groups by
  `(benchmark, branch)`, and keeps only the highest-scoring run per group.

`benchmark_overview.sh` also hard-codes a match on `tags.project: 'lynx-journal'` — a leftover
from the original consuming project, and a reason not to trust its output for other projects.

### Retained — used by CI and release

`scripts/get-version-property.sh`, `scripts/get-version-property-simple.sh`, and
`scripts/update-dependencies.sh`. Keep these. `update-dependencies.sh` contains a hardcoded
`dependencies=()` array — edit it to perform semi-automated POM version bumps with build verification.

---

## AWS Deployment Topology

### Two CloudFormation stacks

Deployed independently. `infra/README.md` documents the procedure and is **current** — follow it.
There is no `cf-template-main.yaml` and no bootstrap stack; both were replaced by this split.

**`infra/cf-template-core.yaml`** — everything the CLI itself needs. This is what `baas admin setup`
deploys, bundled into `baas-cli` as the classpath resource `/templates/cf-template-core.yaml`.

- VPC (`10.0.0.0/16`), public subnet, Internet Gateway, S3 gateway endpoint
- `baas-<prefix>` S3 bucket (`DeletionPolicy: Retain`, encrypted, public access blocked)
- `RunnerSecurityGroup` — no inbound rules; outbound 443 (GitHub/S3/AWS APIs), 80 (yum), and
  **27017 for MongoDB Atlas**. Atlas is *not* reachable over 443; omitting 27017 makes every run
  fail at the database write.
- `RunnerRole` + `RunnerInstanceProfile` — assumed by EC2; S3 access on the bucket +
  `ec2:TerminateInstances` (tag-scoped to `baas-role=benchmark-runner`) + `ssm:GetParameter` for
  the mongo and AMI paths
- `OperatorRole` — the narrow day-to-day role for `baas run` / `baas results`

Outputs: `BucketName`, `S3BucketArn`, `RunnerRoleName`, `RunnerRoleArn`,
`RunnerInstanceProfileName`, `OperatorRoleArn`, `SubnetId`, `SecurityGroupId`, `VpcId`.

`UseExistingVpc` / `ExistingVpcId` / `ExistingSubnetId` / `ExistingSecurityGroupId` allow reusing
existing network infrastructure.

**`infra/cf-template-ci.yaml`** — the GHA CI path only: `GithubOidc` (conditional on
`OIDCProviderArn` being empty) and `WorkflowRole`. Split out of the core stack so the local CLI's
identity never needs `iam:CreateOIDCProvider`. Takes `RunnerRoleArn` and `BucketName` from the
core stack's outputs.

### IAM policy model

Two policies in `infra/`, and the split is deliberate:

- `deployer-policy.json` → `BaasCliDeployerPolicy`, elevated, needed only by `baas admin setup` /
  `baas admin teardown`.
- `operator-policy.json` → the stack-created `BaasCliOperatorRole`, narrow, used by `baas run` /
  `baas results`.

`aws.operatorProfile` in `~/.baas/config.yaml` deliberately does **not** fall back to `aws.profile`
(which holds deployer credentials). Don't "helpfully" add that fallback — it silently hands
day-to-day commands elevated rights.

Both JSON files reach the **test** classpath only (`baas-cli/pom.xml` `<testResources>`); only
`cf-template-core.yaml` is bundled into the shipped JAR.

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
- Tags (YAML map, converted to `[{Key, Value}]` JSON via `yq`): `baas-role: benchmark-runner`.
  The tag key is `baas-role`, **not** `baas:role` — `RunnerRole`'s `ec2:TerminateInstances`
  condition is scoped to it, so changing the key breaks self-termination.
- Lifecycle: created on `start-runner`, **always terminated** by `stop-runner` (even on failure)

---

## Data Model

### MongoDB collections
| Collection | Entity class | Key fields |
|---|---|---|
| `jmh_benchmarks` | `JmhBenchmark` | `_id`: `{requestId, benchmarkName, mode}` · `jmhResult` (raw JMH JSON) · `benchmarkMetadata.{tags, createdAt}` |
| (JCStress – no explicit `@Entity("…")`) | `JCStressResult` | `totalTests`, `passedTests`, `testsWithFailedResults`, `testsWithInterestingResults` |

- Tags are a free-form `Map<String,String>` on `BenchmarkMetadata` — used for filtering (e.g. `branch`, `type`, `source`, `exclude_from_results`, `project`)
- `baas results` groups by `(benchmark, branch)`, keeping the highest-scoring run; excludes documents where `benchmarkMetadata.tags.exclude_from_results = true`

**Measurements live only in MongoDB.** S3 holds process output and profiling artifacts; there is
no `result.json`. The machine-readable JMH file is parsed on the runner and persisted as
documents, so if `DatabaseServiceBuilder` selected `NoOpDatabaseService` (empty or unset mongo
URI) the numbers are not stored anywhere — the run still "succeeds".

### S3 result layout
```
<result-path>/                          (= <branch>/<type>/<YYYYMMDD_HHMMSS>)
 ├─ jmh-output.txt            (process stdout for plain jmh)
 ├─ jmh-profiler-output.txt   (process stdout for jmh-with-prof)
 ├─ jmh-with-async-output.txt (process stdout for jmh-with-async)
 ├─ jcstress-output.txt       (process stdout for jcstress)
 ├─ logs/*.log                 (any .log files found below the working directory)
 ├─ run-status                 (sentinel written by user-data: "completed" or "failed:<exitCode>")
 ├─ cloud-init-output.log      (runner boot log, uploaded before self-termination)
 └─ <fully.qualified.BenchmarkName-Mode>/
      ├─ flame-<event>-forward.html   (async profiler flamegraph)
      ├─ jfr-<event>.jfr             (async profiler JFR)
      └─ profile.jfr                  (JMH profiler output)

runs/<requestId>/                       (separate top-level prefix — uploaded inputs)
 ├─ benchmark.jar
 └─ runner.jar                (only when --runner-jar overrides the GitHub Releases download)
```

The `runs/<requestId>/` scoping is load-bearing: without it, two developers on the same branch
overwrite each other's JARs mid-run.

---

## CI / Release Workflows

| Workflow | Trigger | Purpose |
|---|---|---|
| `ci-pr-build.yml` | PR | `mvn -B clean verify` (unit + integration tests) |
| `e2e-cloud-test.yml` | PR / manual | Full cloud E2E: builds JAR, uploads to S3, runs `jmh-with-async` + `jmh-with-prof` on EC2, verifies S3 artifacts and MongoDB documents |
| `release.yml` | push to `main` | `mvn verify` → semantic-release → GitHub Release with `benchmark-runner.jar` asset → `mvn deploy` to GitHub Packages |
| `benchmark-runner.yml` | `workflow_dispatch` | Benchmark execution via GHA (CI path; `baas run` bypasses it) |
| `start-ec2-runner.yml` / `stop-ec2-runner.yml` | called by `benchmark-runner.yml` | EC2 lifecycle via `machulav/ec2-github-runner@v2` |
| `exec-single-benchmark.yml` | called by `benchmark-runner.yml` | Downloads runner + benchmark JARs, runs `benchmark-runner.jar` |

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

# ── Remote benchmarks (see Module: baas-cli for the full option reference) ──

# Trigger a remote benchmark run and wait for results
baas run jmh-with-async -- MyBenchmark -f 1 -wi 1 -i 3

# View aggregated results from MongoDB
baas results
```

**`docker-compose.yaml` services** (note: `.yaml` extension, not `.yml`):
- `localstack-baas` — LocalStack with `SERVICES=s3,ssm`
- `mongo` — MongoDB at port 27017
- `mongo-express` — MongoDB UI at port 8081

There is **no** init service — the S3 bucket and any SSM params must be created by hand against
the LocalStack endpoint.

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
- Shell scripts in `.github/test/testing-scripts/` use a shared `logger.sh` providing `log INFO|SUCCESS|WARNING|ERROR "message"` — always source it with `LOGGER_NAME="..."` set first. (`scripts/logger.sh` serves only the superseded zsh helpers.)
- New benchmark types need: a subcommand class in `commands/`, a service + builder in `services/`, an options record in `services/options/`, and registration in `TestWrapper`'s `subcommands` list.
- EC2 instance tags use the key `baas-role`, not `baas:role`. IAM conditions depend on it.

---

## Module: baas-cli

Self-contained Java CLI that replaces `run-remote-benchmark.zsh` and `benchmark_overview.sh`. **Fully implemented** — not a planned feature.

- Main class: `pl.wsztajerowski.baas.BaasApp` (picocli root command `"baas"`)
- Config stored at `~/.baas/config.yaml` (Jackson YAML)
- The core CF template is bundled as a classpath resource (`/templates/cf-template-core.yaml`) — `baas admin setup` reads it directly, no local `infra/` file needed
- Does **not** use Morphia; uses raw `mongodb-driver-sync`

### Subcommands

`setup`/`teardown` sit under `admin` because they need the elevated `BaasCliDeployerPolicy`;
everything else runs with the narrow operator role.

```
baas admin setup     # deploy/update the CORE stack; write config to ~/.baas/config.yaml
                     # options: --region --aws-profile --mongo-uri
                     #          --use-existing-vpc --vpc-id --subnet-id --sg-id
baas admin teardown  # tear down the core stack
                     # options: --stack-name --yes --delete-bucket
baas config set      # store MongoDB URI in SSM SecureString; write non-sensitive config
baas config show     # print current config (MongoDB URI masked)
baas run <type> -- <params>
                     # build → upload → provision EC2 → poll → show results
                     # types: jmh | jmh-with-async | jmh-with-prof | jcstress
                     # options: --benchmark-jar --runner-jar --skip-build --instance-type
                     #          --timeout --max-wall-clock --tag --branch
                     # -- is required; params after it go verbatim to benchmark-runner.jar
baas results         # query MongoDB and print the aggregated results table
```

The CI stack (`cf-template-ci.yaml`) is **not** deployed by the CLI — deploy it by hand per
`infra/README.md`.

### `baas run` execution flow

1. Runs `mvn clean package -q -DskipTests` in the **current working directory** (the project being benchmarked, not the BaaS repo)
2. Uploads JARs to S3
3. Looks up latest Amazon Linux 2023 AMI via SSM
4. Calls `ec2:RunInstances` directly (no GHA); EC2 user-data installs **Amazon Corretto 25** (`yum install -y java-25-amazon-corretto-headless`)
5. User-data fetches MongoDB URI from SSM, runs `benchmark-runner.jar`, writes `run-status` sentinel to S3, then self-terminates
6. CLI polls S3 every 15 seconds for `run-status` sentinel
7. JVM shutdown hook calls `ec2:TerminateInstances` on `Ctrl+C`

### Three independent EC2 termination layers

All three are required. Any one alone leaves a way to orphan a paid instance.

- **Shell watchdog** (layer 1, `UserDataScriptBuilder`): background `sleep N && ec2:TerminateInstances`,
  fires `timeout + 300 s` after launch. Started *immediately* after `INSTANCE_ID` resolves, so it
  covers every later failure. It's the only layer that survives a deadlocked JVM.
- **Process timeout** (layer 2): Linux `timeout` kills `java -jar benchmark-runner.jar` after `--timeout` seconds
- **CLI shutdown hook** (layer 3, `RunCommand`): `ec2:TerminateInstances` on `Ctrl+C`

### User-data invariants — read before editing `UserDataScriptBuilder`

- **No `set -e`.** If the IMDSv2 instance-id fetch fails under `set -e`, the script exits *before*
  starting the watchdog and orphans the instance. Errors are handled by exit code and the
  `run-status` sentinel instead.
- **The benchmark runs from `/app`, never from `/`.** The runner scans below its working directory
  for `.log` files to upload; cloud-init starts user-data in `/`, so that walk covers the whole
  root filesystem and aborts on `/proc` entries that vanish mid-walk.
- **The mongo URI is never placed in user-data.** It's fetched from SSM at runtime so it stays out
  of instance metadata and CI logs.

### Result output columns
`baas results` outputs: `BENCHMARK | REQUEST_ID | TYPE | MODE | SCORE | ±ERROR | UNIT`

> Design rationale, the full invariant list, and the open risks:
> [`docs/adr/0001-self-contained-baas-cli.md`](docs/adr/0001-self-contained-baas-cli.md).
> Per-change design records live in `openspec/changes/*/design.md`.
>
> Call-level sequence diagrams (Mermaid sources, rendered by GitHub in-place):
> [`baas-run.mmd`](docs/diagrams/baas-run.mmd) ·
> [`baas-setup.mmd`](docs/diagrams/baas-setup.mmd) ·
> [`baas-teardown.mmd`](docs/diagrams/baas-teardown.mmd).
> No checked-in SVGs — they went stale faster than the sources. Update the `.mmd` when the
> corresponding command changes.

---

## Known Constraints

| Area | Notes |
|---|---|
| SecureString in CF | Not supported — the mongo URI is written by `baas config set` / `baas admin setup --mongo-uri`, not by CloudFormation |
| Atlas IP allowlist | Runners get a fresh public IP per run, so there is no stable address to allowlist — the access list has to be `0.0.0.0/0`, gated by connection-string credentials. Accepted for v1; see `infra/README.md` |
| MongoDB is connect-only | `baas` never provisions a cluster. The user supplies the URI; empty/unset silently selects `NoOpDatabaseService` and discards measurements |
| GHA as compute orchestrator | `benchmark-runner.yml` EC2 lifecycle is still managed by `machulav/ec2-github-runner@v2`; `baas run` bypasses GHA entirely |
| Runner source | Downloaded from GitHub Releases at runtime **without checksum verification** (known open risk); `--runner-jar` / the `runner-path` workflow input override with an S3 URL |
| Root volume size | 30 GB gp3, not the AL2023 default — 8 GB is exhausted by profiling artifacts |
| Shared `RunnerRole` | One SSM path for the mongo URI, so any operator's runner can read it. Fine for single-tenant; would need per-operator path prefixes otherwise |
| `baas run` project layout | Assumes a Maven project producing one JAR; `--benchmark-jar` plus `--skip-build` covers other layouts |
| Tags as metadata | Free-form `Map<String,String>` on `BenchmarkMetadata`; `exclude_from_results` tag is a convention, not a first-class field |
| Distribution | Ships as a shaded JAR only. Install script, Homebrew tap, jpackage, native image, Docker image were all specified but never built — backlog, not decisions |
