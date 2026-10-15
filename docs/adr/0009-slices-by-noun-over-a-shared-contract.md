# ADR 0009 — Slices by noun over a shared kernel, with baas-model as the one contract

- **Status:** Accepted — implemented on `slice-architecture`, pending review
- **Date accepted:** 2026-10-15
- **Supersedes:** nothing; the package layout was never decided before, it accreted
- **Closes findings:** B1–B5, B8, B9, B11 (the runner's package cycles), B14, K1, K3, K8, K13, T1, G16; parts of
  B6, B7, K9, T2, T4, T5 (`docs/review/2026-10-13-codebase-review/`)

## Context

`baas-cli` was organised by technology — `commands`, `commands.admin`, `infra`, `jobs`, `results`,
`config` — so one feature was spread over four packages, and `infra` held AWS adapters, renderers,
image rules and session policy together. The two largest commands, `RunCommand` and the old
`SetupCommand`, held their use cases inline, which put the success path of `baas run` beyond any
JVM test: every adapter was constructed inside `call()`. Three package cycles ran through the CLI
(root ↔ commands ↔ admin through a static service locator, and jobs ↔ results) and one through the
runner. The CLI, the runner and the generated user-data shared part of their contract through
`baas-model` and spelled the rest — artifact names, job-item attributes, the runner's option names,
the benchmark types — each in its own way, so a rename compiled and failed a paid job.

The review compared nine CLI architecture styles (`cli-architecture-survey.md`) and recommended
vertical slices by noun, each with a functional core and an imperative shell, over a contract module,
with manual wiring. A feasibility spike moved every class into that layout on a throwaway branch: the
moves compiled and every test passed after a handful of visibility fixes, but the proposed rule that
no slice may import another did not fit the domain (below).

## Decision

1. **`baas-model` is the one contract the three runtimes read.** It gains the artifact names
   (`JobLayout`), the benchmark types (`BenchmarkType`), the runner's option names (`RunnerArgs`,
   constants the runner's picocli annotations use) and the job item's guard (`JobItemConditions`).
   It splits into `model`, which imports no AWS SDK, and `model.dynamodb`, the two item mappers and
   the guard. User-data takes every shared name from the model through tokens substituted at class
   load, so the script's text did not change; contract tests bind the halves.
2. **`baas-cli` is the composition root, a kernel and five slices.** `baas` (BaasApp, AdminCommand)
   registers commands and is imported by nothing. The kernel — `cli`, `console`, `session`, `aws` —
   is what every slice may use and imports no slice. The slices are the nouns of the command tree:
   `jobs`, `results`, `deployment` (with `setup`), `image`, `config`.
3. **Slices import each other only along deployment → jobs → {image, results}.** Setup builds the
   runner image and teardown retires it and names in-flight runners; `run` looks up the image and
   prints its own measurements. Hiding those behind ports would add a mechanism with no second
   implementation. Shared AWS readers that sat in one slice moved to the kernel's `aws` package.
4. **Commands parse and print; use cases talk to AWS through one narrow port each.** `run` is
   `JobLaunch` over `RunAws` (`AwsRunAws` in production, `FakeRunAws` in tests), following setup's
   `SetupSteps` over `SetupAws`. A use case takes the deployment as immutable `DeploymentNames`,
   not the mutable `BaasConfig`. The command keeps what outlives the use case: the JSON summary and
   the shutdown hook, which it arms from the use case's `Listener.reserved` callback.
5. **Wiring stays manual.** No container: `BaasApp` implements a small `cli.Root`, commands read it
   through `cli.Invocation`, and `OperatorSession`/`DeployerSession` build the clients.
6. **The boundaries are tests, not conventions.** `SliceBoundaryTest` (CLI), `ModelBoundaryTest`
   (model) and `RunnerLayeringTest` (runner) fail on a violation; a new top-level package fails until
   it is given a place.

## Consequences

- `baas run`'s whole flow, success path included, runs in JVM tests; the paid E2E job now proves
  only the real AWS behind the port.
- A renamed artifact, attribute, option or type fails a build instead of a job.
- Teardown and the image commands still construct their adapters inline: they have no port yet, and
  their sequencing stays tested only through their services (T2). The next port follows the
  `RunAws`/`SetupAws` shape when one of them needs a sequence test.
- `BaasConfig` remains the persisted form and is still what most commands hold; only the use cases
  extracted here take the immutable `DeploymentNames` (B7, partly).
- Three things a user can see changed, all deliberate: JSON payloads escape every string field
  (identical for ordinary values); diagnostics logged by the moved `run` flow carry `JobLaunch` as
  their logger name instead of `RunCommand` (the text is unchanged); and artifact keys are joined by
  `JobLayout.artifactKey`, which tolerates a trailing slash on a result path, so `jobs diff` now finds
  a manifest it used to report missing for such a path.

## Alternatives considered

See `docs/review/2026-10-13-codebase-review/cli-architecture-survey.md`. A separate `baas-core`
Maven module would let the compiler enforce what `SliceBoundaryTest` enforces; it is worth its
shading and test-resource plumbing once a second front end exists. Hexagonal ports for every AWS
call, a DI container, plugins and a mediator were rejected as mechanism without a consumer.
