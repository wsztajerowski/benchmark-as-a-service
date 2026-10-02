# Verify: run-status-in-dynamodb

Evidence collected during `/opsx:apply`, then the requirement → code → test → gap table from
`/opsx:verify` (task 14.1).

## Section 1: blocking assumptions (2026-10-03)

### 1.1 `dynamodb:LeadingKeys` per action

**Deviation:** `aws iam simulate-custom-policy` is not available. The deployer user
`baas-admin-wiktor` is denied `iam:SimulateCustomPolicy`, and widening the deployer policy for a test
was ruled out. The check moved to after task 2.2:
- `simulate-principal-policy` against the *deployed* `RunnerRole` and `OperatorRole`, which tests the
  real policies rather than candidates;
- plus the live AccessDenied checks of task 13.7.

The result is recorded under task 2.2 below.

### 1.2 AWS CLI on the runner image

The newest run's `environment.json` (`runs/benchmark-as-a-service/20261002T195517542Z-6c4ff89e/`,
image `1.2.0`, `ami-0ae60d9d990dc00df`) reports
`aws-cli/2.33.15 Python/3.9.25 Linux/6.1.177-224.371.amzn2023.x86_64`. That is v2, which supports
`AWS_RETRY_MODE`, `AWS_MAX_ATTEMPTS`, `--cli-connect-timeout`, `--cli-read-timeout` and
`update-item --condition-expression`. ✓

### 1.3 The designed condition, against LocalStack 3.8

The condition was
`attribute_exists(pk) AND (attribute_not_exists(#status) OR NOT (#status IN (:completed, :timedOut,
:cancelled, :launchFailed) OR begins_with(#status, :failedPrefix)))`.

| Case | Exit | Item after |
|---|---|---|
| `completed` → `running` | 254, `ConditionalCheckFailedException` | `completed` |
| `failed:3` → `completed` | 254, `ConditionalCheckFailedException` | `failed:3` |
| missing key → `running` | 254, `ConditionalCheckFailedException` | none (no item created) |
| `launching` → `running` | 0 | `running` |

All as designed. ✓

**Note carried into 6.1:** a missing key also surfaces as `ConditionalCheckFailedException`, so the
shell's log line for it reads "status already terminal, or no run item at this key". It does not
claim the first only. Nothing is created either way, which is the property that matters.

### 1.4 `--no-database` in workflows and scripts

`git grep -n -e '--no-database' -- .github scripts` finds nothing (exit 1). ✓
