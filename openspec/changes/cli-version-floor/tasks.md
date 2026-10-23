# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 Confirm CloudFormation treats a changed value of a parameter no resource references as an update (not "No updates are to be performed"), and that `AllowedPattern` rejects a malformed value: on `baas-development`, a change set from the branch's template with `BaasCliVersion` set shows a parameter change and executes; record the commands and output in `verify.md`
- [x] 1.2 Confirm `aws cloudformation validate-template` accepts the template with the unreferenced `BaasCliVersion` parameter, and record the output
- [x] 1.3 List every broad `catch` an operator command can reach before its first client is built, and note in `verify.md` which would reword or swallow a `CommandRefusal`

## 2. Version comparison in the kernel

- [x] 2.1 Add `BaasVersion.compare` (numeric, field by field) and `BaasVersion.comparable` (`x.y.z` only); unit tests cover `8.10.0 > 8.9.0`, equality, patch order, and the placeholder and a pre-release being non-comparable
- [x] 2.2 Make `RunnerImageParameters.compareVersions` delegate to `BaasVersion.compare`; the existing image-base tests still pass

## 3. The stack parameter

- [x] 3.1 Declare `BaasCliVersion` in `cf-template-core.yaml` (`Default: ""`, `AllowedPattern: '^$|^[0-9]+\.[0-9]+\.[0-9]+$'`, a description saying only setup writes it) with the conditional output that makes a value change an update (1.1), and `CoreStack.PARAM_BAAS_CLI_VERSION`; a `CoreTemplateTest` case pins the output's reference; `CoreStackTemplateTest`'s name pin and `theDeclaredParametersAreTheBundledTemplatesParameters` pass
- [x] 3.2 Test that `updateStackParameters` without the parameter named sends it as `UsePreviousValue` when deployed and omits it when the deployed stack lacks it

## 4. The check

- [x] 4.1 Add `session.CliVersionCheck`: refuse (exit 1, both versions, the `curl -fsSL https://github.com/<sourceRepo>/releases/latest/download/install.sh | sh` line), newer hint (operator and admin wording), unreleased warning with no lookup, silent proceed for equal / empty / absent stack; unit tests for each outcome
- [x] 4.2 Wire it into `OperatorSession.factory()` (deferred, once per session, region memoised and shared) with `Aws.recordedCliVersion`; `OperatorSessionTest` covers refuse, proceed, once-only, and that a refusal is thrown before any client of the command is built
- [x] 4.3 Add `OperatorSession.uncheckedFactory()` and use it in `JobsTerminateSubcommand`; a source-reading test pins that it has exactly that one caller
- [x] 4.4 Run the check eagerly in `DeployerSession.factory(config)`, with a package-private overload taking the lookup so a test covers refuse and proceed
- [x] 4.5 Apply the audit from 1.3 (no catch swallows the refusal, so no catch changes): remember the check's outcome per deployment for the process, so teardown's second deployer factory neither repeats the hint nor the call; a test covers it

## 5. Setup

- [x] 5.1 In the names step, for a present stack, run the check over `stackParameters`, keep the recorded value on `SetupContext`, and stop with exit 1 on an older CLI; `SetupWalkthroughTest`-style tests cover refuse (interactive and not), absent stack, rolled-back stack, empty value, and an unreleased build
- [x] 5.2 In the stack step, submit `BaasCliVersion` for a released `x.y.z` build on create and update, nothing otherwise, and log the raise announcement after an update that changed it; tests cover raise from a version, raise from none, same version, create, and a reactor build carrying the value forward

## 6. Installer

- [ ] 6.1 Remove `--update`, `store_installer`, `installed_version`, `latest_tag`, `version_newer`, the two test seams and the usage line from `scripts/install.sh`; keep `--uninstall` deleting a stored `install.sh`; replace the "re-fetch it" line with the curl command; `sh -n` and `shellcheck` (if installed) pass
- [ ] 6.2 Delete `scripts/tests/version-compare-probe.sh`; in `install-test.sh` drop the update cases and the stored-installer assertion, and add cases for `--update` being refused, nothing stored beside the jar, and `--uninstall` removing a planted earlier-release `install.sh`; `sh scripts/tests/install-test.sh` passes locally
- [ ] 6.3 Update `install-test.yml`'s comment that ties the `baas --version` step to `--update`; the workflow still parses (`actionlint` if installed)

## 7. Docs

- [ ] 7.1 README: replace the `--update` paragraphs with updating by the curl line, describe the version check, and turn the "Upgrade every `baas`" warning into the check's description
- [ ] 7.2 `infra/README.md`: the `BaasCliVersion` parameter and the upgrade order (CLI, then setup)
- [ ] 7.3 CLAUDE.md: rewrite the installer invariant (no `--update`, no stored copy), add the version-floor invariant (only setup writes it; `jobs terminate` exempt; unreleased builds unchecked), and correct the "every `baas` is an alias onto a reactor build" line
- [ ] 7.4 Update the setup sequence diagram in `docs/diagrams/` for the names-step check and the stack step's parameter, render it with `mmdc` to the scratchpad and look at the PNG
- [ ] 7.5 Draft the release-notes paragraph (update every CLI once, then run setup) in the PR description; check no commit message carries a `BREAKING CHANGE` footer or prose that parses as one

## 8. Build and manual verification

- [ ] 8.1 `mvn -B verify` on the full reactor passes
- [ ] 8.2 Manual, on `baas-development` (second deployment): setup from a JAR stamped as a release (`mvn versions:set -DnewVersion=<x.y.z>` in a scratch copy, never committed) records the version and announces the raise; a JAR stamped lower is refused by `jobs list` and by setup; `jobs terminate` from the lower JAR still works; the reactor build warns and runs; record each command and its output in `verify.md`
- [ ] 8.3 Manual, on `baas-development`: `admin image build` leaves `BaasCliVersion` unchanged, a `baas run` of `fake-jmh-benchmarks` completes, and the check's line order on stderr reads sensibly; then teardown (export declined) succeeds; record in `verify.md`
- [ ] 8.4 Run /opsx:verify and record the result in `verify.md` in the change directory — a requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation from design or tasks
