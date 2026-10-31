# CLAUDE.md — Benchmark as a Service (BaaS)

This file deliberately carries only what you **cannot** get by reading the code: invariants that
look arbitrary but aren't, facts about what *isn't* there, and decisions whose rationale lives
nowhere else. Standard Maven/AWS/picocli behaviour, directory-name-restates-purpose descriptions,
and anything `--help` or a template file will tell you are omitted on purpose. Don't add them back.

## How to read the prompts here

**Most prompts in this repository are dictated, not typed.** So the transcript is one lossy step
away from what was meant, and the errors cluster in exactly the words that matter most here:
identifiers, flags, file names, AWS service names, and anything CamelCase.

Treat a word that doesn't fit — a wrong homophone, a mangled class or option name, a sentence that
parses strangely, a stray "the" splitting a term — as a speech-to-text artefact first and a
deliberate instruction second. Reconstruct the term the code actually uses, and say in one line
which reading you took ("reading X as Y") rather than either asking about it or silently guessing.
Ask only when two readings would lead to materially different work.

The same applies to names you are asked to create: a dictated branch, change or file name arrives
without spelling or casing, so slugify it to match what is already in the tree and state what you
chose.

**Open questions get asked one at a time, the way brainstorming does.** When you have several
decisions to put to the user — an artifact's Open Questions, a set of unresolved options, a list of
things to confirm — do not present them as a batch to be answered in one reply. Ask the first,
wait for the answer, then ask the next. Dictating a reply that addresses four numbered questions
at once is exactly where answers get merged, misattributed or silently dropped. Analysis and
recommendations for all of them may be written out together; the *questions* are serialised.

## What this is

Runs JMH and JCStress benchmarks on throwaway EC2 instances. Measurements go to a DynamoDB table,
one item per measurement, and each job's status to one job item in the same table; process output,
the verbatim result JSON and profiling artifacts go to S3.

| Module | Runs where |
|---|---|
| `baas-cli` | **Your laptop.** Provisions infrastructure, launches runners, polls for results. `pl.wsztajerowski.baas.BaasApp` |
| `benchmark-runner` | **The EC2 instance.** Executes benchmarks, uploads to S3, writes measurements to the results table. `pl.wsztajerowski.commands.TestWrapper` |
| `baas-model` | The one contract the CLI, the runner and the generated user-data read: the stored measurement shape, the key encoding, the tag vocabulary, the artifact names inside a job's prefix (`JobLayout`), the benchmark types (`BenchmarkType`) and the runner's option names (`RunnerArgs`). `model` imports no AWS SDK (`ModelBoundaryTest`); `model.dynamodb` holds the two item mappers and the job item's guard (`JobItemConditions`). No MongoDB dependency, enforced by the build |
| `fake-jmh-benchmarks`, `fake-stress-tests` | Test fixtures |

**`baas-cli`'s packages are the command tree's nouns over a small kernel** (ADR 0009). `baas` is the
composition root and nothing imports it; the kernel — `cli`, `console`, `session`, `aws` — imports no
slice; the slices — `jobs`, `results`, `deployment` (with `setup`), `image`, `config` — import each
other only along deployment → jobs → {image, results}. Command classes import no AWS SDK type; `run`
and setup reach AWS through a port each (`JobLaunch` over `RunAws`, `SetupSteps` over `SetupAws`),
while the other commands still build their clients inline from a session's factory (ADR 0009, T2) —
give one a port when its sequence needs a test. `SliceBoundaryTest` enforces the package rules and the
import rule, reading import lines only; a new package fails it until it is given a place. Strict slice isolation was measured and rejected — setup builds and teardown retires
the image, and `run` prints its own measurements.

**The command tree is `baas [--deployment X] [admin] <noun> <verb>`, and its exceptions are aliases,
never shapes.** Nouns: `jobs` (the execution), `results` (the measurements), `config`; under `admin`
(deployer credentials) `deployment` and `image`. A top-level alias exists only for a verb owned by
exactly one noun — `run` (`jobs run`) and `query` (`results query`) — so `list` and `show` can never
be aliased and `baas list` cannot mean two things. A noun alone prints its usage. A new command
finds its noun; nothing else joins the top level. Decided in `jobs-command` (its `exploration.md`
records every alternative).

One trigger path: `baas run`. CI does not have a second one — `e2e-cloud-test.yml` is two
`ubuntu-latest` CI jobs that federate into `OperatorRole` and call `baas run`, so a regression in the
CLI cannot pass CI. The old GitHub Actions benchmark path was deleted, not fixed: it was broken six
ways at once — a deleted SSM mongo parameter, a revoked IAM grant, no 27017 egress, an expired PAT,
an unresolvable project, and an assertion (`tags.source == gha-e2e-test`) no job could satisfy — and
`private-runner-network` moves runners onto a subnet with no route to `github.com`, which a
self-hosted Actions runner agent must reach. The consumer contract is *install the CLI*, not *call
our reusable workflow*. Any reference you find to `benchmark-runner.yml`, `exec-single-benchmark.yml`,
`start-ec2-runner.yml`, `stop-ec2-runner.yml`, the `act` harness under `.github/test/`,
`MONGO_CONNECTION_STRING` in CI, `machulav/ec2-github-runner`, `GHA_EC2_PAT`, `RUNNER_ROLE_NAME`,
`SUBNET_ID`, `SECURITY_GROUP_ID`, `RESOURCE_NAME_PREFIX`, `WORKFLOW_ROLE_ARN` or `CORE_STACK_NAME` is
stale.

Sequence diagrams for the main CLI commands and the C4 views: [`docs/diagrams/`](docs/diagrams/) (Mermaid sources,
no checked-in SVGs — update the `.mmd` when a command changes; `mmdc` rejects a `;` in sequence-diagram text, it
ends the statement). **Every `.mmd` edit is rendered and looked at before it is committed:**
`mmdc -i docs/diagrams/<file>.mmd -o <scratch>/<file>.png`, then open the PNG and check the change reads as
intended — a clean exit only proves it parsed, not that the arrow landed in the right branch. The render stays out
of the repository. Mermaid CLI is installed globally (`npm install -g @mermaid-js/mermaid-cli`); nothing in CI
renders these files, so a broken diagram is otherwise found by its next reader. State machines for the deployment, an operator machine and a job: `docs/diagrams/baas-states-*.mmd`. Design rationale and open risks:
[`docs/adr/0001-self-contained-baas-cli.md`](docs/adr/0001-self-contained-baas-cli.md); later decisions, and the hardenings declined
with their reasons, in `docs/adr/0002`–`0010`. Per-change
records: `openspec/changes/*/design.md`, and `openspec/changes/archive/*/design.md` once archived.

**OpenSpec changes use the stock `spec-driven` schema** through `/opsx:*`. For those,
`/opsx:explore` replaces `superpowers:brainstorming` and `tasks.md` replaces `writing-plans`
(details in `openspec.md`).

**Open review findings live in one file, [`docs/review/open-findings.md`](docs/review/open-findings.md)**
— every finding neither fixed nor accepted, with the designs of the changes queued to close them. IDs
keep the prefix of the review that filed them (`S`/`A`/`D`/`C`, `P`, `U`, `N`, `X`/`G`/`K`/`T`/`B`),
so citations still resolve. Read it before proposing security or architecture work. Closing a finding deletes its
entry; a decision worth keeping becomes an ADR (`docs/adr/0005` collects the declined hardenings).
Items in *Accepted risks* below are excluded on purpose.

## Rules by area

The invariants of each part of the tree live in `.claude/rules/`, each loaded automatically once a
file it names is read. Before proposing a change to an area, read its file even if no matching file
has been opened yet — most of these rules exist because breaking them costs money or silently loses
data.

| File | Covers |
|---|---|
| `user-data.md` | `jobs`: the user-data script, the three termination layers, launch, runner-JAR pinning, `jobs connect` |
| `runner-image.md` | The runner image: components, build and adoption, the pointer, the extension guard, Image Builder template quirks |
| `infra-iam.md` | `infra/`, `deployment`: stacks, resource naming, the security group, bucket and SSM grants, the deployer policy and its preflight |
| `deployment-config.md` | Setup walkthrough, operator session, deployment files and selection, region, CLI version floor, teardown, export and import |
| `results-store.md` | `baas-model`, `results`: the S3 job layout, results-table keys, tags, job items, `results diff` |
| `cli-console.md` | The stdout/stderr split, terminal detection, log style, `-v`, `Locale.ROOT` |
| `ci-release.md` | `e2e-cloud-test.yml`, the installer and its CI, release, GitHub Actions variables |
| `openspec.md` | The OpenSpec workflow and delta-spec pitfalls |
| `benchmark-runner.md` | The runner module, argument pass-through after `--`, adding a benchmark type, the async-profiler test gate |

## What isn't there, and what fails silently

- **A session leaves no trace in a job's results.** No transcript is stored, and measurements of a
  job someone connected to carry no tag or attribute that differs from an untouched job's — decided,
  not overlooked (ADR 0010). A result measured while a shell was open is indistinguishable by query;
  the operator who connected knows the job id. Results from before and after every runner registered
  its agent share one `imageVersion`, too: the change date is the only boundary.
- **`baas run` has no local mode.** It always provisions EC2. The no-cost way to exercise the
  runner is its integration tests, which start their own LocalStack through Testcontainers; there
  is no `docker-compose` setup and no local runner script.
- **A reactor build cannot launch a job.** The CLI pins the runner JAR to its own released version,
  and `0.0.0-semantically-released` names no release — so `baas run` fails immediately, before
  resolving the project or the results table, unless `--runner-jar` is passed. Same no-fallback
  stance as the runner AMI. Installed releases are the ordinary case; a reactor build is the
  developer's explicit special case — the `baas-dev` alias and CI's `e2e-cloud-test.yml` — which
  is also why the version check skips it with a warning instead of comparing.
- **The runner refuses an unresolved project.** `getProject()` used to fall back to `"unknown"`,
  and CI had been writing `RESULT#unknown` because of it — a partition nobody queries. It now
  throws, before the benchmark runs.
- **There is no MongoDB and no local storage mode, and absent store configuration is a hard
  failure.** `baas run` resolves the table before the runner-image lookup and before any upload, and
  `benchmark-runner` requires both `--results-table` and `--s3-bucket`, so a local run names a
  LocalStack table and bucket. `--no-database`, the runner's Mongo store, `--mongo-connection-string`/`-m`,
  `MONGO_CONNECTION_STRING` and the write-to-disk mode are gone (`retire-mongodb`): every job records
  its status in the table, so a job without one could not be seen at all. The old behaviour — unset
  URI selects a no-op store, the job reports success, numbers vanish — is what any reintroduced
  fallback brings back. `baas-model`'s `ban-mongodb` enforcer rule stays as the guard.
- **`s3-hook-lambda` is gone** — module, CloudFormation resources, `<prefix>-lambda` bucket, and the
  S3-object-create trigger path. Any reference you find is stale.
- **The zsh orchestration helpers are gone** (`run-remote-benchmark.zsh`, `wait-for-gha-run.sh`,
  `benchmark_overview.sh`, `logger.sh`, `git_helpers.sh`, `aws_helpers.sh`). Use `baas run` /
  `baas results query`, and don't reintroduce shell helpers for orchestration. The
  `.github/test/testing-scripts/` copies went with the `act` harness, so there is no live copy
  left anywhere.

## Gotchas that will waste your time

- **`--` is required before benchmark parameters**, and `baas` options must come before it.
  Without it picocli parses JMH flags as `baas` options: `Unknown options: '-f', '-wi', '-i'`.
  `baas run --instance-type c6i.4xlarge jmh -- MyBenchmark -f 1 -wi 1 -i 3`
  Everything after `--` reaches the benchmark JAR verbatim (`benchmark-runner.md`).
- **JUnit 6** (`6.0.2`) and **Testcontainers 2.x** — both differ from the versions you'd assume.
  Every integration test runs on LocalStack (S3 and DynamoDB); the store contract suite's one real
  binding is the DynamoDB adapter.
- **LocalStack is pinned to `4.14.0`, the newest release confirmed (2026-10-05) to start without an account.** `2026.09.0`
  (and so `latest`) exits 55 without `LOCALSTACK_AUTH_TOKEN`, and this repository holds no secrets. The pin
  replaced `0.12.16`, which took 25–65 s to start against Testcontainers' 60 s wait and stored the SDK's
  `aws-chunked` upload framing as object bytes — `StorageServiceIT` had asserted that corrupted size.
- **`pom.xml` version stays `0.0.0-semantically-released`.** Never bump it by hand; `release.yml`
  sets the real version at release time. Shaded artifacts are named `${project.artifactId}` with no
  version suffix, so `target/baas-cli.jar` and `target/benchmark-runner.jar` are stable paths.


## Accepted risks

Decisions already made and deliberately not revisited — don't file these as bugs.

| Area | Position |
|---|---|
| Deployer privilege | `iam:CreateRole` also writes the trust policy, so a deployer can recreate `<prefix>-role-operator` trusting itself with `Action:*` and assume it — the deployer policy is effectively account admin. Accepted: internal tool, development environments, deployer is a trusted developer. A permissions boundary was built and removed as not worth the bootstrap cost. Don't reintroduce one without a multi-principal account to justify it. |
| Deployer Image Builder wildcard | The deployer policy's `ImageBuilder` statement names `…:*/<prefix>-*`, so the deployer of `baas-123456789012` may also update, delete or start the Image Builder resources of a deployment named `baas-123456789012-dev` — never its stack, bucket, table, roles, pointer or AMIs, which are exact (finding X4). Accepted (2026-10-22): it needs one deployment's name to extend another's, the deployer is already effectively account admin (above), and the CLI only composes exact names through `DeploymentNames`. The exact form — seven `*/<prefix>-<suffix>` patterns, ~550 characters, inside the policy budget — was declined as mechanism for a case no deployment has. The `SsmSessionDocument` statement's `document/<prefix>-*` is the same shape and accepted for the same reason. |
| CI can open a shell on a runner | GitHub Actions federates into the same `OperatorRole`, so the session grants reach CI too, although CI never calls `connect`. Accepted (2026-10-22): CI already terminates any runner and writes any job item, and a shell on a box that terminates itself within `timeout + margin` adds nothing it could not already break. The flip side is wanted: a hung CI job is reached from a laptop with `baas jobs connect <jobId>`, by id alone. |
| Operators can end each other's sessions | `TerminateSession`/`ResumeSession` are scoped to sessions whose id starts with the caller's `aws:userid`, but every operator session is named `baas-cli` (finding X6), so "own sessions" means every operator's of that deployment. Accepted (2026-10-22): any operator can already terminate any runner, which ends its sessions anyway; seen live, when one operator session ended another's hung one. Closing X6 narrows it with no change to the policy. |
| Relaxed kernel isolation on the runner | The image sets `perf_event_paranoid=1` and `kptr_restrict=0` so async-profiler can walk kernel stacks *and resolve kernel symbols* — without them the profiler is crippled. This weakens kernel isolation on a box that runs arbitrary benchmark JARs. Accepted: single-tenant, throwaway, terminated within `timeout + margin` (300 s by default). Recorded because these were previously AL2023 defaults that nobody chose; now they are a decision. |
| Runners can terminate each other | `RunnerRole`'s `ec2:TerminateInstances` is scoped by the shared `baas-role=benchmark-runner` tag, not to the calling instance, so code on one runner can kill every concurrent job. Accepted (2026-10-02): only an operator can supply a benchmark JAR, and `OperatorRole` already terminates any runner; the runner role's remaining bucket and table writes are the larger exposure — it can overwrite another job's objects under `jobs/*` (finding S7), though no longer delete them or touch `releases/`. The self-only scoping (`ec2:SourceInstanceARN`) was declined because a subtly wrong condition would silently disable self-termination *and* the watchdog, found only when a paid job hangs. Revisit if more than one team ever shares a deployment. |
| `OperatorRole` trusts the account root | The trust policy names `:root` with no condition, so any principal in the account whose identity policy allows `sts:AssumeRole` on `*` can become an operator. Accepted (2026-10-02): that is AWS's standard same-account delegation, and in practice such principals (admins, `PowerUserAccess`) already hold the EC2, S3 and DynamoDB rights the role grants. A `aws:PrincipalArn` allow-list was declined — every new teammate would need a deployer re-run, SSO role ARNs churn on re-provisioning (the A10 failure), and a wrong pattern locks every operator out. `sts:ExternalId` was declined as a same-account no-op. |
| Re-measuring a historical environment | There is no command for it. A diff showing `jdk.release: 25.0.4 → 25.0.3` tells you the environment moved, but isolating whether it caused a score change means `git checkout <sha> -- infra/runner-image.yaml && baas admin image build`, which clobbers the current image. Accepted: the question actually asked is "did it change", which `environment.json` answers directly. Git is the archive; nothing in S3 duplicates it. |
| Runner AMI snapshot cost | ~$0.20/month for the single retained 30 GB snapshot. The project previously had **zero** standing cost, so this is a real change in kind, not just degree. Bounded by the one-image-at-a-time rule: a build deregisters its predecessor and deletes that snapshot, so the figure does not grow with the number of builds. Teardown retires the image, so a torn-down deployment costs nothing. |
| Operators can overwrite the pinned runner JAR | `OperatorRole` holds `s3:PutObject` on the whole bucket, so any operator — CI on any ref included — can replace `releases/<version>/benchmark-runner.jar` after it was seeded, and nothing re-checks it: every later job of that version runs whatever is there. Accepted (2026-10-21, review X1): only operators can supply benchmark code at all, which already runs on the same instance with the same rights. A per-launch check against a runner digest built into the CLI was implemented and reverted as not worth its build step. Revisit with the runner-terminates-runners risk above, if more than one team ever shares a deployment. |
| ~~Runner JAR integrity~~ | **Closed, not dropped.** The risk was accepted while verification was impossible — the download happened on a throwaway instance mid-boot, with nothing to verify against. Moving the fetch to the laptop is what changed the trade-off: the CLI now verifies the asset against a `.sha256` published by the same release build, and a mismatch uploads nothing and launches nothing. |
| `baas run` project layout | Assumes a pre-built JAR handed in by `--benchmark-jar`, which is required — `baas run` does not build. Anything that produces a JAR before invoking it is fine; the CLI has no opinion on how. |
| Distribution | Installable via `scripts/install.sh`. Homebrew tap, jpackage, native image and Docker image were specified but never built — backlog, not decisions. |
