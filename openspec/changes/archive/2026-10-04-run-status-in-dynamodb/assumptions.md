# Pre-explore assumptions

> **This is not an artifact.** No artifact exists yet; `/opsx:explore` is the first step. This file
> exists so that session starts from established facts instead of re-deriving them, and so the open
> questions below are not silently resolved by whoever writes the proposal. Captured 2026-10-02 from
> the CLI usage analysis and the review walkthrough.

**Priority 1** of the queue in [`../QUEUE.md`](../QUEUE.md).

## Why this change exists

Review finding **U3** (`docs/review/baas-cli-findings.md` row 17, `docs/analysis/cli-usage-analysis.md`
§3): *a detached run is invisible.* If the CLI dies without its shutdown hook (SIGKILL, laptop sleep,
lost network), no `baas` command lists in-flight runs, reattaches to one, or terminates one. Teardown
refuses while one exists and its only advice is "terminate them manually". The watchdog bounds the
cost to `timeout + margin` (2 h 05 m by default), so this is a visibility gap, not an unbounded bill.

The `Detached` state is in the run state graph, `cli-usage-analysis.md` §2.3.

## Established facts — verified in the code on 2026-10-02

- **Run status is an S3 object today.** User-data writes `completed` / `failed:<n>` with
  `aws s3 cp - "s3://${S3_BUCKET}/${RESULT_PATH}/run-status"` (`UserDataScriptBuilder.java:194`).
  `RunCommand` polls `resultPath + "/run-status"` (`RunCommand.java:455`) and maps it to an exit
  code (`RunCommand.java:556`). The CLI tells a vanished run apart by an instance that ended without
  that object (`RunCommand.java:498`).
- **Teardown already finds running instances**: `Ec2ProvisioningService.listRunningBenchmarkInstances()`,
  called from `TeardownCommand.java:67`. It refuses and names them.
- **Readers that would see run items once they share the table:**
  - `ResultsQueryService.scanAllProjects` (`--all-projects`, a Scan, line 75)
  - the project-picker Scan (`ResultsQueryService.java:104`), which derives names from `pk`
  - `queryByRequestId` and the `requestId-index` lookup (lines 146, 166), used by `ResultsCommand`,
    `RunReference` (`download`, `env diff`) and `RunCommand.java:586`
- **IAM today:** `RunnerRole` holds `dynamodb:PutItem` and `BatchWriteItem` on the table
  (`cf-template-core.yaml:369-376`). `OperatorRole` holds `Query`, `Scan` and `GetItem`, and no write
  (`cf-template-core.yaml:671-686`).
- **Keys are built only in `ResultKeys`, items only in `MeasurementItemMapper`** (CLAUDE.md
  invariant). Any run-item key has to live there too.

## Decided 2026-10-02 (`cli-usage-analysis.md` §7). Do not re-open these without cause.

- A run listing is worth a command, and **run status moves from S3 into DynamoDB**, so the bucket goes
  back to being write-once storage that nothing polls.
- **One table, not a separate `-runs` table.** A new table would need another retained resource and a
  deployer-policy edit, and that policy is already near its size budget.
- **Run item keys:** partition `RUN#<project>`, `sk = <createdAt>#<runId>`, `gsi1pk = <runId>`. That
  lets `requestId-index` resolve failed runs, so `download <runId>` needs no S3 fallback.
- **Writers:**
  - The CLI writes `launched`, and writes `cancelled` from its shutdown hook.
  - The instance's shell writes `running`, then `completed` / `failed:<n>` in place of the
    `run-status` object.
  - Every write is a conditional `UpdateItem` that never overwrites a terminal status.
  - "Vanished" is inferred at read time (a non-terminal status and an instance that no longer exists).
- **IAM:**
  - `OperatorRole` gains `dynamodb:UpdateItem`, restricted by `dynamodb:LeadingKeys` to `RUN#*`. It is
    the role's first write right on the table, and it is granted deliberately.
  - `RunnerRole` gains the same. In the same edit, its `PutItem`/`BatchWriteItem` narrow to
    `RESULT#*`, which tightens finding S7.
  - The deployer policy is unchanged.
- **Every Scan and every `requestId-index` reader must exclude run items, with tests.** The picker
  derives project names from `pk`, so a missed exclusion fails silently.
- **`baas runs`** does one reverse `Query` per project plus `DescribeInstances`, and gains
  `--terminate <runId>`.
- **No `--detach` / `attach` pair** (§5, "Not proposed").
- **Rough costs:** about $0.00015 of reads to poll a 2-hour run, against about $0.0002 of S3 GETs today.
  Listing 10,000 runs takes about 0.2 s instead of about 70 s. Effort is two to three times the
  S3-listing alternative, IAM change included.
- **No backfill.** The table held two runs when this was decided.

## Things the explore session has to settle

1. **The command name collides with `runs-command`** (priority 3, decided in review P11 and recorded in
   `docs/review/prebaked-runner-ami-review.md` §11). That change gives
   `baas runs show` and `baas runs diff`. This change ships first, so it defines the `runs` group.
   Is the listing `baas runs` bare, or `baas runs list`? Is terminate `--terminate` or
   `baas runs terminate <runId>`? Choose with the later `show`/`diff` siblings in mind.
2. **When the user-data shell can't reach DynamoDB.** Today a failed `aws s3 cp` of `run-status` makes
   the run look vanished. A failed conditional `UpdateItem` behaves the same way. Should the boot log
   still go to S3 (yes: it is an artifact, not a status), and is the item's absence enough of a
   signal?
3. **The `cancelled` write and the watchdog.** The shutdown hook writes `cancelled` and then
   terminates. The instance's `failed:<n>` may land later and be refused as a non-terminal-to-terminal
   race. Decide which terminal status wins and say so in the spec.
4. **Does the `run-status` S3 object go away entirely, or stay for one release** so a CLI older than
   the runner AMI's user-data can still poll? User-data is generated by the CLI per run, so a version
   skew may not be possible at all. Confirm before keeping any compatibility path.
5. **Which project a run item belongs to when the project is invalid.** The runner refuses an
   unresolved project, and PR #73 restricts project names. The `launched` write happens before the
   runner validates anything.
6. **Teardown's refusal message** could name run ids and `baas runs --terminate` instead of bare
   instance ids and "terminate them manually".

## Sequencing

PR #73 merged on 2026-10-02, and its changes are already on `main`: every user-data export is
quoted, project names are restricted, and each `@Param` variant of a benchmark is stored under its
own sort key (A11, which touches `ResultKeys`). Start from `main`. The line numbers above were
checked against `acbbd1f`.
