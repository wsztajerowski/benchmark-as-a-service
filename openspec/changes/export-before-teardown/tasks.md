## 1. Verify blocking assumptions

- [x] 1.1 Confirm that no stored attribute names a bucket, account or region. Check every pointer
      `MeasurementItemMapper` and `JobItemMapper` write (`resultPath`, `resultJsonKey`,
      `environmentJsonKey`, `profilerOutputPath`, the job item's path fields), and read one real item
      from `baas-development`. If any carries `s3://` or a bucket name, the verbatim import is wrong:
      stop and revise the design.
- [x] 1.2 Confirm `PutObject` with `If-None-Match: *` answers 412 or 409 for an existing key on
      LocalStack 4.14.0, which the round-trip IT relies on. The real bucket already does this for
      `releases/`. If LocalStack ignores the header, the IT asserts skip-counting with a pre-check
      instead, and the task records that.
- [x] 1.3 Render the deployer policy with `dynamodb:Scan`, `dynamodb:PutItem` and `s3:PutObject` added.
      Record its non-whitespace length (expected about 4640, budget 5632).
      *Measured 2026-10-23: 4936 before, 4986 after (fixture prefix `baas-123456789012`). CLAUDE.md's
      "about 4580" was already stale; 646 characters remain.*
- [x] 1.4 Confirm the setup preflight (`DeployerPreflight`) simulates both edited statements. They must
      carry no `Condition`.
- [x] 1.5 Grep the repository, `docs/`, `infra/README.md` and `.github/` for `teardown --yes` and
      `teardown … --yes`, and list every call site outside tests and specs. Expected: only the two
      diagrams.
- [x] 1.6 Confirm `TeardownCommand`'s tests can mark the console interactive and script answers through
      `answerReader`. If not, add that seam first, as its own commit.

## 2. Deployer policy

- [x] 2.1 Add `dynamodb:Scan` and `dynamodb:PutItem` to the results-table statement of
      `deployer-policy.json`, and `s3:PutObject` to `S3WorkingBucketLifecycle`.
- [x] 2.2 Test: the rendered policy names the three actions and names neither `s3:Put*` nor
      `dynamodb:BatchWriteItem`. The budget test still passes. *Done by rewriting the two tests that
      pinned the old boundary (`deployerProvisionsTheBucketWithoutWritingObjectsIntoIt`,
      `theDeployerHasNoDataPlaneAccessToResults`): the deployer may now `PutObject`, `Scan` and
      `PutItem`, and still not `s3:Put*`, `PutBucketPolicy`, `BatchWriteItem`, `DeleteItem`,
      `UpdateItem`, `GetItem` or `Query`.*
- [x] 2.3 Test: the operator policy grants no `dynamodb:PutItem`. Pin the absence.

## 3. Item codec

- [x] 3.1 Write an `AttributeValue` ↔ JSON codec in `deployment`, covering
      `S N B BOOL NULL M L SS NS BS` in DynamoDB's typed JSON, with `N` kept as its string and `B` as
      base64.
- [x] 3.2 Tests: a round trip for every type, nesting included. A real measurement item and a real job
      item round-trip byte-identically. An unknown type key in the input is refused.

## 4. Export port and bundle

- [x] 4.1 Define `ExportAws`: scan the table, list keys under a prefix, download a key to a file, read
      the stack parameters, a conditional put of an item and of an object (each reporting
      written or skipped), and whether the table and bucket exist. Implement `AwsExportAws` over a
      `DeployerSession` factory.
- [x] 4.2 Write `Bundle`, which owns the layout (`results.jsonl`, `recordings/<key>`,
      `runner-image-extension.yaml`, `manifest.json`) and reads and writes the manifest
      (`schemaVersion: 1`). The manifest's fields are the ones design.md lists.
- [x] 4.3 Define the `Part` vocabulary: `results`, `recordings` and `extension`, each with the one-line
      description the help prints. `--parts` parses to it, and an unknown name exits 2 listing all
      three.

## 5. `baas admin deployment export`

- [x] 5.1 Add the command under `DeploymentCommand`, with `--parts`, `--without-inputs` and
      `--output-dir` (default `./baas-export-<deployment>-<instant>`). Refuse a non-empty directory
      before any AWS call.
- [x] 5.2 Export each part:
      - `results`: stream the scan into `results.jsonl`, counting measurement items and job items.
      - `extension`: write the extension with `withMarker`, or record that none is deployed.
      - `recordings`: list `jobs/`, skip `*/input/` under `--without-inputs`, download through the
        `destinationFor` escape rule on a pool of eight, then check every size.
- [x] 5.3 Write the manifest last, then print the bundle path and the counts on the console.
- [x] 5.4 Write help text that describes each part, names what a bundle never contains, and states
      that a standalone export racing a live job can miss its later writes.
- [x] 5.5 On `AccessDenied`, name the denied action and `baas admin deployment setup`.
- [x] 5.6 Tests against a fake `ExportAws`:
      - the default and single-part selections;
      - `--without-inputs`;
      - `releases/` and `image-builds/` never listed;
      - an escaping key;
      - a size mismatch with no manifest written;
      - an interrupt leaving no manifest;
      - a non-empty directory refused;
      - no extension deployed;
      - the `AccessDenied` hint.

## 6. `baas admin deployment import`

- [x] 6.1 Add the command under `DeploymentCommand`, taking `<bundle>` and `--parts`, which reuses
      the `Part` vocabulary and its help descriptions from 4.3 and defaults to the bundle's parts.
      Refuse, before any write:
      - a bundle without a manifest;
      - an unknown `schemaVersion`;
      - a part the bundle lacks;
      - a target whose table or bucket is missing (naming setup);
      - a run with no terminal.
- [x] 6.2 Print the source and the target (deployment, account, region) with the parts and counts, and
      require the target name typed back.
- [x] 6.3 Load the parts:
      - `results`: one `PutItem` per line with `attribute_not_exists(pk)`.
      - `recordings`: one `PutObject` per file with `If-None-Match: *`, never under `releases/`.
      - Report the written and skipped counts for each.
- [x] 6.4 Extension hand-off: when the manifest's hash equals the target's deployed hash, say so.
      Otherwise print the `admin image build --extension <bundle file>` command for the target.
- [x] 6.5 Tests against the fake:
      - `--parts` selects a subset of a full bundle: only `results`, only `recordings`, only
        `extension`, and no unselected part is written or acted on;
      - an unknown part exits 2 before any AWS call;
      - a re-run skips everything;
      - a target item is never replaced;
      - a cross-deployment import prompts with both names;
      - every refusal in 6.1;
      - all three extension cases.
- [x] 6.6 An IT on LocalStack: create a table and a bucket, seed them with real-shaped items (a
      measurement with `params`, a job item) and objects, export, import into a second table and
      bucket, and compare them item by item and object by object. Then run the import again and
      assert that everything is skipped.

## 7. Teardown

- [x] 7.1 Remove `--yes`. Without an interactive console, refuse with exit 1, saying a terminal is
      required.
- [x] 7.2 Delete `saveExtension`, `extensionSavedNotice` and the refusal for a failed save. Name the
      extension in the notice when the stack parameter holds one.
- [x] 7.3 Add the export offer after the notice and before the confirmation: `[Y/n]`, then the
      directory with the export's default. An accepted offer runs the full export, and a failure
      refuses with nothing deleted.
- [x] 7.4 Move every teardown test from `--yes` to scripted answers (`TeardownSequenceTest`,
      `TeardownNoticeTest`, `TeardownConfigFileTest`, `DeploymentOptionTest`). Add tests for:
      - `--yes` is an unknown option;
      - no terminal deletes nothing;
      - an accepted export completes before `emptyBucket`;
      - a failed export means no confirmation is asked and nothing is deleted;
      - a declined offer writes no file even with an extension deployed.
- [x] 7.5 Extend the `TeardownSequenceTest` order pin with the export step.

## 8. Documentation

- [x] 8.1 Update CLAUDE.md:
      - *Results table*: "Data leaves a deployment before a teardown through export" becomes the
        command's name, with no more "queued change".
      - The deployer-policy paragraph: `PutObject` is granted by name for import, and `s3:Put*`
        stays excluded.
      - The extension invariant: export carries the extension, and teardown saves nothing.
      - Teardown is terminal-only.
      - Add a short entry for the bundle and the conditional import under *What isn't there*, or
        beside the S3 layout: no overwrite mode, `releases/` never exported.
- [x] 8.2 Update `infra/README.md`'s teardown steps: no `--yes`, and the export offer.
- [x] 8.3 Update `docs/diagrams/baas-teardown.mmd` (offer, then confirmation) and
      `baas-lifecycle.mmd` (export and import arrows). Render each with `mmdc` into the scratchpad
      and look at the PNG before committing.
- [x] 8.4 Delete U2's row and its *export-before-teardown* section from
      `docs/review/open-findings.md`.
- [x] 8.5 Update `openspec/changes/QUEUE.md`: remove row 1 at archive time. In the deferred
      two-deployment check, the teardown step is run by a person at a terminal. *Row 1 now points at
      the live check; deleting it is left to the archive.*
- [x] 8.6 Mark `brainstorm.md` superseded by `design.md` with a one-line header, keeping the file.

## 9. Live verification on a second deployment (manual: needs a person at a terminal)

- [ ] 9.1 Before attaching the new policy, run `baas --deployment baas-development admin deployment
      export --parts results` under the old one and confirm the `dynamodb:Scan` and setup hint.
- [ ] 9.2 Re-run `admin deployment setup` for `baas-development`, attach the handed-over policy
      customer-managed, then check that setup's preflight passes.
- [ ] 9.3 Run `admin image build` with an extension, then a `fake-jmh-benchmarks` job
      (`jmh-with-async`, so there are profiler artifacts).
- [ ] 9.4 Teardown at a terminal: accept the export offer into a named directory. Confirm that the
      manifest's counts match `baas results query --format json | jq length`, that
      `baas jobs list` was captured before the teardown, and that the extension file exists. Then
      confirm the teardown completed.
- [ ] 9.5 `admin deployment setup`, then `admin deployment import <bundle>`. Confirm that
      `results query`, `jobs list` and `jobs download <jobId>` show the exported data. Run
      `admin image build --extension <bundle>/runner-image-extension.yaml` and confirm the label
      carries the same `+ext.<hash>`.
- [ ] 9.6 Run the import again and confirm everything is reported as skipped.
- [ ] 9.6a Import the same bundle into a second set-up deployment with `--parts results`, and confirm
      the table is loaded while the bucket holds no `jobs/` key and no extension command is printed.
- [ ] 9.7 Record each result in `verify.md`.

## 10. Verify

- [ ] 10.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory: a
      requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any
      deviation from the design or tasks.
