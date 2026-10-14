# Security review — branch `codebase-review`, 2026-10-13

## Summary

1. The main gap: the "never overwritten" pinned runner JAR (`releases/<v>/benchmark-runner.jar`) can be overwritten by any operator, and by any CI branch through S2. The CLI and the instance both trust whatever object is at that key, so one plain `PutObject` changes the runner code for every later job of that version (X1).
2. The runner image installs async-profiler, which runs as root on every runner, from a GitHub tarball with no checksum. The version is pinned but the bytes are not (X2).
3. The runner role can create job items through the upsert behaviour of `UpdateItem`, and can write malformed measurement rows. The readers throw on these rows, and no BaaS role can delete an item, so one bad row breaks `jobs list` or `results query` until an admin removes it. The template comment says this cannot happen (X3).
4. One statement in the deployer policy is not prefix-exact. Its Image Builder `${PREFIX}-*` lets the default deployment's deployer reach `baas-<acct>-dev`'s pipeline (X4).
5. The rest is small hardening: OIDC subject input validation (X5), CloudTrail attribution (X6), terminal and JSON output of runner-written data (X7), and same-origin checksums (X8). User-data quoting, IMDS, the security group, `jobs download` path handling, the YAML/JSON parsing, and logging all came out sound.

## Index

| ID | Finding | Necessity | Effort |
|---|---|---|---|
| X1 | The pinned runner JAR under `releases/` is writable by every operator (and every CI ref via S2), and nothing re-verifies it once it is seeded | Should | S |
| X2 | async-profiler is baked into the AMI from a GitHub tarball with no checksum | Should | S |
| X3 | The runner role can create job items (because `UpdateItem` upserts) and malformed `RESULT#` rows. Readers throw on them, which permanently breaks `jobs list` / `results query` | Could | S |
| X4 | The deployer policy's Image Builder resource `*/${PREFIX}-*` also covers every deployment whose name extends this one | Could | XS |
| X5 | `--github-org` / `--github-repo` are not validated, so `*` or `?` widen the OIDC trust silently | Could | XS |
| X6 | Every operator session is named `baas-cli`, so CloudTrail cannot tell operators apart without a join | Could | XS |
| X7 | Runner-written strings reach the terminal unsanitised, and `jobs show --format json` splices raw `environment.json` text into its output | Could | XS |
| X8 | The CLI and runner-JAR checksums come from the same release as the assets: this gives integrity, not authenticity (challenges CLAUDE.md "Runner JAR integrity: Closed") | Could | S |

Necessity and effort are graded as defined in `docs/review/open-findings.md`.

---

## X1 — The pinned runner JAR is writable by every operator, and nothing re-verifies it · Should · S

**Where:**
- `infra/cf-template-core.yaml:709-720` (operator S3 grant)
- `baas-cli/.../infra/RunnerJarResolver.java:52-57` and `:91-103`
- `baas-cli/.../infra/UserDataScriptBuilder.java:237`
- the test fixture `infra/operator-policy.json:80-92` mirrors the template grant

**Evidence:**
- `OperatorRole` holds `s3:PutObject` on `${S3MainBucket.Arn}/*`, which includes `releases/*`.
- `RunnerJarResolver.resolve` does `if (exists(s3, bucket, key)) { … return key; }`, a bare `HeadObject` with no checksum comparison. The SHA-256 check (`verify`) runs only on the seeding path.
- The instance runs `aws s3 cp "s3://${S3_BUCKET}/${RUNNER_JAR_S3_KEY}" /app/benchmark-runner.jar`, again with no check.
- CLAUDE.md says immutability rests on "two things": `If-None-Match: *` and `RunnerRole` being read-only. But `If-None-Match` is a header the *writer* chooses. An unconditional `PutObject` from an operator overwrites the key, and operators are not denied that.

**Scenario:**
- Anyone who can become an operator overwrites `releases/7.1.1/benchmark-runner.jar`. That includes any principal in the account with `sts:AssumeRole *` (accepted root trust) and, per S2, any workflow on any branch of the repository.
- Every later `baas run` of 7.1.1, by every teammate and by CI, executes that code with `RunnerRole` credentials. It can fabricate measurements, tags and `environment.json`, or skip the benchmark.
- `--runner-jar` already lets an operator run arbitrary code for *their own* job. What X1 adds is reach into *other people's* jobs, persistent and silent, which is exactly what the pinning argument claims to exclude.
- S2 compounds it: a PR branch's federated job can do this with no review.

**Recommendation:**
1. Enforce the conditional write in IAM. Split the operator's `PutObject` so that `releases/*` is allowed only with `Condition: {"Null": {"s3:if-none-match": "false"}}`, using the S3 conditional-write condition key (Nov 2024). Alternatively, add a bucket-policy `Deny` on `releases/*` when the header is absent. Pin it in `CoreTemplateTest` as the runner grants are.
2. Make the trust check positive on every launch:
   - Seed with `ChecksumAlgorithm.SHA256`.
   - On the `exists` path, `HeadObject` with `ChecksumMode.ENABLED` and compare the result against an expected hash. Either fetch the published `.sha256` (small), or better, have the release bake the runner's hash into `baas-build.properties`.
   - Refuse on mismatch, naming the key to delete.
3. Update the CLAUDE.md invariant so it names the third leg.

## X2 — async-profiler is baked into the AMI without a checksum · Should · S

**Where:**
- `baas-cli/.../infra/RunnerImageRenderer.java:173-177`
- `baas-cli/.../infra/RunnerImageDefinition.java:103-106`
- `infra/runner-image.yaml:59-61`

**Evidence:**
- The bake runs `curl -fsSL '{{ASYNC_PROFILER_URL}}' -o /tmp/ap.tgz … tar -xf … mv …` against `https://github.com/async-profiler/async-profiler/releases/download/v%s/…tar.gz`.
- `runner-image.yaml` declares only `version: "4.0"`, and nothing verifies the bytes.
- Corretto and perf come through `dnf`, which is GPG-checked. This download is the one unauthenticated input to the image.

**Scenario:**
- GitHub release assets are mutable: a maintainer, or anyone who compromises their account or the project's release automation, can replace an asset under an existing tag.
- The next `baas admin image build` bakes the replaced `libasyncProfiler.so` / `asprof`. That code is loaded into the benchmark JVM, runs as root on every runner (with `RunnerRole` credentials and relaxed `kptr_restrict`), and is invisible in `environment.json`, which records only `asprof --version`.
- The same reasoning that closed "Runner JAR integrity" applies here, and it also breaks the image's "declared, not inherited" promise: same declaration, different bytes.

**Recommendation:**
- Add `sha256:` under `tools.asyncProfiler` in `runner-image.yaml`.
- Render `echo '<sha>  /tmp/ap.tgz' | sha256sum -c -` after the `curl`; under `set -euxo pipefail` a mismatch aborts the bake.
- This needs an `imageVersion` bump, as any base edit does.
- Unit-test that the rendered component contains the check.

## X3 — The runner can create job items and malformed rows, and readers fail on them · Could · S

**Where:**
- `infra/cf-template-core.yaml:380-404`, whose comments at `:384-385` ("it cannot forge a job item") and `:395-397` ("no Put, so it can never create a job item") are wrong
- `baas-model/.../JobItemMapper.java:67-83` (`Instant.parse(str(item, CREATED_AT))` at :76)
- `baas-model/.../MeasurementItemMapper.java:141-151` (`Instant.parse`, `MeasurementKind.valueOf` on possibly-null attributes)
- `baas-cli/.../jobs/DynamoDbJobRecorder.java:149-151` (`newestFirst` maps every item with no try)

**Evidence:**
- DynamoDB `UpdateItem` creates the item when the key does not exist. The `attribute_exists(pk)` guard is a *request* condition that user-data happens to send. IAM does not require it, and the policy has no `dynamodb:Attributes` restriction.
- So code on a runner (any benchmark JAR or one of its dependencies, through IMDS credentials) can issue an unconditional `UpdateItem` with `pk=JOB` and `sk=` any string. It can set any attributes (`resultPath`, `tags`, `gsi1pk`, `status`).
- With `PutItem` it can write a `RESULT#<anyProject>` row that has no `createdAt` or `kind`.

**Scenario:**
- A runner writes `pk=JOB, sk="~"` with only `status`. Because `"~"` sorts after every timestamp, `baas jobs list` (newest first) reads it on its first page, and `JobItemMapper.fromItem` throws an NPE from `Instant.parse(null)`. Every operator's `jobs list` is broken.
- The same applies to `baas results query --project X` / `--all-projects` with a malformed `RESULT#X` row.
- No BaaS principal holds `dynamodb:DeleteItem`: not the operator, not the runner, and not the deployer, which has table lifecycle only. Recovery therefore needs an account admin with the AWS CLI. Forged rows also pollute `--best-per`.
- The trust position is the same as S7 and *Runners can terminate each other*. What is new here is the incorrect invariant comment and the persistent DoS.

**Recommendation:**
1. Make the readers tolerant: skip and warn on an item the mapper cannot parse, in `newestFirst`, `runQuery` and the Scans, rather than failing the whole command.
2. Narrow the runner's `UpdateItem` with `ForAllValues:StringEquals dynamodb:Attributes: [pk, sk, status, instanceId]`. This stops a forged `resultPath`, `tags` or `gsi1pk`, but a bare item can still be created.
3. Fix both template comments.

## X4 — The deployer policy's Image Builder statement is not prefix-exact · Could · XS

**Where:** `infra/deployer-policy.json:76-91`. Names come from `DeploymentNameRule` (`^[a-z][a-z0-9-]*[a-z0-9]$`), and `cf-template-core.yaml:9` itself gives `baas-123456789012-dev` as an example name.

**Evidence:**
- The statement's resource is `arn:aws:imagebuilder:${REGION}:${ACCOUNT_ID}:*/${PREFIX}-*`, covering `Delete*`, `Update*`, `Create*` and `StartImagePipelineExecution`.
- For the default deployment `baas-123456789012`, that pattern also matches `component/baas-123456789012-dev-component-runner/…`, `image-pipeline/baas-123456789012-dev-pipeline-runner`, and so on.
- Every other statement is exact (`stack/${PREFIX}/*`, `${PREFIX}-results`, the named roles).
- CLAUDE.md states "a deployer policy is prefix-exact, so it covers one deployment only".

**Scenario:** The deployer of the default deployment can delete or update the dev deployment's Image Builder resources and start its pipeline, or a bug or mistyped name in the CLI does so. This is not an escalation, since the deployer is admin-equivalent (Accepted risks). It is a broken isolation claim that ADR 0006 relies on.

**Recommendation:**
- Enumerate the seven exact resource names: `component/${PREFIX}-component-{runner,extension,contract}/*`, `image-recipe/${PREFIX}-recipe-runner/*`, `infrastructure-configuration/${PREFIX}-infra-config-runner`, `distribution-configuration/${PREFIX}-dist-config-runner`, `image-pipeline/${PREFIX}-pipeline-runner`, plus `image/${PREFIX}-recipe-runner/*` for the build records.
- Re-check `renderedPolicyLeavesRoomInAnInlinePolicyBudget`: this adds roughly 400 characters against 238 of headroom under 4608. If it does not fit, correct CLAUDE.md instead.

## X5 — OIDC subject components are not validated · Could · XS

**Where:** `baas-cli/.../commands/admin/SetupCommand.java:562-568` and `infra/cf-template-core.yaml:633-639`.

**Evidence:** `"repo:" + org + "/" + repo + ":*"` is built from raw `--github-org` / `--github-repo` values and lands in a `StringLike` condition, where `*` and `?` are wildcards.

**Scenario:**
- `--github-repo '*'` admits every repository of the org.
- `--github-org '*'` admits *every repository on GitHub* that targets `sts.amazonaws.com`. Any public repo's workflow could then assume the operator role.
- Setup reports success either way. There is also no pin on the immutable `repository_id` claim, so a deleted or renamed org or repo name can be re-registered by someone else.

**Recommendation:** Validate both values against GitHub's name grammar (`[A-Za-z0-9-]` for owners, `[A-Za-z0-9._-]` for repositories) and refuse anything else. Optionally pin `repository_owner_id` as well.

## X6 — Every operator session has the same name · Could · XS

**Where:** `baas-cli/.../infra/OperatorSession.java:251` (`roleSessionName("baas-cli")`).

**Evidence:** Every human and every machine appears in CloudTrail as `assumed-role/<prefix>-role-operator/baas-cli`. `OperatorRole` trusts the account root (accepted), so the session name is the only cheap per-actor label on `RunInstances`, `PutObject` and `UpdateItem` events. Without it, attribution needs a join through the `AssumeRole` event's access-key id.

**Scenario:** Investigating X1 or S7 ("who overwrote `releases/…`?", "who launched these instances?") is a manual correlation instead of a filter.

**Recommendation:** Derive the session name from the caller ARN already fetched in `caller()`: the last path segment, sanitised to `[\w+=,.@-]{2,64}`, for example `baas-<user>`.

## X7 — Runner-written data reaches the terminal and JSON output unsanitised · Could · XS

**Where:**
- `baas-cli/.../commands/JobsShowSubcommand.java:156-185` and `:206`
- `baas-cli/.../results/ResultsTable.java:79-83`
- `baas-cli/.../results/EnvironmentManifest.java:28-37`

**Evidence:**
- Tags, params, `environment.json` fields and job-item strings are printed with no control-character filtering. All of these are writable from a runner: S7 for objects, X3 for items.
- In JSON mode, `printJson` concatenates `environmentJson.strip()` verbatim. `EnvironmentManifest.parse` accepts it because Jackson ignores trailing tokens by default, so a manifest such as `{…}, "job": {"status":"completed"}` injects a duplicate `job` key into `baas jobs show --format json`.

**Scenario:**
- ANSI/OSC sequences in a tag or manifest value can rewrite what the operator sees: hide lines, spoof a status, set the title, or write the clipboard via OSC 52 on terminals that allow it.
- A JSON consumer using last-key-wins (jq) reads the forged status.

**Recommendation:**
- Strip C0/C1 control characters, except tab, in the `Console` text path.
- In `printJson`, re-serialise the parsed manifest map instead of splicing the raw text, or enable `FAIL_ON_TRAILING_TOKENS`.

## X8 — Checksums come from the same release as the assets · Could · S · challenges CLAUDE.md "Runner JAR integrity: Closed"

**Where:**
- `baas-cli/.../infra/RunnerJarResolver.java:58-62`
- `scripts/install.sh:134-136` and `:292`
- `.github/workflows/release.yml:50-51` and `:71-83`

**Evidence:**
- Both the CLI and the installer fetch `X` and `X.sha256` from the same GitHub release, produced in the same job.
- `release.yml`'s own comment concedes that whatever that job installs "could swap both".
- `--update` pipes the newer release's `install.sh` to `sh` with no check at all.

**Scenario:** A compromised release job (an npm dependency of semantic-release: unpinned transitive dependencies, no lockfile), a stolen token with `contents: write`, or a maintainer account takeover replaces an asset and its checksum together. Both checks pass, and every new deployment seeds the attacker's runner. Combined with X1, that runner persists in the bucket.

**Recommendation:**
- Enable GitHub immutable releases for the repository.
- Add `actions/attest-build-provenance` for the two JARs and `install.sh`.
- Optionally verify the attestation (or a hash embedded in the CLI build, as in X1) when seeding.
- Until then, state in CLAUDE.md that the check protects against corruption, not substitution.

---

## Checked and fine

- **User-data quoting** (`UserDataScriptBuilder.java:360-381`). Every value goes through `shellQuote`, or an array literal for params and tags. There is no `eval`, and newlines and `'` are handled. JSON spliced into the `aws dynamodb` call carries only fixed or CLI-built values (status from a fixed set, `JOB_SORT_KEY`, the IMDS instance id). The manifest heredoc carries only machine-observed values, escaped with `json_escape`. `project` is validated by `[A-Za-z0-9._-]+` (`RunCommand.java:731`).
- **IMDS.** `HttpTokens=REQUIRED` and hop limit 1 (`Ec2ProvisioningService.java:82-85`). `ShutdownBehavior=TERMINATE`, and the client token is the job id.
- **Security groups.** The runner group allows egress on 443 only, with no ingress. The build group is separate.
- **Bucket.** SSE-AES256, all four public-access blocks, no public policy. The TLS-only policy was declined in ADR 0005.
- **`baas jobs download` path traversal** (`JobsDownloadSubcommand.java:153-172`). Normalised `startsWith(base)` with a non-zero exit on refusal. `defaultRoot` refuses `.` and `..`. S3 cannot create symlinks.
- **Runner log walk** (`JobLogs.java`). Does not follow links, depth 8, runs from `/app`.
- **Runner process launch.** `ProcessBuilder` takes an argv list, with no shell.
- **Deserialisation.** `ConfigService`, `RunnerImageExtension`, `RunnerImageRenderer` and `EnvironmentManifest` use plain Jackson with no default typing. Jackson's YAML mapper has no SnakeYAML type tags. There is no `ObjectInputStream`.
- **Logging.** SimpleLogger raises only `pl.wsztajerowski` to debug, so there are no SDK wire logs. The user-data debug dump holds no secret. The runner does not log the Mongo URI (it logs the database name only).
- **Operator session.** It refuses another deployment's operator role. A failed assumption stops before any client is built. The source credentials are used only for `GetCallerIdentity` and `AssumeRole`.
- **IAM shape.**
  - `RunnerRole` holds only `jobs/*` put/get and `releases/*` get, and TerminateInstances by tag (accepted).
  - The operator's `RunInstances` is split per resource type and restricted to compute/general instance families. `key-pair` and `launch-template` are not granted, so SSH keys cannot be attached. `PassRole` covers `RunnerRole` only. `CreateTags` is allowed only at launch, so existing instances cannot be re-tagged into the terminate scope.
  - The deployer's IAM, S3, SSM, DynamoDB and CloudFormation statements are exact. X4 is the only exception.
- **Stale-bucket and sniping paths.** After a teardown the operator role is gone, so a stale `~/.baas` file fails at assumption before any upload to a re-registered bucket name. Setup refuses a taken name.
- **Installer.** `set -u`, staged atomic `mv`, the checksum is verified before install, and the trap-based cleanup is correct. The `--update` hand-off interpolates `$newest`, which comes from GitHub's redirect URL. A malicious tag would need repository write access, which already allows publishing a malicious `install.sh`, so this adds nothing new.
- **DynamoDB conditions from the CLI.** `reserve` uses `attribute_not_exists`. Later writes use `attribute_exists AND NOT_TERMINAL`, and the instance receives the same expression. These are correct for honest writers. X3 covers dishonest ones.
- **Already filed or accepted, and not re-filed:** S2, S7, S10, A4; operator root trust; runners terminating each other (including across deployments, per ADR 0006); deployer escalation; relaxed kernel tunables; no TLS-only policy and no 0700 on `~/.baas` (ADR 0005).
