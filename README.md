# Benchmark as a Service (BaaS)

Run JMH and JCStress benchmarks on throwaway EC2 instances, from one command, on hardware that
isn't your laptop. Measurements land in DynamoDB; process output, the verbatim result JSON and
profiling artifacts land in S3.

The `baas` CLI provisions its own AWS infrastructure, launches the runner, polls for completion,
and prints the numbers. It does not need GitHub Actions.

```bash
baas run jmh -- MyBenchmark -f 1 -wi 1 -i 3
```

## Requirements

- **Java 25** and Maven (the build targets 25; fat JARs via `maven-shade-plugin`)
- **AWS account** and credentials — see [Permissions](#permissions) for the two roles involved
- No database to bring. The results table is created by `baas admin setup` alongside the rest of
  the stack.
- Docker, for integration tests and local development

## Getting started

### 1. Build

```bash
mvn clean package -DskipTests
```

There is no packaged binary yet — no install script, no Homebrew tap, no native image. `baas` is a
shaded JAR, so alias it:

```bash
alias baas='java -jar '"$PWD"'/baas-cli/target/baas-cli.jar'
```

### 2. Deploy the infrastructure

One-time, and it needs the elevated deployer credentials described under
[Permissions](#permissions).

```bash
baas admin setup
```

This deploys the **core** CloudFormation stack — VPC, public subnet, internet gateway, S3 and
DynamoDB gateway endpoints, security group, the results bucket, the results table, and the
runner/operator IAM roles — then writes the outputs, including the table name, to
`~/.baas/config.yaml`. Nothing sensitive goes in that file.

Two things to know about naming: the stack, bucket and table are all derived from `baas-<prefix>`,
where `prefix` is a hash of your caller ARN. You don't choose it, and it's stable for a given
identity. The bucket and the table are both declared `DeletionPolicy: Retain` — benchmark history
outlives any single stack — so a previous teardown can leave either behind and block the next
setup. `baas admin setup` checks for both and tells you how to recover.

Setup finishes by printing follow-up steps for the operator role. **Do them** — until you do,
`baas run` uses your default credential chain rather than the narrow role.

### 3. Build the runner image

One-time, and again with deployer credentials. Takes ~15 minutes.

```bash
baas admin build-image
```

`baas run` **fails until this exists** — the runner boots from a purpose-built AMI and installs
nothing at run time, so there is no fallback path. Setup deliberately does not do this for you: a
build takes ~15 minutes and every re-setup would pay for it.

What gets baked is declared in [`infra/runner-image.yaml`](infra/runner-image.yaml) — the pinned
parent AL2023 AMI, Corretto, `perf`, the AWS CLI, async-profiler, and the kernel tunables that
move benchmark numbers (`perf_event_paranoid`, `kptr_restrict`, transparent hugepages, swap).
That file is the only place a tool version is written down, and `git log -p` on it is the image
history.

Changing a version is a one-line edit **plus** a bump of `imageVersion` in the same file. Image
Builder components are immutable at a given version, so `baas admin build-image` checks that up
front and refuses to start a build the stack would reject 15 minutes later:

```
Recipe version 1.0.0 is already registered and its content differs.
Bump imageVersion in infra/runner-image.yaml.
```

Exactly one image exists at a time. A successful build publishes the new AMI to
`/<prefix>/runner/ami-id` and only then deregisters the one it replaced, so a run launched during
a build never resolves a deleted AMI. `baas admin image` reports what is currently published, and
flags when your working tree declares a version you haven't built yet.

### 4. Run a benchmark

From **your benchmark project's** directory, not this repo — `baas run` builds in the current
working directory.

```bash
baas run jmh -- MyBenchmark -f 1 -wi 1 -i 3
```

Types: `jmh`, `jmh-with-async` (async-profiler flame graphs), `jmh-with-prof` (JMH's own
profilers), `jcstress`.

> **`--` is required.** Everything after it is forwarded verbatim to `benchmark-runner.jar`.
> Without it, picocli parses JMH flags as `baas` options and fails with
> `Unknown options: '-f', '-wi', '-i'`. `baas` options go *before* the separator:
>
> ```bash
> baas run --instance-type c6i.4xlarge --timeout 1800 jmh -- MyBenchmark -f 1 -wi 1 -i 3
> ```

Useful options: `--skip-build`, `--benchmark-jar`, `--runner-jar`, `--instance-type`, `--timeout`,
`--max-wall-clock`, `--tag key=value`, `--branch`, `--project`, `--no-database`.

> **`--project` defaults to the current git repository's directory name** and composes the results
> partition key, as well as being recorded as a tag. Outside a git repository, `baas run`
> hard-fails before any build or upload unless `--project <name>` is passed explicitly.

> **Tags are how you find a result later.** `--tag key=value` is repeatable and reaches the stored
> measurement, not just the EC2 instance. `project`, `commit` and `type` are added for you, and the
> instance adds what it observes: `imageVersion`, `instanceType`, `jdk`, `cpuModel`, `cpuArch`.
> Passing `--tag` for one of those observed keys is rejected — they come from the same values the
> run's own `environment.json` records, so the two can never disagree.

> **A run with nowhere to store its measurements fails before it costs anything.** If the table
> name is missing from your config, `baas run` stops before the Maven build and before any upload,
> and tells you to run `baas config sync`. To deliberately throw the numbers away, pass
> `--no-database`.

`-v` / `--verbose` works on every command and switches `baas`'s own logging to debug — resolved run
parameters, the AMI, the CloudFormation parameters, and the full generated user-data script. It
must come before the `--` separator, or it is passed to the benchmark instead.

### 5. Read results

```bash
baas results
```

Prints `BENCHMARK | REQUEST_ID | TYPE | MODE | SCORE | ±ERROR | UNIT`, reading the table directly.

By default it reads the partition for the current git repository, drops rows tagged
`exclude_from_results=true`, groups by `(benchmark, branch)` and keeps the best score in each
group. Filters:

| | |
|---|---|
| `--project <name>` | Read a different project's partition |
| `--tag key=value` | Repeatable; repeated tags must **all** match |
| `--benchmark-name <regex>` | Match on the benchmark name |
| `--living-branches` | Only branches that still exist in the local repo |
| `--request-id <id>` | Every measurement of one run. Cannot be combined with the others |
| `--group-by <tag>` | Group by something other than `branch` |
| `--all` | Every measurement, not just the best per group |
| `--limit <n>`, `--format json\|csv` | Bound and reshape the output |

### 6. Fetch everything a run produced

```bash
baas download main/jmh/20260819_090000
```

Takes a result path as `baas results` reports it, and pulls down the whole run: the verbatim
`jmh-result.json`, `environment.json`, process output, logs and profiling artifacts. The stored
measurement deliberately drops JMH's `rawData` and `scorePercentiles` — per-iteration numbers
dominate a result's size — so this is where you go when you need them.

### 7. Check that two results are comparable

Every run records the environment it measured on, in two tiers.

**Tier 1 — the results store.** Each result carries `imageVersion` and `instanceType` tags, so
`baas results` can tell you that rows in front of you did *not* measure the same thing, without
fetching anything:

```
These rows span runner image versions: 1.0.0, 1.1.0
They did not all measure the same environment. Compare two of them with:
  baas env diff <resultPathA> <resultPathB>
```

Rows are flagged, never filtered — the difference is the point, and whether it matters is your
call. Runs recorded before this existed carry no tags and are not treated as a difference.

**Tier 2 — the manifest.** `<result-path>/environment.json` holds ~20 fields describing what
actually ran: image version and AMI, instance type, CPU model and topology, memory, OS and kernel,
JVM and tool versions, and the kernel tunables in effect. `<result-path>/packages.txt` holds the
full `rpm -qa`, kept separate so it doesn't drown the readable file.

```bash
baas env diff main/jmh/20260812_233528 main/jmh/20260813_000550
```

```
FIELD          <run A>                         <run B>
amiId          ami-091ea218d041f91eb           ami-0a89e2bd4bf6f208a
imageVersion   1.0.0                           1.1.0
jvmVersion     openjdk version "25.0.4" ...    openjdk version "25.0.3" ...
```

Result paths are `<branch>/<type>/<timestamp>`, as printed by `baas run`. Identical environments
report no differences and exit 0.

Note the split: `infra/runner-image.yaml` is the *declaration* — what was asked for.
`environment.json` is the *observation* — what was got, including what the image cannot control
(instance type, CPU model, resolved patch levels). Compare runs with the observation; never infer
it from the declaration.

### Tear down

```bash
baas admin teardown                  # deletes the stack, retains the bucket
baas admin teardown --delete-bucket  # also empties and deletes the bucket
```

Two safety gates: it aborts if any benchmark runner is still running, and without `--yes` it makes
you type the stack name. The results bucket and the results table both survive it — benchmark
history outlives the stack — and teardown names both so the next setup doesn't fail on them.

## How it works

```
baas run
  ├─ resolve the runner AMI from /<prefix>/runner/ami-id  (fails here if unbuilt —
  │    before the build, before any upload, before anything is launched)
  ├─ build the benchmark JAR in the current directory
  ├─ upload it to s3://<bucket>/runs/<requestId>/benchmark.jar
  └─ ec2:RunInstances from that AMI, with a generated user-data script
       ├─ record the environment: environment.json + packages.txt, uploaded
       │    BEFORE the benchmark starts, so a crashed run still says what it
       │    crashed on  (nothing is installed — the toolchain is already baked)
       ├─ download benchmark-runner.jar (GitHub Releases, or S3 if overridden)
       ├─ run benchmark-runner.jar from /app, pointed at the results table
       │    └─ launch the benchmark JAR as a subprocess, parse results,
       │       upload output and the verbatim result JSON to S3, then write
       │       one item per measurement, tagged with what it observed
       ├─ write the run-status sentinel to S3
       └─ self-terminate
  ├─ poll run-status every 15s
  └─ print this run's measurements from the table
```

Instances self-terminate through **three** independent mechanisms — a process `timeout`, a
background shell watchdog that fires even if the JVM deadlocks, and a CLI shutdown hook for
Ctrl+C. Any one alone leaves a way to orphan a paid instance.

**S3 first, then the table.** The verbatim result JSON is uploaded before the measurement is
stored, so a stored row always has its full-fidelity counterpart to point at. The item carries what
a table view needs; `rawData` and `scorePercentiles` live only in S3, reachable with
`baas download`.

**Nothing is silently discarded.** A run with no store configured fails before the build, and a
store write that ultimately fails exits non-zero while leaving the S3 artifacts intact. Discarding
measurements requires `--no-database`.

Design rationale, the invariants the runner depends on, and the open risks:
[`docs/adr/0001-self-contained-baas-cli.md`](docs/adr/0001-self-contained-baas-cli.md).
Call-level sequence diagrams: [`docs/diagrams/`](docs/diagrams/).

## Permissions

Two roles, deliberately separate:

| Role | Policy | Used by |
|---|---|---|
| Deployer | `infra/deployer-policy.json` | `baas admin setup` / `baas admin build-image` / `baas admin teardown` |
| Operator | `infra/operator-policy.json` (role created by the stack) | `baas run` / `baas results` |

`aws.operatorProfile` in `~/.baas/config.yaml` does **not** fall back to `aws.profile`. That's
intentional: the fallback would silently hand everyday commands deploy-level rights.
[`infra/README.md`](infra/README.md) covers the assume-role setup.

## Modules

| Module | Purpose |
|---|---|
| `baas-cli` | The `baas` CLI. Main class `pl.wsztajerowski.baas.BaasApp` |
| `benchmark-runner` | Runs on the EC2 instance — executes benchmarks, writes to S3 and the results table |
| `baas-model` | The stored measurement shape, key encoding and tag vocabulary, shared by CLI and runner |
| `fake-jmh-benchmarks` | Minimal JMH JAR, test fixture |
| `fake-stress-tests` | Minimal JCStress JAR, test fixture |

Infrastructure lives in [`infra/`](infra/README.md) as two independently-deployed CloudFormation
stacks: `cf-template-core.yaml` (what the CLI deploys) and `cf-template-ci.yaml` (GitHub OIDC and
the workflow role, for CI only — split out so the CLI's identity never needs
`iam:CreateOIDCProvider`).

## Build and test

```bash
mvn clean package                              # unit tests only
mvn clean verify                               # + integration tests (needs Docker)
mvn -pl benchmark-runner test -Dtest=MyTest    # single test
mvn -pl benchmark-runner verify -Dit.test=MyIT # single integration test
```

Integration tests (`*IT.java`) spin up LocalStack (S3 + DynamoDB) and MongoDB via Testcontainers —
MongoDB because the runner keeps that adapter for standalone use, and one store contract suite runs
against both. `mvn clean
verify` copies `fake-jmh-benchmarks.jar` and `fake-stress-tests.jar` into
`benchmark-runner/target/` during `pre-integration-test`.

`mvn -pl benchmark-runner verify` on its own needs the two fixture JARs already installed in your
local Maven repository (`classifier=shaded`) — run the full reactor first.

Note: JUnit **6** and Testcontainers **2.x**, both of which differ from the versions you may be
used to.

## Local development

```bash
docker-compose up
```

Starts LocalStack (`SERVICES=s3,ssm,dynamodb`) and MongoDB on 27017. Mongo is there for the
runner's retained standalone adapter, not for BaaS. Credentials for LocalStack come from `.env` —
test values only, never real credentials.

There is **no** init container, so create what you need yourself — the bucket, and the results
table if you want the scripts below to store anything. `docker-compose.yaml` carries the exact
`create-table` command in a comment, with the key schema that must match `ResultsTable` in
`infra/cf-template-core.yaml`:

```bash
aws --endpoint-url=http://localhost:4566 --profile localstack s3 mb s3://baas
```

Then run the runner directly against LocalStack. Both scripts use the `fake-jmh-benchmarks`
fixture, so they work straight after a build:

```bash
./jmh-with-profiler.sh   # JMH's own profilers (gc, comp, cl, jfr)
./jmh-with-async.sh      # async-profiler flame graphs
```

`jmh-with-async.sh` additionally needs a local async-profiler library, because `--async-path`
defaults to the on-instance location and is validated for existence:

```bash
export ASYNC_PATH=~/async-profiler/lib/libasyncProfiler.dylib   # .so on Linux
```

Both scripts assume an `AWS_PROFILE=localstack` entry in your AWS config, and both write to
LocalStack S3 and the LocalStack table. Swap `--results-table`/`--dynamodb-endpoint` for
`--no-database` if you'd rather not create the table — one of the two must be named, since the
runner treats absent store configuration as an error rather than quietly discarding results.

Setting `ASYNC_PATH` also enables `JmhWithAsyncProfilerSubcommandServiceIT`, which is skipped
without it — so `mvn verify` covers async profiling only when that variable is set.

Endpoints: S3 browser `http://localhost:4566/baas/` · DynamoDB at the same LocalStack endpoint
(`aws --endpoint-url=http://localhost:4566 dynamodb scan --table-name baas-results`)
(the SDK/CLI endpoint is `https://s3.localhost.localstack.cloud:4566`).

## GitHub Actions

The workflows are for CI. `baas` neither dispatches nor depends on them.

| Workflow | Trigger | Purpose |
|---|---|---|
| `ci-pr-build.yml` | PR | `mvn clean verify` |
| `e2e-cloud-test.yml` | PR / manual | Full cloud E2E against real AWS |
| `release.yml` | push to `main` | semantic-release → GitHub Release → GitHub Packages |
| `benchmark-runner.yml` | `workflow_dispatch` | Benchmark execution via GHA |
| `exec-single-benchmark.yml`, `start-ec2-runner.yml`, `stop-ec2-runner.yml` | called by the above | Executor and EC2 lifecycle |

Secrets: `WORKFLOW_ROLE_ARN`, `RUNNER_ROLE_NAME` (both from the CI stack / core stack outputs),
`GHA_EC2_PAT` (classic token, `repo` scope, for `machulav/ec2-github-runner`).

Variables: `SUBNET_ID`, `SECURITY_GROUP_ID`, `AWS_REGION`, `ASYNC_PROFILER_VERSION`,
`RESOURCE_NAME_PREFIX`.

> **The GitHub Actions path still writes to MongoDB.** The DynamoDB cutover covered `baas run`
> only, so results from `benchmark-runner.yml` / `e2e-cloud-test.yml` land in Atlas and do not
> appear in `baas results`. `MONGO_CONNECTION_STRING` is **not** a secret:
> `exec-single-benchmark.yml` reads it from SSM at `/<RESOURCE_NAME_PREFIX>/mongo/connection-string`
> and fails the job if it's absent or empty.

Versioning is handled by semantic-release; `pom.xml` stays at `0.0.0-semantically-released` and the
real version is set at release time.

## E2E test

See [`.github/test/README.md`](.github/test/README.md). Requires `act`, Docker Compose, LocalStack,
the AWS CLI, and `mongosh`.

```bash
/bin/bash .github/test/exec-single-benchmark-e2e-test.sh
```

`docker-compose.yaml` does not create the `/baas/mongo/connection-string` parameter that
`exec-single-benchmark.yml` requires, so add it first:

```bash
aws --endpoint-url=http://localhost:4566 --profile localstack ssm put-parameter \
  --name /baas/mongo/connection-string \
  --value "mongodb://host.docker.internal:27017/local_test" \
  --type SecureString
```
