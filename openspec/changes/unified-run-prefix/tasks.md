# Tasks — unified run prefix

## 1. Verify blocking assumptions

- [x] 1.1 Download the current `benchmark-runner.jar` release asset and read
      `META-INF/maven/pl.wsztajerowski/benchmark-runner/pom.properties` from it. Confirm it carries
      `0.0.0-semantically-released`. If it already carries a real version, the `release.yml` premise
      in design.md is wrong and section 2 collapses to publishing the CLI JAR and the checksum.
      **Confirmed 2026-09-04** against release `v1.6.2`: `pom.properties` reads
      `version=0.0.0-semantically-released` and the manifest carries no `Implementation-Version`.
      design.md's premise holds — section 2 was necessary, not over-built.
- [x] 1.2 Confirm no release asset named `baas-cli.jar` exists on any published release, and that
      `unzip -p baas-cli/target/baas-cli.jar META-INF/MANIFEST.MF` shows no `Implementation-Version`.
      **Confirmed 2026-09-04**: `v1.6.2` publishes exactly one asset, `benchmark-runner.jar`. The
      second clause is now superseded by its own fix — 2.1 deliberately added
      `Implementation-Version` to the CLI shade, so the local JAR carries it by design. The
      published runner JAR having none corroborates the pre-change state the clause was checking.
- [x] 1.3 Confirm `git rev-parse --git-common-dir` resolves to the main repository from a linked
      worktree and is a no-op for an ordinary clone, on the git version in use.
- [x] 1.4 Confirm `requestId-index` uses `ProjectionType: ALL`, so resolving a run identifier to its
      `resultPath` needs one query and no follow-up `GetItem`.
- [x] 1.5 Confirm `RunnerRole` and the operator policy grant bucket-wide `s3:PutObject`/`GetObject`,
      so `releases/*` needs no new IAM statement. If either is prefix-scoped, the proposal's "no new
      IAM" claim is wrong and must be corrected before proceeding.
- [ ] 1.6 Inventory the bucket and table: how many runs exist, which projects they span, how many
      items carry `pk = RESULT#unknown`, and whether every item's `resultPath` matches the historical
      `<branch>/<type>/<timestamp>` shape. Record the counts — section 11 checks against them.
      **Do this before 12.3, not merely before 11.4:** section 12's manual runs write new items in
      the new layout, so a count taken after them is not a pre-migration baseline and 11.4 has
      nothing honest to compare against. There is no second chance to observe the pre-cutover state.
- [x] 1.7 Confirm the four Image Builder and bucket facts pinned by `CoreTemplateTest` so section 8
      knows exactly which assertions move.

## 2. Release pipeline (prerequisite — nothing downstream works without it)

- [x] 2.1 Add `<Implementation-Version>${project.version}</Implementation-Version>` to `baas-cli`'s
      shade `ManifestResourceTransformer`, which currently declares only `mainClass`.
- [x] 2.2 Add a `BaasVersion` accessor reading the manifest, returning the placeholder unchanged when
      it is absent, with a unit test for both branches.
- [x] 2.3 In `release.yml`, extend `prepareCmd` so it runs `versions:set`, then rebuilds the shaded
      JARs, then writes `benchmark-runner.jar.sha256` next to the runner JAR.
- [x] 2.4 Add `baas-cli/target/baas-cli.jar` and `benchmark-runner/target/benchmark-runner.jar.sha256`
      to the `@semantic-release/github` asset list.
- [ ] 2.5 Land section 2 and cut a real release before starting section 12's manual verification —
      that section cannot be exercised without one. **The release gate sits behind the merge, not in
      front of it:** `release.yml` triggers only on `push: branches: [main]`, so cutting a release
      means merging this branch to `main` first. Section 12 therefore cannot run on the feature
      branch, and the change's closing work — 11.7's script deletion and the finalize/archive
      artifacts — happens after that merge rather than before it.

## 3. Run identity in `baas-model`

- [x] 3.1 Add `RunId` with `generate()` (UTC instant, ISO-8601 basic, milliseconds, `-`, 8 hex from
      `SecureRandom`) and `generate(Instant)` for a caller-supplied instant.
- [x] 3.2 Add `RunLayout` owning `runs/<project>/<runId>` and its `input/` sub-prefix. No other module
      concatenates a run prefix.
- [x] 3.3 Unit-test: fixed 28-character width; alphabet excludes `#` and `/`; lexicographic order
      equals chronological order across month and year boundaries; two ids from one instant differ.
- [x] 3.4 Unit-test that a `RunId` placed in the last field of a `ResultKeys` sort key leaves the
      field count unchanged.
- [x] 3.5 Add `TagKeys.BRANCH`, include it in `KNOWN`, and leave it out of `MACHINE_OBSERVED` — it is
      caller-supplied like `project` and `commit`.

## 4. Runner

- [x] 4.1 Add `--created-at` to `ApiCommonSharedOptions`, defaulting to `Instant.now()`, and thread it
      to the stored `createdAt` so one run's measurements share one instant.
- [x] 4.2 Replace the `--request-id` default `Instant.now().toString()` with `RunId.generate()`.
- [x] 4.3 Replace the result-path default `Path.of(requestId)` with `RunLayout.of(project, runId)`.
      `--result-path` stays an override.
- [x] 4.4 Make `getProject()` fail hard instead of returning `"unknown"`, with a message naming
      `--project`. Update the tests that assert the fallback.

## 5. CLI — the run path

- [x] 5.1 Replace the two identifiers in `RunCommand` with one `RunId`, minted from a single
      `Instant.now()`, and derive the result path from `RunLayout`.
- [x] 5.2 Upload the benchmark JAR to the run prefix's `input/`, and an overridden runner JAR to the
      same place.
- [x] 5.3 Pass `--created-at` through `UserDataScriptBuilder` to the runner, capturing it into a shell
      variable first like every other manifest value.
- [x] 5.4 Add `branch` to `buildRunnerTags`, keeping it caller-overridable rather than reserved.
- [x] 5.5 Switch project derivation from `git rev-parse --show-toplevel` to `--git-common-dir`, with a
      test covering a linked worktree.
- [x] 5.6 Add `project`, `branch`, `requestId` and `createdAt` to the `environment.json` heredoc in
      `UserDataScriptBuilder` — the manifest is written by user-data, not by the runner — bumping
      `MANIFEST_SCHEMA_VERSION`. Values that can contain `"` or `\` go through `json_escape`.
- [x] 5.7 Print the `runId` on launch, and name it in the poll output, so the value a user copies is
      the one the download command takes.

## 6. CLI — runner JAR distribution

- [x] 6.1 Delete the `curl api.github.com` / `releases/latest` branch from `UserDataScriptBuilder`.
      The instance's only runner-JAR source becomes `aws s3 cp` from the bucket.
- [x] 6.2 Resolve `releases/<version>/benchmark-runner.jar` from the CLI's own version; when absent,
      fetch the release asset and its `.sha256`, verify, then upload. Never overwrite a present object.
- [x] 6.3 Make the source repository a config key with the current upstream as its default, and fail
      with a message naming the repository and version attempted. Closes **A7**.
- [x] 6.4 Hard-fail before the Maven build and before any upload when the CLI's version is the
      placeholder and no `--runner-jar` was given, naming the option that resolves it.
- [x] 6.5 Unit-test the rendered user-data contains no external host and exactly one bucket-sourced
      runner download; and that a checksum mismatch uploads nothing and launches nothing.

## 7. CLI — download and results rendering

- [x] 7.1 Accept a run identifier in the download command, resolving `resultPath` through
      `requestId-index`; keep the literal-path branch so historical paths resolve.
- [x] 7.2 Widen `REQUEST_ID` to 28 in `ResultsQueryService` and delete `truncate(…, 17)`.
- [x] 7.3 Test both download argument shapes, and that an unknown run exits non-zero creating no
      partial directory.

## 8. Infrastructure

- [x] 8.1 Set `S3MainBucket` versioning to `Suspended` and delete the `expire-uploaded-benchmark-jars`
      rule. Keep both noncurrent rules and the multipart-abort rule.
- [x] 8.2 Remove the CI role's now-dead `ci/*` `PutObject` grant from `cf-template-ci.yaml`; its
      `runs/*` grant already covers the new layout.
- [x] 8.3 Update `CoreTemplateTest`'s pinned facts, and add an assertion that no lifecycle rule expires
      current objects under `runs/`, so the rule cannot be reintroduced unnoticed.
- [x] 8.4 Confirm `S3UploadService.deleteAllObjects` still walks versions and add a test covering a
      suspended bucket that still holds versions written before suspension.

## 9. CI

- [x] 9.1 In `e2e-cloud-test.yml`, mint one run id per benchmark job (`date -u` plus
      `openssl rand -hex 4`) and pass the same instant as `--created-at`.
- [x] 9.2 Pass `--project` explicitly so CI stops writing `RESULT#unknown`, and drop `ci/` from the
      paths the workflow builds.
- [x] 9.3 Verify the two benchmark jobs no longer share a `run-status` key.
- [x] 9.4 Do not touch `exec-single-benchmark.yml` — `gha-workflow-migration-to-dynamodb` owns it.
      Coordinate merge order with that change before landing.

## 10. Documentation and findings

- [x] 10.1 Rewrite CLAUDE.md's *S3 result layout* section for `runs/<project>/<runId>/`, and update the
      *Invariants* entries that name `runs/<requestId>/`, the request-ID-scoped upload rule and the
      GitHub runner-JAR download.
- [x] 10.2 Record the new invariants: one clock read per run; the instance never contacts GitHub; the
      `releases/` slot is seeded once and never overwritten; versioning is suspended deliberately.
- [x] 10.3 Move the *Runner JAR integrity* row out of *Accepted risks* — the checksum closes it. State
      what replaced it rather than deleting the row silently.
- [x] 10.4 Mark **A7** and **A9** Fixed in `docs/review/baas-cli-findings.md`, including the status
      table at the top, with a sentence on how each was closed.
- [x] 10.5 File the `@Param` sort-key collision in `docs/review/benchmark-runner-findings.md`: one
      timestamp per run plus an unparsed `params` object means two `@Param` variants of one method
      produce an identical sort key and the second write silently wins. Pre-existing, not fixed here.
- [x] 10.6 Update `docs/diagrams/` for any command whose sequence changed, and `infra/README.md` if the
      bucket description names the retired prefixes.

## 11. History migration (after the cutover is deployed and verified)

- [x] 11.1 Write `scripts/migrate-run-layout` taking `--dry-run`, enumerating runs from the table and
      resolving each item's current `resultPath`.
- [x] 11.2 Server-side copy each run's tree to `runs/<project>/<existing-requestId>/`, then `UpdateItem`
      `resultPath`, `resultJsonKey`, `environmentJsonKey` and the profiler-output prefix. Never touch a
      partition key or sort key. Tolerate an already-expired input JAR by reporting it.
- [x] 11.3 Make it idempotent — a second run completes and changes nothing.
- [ ] 11.4 Dry-run against the real bucket and read the output by eye against 1.6's counts before the
      real pass.
- [ ] 11.5 Run it for real, then spot-check: `baas download` on a pre-change run, and a `baas results`
      row whose `resultPath` was rewritten.
- [ ] 11.6 Confirm `RESULT#unknown` items landed under `runs/unknown/` with their keys unchanged.
- [ ] 11.7 Delete the script in the same commit that records it ran.

## 12. End-to-end verification (MANUAL — no automated test drives `baas run`)

- [x] 12.1 `mvn -B clean verify` with `ASYNC_PATH` exported, since a plain `verify` silently skips the
      only async-profiler test.
- [ ] 12.2 `baas admin setup` against a real account; confirm the bucket shows suspended versioning and
      no `runs/` expiry rule.
- [ ] 12.2a **Redeploy the CI stack** (`infra/cf-template-ci.yaml`) by hand — the CLI never deploys
      it. 8.2 replaced the `WorkflowRole`'s `ci/*` `PutObject` grant with `runs/*`, but the template
      edit alone changes nothing until the stack is updated. design.md's migration plan step 3 calls
      for this; it had no task until now, which is why it went unnoticed.
      **Evidence it is outstanding:** PR #53's `e2e-cloud-test` run failed with `AccessDenied` on
      `s3:PutObject` to `runs/benchmark-as-a-service/<runId>/input/runner.jar` for
      `baas-lynx-github-actions-workflow-role`. Note this does not by itself make that workflow
      green — `GHA_EC2_PAT` is expired, and the SSM mongo gate in `exec-single-benchmark.yml`
      remains broken by the DynamoDB cutover (unclaimed work, tracked outside this change).
- [ ] 12.3 **MANUAL:** `baas run jmh -- <fake benchmark> -f 1 -wi 1 -i 3` from a released CLI. Confirm
      one prefix holds `input/`, the manifest, the output, `jmh-result.json` and `run-status`; that the
      `runId`'s instant equals the stored `createdAt`; and that `cloud-init-output.log` shows no
      request to GitHub.
- [ ] 12.4 **MANUAL:** launch a second run of the same type in the same second and confirm two distinct
      prefixes and two distinct sort keys — the A9 regression check.
- [ ] 12.5 **MANUAL:** kill a run mid-flight and confirm the prefix still identifies the project, branch
      and instant from `environment.json` alone.
- [ ] 12.6 **MANUAL:** `baas download <runId>` and `baas download <old result path>` both succeed.
- [ ] 12.7 **MANUAL:** from a reactor build, confirm `baas run` fails naming `--runner-jar` before
      building, and succeeds with it, writing nothing under `releases/`.
- [ ] 12.8 **MANUAL — comparability:** run the same benchmark that CI has history for and compare the
      score against a pre-change run. State the observed spread; CI history spans roughly 10.0M-29.6M
      ops/s on one benchmark, so a difference inside that band is not evidence of a regression, and a
      difference outside it must be investigated rather than accepted.
