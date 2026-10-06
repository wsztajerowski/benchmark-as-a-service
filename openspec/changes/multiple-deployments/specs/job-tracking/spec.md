# Spec Delta

## ADDED Requirements

### Requirement: Runner lookups are scoped to the deployment
Every query the CLI makes for live runner instances SHALL filter on `baas-deployment=<prefix>` of the
deployment it addresses, in addition to `baas-role=benchmark-runner`. Two deployments in one region
SHALL NOT see each other's runners. A lookup of one job's instance by its `baas-job-id` tag MAY omit
the filter, since the job identifier is unique. An instance launched before this tag existed carries
no `baas-deployment` and SHALL NOT be found by a scoped query.

#### Scenario: `jobs list` ignores another deployment's runners
- **WHEN** `baas --deployment wiktor-dev jobs list` runs while a runner of `baas-123456789012` is running in the same region
- **THEN** that runner is neither reported nor used to resolve any listed job's liveness

#### Scenario: An untagged runner from an older CLI
- **WHEN** a runner launched by a CLI from before this change is running
- **THEN** a scoped query does not find it, and the shell watchdog still terminates it within its timeout plus margin
