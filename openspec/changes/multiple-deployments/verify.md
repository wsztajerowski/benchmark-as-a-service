# Verify — multiple-deployments

Run 2026-10-07 on `multiple-deployments` (rebased on `next-release` `f40d145`). `openspec validate
multiple-deployments --strict`: valid. `mvn -pl baas-cli -am test`: 738 run, 0 failed, 0 skipped, with
the real `~/.baas` checksummed before and after (unchanged).

## Summary

| Dimension | Status |
|---|---|
| Completeness | 34/35 tasks. 7.4 deferred by the user to its own branch |
| Correctness | 19/19 requirements implemented; every scenario has a unit test or a live observation, except the gaps below |
| Coherence | Design followed; no decision contradicted by the code |

## Requirement → code → test

Paths are under `baas-cli/src/main/java/pl/wsztajerowski/baas/` and the matching test tree.

| Requirement | Code | Test / evidence | Gap |
|---|---|---|---|
| *Each deployment has its own configuration file* | `config/ConfigService` (`fileOf`, `save`, `delete`), `SetupCommand`, `ConfigSyncSubcommand`, `TeardownCommand.call` | `ConfigServiceTest.eachDeploymentIsItsOwnFileNamedByItsPrefix`, `deleteRemovesOnlyThatDeploymentsFile`; `TeardownConfigFileTest` (all three); `ConfigSyncSubcommandTest.adoptingASecondDeploymentLeavesTheFirstAlone`; live 7.1 wrote `deployments/wiktor-dev.yaml` | Setup's write is not unit-tested through `call()` (needs AWS); covered live |
| *An existing flat configuration is migrated once* | `ConfigService.migrateFlatFile`, `BaasConfig.AwsConfig` `@JsonAlias("profile")` | `ConfigServiceTest.theFlatFileMovesToItsDeploymentsFileOnFirstLook`, `theFlatFileOverwritesAnExistingDeploymentFile`, `theDeployerKeyIsRenamedInTheMove`, `aDeploymentFileStillCarryingTheOldKeyIsRenamedOnSave`, `aFlatFileWithoutAPrefixIsLeftAloneAndIgnored`; live: the real flat file migrated and `aws.profile` renamed | *An older CLI afterwards fails loudly*: observed live only (the installed release reports nothing configured) |
| *`baas config list` lists the configured deployments* | `commands/ConfigListSubcommand` | `ConfigListSubcommandTest` (six cases: two rows, lists under ambiguity, JSON, none, migrated, no top-level alias); live 7.3, table and JSON | — |
| *The deployer profile option is named for the deployer* | `ConfigSetSubcommand`, `ConfigShowSubcommand` | `ConfigSetCommandTest.theDeployerProfileIsStoredUnderItsOwnName`; `ConfigShowSubcommandTest.labelsTheDeployerAndTheOperatorProfile`; `AdminProfileOptionTest.theOldOptionIsGone` | — |
| *A deployment is named only by `--deployment`* (MODIFIED) | `BaasApp.configService`, `ConfigService.load` / `ambiguous` / `unknown` / `noneConfigured` | `DeploymentOptionTest` (seven cases incl. env var and removed pointers); `ConfigServiceTest.theOnlyDeploymentIsImplied`, `twoDeploymentsRequireANameAndAreListedWithTheHint`, `anUnknownNameNamesWhatIsConfigured`; `OptionValidationTest.theRemovedResultsOptionsAreUnknown`; live 7.3 | — |
| *`baas admin image build` builds the runner image* (MODIFIED) | `ImageBuildSubcommand` reads `aws.deployerProfile` | `ImageCommandsTest.deployerProfileIsTheOneTheseCommandsRead`; live 7.1 built `ami-03e9ecfdd0ed215fb` | — |
| *User tags are passed through to the runner* (MODIFIED) | `infra/Ec2ProvisioningService.instanceTags` | `Ec2ProvisioningServiceTest.theInstanceCarriesTheFixedTagsAndNothingElse`; live 7.2: `i-0d29c374cf2edbde3` carried exactly the four fixed tags | — |
| *Resource names … derived or resolved, not cached* (MODIFIED) | `BaasConfig.names()` → `DeploymentNames` | `BaasConfigYamlTest.namesTheCompositionRuleFixesAreNotStored`, `theDevDeploymentDerivesItsOwnNames` | — |
| *Every command accepts an alternative configuration file* (REMOVED) | `--config-path` deleted from `BaasApp` | `DeploymentOptionTest.theRemovedPointersAreUnknown`; `grep -rn -- '--config-path' baas-cli/src` matches only that test's assertion | — |
| *Runner lookups are scoped to the deployment* (job-tracking) | `Ec2ProvisioningService.listRunningBenchmarkInstances(deployment)`; callers `JobsListSubcommand:164`, `TeardownCommand:84` | `Ec2ProvisioningServiceTest.theTeardownGateCountsBootingRunnersAsLive`, `aJobsLiveInstanceIsFoundByItsJobIdTag` | W2 |
| *A deployment is named by its prefix, derived from the account by default* | `SetupCommand.deploymentName`, `computePrefix`, `ConfigService.loadForSetup` | `SetupCommandTest` (naming and prefix cases); `ConfigServiceTest.setupAndSyncCreateANamedDeploymentBesideTheOthers`, `setupAndSyncWithoutANameUseTheOnlyOneAndRefuseTwo`; live 7.1 created `wiktor-dev` in us-east-1 beside the default | — |
| *A deployment name satisfies every service that carries it* | `config/DeploymentNameRule`, `DeploymentNames.MAX_PREFIX_LENGTH` | `DeploymentNameRuleTest` (accepted, each rule refused, length boundary); `OptionValidationTest.anInvalidDeploymentNameIsRefusedBeforeAnyAwsCall`; `DeploymentNamesTemplateTest` | — |
| *Setup is the only command that validates a name* | `ConfigService.unknown` | `DeploymentOptionTest.aDifferentDeploymentIsRefusedNamingTheConfiguredOnes` | — |
| *baas admin deployment setup is self-sufficient* (MODIFIED) | `SetupCommand` (`--deployer-profile`, no `--prefix`) | `AdminProfileOptionTest`; `SetupCommandTest` | — |
| *Setup renders the deployer policy and stops when the caller lacks it* (MODIFIED) | `SetupCommand`, `DeployerPreflight` via `DeploymentNames` | existing preflight tests; live 7.1: 12 statements, 4107 characters, only `wiktor-dev` / `us-east-1`, nothing created | — |
| *Teardown safety gates* (MODIFIED) | `TeardownCommand` passes the resolved stack to the scoped lookup | `Ec2ProvisioningServiceTest.theTeardownGateCountsBootingRunnersAsLive` | W2: *Another deployment's runner does not block* is not run live |
| *Day-to-day commands resolve operator credentials* (MODIFIED) | `BaasConfig.AwsConfig.getDeployerProfile` / operator profile | `RunCommandTest.warnsOnALaptopWithNoOperatorProfileAndNoAmbientCredentials`, `staysSilentWhenAnOperatorProfileIsConfigured` | — |
| *Operators can bootstrap config without the deployer's machine* (MODIFIED) | `ConfigSyncSubcommand`, `ConfigService.loadForSync` | `ConfigSyncSubcommandTest` (nine cases) | — |
| *Resource names follow one composition rule* (MODIFIED) | `config/DeploymentNames` | `DeploymentNamesTest`; `DeploymentNamesTemplateTest`; live: stack, bucket, `wiktor-dev-results`, `wiktor-dev-role-operator` | — |
| *Two jobs' environments are compared group by group* (MODIFIED, key rename only) | `JobsDiffSubcommand` | `JobsDiffCommandTest.diffResolvesOperatorCredentials` | — |
| *Resource names are derived from the caller's AWS account* (REMOVED) | replaced by the ADDED naming requirement | as above | — |

## Warnings

- **W1 — fixed.** The ambiguity, unknown-name and none-configured refusals were followed by
  `(run with -v for the full stack trace)`, though each is a usage error with nothing to trace. They now
  throw `config/DeploymentSelectionException`, and `BaasApp.reportFailure` omits the hint for it; any
  other failure keeps it. Pinned by `DeploymentOptionTest.onlyARealFailurePointsAtTheStackTrace`;
  checked live with `--deployment nope jobs list`. Suite: 739 run, 0 failed.
- **W2 — scoping is proven by unit tests and a cross-region run, not in one region.** The filter is
  pinned by `Ec2ProvisioningServiceTest`, and both callers pass the resolved deployment. Live, 7.3
  separated two deployments in different regions, which their tables and regions alone would do. The
  same-region run (task 7.4, U36's live closure) was deferred by the user on 2026-10-07 to its own
  branch, and is tracked under *Deferred checks* in `openspec/changes/QUEUE.md`.

## Deviations from design and tasks

- **1.3** was answered from the SSM API reference rather than a live probe (recorded in design.md
  *Resolved Questions*).
- **The `--config-path` grep is not empty**: the one match is `DeploymentOptionTest`'s assertion
  that the option is unknown. Intentional.
- **U33 was closed too**, beyond U36 and C5: a mistyped `teardown --deployment` is now an unknown
  deployment, refused before any AWS call.
- **A surefire guard was added** (`user.home` = `target/test-home` for unit tests), not in the tasks,
  so no test can touch a developer's real `~/.baas`.
- **7.1–7.3 ran with `--deployment wiktor-dev` typed** on each command rather than through the
  `baas-dev` alias; the alias only prepends the same flag.
- **7.4 is deferred** to a separate branch by the user (see W2).
- **Left by hand after 7.5**: the customer-managed `wiktor-dev` deployer policy on `baas-admin`, and
  the `baas-operator-wiktor-dev` profile in `~/.aws/config`, whose role no longer exists. Neither grants
  anything over a deleted deployment. The policy is region-exact (`us-east-1`), so 7.4 in `eu-central-1`
  needs a new one either way; detaching this one is the user's call.
- Editorial, after archive: the core-stack-provisioning scenario *The values are read back from the
  stack, not from configuration* still says `~/.baas/config.yaml` (noted in task 1.2).

## Live run, 2026-10-07

| Step | Observation |
|---|---|
| Migration | the real flat `~/.baas/config.yaml` moved to `deployments/baas-381492019823.yaml`, `aws.profile` → `aws.deployerProfile` |
| 7.1 setup | under `baas-admin`: policy printed (12 statements, 4107 non-whitespace characters, `wiktor-dev` / `us-east-1` only), nothing created; after the customer-managed attach, stack `wiktor-dev` created in ~3.5 min |
| 7.1 image | parent `ami-07a5b367e8dc8bd92` resolved for us-east-1; contract passed; `ami-03e9ecfdd0ed215fb` at `/wiktor-dev/runner/ami-id` |
| 7.2 job | `20261007T085701862Z-3d8ced78` completed in ~1 min on `i-0d29c374cf2edbde3`, tagged `baas-deployment=wiktor-dev`; `environment.json` written; the row is found on `wiktor-dev` and not on `baas-381492019823` |
| 7.3 selection | the bare query refuses listing both with the hint; `config list` shows both rows; the default's `jobs list` omits the live dev runner |
| 7.4 | deferred (W2) |
| 7.5 extension | `admin image build --extension` (ASCII, 344 bytes) → label `1.3.0+ext.f6620f0d`, `ami-02e77d399863b9e36`; `ami-03e9ecfdd0ed215fb` retired after the repoint |
| 7.5 teardown | saved the extension to `~/.baas/runner-image-extension.wiktor-dev.yaml` (marker `f6620f0d`, body identical to the pushed file; the default's `runner-image-extension.yaml` untouched); emptied and deleted the bucket, deleted the stack (~8.5 min), retired AMI and pointer, removed `deployments/wiktor-dev.yaml`; no stack, parameter, AMI or bucket remains. Bare `jobs list` and `config list` address `baas-381492019823` alone. U21 and U40 closed |

## Assessment

No implementation gaps, and no critical issue. 7.4 is deferred by decision (W2). W1 is fixed.
