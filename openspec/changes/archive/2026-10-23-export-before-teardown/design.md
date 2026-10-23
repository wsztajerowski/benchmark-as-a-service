# Design

## Context

See proposal.md, *Why*. This design replaces `brainstorm.md`, which was written on 2026-10-02 before
`jobs-command`, `unified-run-prefix`, `teardown-retires-runner-image`, `multiple-deployments` and the
slice refactor (ADR 0009) landed. Its alternatives are carried over below. The state that shapes the
approach:

- **Teardown** (`TeardownCommand`) runs its steps in a fixed order behind `TeardownAws`, and
  `TeardownSequenceTest` pins that order:
  1. the in-flight gate;
  2. the "everything goes" notice;
  3. the confirmation (`--yes`, or the name typed back, read through the `answerReader` seam);
  4. the extension rescue (`saveExtension`, which refuses to continue if it cannot write);
  5. empty the bucket;
  6. delete the stack;
  7. retire the image.

  No spec requirement covers the extension rescue. It was added as code and CLAUDE.md prose only.
- **Stored records are portable.** Every pointer stored on an item (`resultPath`, `resultJsonKey`,
  `environmentJsonKey`, `profilerOutputPath`) is a bucket *key*, never `s3://<bucket>/…`. A job item's
  key is `JOB` / `<createdAt>#<jobId>`. No item names its deployment, account or region, so a
  verbatim copy means the same thing in any deployment.
- **Existing rights.**
  - The deployer holds `s3:Get*`/`s3:List*` on the bucket, `dynamodb:Describe*` and the table
    lifecycle actions, but no data-plane read or write on the table. It also holds no `PutObject`.
  - The operator holds `Scan`/`Query`/`GetItem`, `UpdateItem` on `JOB` only, and
    `PutObject`/`GetObject`/`ListBucket`. `baas admin` uses the deployer's credentials as they are
    and never assumes the operator role.
- **Building blocks that already exist.**
  - `S3UploadService.listKeys`/`download`/`upload`.
  - `JobsDownloadSubcommand.destinationFor`, the path-escape guard for keys written to disk.
  - The `If-None-Match: *` conditional put that seeds `releases/`.
  - `RunnerImageExtension.withMarker` and the stale-copy guard on `build-image --extension`.
  - `Console.interactive()`.
  - Jackson, already a dependency through `jackson-dataformat-yaml`.
- **Comparability.** Nothing here reaches the runner, user-data, the image or the item shape. Export
  only reads, and import writes items byte-identical to the ones read. A restored measurement
  therefore means exactly what it meant when it was recorded.

## Goals / Non-Goals

**Goals:**
- A deployment's results, recordings and extension survive a teardown, under one command that
  can also load them back.
- Teardown does one job: it deletes. The only time it writes locally is through an export the
  user accepted.
- Importing never destroys data, whatever the target holds.

**Non-Goals:**
- An analytics-shaped export, meaning decoded measurements as CSV or Parquet (brainstorm's
  Alternative C). `baas results query --format csv` covers ad-hoc use.
- Backups run by an operator or by CI. They would need operator-side grants, which were declined
  below.
- Filtering jobs by project or age, and incremental export. A bundle is a whole deployment,
  optionally without inputs.
- Moving `releases/`, `image-builds/`, the AMI, the stack, IAM, or `~/.baas/deployments/<name>.yaml`.
  Setup, the CLI's seeding and `admin image build` recreate all of these.

## Decisions

### Export and import are `admin deployment` verbs and run as the deployer

They act on the deployment as a whole, alongside `setup` and `teardown`, and import's natural
moment comes right after a setup. The three grants (`dynamodb:Scan`, `dynamodb:PutItem`,
`s3:PutObject`) cost no new power there: CLAUDE.md already accepts that the deployer is effectively
an account admin. Teardown already holds the deployer's credentials, so it can also run the export
inline.

*Rejected: `baas jobs export`/`import` as operator commands.* Export would have needed no new grant.
Import would have given day-to-day credentials `PutItem` on `RESULT#*` and `JOB`, and through S2 any
pushed branch holds those credentials. An operator can already plant results indirectly by
launching a JAR, but that is a reason not to make it easier.
*Rejected: export as an operator command and import as an admin command.* It would split the pair
across two places in the tree. The user chose to keep them together (2026-10-23).

### The deployer's new rights are named actions, not wildcards

`dynamodb:Scan` and `dynamodb:PutItem` are added to the table statement. `s3:PutObject` is added to
the bucket statement. `s3:Put*` stays excluded, because it would also grant bucket-policy and ACL
writes. `BatchWriteItem` is not needed (see the next decision). Together these add 50
non-whitespace characters, 4936 → 4986 against the 5632 budget (measured), and
`renderedPolicyLeavesRoomInACustomerManagedPolicyBudget` still holds. The setup preflight simulates
every unconditioned statement it renders, so the new actions are probed with no list kept beside
the policy.

### Export and import share one part vocabulary, and each selects with `--parts`

`results`, `recordings` and `extension` are one enum. Its help descriptions are printed by both
commands, and `--parts` parses the same way on both. Export defaults to all three parts. Import
defaults to whatever parts the manifest says the bundle holds. Selecting a part on import that the
bundle lacks is refused, rather than skipped, so a typo or the wrong bundle cannot pass as a
successful import. A part that isn't selected is not touched at all. For example, `--parts results`
writes no object and prints no extension command. This is how you restore only the table and drop
gigabytes of old recordings, or take just the extension into a new deployment.

### Import asks nothing

Import prints the source and the target, deployment, account and region, then loads. A typed-name
confirmation was built and dropped the same day (user, 2026-10-23). The only harm it guarded against
is a bundle loaded into the wrong deployment, and that is not a loss: nothing is overwritten, and the
extra rows can be removed by job id. The target is already explicit, because with two deployments
configured every command needs `--deployment`. Import's moment is right after an unattended setup,
and the prompt would have needed a terminal there.

### Import writes conditionally, one item at a time, and has no overwrite mode

Each item is a `PutItem` with `attribute_not_exists(pk)`. Each object is a `PutObject` with
`If-None-Match: *`, and a 412 or 409 response counts as *skipped*. That one rule makes a re-run after
an interrupt safe, makes merging into a live deployment harmless, and keeps `releases/` write-once
even though import never writes there. A table holds tens to thousands of items, so writing one at
a time costs seconds.

*Rejected: `--overwrite` with 25-item `BatchWriteItem` calls (brainstorm).* A batch cannot carry a
condition. It would be the only path in `baas` that silently replaces stored history, and no use for
it has come up. Dropping it also drops the unprocessed-items retry loop.

### The bundle's files are named by its parts, and the manifest is written last

```
baas-export-<deployment>-<instant>/
  results.jsonl                    part `results`
  recordings/jobs/<project>/<jobId>/…   part `recordings` (no input/ with --without-inputs)
  runner-image-extension.yaml      part `extension`; absent when none deployed
  manifest.json                    written last: its presence means "complete"
```

`manifest.json` holds:

- `schemaVersion: 1`;
- the source deployment, account and region;
- `exportedAt` and `cliVersion`;
- `parts` and `withoutInputs`;
- the counts: items, split into measurement items and job items (`pk` begins with `RESULT#`, or
  equals `JOB`), objects and bytes;
- `extensionHash`, or `null`.

An interrupt leaves a directory with no manifest, and import refuses it. The directory is not
resumable: export refuses a non-empty directory, so a re-run starts in a fresh one. Resuming would
mean trusting a partial file's integrity, and the only cost it saves is a re-download.

### `results.jsonl` is DynamoDB's typed JSON, one item per line, through a hand-written codec

Each line is the item's attributes in the wire encoding, for example
`{"pk":{"S":"RESULT#p"},"score":{"N":"8234574.731914"},…}`. That covers all ten types
(`S N B BOOL NULL M L SS NS BS`). `N` stays a string, so precision is never reformatted, and `B` is
base64. The codec lives in the `deployment` slice and knows nothing about keys. JSON Lines streams
in both directions, and `aws dynamodb put-item --item "$line"` can read any line, so the data does
not depend on `baas` to be readable.

*Rejected: native `ExportTableToPointInTime`/`ImportTable` (brainstorm A).* It needs point-in-time
recovery, a standing per-GB charge. Its `ImportTable` can only create a new table, and the table here
belongs to the stack.
*Rejected: decoding through `MeasurementItemMapper` (brainstorm C).* An attribute the mapper does not
model would be silently dropped. Job items are not measurements at all, and any change to the item
shape would become a backup-compatibility question.

### `recordings` mirrors `jobs/` key by key, checked by size

Export lists `jobs/` (and skips each job's `input/` sub-prefix with `--without-inputs`), then
downloads every key to `recordings/<key>`. A key that `destinationFor`'s rule would place outside the
bundle fails the export. Each file's size is compared with its listed size before the manifest is
written. Downloads run on a small fixed pool, eight threads, sized in implementation, and progress is
one `Console` status line. Import walks `recordings/` and uploads each file at its relative path.
Content types are not preserved: no reader of the bucket depends on them.

### The extension travels as a file with its base marker, and import only prints the push

Export reads `RunnerImageExtension` from the stack parameter, the same source
`admin image show --extension` uses, and writes it with `withMarker`. Import changes no stack. It
compares the manifest's hash with the target's deployed hash. When they match, it says the
extension is already deployed. Otherwise it prints
`baas --deployment <target> admin image build --extension <bundle>/runner-image-extension.yaml`.
The existing guard then accepts the file when the target holds no extension, and refuses it when the
target holds a different one. This is decision (b) from exploration: a push means a build of about
15 minutes, which import should never start uninvited.

### Teardown offers the export and saves nothing itself

The new order:

```
gate 1   in-flight runners → refuse
notice   everything goes, naming the extension when the stack holds one
offer    "Export <deployment> first? [Y/n]" → directory [./baas-export-<deployment>-<instant>]
         accepted → full export (all parts); failure → refuse, nothing deleted
gate 2   type the deployment name
delete   empty bucket → delete stack → retire image → delete the config file
```

`saveExtension`, `extensionSavedNotice` and the refusal for a failed save are deleted. Teardown
still reads the stack parameters, for the notice and for the federation line. The export runs
*before* the typed confirmation, so the user confirms already knowing a complete bundle exists, and
a failed export never leaves a half-confirmed teardown. The in-flight gate runs first, so the export
sees no running job, which removes brainstorm's "scan is not point-in-time" limit for this path.

### `--yes` skips the offer and the confirmation, and exports nothing

On a terminal, teardown offers the export before the typed name. `--yes` asks nothing, so it offers
nothing either: whoever passes it has exported already with `admin deployment export`, or decided not
to. Without a terminal and without `--yes`, teardown refuses with exit 1, naming both. Tests drive the
offer and the confirmation through the existing `answerReader` seam and a console marked interactive.

*Rejected after implementation: no `--yes` at all, so that every teardown passes the offer.* It was
built and shipped in this change's first commits (user, 2026-10-23, "no CI pipelining here"). The
live verification then showed its cost. An agent has no terminal, and the permission classifier
refuses, rightly, every way of faking one or skipping the prompt. So only a person could ever run a
teardown, the verification's own included. The user restored `--yes` the same day.

### Export and import translate `AccessDenied` into the setup hint

Every deployment set up before this change carries a deployer policy without the new actions. The
first export or import refused under it names the denied action and
`baas admin deployment setup`, which renders the current policy and hands it over. No separate
preflight is added to export or import.

### AWS calls go behind `ExportAws`

This follows the shape of `TeardownAws`, so the order is testable with a hand fake. The order is:
manifest last, the extension read before the downloads, conditional writes, and teardown's export
before the confirmation. `ExportAws` covers scanning, listing, `get`, conditional `put` of an item
and of an object, stack parameters, and table and bucket existence. Teardown composes the same
export object as the command.

## Risks / Trade-offs

- [The export offer is the only safety net, and it can be declined] → This was decided: teardown
  advises and the operator decides. The notice names exactly what goes, the extension included.
- [`--yes` bypasses the offer] → Its help says nothing is exported and names `admin deployment
  export`, and so does the refusal without a terminal. The notice before any deletion names what goes.
- [A standalone export racing a live job can miss that job's later writes] → It is stated in the
  export's help. The teardown path is not affected, because its in-flight gate comes first.
- [`recordings` egress costs about $0.09/GB] → The manifest reports the byte count, and
  `--without-inputs` and `--parts results,extension` exist for a cheap export.
- [A bundle holds benchmark JARs and profiler output on a laptop disk] → This is the same exposure as
  `baas jobs download`. The bundle never holds credentials: the deployment file is excluded, and the
  extension may never hold one (CLAUDE.md).
- [A future item shape is imported by an older CLI] → The codec copies attributes it does not
  understand verbatim, so only the manifest's `schemaVersion` can refuse a bundle, and that happens
  only when the bundle's *layout* changes.

## Resolved Questions

- *Does import create missing resources?* No. The target must be set up, and import names
  `admin deployment setup` otherwise. The stack owns resource creation (brainstorm Q1, dated block).
- *Bucket versioning and noncurrent versions?* Versioning is `Suspended` since
  `unified-run-prefix`, so a bundle of current versions is the whole bucket (brainstorm Q2).
- *Mirror throughput?* A fixed pool of eight and one status line (brainstorm Q3). Tuning is an
  implementation detail.
- *Manifest versioning policy?* `schemaVersion` counts the layout only. A CLI reads every version
  it knows and refuses a newer one by name (brainstorm Q4).
- *Operator-run export?* Declined for now. The whole pair is admin (brainstorm Q5; user,
  2026-10-23).
- *Second typed phrase on teardown (dated block)?* Superseded by the export offer. One typed
  confirmation stays, which `--yes` skips.
- *U12, the table retained after a rolled-back create?* Closed already by `DeletionPolicy: Delete`
  and the `ROLLBACK_COMPLETE` recreate. Nothing is left for this change.

## Open Questions

None.
