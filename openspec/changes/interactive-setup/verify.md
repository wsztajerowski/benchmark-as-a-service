# Verify — interactive-setup

Paid tests used: **0 / 20** (image builds and `baas run` jobs; user's budget, 2026-10-14).

## 1. Blocking assumptions

### 1.1 Provider absent, policy published

2026-10-14, first attempt (before the user had published the new policy version):

```
$ aws --profile baas-admin iam get-open-id-connect-provider --open-id-connect-provider-arn \
    arn:aws:iam::381492019823:oidc-provider/token.actions.githubusercontent.com
An error occurred (AccessDenied) when calling the GetOpenIDConnectProvider operation: User:
arn:aws:iam::381492019823:user/baas-admin-wiktor is not authorized to perform:
iam:GetOpenIDConnectProvider ... because no identity-based policy allows ...
```

Not published yet. Code work continues; re-checked before live work (section 13).

2026-10-14, after the user published both policy versions:

```
$ aws --profile baas-admin iam get-open-id-connect-provider --open-id-connect-provider-arn …
ClientIDList:
- sts.amazonaws.com
CreateDate: '2024-12-15T18:53:42.260000+00:00'
```

The new `GitHubOidcProvider` statement is in effect. The old provider still exists. It is to be
deleted by the user before 13.3, which tests setup creating it, and is re-checked there.

### 1.2 `ListImagePipelineImages`

Read-only against `baas-development`'s pipeline:

```
$ aws imagebuilder list-image-pipeline-images --image-pipeline-arn \
    arn:aws:imagebuilder:us-east-1:381492019823:image-pipeline/baas-development-pipeline-runner
[ { "arn": "arn:aws:imagebuilder:us-east-1:381492019823:image/baas-development-recipe-runner/1.0.0/1",
    "status": "AVAILABLE", "created": "2026-10-13T18:18:29.772Z",
    "ami": "ami-05f4851d3c30e3cb2", "ver": "1.0.0/1" } ]
```

A finished build carries its output AMI and its recipe version in the ARN
(`image/<recipe>/<recipe version>/<build>`), which is what adoption compares. The API reference
describes the call as listing the images a pipeline created, with no state filter. Whether an
in-progress build appears is confirmed live in 13.2, the first paid build. If it does not, the lookup
falls back to `ListImageBuildVersions` on the recipe's image version, which lists every build state.

### 1.3 OIDC provider calls

From the IAM API reference:
- `GetOpenIDConnectProvider`'s documented errors are `NoSuchEntity`, `InvalidInput` and
  `ServiceFailure`. Authorisation is evaluated against the named ARN first, so a caller allowed on
  that ARN gets `NoSuchEntity` for an absent provider.
- `CreateOpenIDConnectProvider`: `Url` is required. `ClientIDList`, `ThumbprintList` and `Tags` are
  optional. Without a thumbprint, IAM verifies the IdP through its trusted root CAs (exploration D4
  spike).

### 1.4 CloudFormation waiters

SDK 2.54.12, `CloudFormationWaiter`: `waitUntilStackCreateComplete`, `…UpdateComplete`,
`…DeleteComplete`, `…RollbackComplete` (the `RollbackStack` API's `UPDATE_ROLLBACK_COMPLETE`),
`…ImportComplete`, `…Exists`, and change-set, refactor and type-registration waiters.

There is no waiter for `ROLLBACK_IN_PROGRESS` (create rollback) or for
`UPDATE_COMPLETE_CLEANUP_IN_PROGRESS`, and the create and update waiters *fail* on rollback states
rather than returning them. **Deviation from the design:** step 8 waits on any `*_IN_PROGRESS` status
with one poll loop over `DescribeStacks` (every 10 s, the waiters' own cadence) until the status no
longer ends in `_IN_PROGRESS`, then decides from the settled status. This is the fallback the design
names for a status with no waiter, applied to all of them so there is one path.

## 11.1 Commands still printed without `--deployment`, and why

`git grep '"baas \|  baas \|`baas '` over `baas-cli/src/main`, after the change. Every remaining
bare `baas` is one of the following:

- **Help text and usage examples:** `BaasApp`'s quick start, `RunCommand`'s footer and its
  `--detach` description, `ImageBuildSubcommand`'s footer, and option descriptions that name
  another command (`JobsShow`/`JobsDownload`/`JobsDiff`, `ConfigSet`, `AdminCommand`). They are
  generic by nature, and D12 puts the rule in `--deployment`'s own help instead.
- **Printed before any deployment is resolved:** `RunCommand`'s unreleased-build refusal
  (`baas run --runner-jar`), checked before the config is read. Also `ConfigService`'s
  `noneConfigured`/`ambiguous` texts, where `--deployment <name>` is a placeholder or there is no
  deployment to name.
- **Descriptions, not instructions:** "`baas run` fails until…", "`baas jobs list` shows…",
  StackStep's "a permission `baas admin deployment setup` needs", `OperatorSession`'s "the name
  `baas admin deployment setup` printed".
- **Portable file content:** `RunnerImageExtension`'s marker comments (`# Push: baas admin image
  build --extension <this file>`), since one file can be pushed to any deployment, and its
  stale-push refusal, a static check that is given no deployment.
- **Comments** in `UserDataScriptBuilder` and `simplelogger.properties`.

Every instruction printed after a deployment is resolved goes through `Hints.command`:
- run hints and the no-image refusal;
- the `jobs list` and `jobs show` hints;
- the `results query` hint;
- `admin image show`'s "no image" message and drift warning;
- teardown's in-flight refusal, saved-extension notice, image notice and extension-rescue failure;
- `RunCommand`'s missing-outputs and reservation messages;
- `CloudFormationService`'s missing stack;
- setup's closing text.
