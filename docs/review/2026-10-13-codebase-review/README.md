# Codebase review, 2026-10-13

Five reviews and one survey of CLI architecture styles, written against `next-release` at `dabbc55` and **re-verified on 2026-10-14 against `7d9660a`**, after
`interactive-setup` landed (the rewritten `next-release` `c66dac4` has a byte-identical code tree). Scope:
`baas-cli`, `benchmark-runner`, `baas-model`, `infra/`, the generated user-data and `scripts/install.sh`.
The fixtures were left out. The reviews were read-only, and nothing in the code was changed.

**Status: untriaged, queued** in [`openspec/changes/QUEUE.md`](../../../openspec/changes/QUEUE.md) right after `interactive-setup`, which has now shipped. The review lives only on the `codebase-review` branch. None of these findings is in [`../open-findings.md`](../open-findings.md) yet. Each one
moves there once it is accepted (as a Must, Should or Could item) or gets dropped. Each report avoided
re-filing anything already in that file, in *Accepted risks* or in ADR 0005, and cites the existing ID
wherever a finding builds on one.

| Report | Prefix | Findings |
|---|---|---|
| [security.md](security.md) | X | 8: 0 Must, 2 Should, 6 Could. None fixed by interactive-setup; X4 and X5 changed |
| [api-gaps.md](api-gaps.md) | G | 21: 1 Must, 8 Should, 12 Could. None fixed; G7, G12, G14 and G20 changed or widened to setup |
| [code-complexity.md](code-complexity.md) | K | 13 open: 0 Must, 6 Should, 7 Could. K10 resolved by interactive-setup; K13 and K14 new in the setup package; K2, K8 and K9 changed |
| [test-complexity.md](test-complexity.md) | T | 13: 0 Must, 5 Should, 8 Could. T13 new; T2, T4 and T5 changed |
| [architecture.md](architecture.md) | B | 14: 0 Must, 7 Should, 7 Could. None fixed; B6, B7, B9 and B12 changed |
| [cli-architecture-survey.md](cli-architecture-survey.md) | — | 9 styles, a comparison matrix, a recommendation |

The grades use the Necessity/Effort scale defined in `open-findings.md`. Each report records its own
re-verification: a status per finding, and *Resolved by interactive-setup* and *Withdrawn* sections.

**What interactive-setup changed.** Setup now has the shape the target architecture recommends. Its
steps run against a narrow `SetupAws` port with a `FakeSetupAws` for tests, and policy values travel on
one `SetupContext`. That fixes K10 and half of T2, B6 and B7. Every review now cites setup as the
precedent for extracting `JobLaunch` from `RunCommand` (B1, K1, T1).

Three parts of it should not be copied:
- `SetupAws` returns the SDK's `StackStatus` type;
- `AwsSetupAws` imports `commands.admin.RunnerImageBuild`, which creates a new
  `admin` ↔ `admin.setup` package cycle (K13, part of B5);
- `NamesStep.rolledBack` is never called (K14).

Teardown, image build and image show still build their AWS client factory inline, and no test covers
them.

## 1. Checked by hand

Subagents wrote the reports. I re-checked these findings against the code because they are either the
most serious or the most surprising:

| ID | Claim | Verdict |
|---|---|---|
| X1 | Any operator (and so any CI ref) can overwrite `releases/<v>/benchmark-runner.jar`, and nothing re-verifies it once the key exists | **Confirmed.** The operator grants `s3:PutObject` on `${S3MainBucket.Arn}/*` (`cf-template-core.yaml:705-716`). `RunnerJarResolver.resolve` returns early when the key exists (`:54`). `If-None-Match` is a header the writer chooses, and IAM does not enforce it. The fix the report proposes relies on an `s3:if-none-match` condition key, which the agent named from memory: **verify it before relying on it.** |
| X3 | The runner can create job items | **Confirmed.** It holds `UpdateItem` on `LeadingKeys = JOB` (`cf-template-core.yaml:390-400`), and `UpdateItem` creates a missing item unless the caller sends a condition. The template comment "it can never create a job item" is wrong. |
| X4 | The Image Builder resource `*/${PREFIX}-*` also matches `<prefix>-<anything>` deployments | **Confirmed** (`infra/deployer-policy.json:90`). This contradicts "a deployer policy is prefix-exact" in CLAUDE.md. |
| G1 | Passing `--tag imageVersion=…` / `--tag project=…` / `--project` after `--` gets round the CLI's reserved-key guard | **Confirmed.** `BENCHMARK_PARAMS_ARRAY` is the last argument to the runner (`UserDataScriptBuilder.java:275`), after the observed tags, with no `--` separator. picocli on the runner parses those as its own options, and the last value wins. |
| G2 | A JCStress job with failing tests ends `completed`, exit 0, and no command shows the failure counts | **Confirmed in code:** `failedTests` is stored (`MeasurementItemMapper.java:184`), a non-zero JCStress exit is only logged as a warning (`JCStressSubcommandService.java:82-83`), and no CLI reader shows the counts. **Your call** whether this is a defect or intended: `jcstress-e2e` deliberately does not assert pass/fail. |
| K6 | The JMH services have drifted apart | **Confirmed, and it is a correctness bug, not only duplication.** `JmhWithAsyncProfilerSubcommandService.uploadProfilerArtifacts` calls `Files.list(localDir)` with no `isDirectory` guard (`:103`), while `JmhWithProfilerSubcommandService` has one (`:101`). A benchmark that leaves no async-profiler directory fails the job with `NoSuchFileException` *after* the measurement, so the measurement is lost. |
| T2 / B6 | Admin commands build `AwsClientFactory` inline, which leaves no seam for tests | **Half fixed at `7d9660a`.** Setup builds it only inside its adapter (`setup/AwsSetupAws.java:40`, behind `SetupAws`), and the setup steps are tested through `FakeSetupAws`. Teardown (`TeardownCommand:82`), image build (`ImageBuildSubcommand:104`) and image show (`ImageShowSubcommand:54`) still build it inline. |
| T13 | A setup test writes `baas-cli/policy.json`, and it was committed | **Confirmed.** `SetupCommandTest.withoutTheOptionATerminalIsAsked` takes the prompt's default answer, `policy.json`, so the file lands in the surefire working directory. Commit `6eebcb8` (now `daeaea9` on the rewritten history) checked it in. |

Everything in this table was re-checked by hand at `7d9660a`, and all of it except T2 still holds as
first written. Findings not in this table are as the agents reported them and re-verified them. Each one
cites file:line, but I did not re-check those myself.

## 2. What to look at first

Deduplicated across the reviews and ordered by necessity, then by how much each fix unlocks.

| # | Finding(s) | Necessity | Effort | Why now |
|---|---|---|---|---|
| 1 | **G2**: the JCStress verdict is invisible and a failing job shows green | Must (if confirmed) | S | A false green someone will act on |
| 2 | **K6 (bug part)**: async-profiler upload with no directory guard | Should (borders on Must: loses a finished measurement) | XS | One `if`, plus a test |
| 2a | **T13**: a test writes `baas-cli/policy.json`, and it was committed | Should | XS | Every test run recreates the file. Delete it and point the test at a `@TempDir` |
| 3 | **X1**: the pinned runner JAR is mutable by operators | Should | S | The whole runner-pinning argument rests on this object being immutable |
| 4 | **G1 + B4**: the runner's argv contract is untyped, and user params override reserved options | Should | S | Reopens U24 by another route, and is the same root cause as B4 |
| 5 | **X2**: async-profiler tarball baked into the image with no checksum | Should | S | Runs as root on every runner |
| 6 | **B2 + B3 + K8 + B8**: the three runtimes spell their shared contract separately (job-item attributes, artifact leaf names, resource names) | Should | S | Drift there shows up only in the paid E2E, or as an empty download |
| 7 | **G6**: `--format json/csv` silently cut to 20 rows | Should | XS | Silent truncation in a machine format |
| 8 | **B1 = K1 = T1**: `baas run`'s use case lives in `RunCommand.execute` (200 lines) | Should | M | The single highest-value refactor: makes the success path testable |
| 9 | **B6 = K9 = T2 = T5**: no composition seam. Deployer wiring is still in 4 places, one of them now inside setup's adapter, and tests use 5 seam styles | Should | M | Prerequisite for 8 and for testing teardown and image build. Setup's `SetupAws` and its `SetupRun` test harness are the template |
| 10 | **K2**: 52 hand-rolled `log + return 1/2` exits (61 before setup returned `Outcome.Stop` values) | Should | S | The cheapest enabler for extracting long `call()` methods |
| 11 | **B10 = K3 (+ G17, X7)**: hand-built JSON with three disagreeing escapers | Should | S | `RunCommand`'s escaper can emit invalid JSON |
| 12 | **G3, G4, G5, G8**: no post-hoc exclusion of a measurement, no score comparison, no `jobs wait`, the job doesn't record what it ran | Should | M each | Real gaps for CI. G5 is already a non-goal of `detached-run`, "additive later" |

Everything else is Could. The reports have the full lists.

### Overlap map

Several reviews describe the same underlying problem from different sides. Treat each row as one
unit of work:

| Theme | IDs |
|---|---|
| Orchestration inside picocli commands | B1, K1, T1 |
| No injection seam for AWS and session wiring | B6, B7, K9, T2, T5 |
| Contract between CLI, runner and bash spelled separately | B2, B3, B4, B8, B14, K8, G1, G16, G19 |
| Payload encoding | B10, K3, K4, G17, X7 |
| Runner layering | B11, K6, K7, K12, T3 |
| Setup package tidy-up | K13, K14, T4 (`FakeSetupAws` has no contract test), B8 (names rebuilt in the setup steps) |
| Package cycles and catch-all packages | B5, B9, K13 |
| OIDC trust | X5: setup now creates the provider *and* takes the GitHub org from an unvalidated prompt as well as the flag, so one run with `*` trusts any GitHub repo |
| Supply-chain integrity | X1, X2, X8 (X8 challenges CLAUDE.md's "Runner JAR integrity: Closed") |
| Docs that no longer match the code | B12 (diagrams), G19, the X3 template comment, X4 vs "prefix-exact" |

## 3. Architecture: where BaaS is, and where it could go

### Current style

A mix of **flat command-centric** and **loose layering**, with **islands of ports and adapters**.

- **What works.** Two cores are port-based and well tested. One is the job lifecycle: `JobSession`,
  `JobStop` and `JobTermination` over `JobRecorder` and `JobSession.Instances`. The other is the runner's
  services over `ResultsStore` and `StorageService`. `baas-model` is the strongest boundary, and the build
  enforces it. `console` and `config` are leaf packages.
- **What doesn't.**
  - **Mixed layers in commands.** `RunCommand` and `SetupCommand` each occupy all four layers:
    presentation, use case, domain rule and adapter.
  - **Catch-all `infra`.** It holds AWS adapters, pure renderers, image-domain rules and session policy
    together.
  - **Package cycles.** There are three: CLI root ↔ `commands` ↔ `admin` through the static
    `BaasApp.configService(spec)` locator, CLI `jobs` ↔ `results`, and a six-package cycle in the runner.
  - **A third runtime outside the model.** The bash user-data reads part of its contract from a CLI
    adapter class (`DynamoDbJobRecorder.NOT_TERMINAL`) rather than from `baas-model`.

The architecture report has the package diagram, the dependency matrices and the class-by-class
layer map.

### Styles compared

From the survey. ++ is strong and −− is poor. In the last column, ++ means cheapest to migrate to from
today's layout.

| Style | Boundary clarity | Testability | Little ceremony | Fits picocli | Fits a contract shared by 3 runtimes | Migration cost |
|---|---|---|---|---|---|---|
| Flat command-centric (where `RunCommand`/`SetupCommand` are today) | −− | −− | ++ | + | − | n/a |
| Layered (presentation / service / data access) | + | 0 | + | + | + | + |
| Hexagonal / Clean / Onion | ++ | ++ | −− | + | + | −− |
| Command + use-case handler, no mediator (gh's `xRun(opts)`) | + | + | + | ++ | 0 | + |
| Library-first core + thin CLI (gh, kubectl, terraform, JReleaser) | ++ | + | 0 / + | ++ | ++ | 0 (packages) / − (module) |
| Plugin / extension | + | 0 | −− | 0 | 0 | −− |
| Vertical slices by noun | + | + | ++ | ++ | + | + |
| Functional core, imperative shell | + | ++ | ++ | + | + | ++ |
| DI container (Micronaut / Quarkus / Spring) | 0 | + | − | + | − | −− |
| Manual wiring via a factory (gh `cmdutil.Factory`) | + | + | ++ | ++ | + | ++ (already in place) |

These are not all alternatives to one another. Slices, handlers and functional core combine, and the
wiring row is an independent choice.

### Recommended target

**Vertical slices by noun, each slice a functional core with an imperative shell. The shared contract
module stays, and wiring stays manual.**

```
baas-model         contract only, for all three runtimes
  model            pure: JobId, JobLayout (+ artifact leaf names, B3), JobStatus, TagKeys,
                   BenchmarkType + RunnerArgs flag names (B4)
  model.dynamodb   ResultKeys, *ItemMapper, TableSchema names, NOT_TERMINAL guard (B2, B14)
benchmark-runner   unchanged modules; fix its cycle (B11)
baas-cli
  baas             BaasApp (composition root only), BaasVersion
  -- shared kernel: every slice may import these, they import no slice --
  baas.cli         LoggingMixin, Invocation context, payload writers (Json/Csv), refusal exception (K2)
  baas.console     unchanged
  baas.session     Deployment (immutable, B7), OperatorSession, DeployerSession (new), AwsClientFactory,
                   ConfigService, DeploymentFile, DeploymentNames, BucketProbe
  -- slices: one per noun, no slice imports another --
  baas.jobs        run, list, show, diff, download, terminate
                   commands (parse + print) → handlers (JobLaunch, …) → JobSession/JobStop
                   ports: JobRecorder, Instances, ArtifactStore; adapters: DynamoDbJobRecorder,
                   Ec2Instances, UserDataScriptBuilder; EnvironmentManifest moves here
  baas.results     query: command → handler → ResultsQueryService; pure Filters/Grouping/Row
  baas.deployment  setup, teardown: commands → handlers; today's setup steps, SetupContext and the
                   SetupAws port; CloudFormationService, DeployerPreflight, DeployerPolicyRenderer
  baas.image       build, show: commands → handlers; RunnerImageBuild, ImageBuilderService, Ssm,
                   RunnerImage*. Setup's image step reaches it through SetupAws.buildImage, which
                   BaasApp wires, so deployment never imports image
  baas.config      set, show, list, sync: commands only, over session's ConfigService
```

The deployment context and the config file live in the shared `session` kernel rather than in a
slice, because every slice needs them. If they lived in `deployment` or `config`, `jobs` would have to
import another slice. For the same reason, tag-pair parsing moves to `baas-model`, and `jobs download`
calls `JobRecorder` itself. That removes today's `jobs` ↔ `results` cycle.

### Diagrams

Mermaid sources in [`diagrams/`](diagrams/). Render with `mmdc -i <file>.mmd -o <out>.png -s 2 -b white`.

| | Today | Target |
|---|---|---|
| Three runtimes and their shared contract | [current-runtimes.mmd](diagrams/current-runtimes.mmd) | [target-runtimes.mmd](diagrams/target-runtimes.mmd) |
| `baas-cli` packages and allowed imports | [current-packages.mmd](diagrams/current-packages.mmd): real import counts, cycles in red, red = mixes all four layers | [target-packages.mmd](diagrams/target-packages.mmd) |
| Inside one slice (`jobs run`) | the [layer map](architecture.md#layer-map): `RunCommand` is P+A+D+I | [target-jobs-slice.mmd](diagrams/target-jobs-slice.mmd) |

Rules, enforced by one plain JUnit or ArchUnit test:
- picocli is imported only by command classes;
- pure decision classes import nothing from `software.amazon`;
- no slice imports another slice's internals;
- the `model` package imports no SDK.

**Why this one?** It fixes the two real defects, which are orchestration inside picocli classes and the
catch-all `infra`. The fix is package moves and method extraction. There is no framework, and no port
per AWS call. Narrow ports go only where a sequence across services has an ordering rule worth
testing, in the way `JobRecorder` came about. The work can be done one noun at a time, and the
command tree in CLAUDE.md already gives the list of slices.

**Runner-up 1: the same slices, with their logic in a separate `baas-core` Maven module that has no
picocli.** The compiler then enforces the boundary instead of a test. It is worth doing once a second
front end exists, such as a Java GitHub Action or a build plugin. Until then it costs shading and
test-resource plumbing for nobody.

**Runner-up 2: hexagonal, but only for `jobs`, `image` and `deployment`.** This is where the
money-losing sequences live. It is the escalation path for a slice whose SDK-fake tests get painful,
not the default.

**Not recommended:**
- **Plugins:** there are no third-party command authors.
- **DI container:** construction you cannot see, which CLAUDE.md's "no invisible state" rule argues
  against, plus a framework dependency and slower startup.
- **Mediator:** a single-process CLI has nothing to mediate.
- **Full Clean Architecture rings:** too many modules for about 11k lines.

### Migration path

Each step can ship on its own and leaves the build green. Steps 0 and 1 are worth doing even if
the refactor never happens.

0. **Move the shared contract into `baas-model`** (B2, B3, B4, B14, G16, K8, B8). Add an in-process test that
   parses the argv `UserDataScriptBuilder` renders through the runner's `TestWrapper`. This also closes G1
   and removes the `infra`→`jobs` edge.
1. **Add the enablers:**
   - a refusal exception handled in `BaasApp.reportFailure` (K2);
   - `Json`/`Csv` payload writers (B10, K3) and picocli enum `--format` (K4).

   After this, long `call()` methods can be extracted mechanically.
2. **Add the composition seam:** `DeployerSession`; an immutable `Deployment` split from the
   `BaasConfig` DTO (B7); one per-invocation context passed by `BaasApp`; one shared command-test
   harness (B6, K9, T5).
3. **Extract `JobLaunch` from `RunCommand`** (B1), with one `JobRecorder` contract test that runs against
   both in-memory fakes and the DynamoDB adapter (T4). After this, `baas run`'s success path is covered by
   a JVM test.
4. **Move packages into slices, one noun per change.** Go from smallest to largest: `results`, `config`,
   `jobs`, `image`, `deployment`. Add the boundary test with the first move and tighten it with each
   one. This removes the CLI cycles (B5, B9).
5. **Clean up the runner** (B11, K6 dedup, K7, K12, T3), alongside or after `retire-mongodb` (C4).

**Before step 4, protect the tests.** These suites will go red on a pure move, because they find code
by class, method or field rather than by behaviour:
- the static-helper tests in `RunCommandTest`;
- the six anonymous-subclass `IFactory` harnesses;
- the field-writing `RunCommand*Test`s.

Rewrite them against the handler from step 3 first. These suites are safe to move as they are:
`baas-model`, the template and policy suites, `JobSessionTest`/`JobTerminationTest`,
`UserDataScriptBuilderTest` and `CommandTreeTest`.

**Keep in any refactor.** These nine seams already work:
- the job ports;
- `OperatorSession` with its `Aws` port;
- `baas-model` with its enforcer;
- the runner's store and storage ports;
- `UserDataScriptBuilder` as a pure function, with its bash-harness tests;
- `DeploymentNames` and the tests that pin the templates;
- `Console`;
- `BucketProbe` and `JobsShowSubcommand.Sources`;
- CLI and runner versions in lockstep.

## 4. Decisions needed

These are to be put one at a time, in this order:

1. Is G2 (JCStress failures shown green) a defect, or intended?
2. Should X1 be fixed at the IAM level (deny writes to `releases/*` for the operator), or should the CLI
   re-verify the checksum on every launch?
3. Should X4 be fixed in the policy, if it fits the size budget, or should CLAUDE.md's "prefix-exact"
   claim be corrected instead?
4. Do you accept the target architecture and migration path above? Should steps 0–1 go into the QUEUE as
   OpenSpec changes ahead of `interactive-setup`?
5. Which findings go into `open-findings.md`, and which are dropped?
