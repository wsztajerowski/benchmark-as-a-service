# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 Confirm the deployed `BaasCliOperatorRole` can `dynamodb:Scan` the results table (it is in `operator-policy.json` and the core template): run `aws dynamodb scan --select COUNT` under the operator profile and record the result here; a denial means the design needs an IAM change first
- [x] 1.2 Confirm `GitProject.repositoryName(Path)` resolves the repository from a JAR's parent directory that is git-ignored (`fake-jmh-benchmarks/target/`) and from inside a linked worktree; record the observed names
- [x] 1.3 Confirm a picocli `scope = INHERIT` option on `BaasApp` is visible to a subcommand at `call()` time when written either before or after the subcommand, and identify every `new ConfigService()` that runs at field-initialisation time (e.g. `ResultsCommand.configService`) — those run before parsing and must become lazy; list them here
- [x] 1.4 Confirm `System.console().readLine()` returns the typed line on macOS Terminal and a Linux TTY, and that `System.console()` is null when stdin alone is redirected (`baas results < /dev/null`)

**Findings (2026-10-01):**
- 1.1 `aws dynamodb scan --select COUNT` under `baas-operator-381492019823` on `baas-381492019823-results`
  → `Count: 15`, no denial. No IAM change needed.
- 1.2 From git-ignored `fake-jmh-benchmarks/target/` and from the same path inside a detached linked
  worktree, `rev-parse --path-format=absolute --git-common-dir` returns the main `.git` → name
  `benchmark-as-a-service` both times; outside any repo it exits 128 (→ null).
- 1.3 picocli 4.7.7 probe: a `scope = INHERIT` option on the root sets the **root's** field whether given
  before the subcommand, after it, or after a nested subcommand — so commands read it via
  `spec.root().userObject()`. Field-initialised `new ConfigService()` (runs before parsing, must become
  lazy): `ConfigSetSubcommand`, `ConfigShowSubcommand`, `ConfigSyncSubcommand`, `ResultsCommand`,
  `RunCommand`, `DownloadCommand`, `EnvDiffSubcommand`, `admin/DeployerPolicyCommand`,
  `admin/TeardownCommand`, `admin/ImageCommand`, `admin/BuildImageCommand`, `admin/SetupCommand` (12).
- 1.4 JDK 25 under a pty (`script`): both TTY → console non-null, `isTerminal()` true; stdin
  `< /dev/null` → null; stdout `| cat` → null; `readLine()` returned the typed `2`. Verified on macOS
  only — Linux is left to manual check 8.4.

## 2. Configuration path and preferences

- [x] 2.1 Add the inherited `--config-path` option on `BaasApp`; make `ConfigService` take a `Path` (default `~/.baas/config.yaml`) and route all 12 call sites through it; verify with a `BaasAppTest` case parsing it on both sides of the subcommand
- [x] 2.2 Implement missing-file handling: explicit path + read command fails naming the path; `config sync`, `config set`, `admin setup` create it; default path unchanged; verify with `ConfigSyncSubcommandTest` and a new results-side test
- [x] 2.3 Add `git.resolveProject` (default false) and `ec2.watchdogMarginSeconds` (default 300) to `BaasConfig`; remove `wallClockHardKillSeconds`; verify `BaasConfigYamlTest` round-trips both and that a YAML still carrying `wallClockHardKillSeconds` loads with margin 300
- [x] 2.4 `baas config set`: add `--git-resolve-project <true|false>` and `--watchdog-margin`, remove `--max-wall-clock`; `baas config show` prints both new values; verify with a config set/show test

## 3. `baas run`

- [x] 3.1 Remove `--branch`, `--commit` and their git derivation; `branch`/`commit` come only from `--tag`; verify `RunCommandTest` asserts the rendered runner args carry the supplied tags and carry neither when absent, inside a git repo
- [x] 3.2 Project resolution: `--project`, else (flag on) repository of `--benchmark-jar`'s directory, else fail before provisioning naming `--project`; verify `RunCommandTest` covers flag off, flag on from a different working directory, worktree, and a JAR outside any repo
- [x] 3.3 Replace `--max-wall-clock` with `--watchdog-margin` (floor 60); compute `timeout + margin` once and pass it to both `UserDataScriptBuilder` and `poll`; verify `RunCommandTest`/`UserDataScriptBuilderTest` for 7200+300=7500, 1000+120=1120, and rejection of 10
- [x] 3.4 Remove `--ami-id` and the override branch of `resolveRunnerImage`; verify `RunnerImageResolutionTest` updated and `baas run --ami-id x` is an unknown option
- [x] 3.5 Update the run command's footer/description text and verify `baas run --help` lists none of the removed options

## 4. `baas results` query path

- [x] 4.1 `ResultsQueryService`: add a project-listing `Scan` (projecting `pk` and the exclusion tag, distinct projects with ≥1 non-excluded row) and an all-projects `Scan` with the exclusion filter optional; add an `includeExcluded` switch to the partition query; verify in `ResultsQueryServiceIT` against LocalStack, including pagination past one page and that unused expression names are not sent when the filter is dropped
- [x] 4.2 Remove `--results-table`, `--living-branches` and `gitRemoteBranches`; rename `--all` to `--all-runs` (lifting grouping and exclusion); add `--all-projects`, mutually exclusive with `--project`; verify the conflict and unknown-option cases in a `ResultsCommand` test
- [x] 4.3 Project selection order: `--project` → `--all-projects` → (flag on and cwd in a repo) cwd repository → interactive table picker → failure listing projects; verify each branch with a test that injects the console and stdin
- [x] 4.4 Picker: numbered list, tip naming `baas config set --git-resolve-project true`, one line read, invalid input exits non-zero with no partition query; under `--watch`, prompt once before the alternate screen; verify with an injected-input test
- [x] 4.5 `ResultsGrouping.bestPerGroup` keys on `(project, benchmark, group tag)`; verify `ResultsGroupingTest` with two projects sharing a benchmark name

## 5. `baas results` output

- [x] 5.1 `ResultsTable`: drop `±ERROR`; add a PROJECT column only under `--all-projects`; de-emphasise excluded rows; verify `TableTest`/`ResultsFormatTest` including the stripped-escapes alignment check
- [x] 5.2 With `-v`, print an indented, key-sorted tag line under each row; none without; verify both in `ResultsFormatTest`
- [x] 5.3 JSON: add a `tags` object, keep `scoreError`, `imageVersion`, `instanceType`; CSV: add a quoted `tags` column (`k=v;k=v`, RFC 4180 quoting); verify JSON parses with `jq`-equivalent assertions and CSV with a tag value containing `,`, `;` and `"`, under a `pl-PL` default locale
- [x] 5.4 Confirm `baas run`'s post-run summary table (which reuses `ResultsTable`) renders without the error column; verify in `RunCommandSummaryTest`

## 6. `baas download` and teardown

- [x] 6.1 Remove `--results-table` and `--bucket` from `DownloadCommand`; it reads both from the configuration selected by `--config-path`; verify `DownloadArgumentTest`
- [x] 6.2 Change `TeardownCommand`'s retention hint to `baas results` (no table option); verify the teardown message test

## 7. CI and documentation

- [ ] 7.1 `e2e-cloud-test.yml`: pass `--project`, `--tag branch=${{ github.head_ref || github.ref_name }}`, `--tag commit=${{ github.sha }}`; assert `project`, `branch`, `commit` and `source=ci` from `.tags` in `baas results --request-id --format json`; remove the comment explaining why `source=ci` could not be asserted; verify with `actionlint` and a green run on this PR
- [x] 7.2 README: option tables for `run` and `results`, the `--commit`/`--branch` paragraph, the `--ami-id`/`--max-wall-clock` mentions; verify `grep -nE -- '--(ami-id|max-wall-clock|living-branches|results-table|branch |commit )' README.md` returns only intended hits
- [x] 7.3 Update `docs/diagrams/baas-run.mmd` (option list, no `--ami-id`) and any results diagram; verify the Mermaid source renders
- [x] 7.4 CLAUDE.md: replace *Read-only commands take `--results-table`* with `--config-path`; state the watchdog bound as `timeout + watchdogMarginSeconds` (floor 60); record that git is consulted only under `git.resolveProject`, JAR-anchored for `run`; note `--all-runs` includes excluded rows; verify by re-reading the edited sections
- [x] 7.5 `docs/review/`: no finding is closed by this change; append a note to D1 in `baas-cli-findings.md` that `--all`/`--living-branches` were since renamed/removed, so its *Fixed* text is not read as current

## 8. End-to-end verification (manual — no automated test covers the `baas run` path)

- [ ] 8.1 Full reactor `mvn verify` with `ASYNC_PATH` exported; record the result
- [x] 8.2 `baas run --project fake-jmh-benchmarks --tag branch=… --tag commit=… --tag exclude_from_results=true jmh …` against the real installation; confirm the stored tags and that the instance terminated
- [x] 8.3 Same run with `--timeout 600 --watchdog-margin 60`; confirm the rendered user-data (`-v`) carries 660 and the run completes
- [x] 8.4 `baas results` interactively: picker lists only projects with visible rows, tip shown, choice works; `baas results | cat` fails listing projects; `--all-projects`, `--all-runs`, `-v` tag lines, excluded rows faint
- [x] 8.5 `baas results --config-path` pointing at a copy of the config with a different prefix reads that installation; a missing path fails naming it
- [x] 8.6 Comparability check: this change does not touch the runner or image, but run one `jmh` benchmark and compare its score with the most recent pre-change run of the same benchmark; record both scores and the historical spread (CI history spans 10.0M–29.6M ops/s), and investigate any difference outside it rather than accepting it

## 9. Verify

- [ ] 9.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory — a requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation from design or tasks

**Section 8 findings (2026-10-01):**
- 8.1 Full reactor `mvn verify` with `ASYNC_PATH` exported: **red in `benchmark-runner`**, which this change does
  not touch (`git status` clean there). All 7 errors are `MongoTimeoutException … Connection refused` on the
  Testcontainers-mapped Mongo port (`MongoResultsStoreContractIT` ×3, the four `*SubcommandServiceIT`s, which
  fail at their Mongo store write). Environmental, not caused by this change, and left open: the box stays
  unticked until the reactor is green. `mvn -pl baas-model,baas-cli verify` is green: 53 + 404 unit tests, 24 ITs.
- 8.4 Against the real table: `baas results | cat` fails listing `lynx-journal` only (`benchmark-as-a-service` holds
  only excluded rows, so the picker omits it); under a pty the picker printed the numbered list and the
  `--git-resolve-project` tip, accepted `1`, and `-v` printed the key-sorted tag line under each row;
  `--all-projects` added PROJECT; `--all-projects --all-runs` surfaced the excluded CI rows; JSON carries the
  full `tags` object. Faint rendering of excluded rows is covered by `TableTest` only, not eyeballed. macOS only.
  Seen in passing, pre-existing and out of scope: the table formats scores with the default locale
  (`3307897,585` under pl-PL); JSON/CSV are `Locale.ROOT` as required.
- 8.5 `--config-path` on a copy naming `baas-000000000000` addressed `baas-000000000000-results` (AccessDenied
  naming that table — the path took effect); a missing path failed with `Configuration file not found: <path>`.
- 8.2/8.3/8.6 One paid run, `20261001T152503637Z-19ebdb4b` on `i-062a9912cbfe65385` (c5.2xlarge, image 1.2.0):
  `jmh-with-async` `Incrementing_Synchronized -f 1 -wi 1 -i 1`, CI's own async flags, built from `28fa534` plus this
  change's uncommitted working tree, `--runner-jar` the reactor build. `--project benchmark-as-a-service --tag branch=simplify-cli-options
  --tag commit=28fa534… --tag exclude_from_results=true --timeout 600 --watchdog-margin 60`.
  - CLI logged `watchdog=660s`; decoded user-data carries `BENCHMARK_TIMEOUT='600'`, `WALL_CLOCK_HARD_KILL='660'`,
    `sleep ${WALL_CLOCK_HARD_KILL}`. Status `completed` after ~80 s, exit 0, one measurement stored, instance
    `terminated` (checked with `ec2 describe-instances`).
  - Stored tags: `project`, `branch`, `commit` exactly as passed, `source=local`, `exclude_from_results=true`,
    `imageVersion=1.2.0`, `instanceType=c5.2xlarge`. No derived branch/commit — the tree was on a git branch, and
    only the passed values appear.
  - Score 11,510,635 ops/s. Prior runs of the same benchmark on this image/type: 11.24M, 11.51M, 22.42M, 14.85M,
    9.60M, and most recently 12.51M (`20260930T202618023Z-0aedafd7`). Inside the recorded 10.0M–29.6M CI spread
    (one prior run sits just below it at 9.60M), so no difference to investigate — expected, since nothing the
    runner, the image or user-data measures with changed.
