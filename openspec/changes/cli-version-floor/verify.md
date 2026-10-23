# Verify — cli-version-floor

## 1. Blocking assumptions (2026-10-23)

### 1.1 A value-only change of the parameter — disproved, then fixed

On `baas-development` (us-east-1, deployer `baas-admin`), with the branch's template, every other
parameter `UsePreviousValue=true`:

| Step | Template | Submitted `BaasCliVersion` | Answer |
|---|---|---|---|
| a | parameter only, referenced by nothing | `0.0.1` (parameter new to the stack) | `No updates are to be performed.` — not stored |
| b | same | `0.0.2` | `No updates are to be performed.` |
| d | same | `8.0.0-rc.1` | `Parameter 'BaasCliVersion' must match pattern ^$|^[0-9]+\.[0-9]+\.[0-9]+$` |
| e | same | `UsePreviousValue` | `Cannot specify usePreviousValue as true for a parameter key not in the previous template` |

So an unreferenced parameter can never be recorded: the design's first assumption was wrong. With a
conditional output referencing it (`Condition: RecordsCliVersion`, `Value: !Ref BaasCliVersion`):

| Step | Submitted | Answer | Parameter / output after |
|---|---|---|---|
| a | `0.0.1` (template adds parameter and output) | `UPDATE_COMPLETE` | `0.0.1` / `0.0.1` |
| b | `0.0.2`, nothing else different | `UPDATE_COMPLETE` | `0.0.2` / `0.0.2` |
| c | `0.0.2` again | `No updates are to be performed.` | unchanged |
| d | parameter carried with `UsePreviousValue` (an image build's shape) | `No updates are to be performed.` | `0.0.2` / `0.0.2` |
| e | empty | `UPDATE_COMPLETE` | empty / output absent |

`baas-development` was left with the branch's template and an empty `BaasCliVersion`. design.md and
the core-stack-provisioning delta were updated to carry the output (decision 1 and its rejected
alternatives; a risk entry replaced).

### 1.2 The template validates

`aws cloudformation validate-template --template-body file://infra/cf-template-core.yaml` returns the
parameter (`DefaultValue: ""`) and no error, before and after the output was added. Template size
39 KB, under the 51,200-byte `TemplateBody` cap.

### 1.3 Broad catches before a command's first client

| Command | First client | Inside a broad catch? |
|---|---|---|
| `run` (`JobLaunch`) | `aws.currentImage` | No — the catches (reserve, launch, launch-error upload, results) all come after it |
| `jobs list` | `factory.ec2()` in `fetch` | No |
| `jobs show` | `factory.dynamoDb()` | No |
| `jobs diff` | `jobLookup` | No (`try`-with-resources, no catch) |
| `jobs download` | `factory.dynamoDb()` | No (narrow `IllegalStateException`/`IllegalArgumentException` catches only) |
| `jobs connect` | `AwsConnectSources` | `catch (RuntimeException)` rethrows anything not an access denial — a `CommandRefusal` passes |
| `results query` | `openResults` | No |
| `config sync` | `factory.cloudFormation()` in `stackOutputs` | `factory.region()` is wrapped in `catch (RuntimeException) → refused(e.getMessage())`, which would keep the message and exit 1; the check fires at the credentials, not the region |
| admin commands | `DeployerSession.factory(config)`, eager | No — every call site is at the command's start, outside a `try`; teardown's export builds a second factory, so the check's outcome is remembered per deployment for the process |

No catch swallows or rewords the refusal, so task 4.5 needs no code change beyond the memo.

## 7.5 Release-notes paragraph (for the PR description)

> **Update every `baas` once, then have an admin run setup.** A deployment now records the release
> that last ran `baas admin deployment setup` on it, and every command that reaches AWS refuses a
> CLI older than that release — patch level included — printing the one command that updates it:
> `curl -fsSL https://github.com/wsztajerowski/benchmark-as-a-service/releases/latest/download/install.sh | sh`.
> `baas jobs terminate` is never refused. CLIs released before this one ignore the record, so it
> protects nobody until every machine has updated once. `install.sh --update` and the installer copy
> stored beside the CLI are gone: updating is that same `curl` line, and `--uninstall` removes an
> old stored copy.

No commit on the branch carries `BREAKING` in its subject or body (`git log origin/next-release..HEAD`
scanned); the installer change ships as `feat`, per the proposal.
