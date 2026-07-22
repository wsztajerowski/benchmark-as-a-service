# BaaS Redesign – Self-contained CLI

> **Superseded:** The setup/teardown provisioning model (§1, §6) and command structure (§3
> "Command reference") described below reflect the original single-stack design. They have
> been superseded by the core/CI stack split and the `baas admin` subcommand group — see
> `openspec/changes/baas-cli-core-ci-split/`.

## Goal

Replace the `run-remote-benchmark.zsh` + GitHub Actions `workflow_dispatch` dependency with a self-contained Java CLI (`baas`) that provisions AWS infrastructure, launches EC2 benchmark runners, manages the MongoDB connection string, polls for results, and tears everything down — **with no dependency on GitHub Actions**. The existing GHA workflows (`benchmark-runner.yml`, `e2e-cloud-test.yml`, `release.yml`) are retained **only for automated CI**; the CLI never dispatches or depends on them.

### Design decisions driving this revision

1. **MongoDB is connect-only.** The CLI does **not** provision a cluster. The user brings a connection string (e.g. from a free-tier Atlas cluster); `baas` validates it and stores it in SSM SecureString. When none is supplied, `baas` prints the Atlas free-tier signup link.
2. **Reuse the current networking model.** The EC2 runner sits in a **public subnet with outbound internet egress** (exactly what the current externally-managed `SUBNET_ID`/`SECURITY_GROUP_ID` already provide), so it reaches MongoDB Atlas and GitHub over the public internet. The private-subnet + VPC-endpoint-only design from earlier drafts is **dropped as the default** and demoted to an optional hardening profile.
3. **No GHA dependency in the app.** `baas` uses the operator's own AWS credentials directly (`ec2:RunInstances`, etc.). It never assumes the OIDC `WorkflowRole`. That role and the `/github/*` SSM params stay in the stack for the GHA CI path only.
4. **Scripts are removed after migration** — but only the benchmark-workflow scripts that `baas` replaces. Build/release helpers used by GHA stay.
5. **Atlas IP allowlist is manual for v1.** No Atlas API integration.

---

## Scope of Changes

| Area | Action |
|---|---|
| `s3-hook-lambda` module | **Remove** – Maven module, JAR, all source |
| `cf-template-bootstrap.yaml` | **Remove** – GitHub SSM params moved into the main stack |
| `cf-template-main.yaml` | **Rewrite** – strip Lambda; add VPC + **public** subnet + internet gateway + security group; add `RunnerRole` permissions; inline surviving SSM params |
| `scripts/run-remote-benchmark.zsh` | **Remove** post-migration – replaced by `baas run` |
| `scripts/wait-for-gha-run.sh` | **Remove** post-migration – replaced by CLI internal polling |
| `scripts/benchmark_overview.sh` | **Remove** post-migration – replaced by `baas results` |
| `scripts/{logger,git_helpers,aws_helpers}.sh` | **Remove** post-migration – sourced only by the three scripts above |
| `scripts/get-version-property*.sh`, `scripts/update-dependencies.sh` | **Keep** – used by GHA CI/release, not the benchmark path |
| GHA workflows (`benchmark-runner.yml`, `e2e-cloud-test.yml`, `release.yml`) | **Keep** – automated CI only; not invoked by `baas` |
| New `baas-cli` Maven module | **Add** |

---

## 1. Infrastructure Changes

### 1.1 New unified CloudFormation template — single-stack provisioning

`baas setup` deploys a **single** CloudFormation stack that creates the entire AWS environment from scratch. No pre-existing VPC, subnet, or security group is required.

**Default networking (public-egress model — mirrors current production):**

| Resource | Type | Purpose |
|---|---|---|
| `BaasVpc` | `AWS::EC2::VPC` | Dedicated VPC (`10.0.0.0/16`); DNS support + DNS hostnames enabled |
| `PublicSubnet` | `AWS::EC2::Subnet` | Public subnet (`10.0.1.0/24`) in first AZ; `MapPublicIpOnLaunch: true` |
| `InternetGateway` | `AWS::EC2::InternetGateway` | Outbound internet for the runner (reaches Atlas + GitHub) |
| `VpcGatewayAttachment` | `AWS::EC2::VPCGatewayAttachment` | Attaches IGW to the VPC |
| `PublicRouteTable` | `AWS::EC2::RouteTable` | Route table for the public subnet |
| `DefaultRoute` | `AWS::EC2::Route` | `0.0.0.0/0` → `InternetGateway` |
| `PublicSubnetRouteTableAssociation` | `AWS::EC2::SubnetRouteTableAssociation` | Associates subnet with route table |
| `S3GatewayEndpoint` | `AWS::EC2::VPCEndpoint` | Gateway endpoint for S3 (free; keeps S3 traffic off the IGW) |
| `RunnerSecurityGroup` | `AWS::EC2::SecurityGroup` | **No inbound rules**; outbound 443 (HTTPS to Atlas/GitHub/AWS APIs) + outbound to S3 prefix list |

**Why this model:** the current solution already runs the EC2 runner in a subnet with internet egress — it has to, because the GHA self-hosted runner registers with `api.github.com` and the runner downloads async-profiler, the JDK, and `benchmark-runner.jar` from GitHub. That same egress is how it reaches MongoDB Atlas today. Reusing it means free-tier Atlas works immediately over the public internet (gated by a manual IP allowlist), with **no NAT gateway** and **no interface VPC endpoints** required.

**Cost note:** No NAT gateway (~$32/month saved vs. a private-subnet design). The only networking cost is the IPv4 public-IP charge (~$0.005/hr ≈ $3.6/month *while an instance is running*, prorated to actual run time) plus the free S3 gateway endpoint.

**Optional hardening profile (`--private-networking`):**

For security-sensitive deployments, a CF `Condition` can instead create a private subnet + interface VPC endpoints (SSM, EC2, ECR, logs) and no public IP. This requires either a NAT gateway or a full set of interface endpoints, **and** an Atlas connectivity path that does not traverse the public internet (Atlas PrivateLink/peering, which require a paid M10+ tier). Out of scope for v1; documented as a future option only.

**Conditional networking (`--use-existing-vpc`):**

```
baas setup --use-existing-vpc --vpc-id vpc-xxx --subnet-id subnet-xxx --sg-id sg-xxx [...]
```

When set, all networking resources above are **skipped** (CF `Condition`); the provided IDs are used directly. The subnet must have outbound internet egress. This preserves compatibility with the GHA workflow, which still passes `SUBNET_ID`/`SECURITY_GROUP_ID` as GHA variables.

### 1.2 Remove from `cf-template-main.yaml`

- Parameter `S3LambdaBucketName`
- `S3MainBucket.NotificationConfiguration` (S3 → Lambda event trigger)
- `S3HookLambdaExecutionRole`, `S3HookLambdaFunction`, `S3HookLambdaPermissionForS3`
- Output `LambdaFunctionArn`

### 1.3 Inline from `cf-template-bootstrap.yaml` → `cf-template-main.yaml`

The four plain-String SSM params (`/github/{org,repo,workflowid,workflowbranch}`) are referenced via `{{resolve:ssm:...}}` inside `WorkflowRole`'s trust policy. They are **still required by the GHA CI path**, so they move into the main stack (rather than being deleted) so the bootstrap stack can be removed:

```yaml
GitHubOrgSSMParam:
  Type: AWS::SSM::Parameter
  Properties:
    Name: !Sub '/${ResourceNamePrefix}/github/org'
    Type: String
    Value: !Ref GitHubOrg   # new parameter, default: wsztajerowski
# … /github/repo, /github/workflowid, /github/workflowbranch (same as bootstrap)
```

`WorkflowRole`, `RunnerRole`, the OIDC provider, and these SSM params are kept **for the GHA CI path**. The `baas` CLI does not use `WorkflowRole`.

### 1.4 Add to `RunnerRole` policies

```yaml
# EC2 self-termination – scoped to own-kind instances only
- Effect: Allow
  Action: ec2:TerminateInstances
  Resource: "arn:aws:ec2:*:*:instance/*"
  Condition:
    StringEquals:
      aws:ResourceTag/baas-role: benchmark-runner

# Read MongoDB connection string from SSM at runtime
- Effect: Allow
  Action: ssm:GetParameter
  Resource: !Sub "arn:aws:ssm:${AWS::Region}:${AWS::AccountId}:parameter/${ResourceNamePrefix}/mongo/connection-string"
```

`AmazonSSMManagedInstanceCore` (SSM Session Manager) is **optional** in the public-egress model since SSH/Session Manager isn't required for normal runs — attach it only if interactive debugging into runners is wanted.

### 1.5 New SSM parameter (created by `baas config set`, not CloudFormation)

CloudFormation still does not support `SecureString` parameters. The CLI writes it:

```
Path:  /<prefix>/mongo/connection-string
Type:  SecureString  (AWS-managed KMS key)
Value: mongodb+srv://<user>:<pass>@<host>/<db>
```

**Validation:** `baas config set --mongo-uri` rejects a URI without a database name, because `DatabaseServiceBuilder.build()` hard-requires one (`benchmark-runner/.../infra/DatabaseServiceBuilder.java`). Failing at config time avoids a silent `NoOpDatabaseService` fallback on the runner (results would otherwise land in S3 only, never MongoDB).

### 1.6 New CF outputs

```yaml
RunnerRoleName:
  Value: !Ref RunnerRole
SubnetId:
  Value: !If [CreateNetworking, !Ref PublicSubnet, !Ref ExistingSubnetId]
SecurityGroupId:
  Value: !If [CreateNetworking, !Ref RunnerSecurityGroup, !Ref ExistingSecurityGroupId]
VpcId:
  Value: !If [CreateNetworking, !Ref BaasVpc, !Ref ExistingVpcId]
BucketName:
  Value: !Ref S3MainBucket
```

`baas setup` reads these outputs and writes them to `~/.baas/config.yaml`.

---

## 2. MongoDB & Credential Flow

### 2.1 How MongoDB access works today (baseline)

MongoDB access is a **plain connection string with credentials embedded in the URI** — no IAM/auth integration. Two disconnected consumers:

- **EC2 runner (writes):** `benchmark-runner.jar` reads `--mongo-connection-string` / `-m`, which picocli defaults to the `MONGO_CONNECTION_STRING` env var (`ApiCommonSharedOptions.java`). The GHA workflow `exec-single-benchmark.yml` sets that env var from the GHA secret `MONGO_CONNECTION_STRING`. `DatabaseServiceBuilder` parses it, requires a DB name, and builds a Morphia `Datastore`. An empty value → `NoOpDatabaseService` (S3 only, no Mongo).
- **Developer laptop (reads):** `run-remote-benchmark.zsh` / `benchmark_overview.sh` use a *different* env var, `BENCHMARK_DB_CONNECTION_STRING`, with `mongosh`.

There is no central store; the secret lives in two places (GHA secret + local env var).

### 2.2 Target flow — single source of truth in SSM

```
Developer machine
  baas config set --mongo-uri mongodb+srv://...
       │ ssm:PutParameter (SecureString, KMS-encrypted, TLS in transit)
       ▼
  SSM Parameter Store  /<prefix>/mongo/connection-string
       ├──────────────────────────────────────────────┐
       │ (runner) ssm:GetParameter --with-decryption    │ (laptop) baas results:
       ▼                                                 ▼  ssm:GetParameter --with-decryption
  EC2 user-data exports MONGO_CONNECTION_STRING      reuses DatabaseServiceBuilder/Morphia
       ▼                                                 → prints results table
  benchmark-runner.jar  (reads env var — UNCHANGED)
```

**`benchmark-runner.jar` requires no code changes** — it already reads `MONGO_CONNECTION_STRING`. The migration just changes *where the value comes from* (SSM instead of a GHA secret). `baas results` replaces the laptop-side `BENCHMARK_DB_CONNECTION_STRING` + `mongosh` path by reusing `DatabaseServiceBuilder`.

### 2.3 No connection string yet

If `baas setup`/`config set` runs without a `--mongo-uri`, the CLI prints:

```
No MongoDB connection string provided.
Create a free Atlas cluster: https://www.mongodb.com/cloud/atlas/register
Then run: baas config set --mongo-uri "mongodb+srv://<user>:<pass>@<host>/<db>"
Remember to add your runner's egress IP and your laptop's IP to the Atlas IP Access List.
```

No Atlas API keys, no cluster provisioning. (See §7 risk R2 for the manual allowlist.)

### 2.4 AWS credentials

- The CLI uses the standard AWS credential chain: env vars → `~/.aws/credentials` profile (stored as `aws.profile` in `~/.baas/config.yaml`). No GHA, no OIDC.
- The EC2 instance uses IMDSv2 + `RunnerInstanceProfile` — temporary, auto-rotated credentials.
- No AWS access keys are ever stored in `~/.baas/config.yaml`.

---

## 3. New CLI Module: `baas-cli`

### Maven module

Add to root `pom.xml` modules list. Dependencies resolved via existing BOMs:

```xml
<dependency>software.amazon.awssdk:ec2</dependency>
<dependency>software.amazon.awssdk:ssm</dependency>          <!-- promote from s3-hook-lambda to BOM -->
<dependency>software.amazon.awssdk:cloudformation</dependency>
<dependency>software.amazon.awssdk:s3</dependency>
<dependency>com.fasterxml.jackson.dataformat:jackson-dataformat-yaml</dependency>
<dependency>org.mongodb:mongodb-driver-sync</dependency>     <!-- already in BOM -->
<dependency>dev.morphia.morphia:morphia-core</dependency>    <!-- already in BOM -->
<dependency>info.picocli:picocli</dependency>                <!-- already in BOM -->
```

### Package layout

```
baas-cli/src/main/java/pl/wsztajerowski/baas/
  BaasApp.java                      # picocli root @Command(name="baas")
  commands/
    SetupCommand.java               # baas setup
    TeardownCommand.java            # baas teardown
    ConfigCommand.java              # baas config
    ConfigSetSubcommand.java        # baas config set
    ConfigShowSubcommand.java       # baas config show
    RunCommand.java                 # baas run <type>
    ResultsCommand.java             # baas results
  config/
    BaasConfig.java                 # Jackson POJO
    ConfigService.java              # read/write ~/.baas/config.yaml
  infra/
    Ec2ProvisioningService.java     # RunInstances, poll, TerminateInstances
    SsmService.java                 # GetParameter (AMI, Mongo), PutParameter (Mongo)
    S3UploadService.java            # PutObject, HeadObject (sentinel polling)
    CloudFormationService.java      # CreateStack / UpdateStack / DeleteStack
    UserDataScriptBuilder.java      # builds EC2 user-data bash script from template
  results/
    ResultsQueryService.java        # MongoDB aggregation (mirrors benchmark_overview.sh)
    ResultRow.java
```

### `~/.baas/config.yaml` schema (non-sensitive fields only)

```yaml
prefix: baas                              # CloudFormation resource name prefix
aws:
  profile: lynx                           # AWS CLI profile (~/.aws/credentials)
  region: eu-central-1
  bucket: baas-main                       # S3 working bucket (from CF output)
  subnetId: subnet-xxxxxxxxxxxx           # from CF output (created or passed through)
  securityGroupId: sg-xxxxxxxxxxxx        # from CF output
  vpcId: vpc-xxxxxxxxxxxx                 # from CF output
  runnerInstanceProfileName: baas-github-actions-runner-role
  stackName: baas-main                    # CF stack name (for updates/teardown)
ec2:
  defaultInstanceType: c5.2xlarge
  benchmarkTimeoutSeconds: 7200           # process timeout (Linux `timeout` cmd)
  wallClockHardKillSeconds: 7500          # absolute watchdog cap (default: benchmarkTimeoutSeconds + 300)
benchmark:
  asyncProfilerVersion: "4.0"
  jarPath: jmh-benchmarks/target/jmh-benchmarks.jar
# mongo.connectionString is NOT here — stored in SSM SecureString only
# benchmark-runner.jar is downloaded from GitHub Releases by default; override with `baas run --runner-jar`
```

### Command reference

```
baas setup
    [--prefix baas] [--region eu-central-1] [--stack-name baas-main]
    [--github-org <o>] [--github-repo <r>] [--workflow-id <id>] [--oidc-provider-arn <arn>]
    [--use-existing-vpc --vpc-id <v> --subnet-id <s> --sg-id <g>]
    [--mongo-uri <uri>]           # optional; if omitted, prints Atlas signup link
    → Creates VPC + public subnet + IGW + S3 gateway endpoint + SG + S3 bucket + IAM (+ OIDC for GHA CI)
    → When --use-existing-vpc: skips networking, uses provided IDs
    → Stores --mongo-uri in SSM SecureString (if provided)
    → Reads CF outputs, writes ~/.baas/config.yaml

baas teardown                     # see §6 for safety gates
    [--stack-name baas-main] [--yes]
    [--delete-bucket]             # default: RETAIN results bucket
    [--delete-oidc]               # default: skip if OIDC pre-existed setup

baas config set
    [--mongo-uri <uri>]           → ssm:PutParameter SecureString (validates DB name present)
    [--aws-profile <p>] [--region <r>] [--bucket <b>]
    [--instance-type <t>] [--timeout <seconds>] [--max-wall-clock <seconds>]

baas config show                  → prints config (mongo URI masked as ***)

baas run <jmh | jmh-with-async | jmh-with-prof | jcstress>
    [--benchmark-jar <path>]      → artifact to upload (default: config.benchmark.jarPath)
    [--skip-build]                → skip mvn build + reuse existing jar
    [--runner-jar <path>]         → upload local runner JAR instead of GitHub Releases
    [--instance-type <t>]         → overrides config default
    [--timeout <seconds>]         → process timeout
    [--max-wall-clock <seconds>]  → absolute watchdog cap
    [--tag key=value]... [--branch <b>]
    -- [benchmark params...]      → forwarded verbatim to benchmark-runner.jar

baas results
    [--benchmark-name <regex>] [--living-branches] [--all] [--request-id <id>]
    [--format table|json|csv]     → default: table
```

---

## 4. EC2 Provisioning Flow (`baas run`)

```
baas run jmh-with-async -- MyBenchmark -f 2 -wi 3 -i 5
 │
 ├─ 1. load ~/.baas/config.yaml + merge CLI flags
 ├─ 2. mvn clean package -q  (skip with --skip-build; runs in CWD)
 ├─ 3. generate requestId = jmh-with-async-<yyyyMMdd_HHmmss>
 │      resultPath  = <branch>/jmh-with-async/<yyyyMMdd_HHmmss>
 ├─ 4. s3:PutObject  benchmark JAR  → s3://<bucket>/runs/<requestId>/benchmark.jar
 │      [--runner-jar given]  s3:PutObject runner JAR → s3://<bucket>/runs/<requestId>/runner.jar
 │      [else]  user-data downloads benchmark-runner.jar from latest GitHub Release
 ├─ 5. ssm:GetParameter /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64
 ├─ 6. UserDataScriptBuilder.build(config, requestId, resultPath, params)  → Base64
 ├─ 7. ec2:RunInstances
 │       MetadataOptions: { HttpTokens: required, HttpPutResponseHopLimit: 1 }
 │       InstanceInitiatedShutdownBehavior: terminate
 │       IamInstanceProfile.Name: <prefix>-github-actions-runner-role
 │       NetworkInterfaces: [{ SubnetId, Groups, AssociatePublicIpAddress: true }]  # public egress
 │       BlockDeviceMappings: [{ DeviceName: /dev/xvda, Ebs: { VolumeSize: 30, VolumeType: gp3 } }]
 │       Tags: project=baas, baas-role=benchmark-runner, baas:request-id=<id>, <user --tag values>
 │       → instanceId
 ├─ 8. register JVM shutdown hook: ec2:TerminateInstances(instanceId)  [Ctrl+C safety net]
 └─ 9. poll every 15 s:
         s3:HeadObject s3://<bucket>/<resultPath>/run-status
         ├─ 404 → still running; print elapsed time
         ├─ 200 → s3:GetObject body
         │    ├─ "completed"  → success; query MongoDB; print results table
         │    └─ "failed:<n>" → print error; link to S3 output
         └─ client-side abort at wallClockHardKillSeconds
```

### EC2 user-data script (rendered by `UserDataScriptBuilder`)

```bash
#!/bin/bash
# No `set -e` — errors handled explicitly so the watchdog always starts.

TOKEN=$(curl -sX PUT "http://169.254.169.254/latest/api/token" \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 300")
INSTANCE_ID=$(curl -sH "X-aws-ec2-metadata-token: $TOKEN" \
  http://169.254.169.254/latest/meta-data/instance-id)

# --- Layer 1: background watchdog (fires even if Java deadlocks) ---
(
  sleep ${WALL_CLOCK_HARD_KILL}
  echo "WATCHDOG: hard-kill cap exceeded; terminating $INSTANCE_ID"
  aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "${AWS_REGION}"
) &
WATCHDOG_PID=$!

# --- Install runtime ---
yum update -y
yum install -y java-25-amazon-corretto-headless

# --- async-profiler (jmh-with-async only): download from GitHub over public egress ---
if [[ "${BENCHMARK_TYPE}" == "jmh-with-async" ]]; then
  mkdir -p /app
  wget -nv "https://github.com/async-profiler/async-profiler/releases/download/v${ASYNC_PROFILER_VERSION}/async-profiler-${ASYNC_PROFILER_VERSION}-linux-x64.tar.gz" -O /tmp/ap.tar.gz
  tar -xf /tmp/ap.tar.gz -C /tmp
  mv /tmp/async-profiler-*-linux-x64 /app/async-profiler
fi

# --- Download JARs from S3 (request-scoped paths for concurrency safety) ---
mkdir -p /app
aws s3 cp "s3://${S3_BUCKET}/runs/${REQUEST_ID}/runner.jar"    /app/benchmark-runner.jar
aws s3 cp "s3://${S3_BUCKET}/runs/${REQUEST_ID}/benchmark.jar" /app/benchmark-under-test.jar

# --- Fetch MongoDB URI from SSM (value never in user-data) ---
export MONGO_CONNECTION_STRING=$(aws ssm get-parameter \
  --name "/${SSM_PREFIX}/mongo/connection-string" \
  --with-decryption --query Parameter.Value --output text --region "${AWS_REGION}")

# --- Layer 2: benchmark process with its own timeout ---
timeout "${BENCHMARK_TIMEOUT}" java -jar /app/benchmark-runner.jar "${BENCHMARK_TYPE}" \
  --request-id     "${REQUEST_ID}" \
  --result-path    "${RESULT_PATH}" \
  --s3-bucket      "${S3_BUCKET}" \
  --benchmark-path /app/benchmark-under-test.jar \
  ${BENCHMARK_PARAMETERS}
EXIT_CODE=$?

# --- Write sentinel to S3 ---
STATUS="completed"; [[ $EXIT_CODE -ne 0 ]] && STATUS="failed:${EXIT_CODE}"
echo "$STATUS" | aws s3 cp - "s3://${S3_BUCKET}/${RESULT_PATH}/run-status"

# --- Cleanup ---
kill $WATCHDOG_PID 2>/dev/null || true
aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "${AWS_REGION}"
```

**Variables injected (all non-sensitive):** `AWS_REGION`, `S3_BUCKET`, `SSM_PREFIX`, `BENCHMARK_TYPE`, `REQUEST_ID`, `RESULT_PATH`, `BENCHMARK_TIMEOUT`, `WALL_CLOCK_HARD_KILL`, `ASYNC_PROFILER_VERSION`, `BENCHMARK_PARAMETERS`.

> async-profiler is downloaded from GitHub (public egress) — same as today's GHA path. With the public-subnet model there is no need to pre-stage it to S3.

---

## 5. EC2 Timeout / Watchdog

Three independent layers ensure no orphaned instance, with a **configurable wall-clock cap**:

| Layer | Mechanism | Fires when |
|---|---|---|
| 1 – process timeout | `timeout $BENCHMARK_TIMEOUT java -jar ...` (SIGTERM→SIGKILL) | Benchmark exceeds `benchmarkTimeoutSeconds` |
| 2 – shell watchdog | Background `sleep $WALL_CLOCK_HARD_KILL && ec2:TerminateInstances` in user-data | Java deadlocks, SIGTERM ignored, OOM, or instance hung |
| 3 – CLI shutdown hook | JVM `Runtime.addShutdownHook` → `ec2:TerminateInstances` | CLI killed with Ctrl+C or crashes |

**Timeout semantics:**
- `benchmarkTimeoutSeconds` (CLI `--timeout`) → the Linux `timeout` on the benchmark process.
- `wallClockHardKillSeconds` (CLI `--max-wall-clock`) → the absolute watchdog cap and the CLI polling abort. Defaults to `benchmarkTimeoutSeconds + 300` (5-min grace for post-run S3 upload), but is **independently configurable** so operators can set a hard ceiling regardless of the benchmark's own timeout.

**On timeout:** JMH flushes results per-benchmark (`JmhSubcommandService.executeCommand()`), so completed benchmarks are already in MongoDB. The sentinel becomes `failed:124` (124 = `timeout` killed). `baas run` prints a warning and links to partial S3 output.

---

## 6. Teardown (`baas teardown`)

`baas teardown` deletes the stack with data-protection gates. All checks run **before** any destructive call:

1. **No active runs** — `ec2:DescribeInstances` filtered on `tag:baas-role=benchmark-runner` + `instance-state-name=running`; abort if any exist.
2. **Explicit confirmation** — interactively retype the stack name, or pass `--yes`.
3. **Data is opt-in to delete:**
   - The S3 results bucket keeps `DeletionPolicy: Retain` — CF will not delete it. It is emptied + deleted **only** with `--delete-bucket`.
   - The MongoDB cluster is **never** touched (connect-only; not owned by this stack). Teardown reminds the user to delete it manually in Atlas if desired.
4. **Don't delete shared infra** — if `setup` reused a pre-existing OIDC provider, teardown leaves it unless `--delete-oidc`.
5. Delete the `/<prefix>/mongo/connection-string` SecureString last; report what was retained vs. deleted.

Sequence: empty bucket (if `--delete-bucket`) → `cloudformation:DeleteStack` → `wait stack-delete-complete` → delete SSM SecureString → print summary.

---

## 7. Open Questions, Risks & Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| **R2 — Atlas IP allowlist.** The runner's public egress IP is ephemeral; the laptop running `baas results` also needs allowlisting. | Medium | **v1: manual.** Document in `baas setup` output. The user adds their laptop IP and (broadly) the runner egress range to the Atlas IP Access List. A future option is a stable EIP/NAT to allowlist once. `0.0.0.0/0` is insecure but works for throwaway free-tier dev. |
| **R6 — SSM param name collision on migration.** Inlined `/github/*` params clash with the bootstrap stack still owning them. | Medium | Delete the bootstrap stack first, *then* deploy the new stack (params disappear then recreate); or `cloudformation import`. See `aws-migration-plan.md §3.1`. |
| **R7 — Elevated IAM for setup/teardown.** `cloudformation:*`, EC2/VPC create, `iam:CreateRole`, `iam:PassRole`. | Medium | Document a separate elevated "operator setup" policy vs. the narrow day-to-day `BaasCliOperatorPolicy` (§8). Grant the elevated policy only when running `setup`/`teardown`. |
| **R9 — runner JAR from GitHub Releases has no checksum check.** | Low | Add SHA-256 verification in user-data (fetch checksum asset from the release). |
| **R10 — `baas run` assumes a Maven project producing one jar.** | Low | Non-Maven/multi-jar projects use `--benchmark-jar` explicitly + `--skip-build`. Document. |
| **R11 — shared `RunnerRole` can read any operator's Mongo URI** (single SSM path). | Low–Med | For multi-tenant use, per-operator SSM path prefixes (`/<prefix>/mongo/<operator>`). Out of scope for v1. |
| **R12 — public IP on the runner widens exposure** vs. a private subnet. | Low | `RunnerSecurityGroup` has **no inbound rules** (outbound only); IMDSv2 required; instance is short-lived and self-terminating. `--private-networking` available as a future hardening profile. |

> Resolved by earlier decisions: Atlas API provisioning (dropped — connect-only); private-subnet/VPC-endpoint Atlas reachability (R1, resolved by reusing public egress); GHA coexistence (decided — GHA CI path retained, app independent of it).

### CLI operator minimum IAM policy (`BaasCliOperatorPolicy`)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "EC2BenchmarkManagement", "Effect": "Allow",
      "Action": ["ec2:RunInstances","ec2:DescribeInstances","ec2:DescribeInstanceStatus",
                 "ec2:DescribeImages","ec2:DescribeSubnets","ec2:DescribeSecurityGroups"],
      "Resource": "*" },
    { "Sid": "EC2TerminateOwnBenchmarkInstances", "Effect": "Allow",
      "Action": "ec2:TerminateInstances", "Resource": "arn:aws:ec2:*:*:instance/*",
      "Condition": { "StringEquals": { "aws:ResourceTag/baas-role": "benchmark-runner" } } },
    { "Sid": "EC2TagOnLaunch", "Effect": "Allow",
      "Action": "ec2:CreateTags", "Resource": "*",
      "Condition": { "StringEquals": { "ec2:CreateAction": "RunInstances" } } },
    { "Sid": "PassRunnerRole", "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": "arn:aws:iam::<account>:role/<prefix>-github-actions-runner-role" },
    { "Sid": "SsmAmiLookup", "Effect": "Allow",
      "Action": "ssm:GetParameter",
      "Resource": "arn:aws:ssm:*::parameter/aws/service/ami-amazon-linux-latest/*" },
    { "Sid": "SsmMongoReadWrite", "Effect": "Allow",
      "Action": ["ssm:GetParameter","ssm:PutParameter"],
      "Resource": "arn:aws:ssm:<region>:<account>:parameter/<prefix>/mongo/connection-string" },
    { "Sid": "S3BenchmarkBucket", "Effect": "Allow",
      "Action": ["s3:PutObject","s3:GetObject","s3:HeadObject","s3:ListBucket"],
      "Resource": ["arn:aws:s3:::<prefix>-main","arn:aws:s3:::<prefix>-main/*"] }
  ]
}
```

`cloudformation:*` and IAM/VPC create permissions are needed only for `baas setup`/`baas teardown` — treat as a separate, temporarily-granted elevated policy.

---

## 8. Distribution

The `baas-cli` shaded JAR is distributed, in priority order:

1. **GitHub Releases (v1 baseline).** `release.yml` publishes `baas-cli.jar` + a small `baas` wrapper (`exec java -jar …`) + `install.sh`. Users run `curl -fsSL <release-url>/install.sh | bash`, which checks for `java` + `aws`, downloads the JAR + wrapper, and puts `baas` on `PATH`. Reuses the existing semantic-release pipeline; `exec-single-benchmark.yml` already downloads `benchmark-runner.jar` from Releases, so the pattern is proven.
2. **Homebrew tap** (`wsztajerowski/homebrew-baas`). Formula depends on `openjdk`, installs the released JAR + wrapper. Best DX for Mac/Linux; `brew upgrade` for updates.
3. **jpackage/jlink bundles** (later). Self-contained per-OS installers (no preinstalled JDK). Heavier release matrix.
4. **GraalVM native-image** (stretch). Single static binary, instant startup — needs reflection/resource config for AWS SDK v2 + Morphia + picocli. Defer unless startup latency matters.
5. **Docker image** (`ghcr.io/wsztajerowski/baas`) for headless/CI users who'd rather not install Java.

**Recommendation:** ship (1) + (2) at migration time; (3)–(5) are backlog.

`install.sh` = bootstrap + `baas setup`; `uninstall.sh` = `baas teardown`. (Both are thin wrappers; the logic lives in the JAR.)

---

## 9. Migration Path

### Phase 1 — additive, zero breaking changes
1. Create `baas-cli` Maven module; add to root `pom.xml`.
2. Implement `run` / `results` / `config` against the existing (unmodified) CF infrastructure and existing `SUBNET_ID`/`SECURITY_GROUP_ID`.
3. Scripts and GHA workflows remain unchanged. Verify `baas run` reaches feature parity with `run-remote-benchmark.zsh`.

### Phase 2 — CF rewrite (backwards-compatible with GHA)
4. Inline surviving `/github/*` SSM params from the bootstrap stack into the main stack.
5. Rewrite `cf-template-main.yaml`: remove Lambda + S3 notification; add VPC/public-subnet/IGW/S3-gateway-endpoint/SG; add `RunnerRole` SSM + EC2-terminate permissions.
6. Deploy. `WorkflowRole`/`RunnerRole` ARNs stay stable; no GHA secrets change.
7. Implement `baas setup` / `baas teardown` against the new template.

### Phase 3 — cleanup
8. Remove `s3-hook-lambda/` and its `<module>` entry; delete `cf-template-bootstrap.yaml`; empty + delete the `<prefix>-lambda` bucket.
9. Remove the benchmark-workflow scripts: `run-remote-benchmark.zsh`, `wait-for-gha-run.sh`, `benchmark_overview.sh`, `logger.sh`, `git_helpers.sh`, `aws_helpers.sh`. Keep `get-version-property*.sh` and `update-dependencies.sh`.
10. Set up distribution (Releases artifacts + `install.sh`/`uninstall.sh` + Homebrew tap).
11. Update `release.yml`, `AGENTS.md`, `README.md`, `CLAUDE.md`.

---

## 10. Resolved Design Decisions

### 10.1 `baas results --living-branches`
Shell out to `git branch -r` via `ProcessBuilder` (matches `git_helpers.sh`). Requires running inside a git working tree. No GitHub API token.

### 10.2 `benchmark-runner.jar` delivery to EC2
Default: user-data downloads `benchmark-runner.jar` from the latest GitHub Release (over public egress). `--runner-jar <path>` overrides for dev (uploads to `s3://<bucket>/runs/<requestId>/runner.jar`).

### 10.3 Multi-prefix support
Out of scope for v1. Single `~/.baas/config.yaml`. Future: `~/.baas/<profile>.yaml` + `--baas-profile`.

### 10.4 `jcstress` in `baas run`
First-class. `benchmark-runner.jar jcstress --benchmark-path …` is already wired; user-data skips async-profiler for non-`jmh-with-async` types.

### 10.5 CloudFormation template packaging
Bundle `cf-template-main.yaml` as a classpath resource in the `baas-cli` JAR (`templates/cf-template-main.yaml`). `SetupCommand` reads it and calls `CreateStack`/`UpdateStack` with `TemplateBody`. No external file dependency.

### 10.6 Maven build target in `baas run`
`baas run` executes `mvn clean package -q` in the **current working directory** (the user's benchmark project). `--benchmark-jar <path>` selects the resulting artifact (default: `benchmark.jarPath`). `--skip-build` skips both build and rebuild-upload.

---

## 11. Scenario Simulations & Findings

### Scenario A: `baas run jmh-with-async -- Incrementing_Synchronized -f 1 -wi 1 -i 3 --async-event=wall`

- **Finding 1 — Concurrent runs collide on S3 paths.** Two devs on the same branch overwrite each other's upload. **Fix:** request-ID-scoped paths (`runs/<requestId>/{benchmark,runner}.jar`). Applied in §4.
- **Finding 2 — `set -e` kills user-data before the watchdog starts.** If IMDSv2/instance-id fetch fails, the script exits before spawning the watchdog → orphan. **Fix:** no `set -e`; start the watchdog right after fetching `INSTANCE_ID`; handle errors via exit codes + sentinel. Applied in §4.
- **Finding 3 — async-profiler download.** *(Revised.)* In the public-egress model the runner downloads async-profiler directly from GitHub (as the current GHA path does). No NAT gateway and no S3 pre-staging required. Applied in §4 user-data.
- **Finding 4 — root volume too small.** AL2023 default is 8 GB; profiling artifacts can exhaust it. **Fix:** `BlockDeviceMappings` 30 GB gp3. Applied in §4.

### Scenario B: `baas run jcstress -- MyStressTest -f 2`
`benchmark-runner.jar jcstress --benchmark-path …` works; async-profiler skipped. No issues. ✓

### Scenario C: `baas run jmh-with-prof -- … --profiler 'jfr=stackDepth=20'`
JFR is built into the JDK; no async-profiler needed. ✓

### Scenario D: `baas config set --mongo-uri …` before `baas setup`
`ConfigService` creates `~/.baas/` + `config.yaml` with defaults if absent; `--prefix` defaults to `baas`. ✓

### Scenario E: Two developers sharing one AWS account
`baas config show` reads the URI from SSM (`ssm:GetParameter --with-decryption`) and prints it masked. Operator policy already grants this. ✓

### Scenario F: EC2 fails to start (bad AMI/subnet)
SDK throws; CLI prints the error and exits. No instance created → shutdown hook is a no-op. ✓

### Scenario G: `baas setup` when stack exists
`DescribeStacks` first → exists ⇒ `UpdateStack`, else `CreateStack`; no-op update ⇒ `NoUpdateToPerformException` ⇒ "stack is up to date". ✓

### Scenario H: `baas teardown` with a run in flight
`DescribeInstances` finds a `baas-role=benchmark-runner` instance in `running` → abort with a message listing instance IDs. ✓ (§6 gate 1.)

---

## 12. Security Notes

### Improvements over the current design

| Area | Before | After |
|---|---|---|
| MongoDB credentials on EC2 | GHA secret → env var in workflow YAML → visible in GHA logs | SSM SecureString fetched at runtime; never in user-data; not in GHA logs |
| Lambda attack surface | Lambda + S3 trigger + GitHub token in SSM | Entire Lambda path removed |
| EC2 metadata | IMDSv1 allowed | `HttpTokens: required` (IMDSv2 only), hop-limit 1 |
| Orphaned instances | `stop-runner` only if the GHA job is reached | Self-terminate via watchdog + CLI shutdown hook + `InstanceInitiatedShutdownBehavior: terminate` |
| Inbound exposure | depends on external SG | `RunnerSecurityGroup` has **no inbound rules** |

### Residual notes

- **Public IP on the runner** (R12): outbound-only SG, IMDSv2, short-lived self-terminating instance. `--private-networking` profile available later for stricter posture (requires paid Atlas tier for private connectivity).
- **`yum`/GitHub/Atlas over public egress**: acceptable and matches current behaviour; S3 traffic stays on the free gateway endpoint.
- **Sentinel `run-status`**: contains only `completed`/`failed:<code>` — no secrets.
