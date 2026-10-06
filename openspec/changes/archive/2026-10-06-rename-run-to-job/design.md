# Design

## Context

See proposal.md — *Why*. The noun appears in four layers with different costs: code and CLI text;
infrastructure names applied by `baas admin setup`; names stored in S3 and DynamoDB; and specs and
docs. A run's identifier already has three names — `runId` (code, S3 layout, `baas run`'s summary),
`requestId` (the stored attribute on status *and* measurement items, `RunItemMapper.RUN_ID =
"requestId"`, and `environment.json`), `request-id` (`requestId-index`, EC2 tag `baas-request-id`,
`baas results --request-id`, the runner's `--request-id`). The id *value* contains no "run", so no key
changes shape; only names and two literals (`RUN`, `runs`) change.

The live installation `baas-381492019823` holds test data only (2026-10-06: 47 items — 24 status
items, 23 measurements in `benchmark-as-a-service`, `baas-e2e`, `baas-lifecycle-test`).

## Goals / Non-Goals

**Goals:**
- One name for one thing: after this change, no code, stored name, IAM condition, CLI option, output
  field or spec uses "run" as a noun; "run" remains the verb (`baas run`, "the benchmark runs") and
  part of `runner`.
- A pure rename: no behaviour changes beyond the names. Every requirement keeps its meaning.

**Non-Goals:**
- `jobs show`, `jobs diff`, the fate of `download` / `env diff` / the literal-path argument, flag
  consistency, hints — all `jobs-command`.
- Migrating or reading anything stored under the old names.
- Renaming `runner` anywhere.

## Decisions

### D1 — "Job" is the only noun; "run" stays a verb and `runner` stays untouched

`baas run` runs a job, as `gh workflow run` runs a workflow. `runner` names the machine and program
that execute a job, so `benchmark-runner`, `<prefix>-role-runner`, `-profile-runner`,
`-pipeline-runner`, `/<prefix>/runner/ami-id`, `RunnerSecurityGroup`, `RunnerRole` and the runner
image keep their names. Consequence: **`infra/deployer-policy.json` is unchanged** — it names no
partition key, attribute, index or tag, and its table grant is the table ARN.

Rejected: `executions` (the short form collides with `docker exec`), `tasks` (ECS), `trials`
(JMH `Level.Trial`) — `jobs-command/exploration.md` has the full comparison. Rejected: renaming only
the CLI noun and freezing stored names — it keeps the three-name split this change exists to remove.

### D2 — Three names merge into `jobId`, and the GSI becomes `jobId-index`

`runId`, `requestId` and `request-id` all become `jobId` / `job-id`: the stored attribute on both
item kinds, `environment.json`, the GSI name, the EC2 tag `baas-job-id`, `baas results --job-id`,
the runner's `--job-id` (short `-id` kept), `baas run`'s summary `jobId` / `jobStatus`. The
partition literal `RUN` becomes `JOB` (`pk`, `gsi1sk`, IAM `LeadingKeys`); `runs/` becomes `jobs/`.
`gsi1pk` / `gsi1sk` keep their generic names. `baas results --all-runs` becomes `--all-jobs`.

Renaming a GSI on a live table means delete and recreate with a backfill; on a freshly created table
it is free — which is the only reason it is in scope (D3).

### D3 — Wipe and rebuild; no migration and no dual reading

Rejected: reading both names (permanent fallback in two mappers plus a two-partition listing, where
one forgotten reader drops old jobs silently — the failure CLAUDE.md warns about for keys); a one-off
migration (an unversioned bucket, and neither the deployer nor the operator holds the reads and
deletes it needs). Both protect data the installation does not hold.

### D4 — Rebuild before merge, from this branch's build

The PR's own CI e2e runs the new CLI against the live installation, so the installation has to be
renamed first. Order: implement → local gates green → tear down → delete the retained table →
`setup` + `build-image` from `baas-cli/target/baas-cli.jar` with `--runner-jar` → paid verification
→ push → CI e2e green → merge. Between teardown and merge, `next-release`'s e2e and any other
branch's fail against the rebuilt installation; nothing else launches meanwhile.

`baas run` from a reactor build needs `--runner-jar benchmark-runner/target/benchmark-runner.jar`
(CLAUDE.md, *A reactor build cannot launch a run*); CI already passes it.

### D5 — Capability directories and scenario titles are renamed at archive, by hand

Deltas must target existing capability paths, and OpenSpec 1.13.1 refuses a MODIFIED block that
drops a scenario by name — a renamed scenario counts as dropped, and there is no scenario-level
RENAMED. So the deltas keep every `#### Scenario:` title verbatim, and archive is followed by an
editorial commit that (a) `git mv`s `run-artifact-layout`, `run-identity`, `run-tracking` to
`job-*`, (b) applies `archive-renames.md`'s scenario-title table, (c) edits the three Purpose
sections. Requirement headers are renamed by the deltas' `RENAMED` sections.

Rejected: REMOVED + ADDED per requirement — doubles the delta, and a REMOVED heading that misses the
main spec by a character archives anyway and leaves the old requirement behind (CLAUDE.md gotcha).

### D6 — The literal-result-path argument stays for now

`download` and `env diff` accept a job id or a literal result path (`RunReference`, renamed
`JobReference`). The path branch exists only for runs that have no status item or predate the unified
layout; after the wipe there are none, so it is dead. Removing it is a behaviour change, and the
P11 design deliberately kept paths for `show`/`diff`. Both commands are redesigned in
`jobs-command`, so the removal is decided there. Here the help text and messages name `jobs/…`.

### D7 — The manifest key changes, so `schemaVersion` bumps

`environment.json`'s `requestId` becomes `jobId`. A renamed key is a schema change, and
`baas env diff` compares keys, so `MANIFEST_SCHEMA_VERSION` goes 4 → 5. `jobs-command` (P11) drops
the key and bumps again.

**Comparability:** no measurement, toolchain, image definition or kernel setting changes;
`runner-image.yaml` and `imageVersion` are untouched. The rebuilt AMI is a fresh bake of the same
definition, so it may resolve newer patch levels — and there is no history left to compare with.

### D8 — Status values are not nouns and do not change

`launching`, `launched`, `running`, `completed`, `failed:<n>`, `timed-out`, `cancelled`,
`launch-failed`, `vanished` keep their spelling; `RunStatus` becomes `JobStatus`.

### D9 — Code identifiers follow a fixed map

`baas-model`: `RunId`, `RunLayout` (`RUNS_PREFIX = "jobs"`), `RunItem`, `RunItemMapper`, `RunStatus`,
`ResultKeys.RUN_*` → `Job…` / `JOB_*`. `baas-cli`: package `…baas.runs` → `…baas.jobs`
(`JobSession`, `JobTermination`, `JobRecorder`, `DynamoDbJobRecorder`, `JobListing`), `RunsCommand` /
`RunsListSubcommand` / `RunsTerminateSubcommand` → `Jobs…`, `RunReference` → `JobReference`.
`RunCommand` keeps its name — it is the verb. `benchmark-runner`: `RunLogs` → `JobLogs`, the
`--request-id` option and every `requestId` field. User-data: `REQUEST_ID` → `JOB_ID`,
`RUN_SORT_KEY` → `JOB_SORT_KEY`, `RUN_STATUS_GUARD*` → `JOB_STATUS_GUARD*`, `run_status` →
`job_status`. IAM statement ids naming run items follow (`DynamoDbRunItemWrite` →
`DynamoDbJobItemWrite`). Test names follow their subjects.

### D10 — Docs say "CI job" for a GitHub Actions job

Every pre-existing "job" in this repository means a CI job. Docs touched here qualify it, so "a red
CI job with a completed job" reads unambiguously. Archived changes and ADRs are history and stay as
written; `docs/review/open-findings.md` and `QUEUE.md` are live and are updated.

## Risks / Trade-offs

- [A missed occurrence is silent: a reader on `RUN`, `requestId` or `runs/` returns nothing rather than
  failing] → a grep gate in tasks: no `runId`, `requestId`, `request-id`, `"RUN"`, `runs/`,
  `RunItem`… outside an explicit allow-list (archived changes, ADRs, `runner`); plus the LocalStack
  ITs and the paid end-to-end runs exercise every reader against real stored names.
- [An old CLI against the rebuilt installation] → its reservation is an `UpdateItem` on `pk = RUN`,
  which the new `LeadingKeys = JOB` condition refuses, so it launches nothing (fail-safe). Every
  machine must upgrade — the same stance as run items (accepted 2026-10-02).
- [A job in flight during the rebuild cannot record its outcome] → teardown already refuses while a
  runner is pending or running.
- [The e2e of other branches is red between rebuild and merge] → short window; nothing else is in
  review.
- [The rename touches ~60 Java files and every spec; a reviewer cannot read it line by line] →
  mechanical commits per layer (model, runner, CLI, infra, docs, specs) so each diff is one kind of
  edit.

## Migration Plan

1. Implement on `rename-run-to-job`; full reactor `mvn verify` green, with `ASYNC_PATH` exported.
2. `baas admin teardown --delete-bucket --yes` (old CLI or new — teardown reads no item).
3. `aws dynamodb delete-table --table-name baas-381492019823-results` under the deployer profile.
4. `baas admin setup --region eu-central-1 --github-oidc-provider-arn …` from the branch build, the
   federation values carried as before.
5. `baas admin build-image` (~15 min).
6. `baas config sync --name baas-381492019823`.
7. Paid verification, at most 5 runs (tasks §verification); record in `verify.md`.
8. Push, open the PR into `next-release`, CI e2e green, `/opsx:verify`, rebase-merge, archive with
   D5's editorial commit.

Rollback: revert the merge and rebuild again from the previous code — test data only, nothing to
restore.
