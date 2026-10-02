# Benchmark as a Service (BaaS)

Run JMH and JCStress benchmarks on throwaway EC2 instances, from one command, on hardware that
isn't your laptop. Measurements land in DynamoDB; process output, the verbatim result JSON and
profiling artifacts land in S3.

The `baas` CLI provisions its own AWS infrastructure, launches the runner, polls for completion,
and prints the numbers. It does not need GitHub Actions.

```bash
baas run --benchmark-jar target/benchmarks.jar jmh -- MyBenchmark -f 1 -wi 1 -i 3
```

## Requirements

- **Java 25** on your `PATH` — that's what runs `baas` itself; the installer checks for it and
  refuses anything older.
- **AWS account** and credentials — see [Permissions](#permissions) for the two roles involved. If
  you authenticate through SSO, you also need the **AWS CLI**: `aws sso login` is what writes the
  token cache the Java SDK reads. `baas` never shells out to `aws` itself.
- No database to bring. The results table is created by `baas admin setup` alongside the rest of
  the stack.
- Maven, Gradle, or whatever your benchmark project already uses, to build **your own** benchmark
  JAR — `baas run` builds nothing, so this is not a prerequisite of `baas` itself. Building `baas`
  from source (see *Developing BaaS itself* below) does need Maven.
- Docker, for integration tests and local development of `baas` itself.

## Getting started

### 1. Install

```bash
curl -fsSL https://github.com/wsztajerowski/benchmark-as-a-service/releases/latest/download/install.sh | sh
```

Installs a checksum-verified `baas-cli.jar` to `~/.local/share/baas/`, a launcher shim to
`~/.local/bin/baas`, and a copy of the installer itself alongside the jar — that stored copy is
what makes `--update` reachable later, since a piped install leaves nothing else on disk to
re-invoke. Add `~/.local/bin` to your `PATH` if the installer tells you to; it never touches
`~/.baas/config.yaml`, which belongs to `baas admin setup`.

Installer options: `--version <v>` (install a specific release instead of the latest),
`--update`, `--uninstall` (leaves `~/.baas` untouched), `-h`/`--help`. Environment overrides:
`BAAS_VERSION`, `BAAS_REPO`, `BAAS_SHARE`, `BAAS_BIN`, `BAAS_JAVA` (the JVM the shim execs).

`--update` resolves the newest release, compares it field-wise against what's installed, and hands
off to *that release's own installer* rather than installing anything itself — it never downgrades,
and if your installed version is already newer it reports both and changes nothing. Re-run it as:

```bash
~/.local/share/baas/install.sh --update
```

A *saved* `install.sh` — the one under `~/.local/share/baas/`, or a copy you kept yourself —
reinstalls its own baked-in version when run plain; it does not upgrade on its own. That's the
whole reason `--update` exists. Note also that `--update` ignores `--version`: pairing them
(`install.sh --update --version X`) silently drops the requested version, since `--version` only
makes sense for a separate install-mode invocation.

### Developing BaaS itself

Working from a checkout instead of a release:

```bash
mvn clean package -DskipTests
alias baas='java -jar '"$PWD"'/baas-cli/target/baas-cli.jar'
```

A reactor build carries the placeholder version `0.0.0-semantically-released`, so `baas run`
refuses to launch anything with it unless you also pass `--runner-jar` — the CLI has no released
runner JAR to pin to, and there is deliberately no fallback. That refusal is the point: the reactor
checkout is the developer's explicit special case, not a silent stand-in for a pinned release.

### 2. Deploy the infrastructure

One-time, and it needs the elevated deployer credentials described under
[Permissions](#permissions).

> **SSO users:** an SSO session's caller ARN carries a per-session name, so a second `baas admin
> setup` computes a different prefix — creating a *separate* stack, bucket and results table (all
> retained) and repointing your config at the empty one. It presents as "all my benchmark history
> is gone," though the original table is untouched. Run `setup` from a stable identity until this
> is fixed (tracked as finding **A10** in
> [`docs/review/baas-cli-findings.md`](docs/review/baas-cli-findings.md)).

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

**The definition is bundled into the CLI when it is built**, and `build-image` bakes that bundled
copy — it reads no file from disk. An installed `baas` therefore bakes the image its release
shipped with, and editing a `runner-image.yaml` next to it changes nothing. Changing the image
today means editing `infra/runner-image.yaml` in a checkout of this repository, rebuilding the CLI
(`mvn package`), and running `baas admin build-image` from that build. Letting an installed CLI
bake a definition you supply is not built yet (finding U20 in
[`docs/analysis/cli-usage-analysis.md`](docs/analysis/cli-usage-analysis.md)).

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
flags when the definition bundled in the CLI you are running declares a version that isn't built yet.

### 4. Run a benchmark

`baas run` builds nothing — build your own benchmark JAR first, then point `baas run` at it with
`--benchmark-jar`, which is required:

```bash
mvn package
baas run --benchmark-jar target/benchmarks.jar --project my-benchmarks \
  --tag branch=main --tag commit=$(git rev-parse HEAD) jmh -- MyBenchmark -f 1 -wi 1 -i 3
```

Nothing is read from the directory you run this in. `--project` is required, and `branch` and
`commit` are recorded only when you pass them as tags — absent otherwise, never `"unknown"`. To
have the project derived instead, opt in once:

```bash
baas config set --git-resolve-project true
```

`baas run` then names the project after the git repository that **contains the benchmark JAR** —
not the directory you typed the command in — and `baas results` uses the current directory's
repository instead of asking. `branch` and `commit` are never derived either way.

Types: `jmh`, `jmh-with-async` (async-profiler flame graphs), `jmh-with-prof` (JMH's own
profilers), `jcstress`.

> **`--` is required.** Everything after it is forwarded verbatim to `benchmark-runner.jar`.
> Without it, picocli parses JMH flags as `baas` options and fails with
> `Unknown options: '-f', '-wi', '-i'`. `baas` options go *before* the separator:
>
> ```bash
> baas run --benchmark-jar target/benchmarks.jar --instance-type c6i.4xlarge --timeout 1800 \
>   jmh -- MyBenchmark -f 1 -wi 1 -i 3
> ```

Useful options: `--benchmark-jar` (required), `--project`, `--runner-jar`, `--instance-type`,
`--timeout`, `--watchdog-margin`, `--tag key=value`, `--no-database`.

> **`--watchdog-margin` is added to `--timeout`, not a bound of its own.** The instance terminates
> itself `timeout + margin` seconds after launch (default margin 300, minimum 60), and the CLI stops
> polling at the same moment — so the watchdog can never fire while the benchmark is still inside
> its own timeout.

> **Tags are how you find a result later.** `--tag key=value` is repeatable and reaches the stored
> measurement, not just the EC2 instance. `project`, `type` and `source` are added for you, and the
> instance adds what it observes: `imageVersion`, `instanceType`, `jdk`, `cpuModel`,
> `cpuArch`. Passing `--tag` for one of those observed keys is rejected — they come from the same
> values the run's own `environment.json` records, so the two can never disagree.

> **A run with nowhere to store its measurements fails before it costs anything.** If the table
> name is missing from your config, `baas run` stops before any upload — before any AWS call at
> all — and tells you to run `baas config sync`. To deliberately throw the numbers away, pass
> `--no-database`.

`-v` / `--verbose` works on every command and switches `baas`'s own logging to debug — resolved run
parameters, the AMI, the CloudFormation parameters, and the full generated user-data script. It
must come before the `--` separator, or it is passed to the benchmark instead.

### 5. Read results

```bash
baas results --project my-benchmarks
```

Prints `BENCHMARK | REQUEST_ID | TYPE | MODE | SCORE | UNIT`, reading the table directly. With `-v`,
each row is followed by an indented line carrying all of its tags. JSON and CSV always carry the
tags, and the score error the table leaves out.

Without `--project`, a terminal offers a numbered list of the projects that hold results (with
`git.resolveProject` enabled, the current directory's repository is used instead); a pipe or
`--format json|csv` gets an error listing them. It drops rows tagged `exclude_from_results=true`,
groups by `(project, benchmark, branch)` and keeps the best score in each group. Filters:

| | |
|---|---|
| `--project <name>` | Read that project's partition |
| `--all-projects` | Every project, with a PROJECT column. Reads the whole table |
| `--tag key=value` | Repeatable; repeated tags must **all** match |
| `--benchmark-name <regex>` | Match on the benchmark name |
| `--request-id <id>` | Every measurement of one run. Cannot be combined with `--project`, `--all-projects`, `--benchmark-name` or `--tag` |
| `--group-by <tag>` | Group by something other than `branch` |
| `--all-runs` | Every measurement, not just the best per group — including excluded runs, shown faint |
| `--limit <n>`, `--format json\|csv` | Bound and reshape the output |

Every command takes `--config-path <file>` to use a configuration other than `~/.baas/config.yaml` —
the way to read another installation, such as a torn-down one whose table was retained.

### 6. Fetch everything a run produced

```bash
baas download 20260820T174432812Z-a3f9c21b
```

Takes the run id `baas run` printed (or a literal S3 result path, `runs/<project>/<runId>` — also
accepted for a run stored before the unified layout, at its original `<branch>/<type>/<timestamp>`
path), and pulls down the whole run: the verbatim `jmh-result.json`, `environment.json`, process
output, logs and profiling artifacts. The stored measurement deliberately drops JMH's `rawData`
and `scorePercentiles` — per-iteration numbers dominate a result's size — so this is where you go
when you need them.

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
baas env diff runs/lynx-journal/20260724T120000000Z-a3f9c21b \
              runs/lynx-journal/20260811T093000000Z-b7e4d0f2
```

```
FIELD          <run A>                         <run B>
amiId          ami-091ea218d041f91eb           ami-0a89e2bd4bf6f208a
imageVersion   1.0.0                           1.1.0
jvmVersion     openjdk version "25.0.4" ...    openjdk version "25.0.3" ...
```

Result paths are `runs/<project>/<runId>`, as printed by `baas run`. A run recorded before the
unified layout keeps its original `<branch>/<type>/<timestamp>` path; both shapes still resolve.
Identical environments report no differences and exit 0.

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
  ├─ resolve the results table and the runner AMI from /<prefix>/runner/ami-id
  │    (fails here if either is missing, before any upload or launch —
  │    --benchmark-jar is required; baas run builds nothing)
  ├─ upload --benchmark-jar to s3://<bucket>/runs/<project>/<runId>/input/benchmark.jar
  ├─ seed releases/<version>/benchmark-runner.jar from GitHub Releases, checksum-
  │    verified, the first time that version runs — never overwritten after
  └─ ec2:RunInstances from that AMI, with a generated user-data script
       ├─ record the environment: environment.json + packages.txt, uploaded
       │    BEFORE the benchmark starts, so a crashed run still says what it
       │    crashed on  (nothing is installed — the toolchain is already baked)
       ├─ download benchmark-runner.jar from S3 — the instance reaches no host
       │    outside the account
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

**Nothing is silently discarded.** A run with no store configured fails before any upload, and a
store write that ultimately fails exits non-zero while leaving the S3 artifacts intact. Discarding
measurements requires `--no-database`.

Design rationale, the invariants the runner depends on, and the open risks:
[`docs/adr/0001-self-contained-baas-cli.md`](docs/adr/0001-self-contained-baas-cli.md).
Call-level sequence diagrams and C4 views (`c4-*.mmd`): [`docs/diagrams/`](docs/diagrams/). The
command surface, state graph and known gaps:
[`docs/analysis/cli-usage-analysis.md`](docs/analysis/cli-usage-analysis.md).

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

CI runs benchmarks by calling the CLI, not by re-implementing it. There is no GitHub Actions
benchmark path any more: `benchmark-runner.yml`, `exec-single-benchmark.yml`,
`start-ec2-runner.yml`, `stop-ec2-runner.yml` and the `act` harness under `.github/test/` are
deleted, and with them `machulav/ec2-github-runner` and the self-hosted runner. If you consumed
the reusable workflow, the contract is now *install the CLI* — see [Install](#1-install).

| Workflow | Trigger | Purpose |
|---|---|---|
| `ci-pr-build.yml` | PR | `mvn clean verify`, with `ASYNC_PATH` exported so the async-profiler test actually runs |
| `e2e-cloud-test.yml` | path-filtered PR / manual | One `baas run jmh-with-async` on real EC2, against the runner AMI |
| `install-test.yml` | PR | `scripts/install.sh` against a fixture release, on Linux and macOS |
| `release.yml` | push to `main` | semantic-release → GitHub Release → GitHub Packages |

Variables (no secrets — a role ARN is not sensitive, and nothing else is left to hold):
`OPERATOR_ROLE_ARN` (core stack output `OperatorRoleArn`), `CORE_STACK_NAME`, `AWS_REGION`.

`e2e-cloud-test.yml` provisions a paid instance per triggering event, which is why its
`pull_request` trigger is path-filtered and there is no schedule. It tags its own measurements
`exclude_from_results=true` — it benchmarks fixture code — so they never reach a comparison, while
`baas results --request-id <runId>` still returns them.

Versioning is handled by semantic-release; `pom.xml` stays at `0.0.0-semantically-released` and the
real version is set at release time. The bump comes from the commit subjects under the
**conventionalcommits** preset: a `!` after the type (`feat(cli)!: …`) marks an incompatible
change, as does a footer naming one. The angular preset in use up to and including v2.2.0
honoured only the footer, so that release's three `feat(cli)!:` commits analysed as a minor and
v3.0.0 is where they are recorded. The preset's major is pinned in `release.yml`; the comment
there says why.

## E2E test

[`e2e-cloud-test.yml`](.github/workflows/e2e-cloud-test.yml) is the end-to-end test: one
`ubuntu-latest` job federates into the operator role and drives `baas run` against the real
installation ([`docs/diagrams/baas-ci-e2e.mmd`](docs/diagrams/baas-ci-e2e.mmd)). It provisions a
paid instance, so it runs on path-filtered pull requests and on demand:

```bash
gh workflow run e2e-cloud-test.yml
```

The only no-cost way to exercise the runner is `./jmh-with-profiler.sh` or `./jmh-with-async.sh`
against LocalStack. The `act` harness and its `/baas/mongo/connection-string` parameter are gone.
