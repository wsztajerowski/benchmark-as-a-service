# Proposal

## Why

A teardown deletes everything a deployment holds: the bucket, the results table and the stack that
carries the runner-image extension. Since `jobs-command` nothing is retained, and no `baas` command
can keep any of it (U2). The only way today is the hand-run `aws s3 sync` and
`aws dynamodb scan` used on 2026-10-10. Nothing can load that scan back into a deployment. Teardown
also saves the extension on its own, as an unrequested side effect, which leaves one part of the
deployment's data in a second place with a second set of rules.

## What Changes

- **New `baas admin deployment export`.** It writes a deployment's data to a local directory, called
  a *bundle*. A bundle has three parts, each optional through `--parts`, all of them by default:
  - `results`: the results table, meaning every measurement item and every job item, verbatim, as
    DynamoDB JSON Lines.
  - `recordings`: every job's S3 prefix `jobs/<project>/<jobId>/`. That covers process output,
    `jmh-result.json`, `environment.json`, `packages.txt`, logs, profiler artifacts and the job's
    `input/` JARs. `--without-inputs` leaves out the `input/` JARs.
  - `extension`: the runner-image extension the stack holds, with its base marker. The bundle has no
    extension file when the deployment holds no extension.

  The bundle never holds `releases/` (the CLI seeds it again), `image-builds/`, the AMI, the stack,
  or this machine's deployment file. A `manifest.json` records the source deployment, account,
  region, the parts exported and their counts.
- **New `baas admin deployment import <bundle>`.** It loads a bundle into the deployment that
  `--deployment` resolves to, which must already be set up. `--parts` chooses which of the bundle's
  parts to load, with the same names as export, and defaults to every part the bundle holds. Every
  write is conditional: an item or
  object that already exists is skipped and never overwritten. So an interrupted import can be
  re-run, and merging into a live deployment cannot clobber anything. The `extension` part is not
  pushed. Import names the bundle's file and prints the `admin image build --extension` command that
  pushes it, and the existing stale-copy guard then decides whether the push is accepted.
- **Teardown saves nothing, and offers to export first.** The extension rescue and its refusal are
  removed. Teardown's notice names the extension when one is deployed. Before the typed
  confirmation, teardown offers to run the export into a default directory. A failed export stops
  the teardown before anything is deleted.
- **BREAKING: `baas admin deployment teardown --yes` is removed.** Teardown runs only on an
  interactive terminal. Without one it refuses and deletes nothing, so every teardown passes through
  the export offer. Nothing in CI or `scripts/` runs a teardown.
- **The deployer policy gains `dynamodb:Scan` and `dynamodb:PutItem` on the results table, and
  `s3:PutObject` on the bucket.** These are the reads export needs and the conditional writes import
  needs. The operator role's policy is unchanged.

## Capabilities

### New Capabilities

- `deployment-export`: the export and import commands, the bundle's parts and layout, the manifest,
  the conditional import, and the extension's hand-off to `admin image build`.

### Modified Capabilities

- `core-stack-provisioning`: *Teardown safety gates* confirms only by the typed name on a terminal,
  offers the export, and no longer has `--yes`. A new requirement covers the deployer grants for
  export and import. Every teardown scenario written with `--yes` is restated without it.
- `cli-command-structure`: `admin deployment` gains the verbs `export` and `import`, and the
  teardown scenarios lose `--yes`.
- `results-store-schema`: the history-leaves-with-teardown scenario is restated without `--yes`.

## Impact

- **Code.** `baas-cli`'s `deployment` slice gets two commands, and AWS calls go behind a port
  (`ExportAws`) in the shape of `TeardownAws`. `TeardownCommand` loses `--yes` and `saveExtension`
  and gains the export offer. Teardown's tests move from `--yes` to the existing answer-reader seam.
  `deployer-policy.json` gains three actions. `JobsDownloadSubcommand.destinationFor`'s path-escape
  guard is reused for the mirror. A hand-written `AttributeValue` ↔ JSON codec is added.
- **Docs.** CLAUDE.md's teardown and extension statements are updated, along with *Results table*
  ("data leaves … through export") and the deployer-policy sentence saying the deployer has "no
  business holding `PutObject`". The diagrams `baas-teardown.mmd` and `baas-lifecycle.mmd` change.
  `infra/README.md`'s teardown instructions change. U2 is deleted from
  `docs/review/open-findings.md`. QUEUE.md's deferred two-deployment check is now run by a person,
  since the agent's shell has no terminal.
- **IAM.** Every existing deployment's attached deployer policy lacks the new actions until a
  re-run of `admin deployment setup` hands over the new document. Until then, export and import fail
  with `AccessDenied`, which they translate into that hint. The setup preflight probes the new
  actions automatically, because it reads the rendered policy. The rendered policy grows by 50
  non-whitespace characters, from 4936 to 4986, against the 5632 budget.
- **Cost.** There is no standing cost: no point-in-time recovery and no new resource. An export pays
  for one table scan (on-demand reads, fractions of a cent at this size), S3 GET requests, and S3
  data transfer out to the internet at about $0.09/GB for `recordings`, which is the only
  non-trivial line. An import pays for PUT requests and on-demand writes.
- **Deliberately not changed.** The operator role gains nothing: day-to-day credentials still
  cannot write measurements, and S2 widens nothing here. Teardown still deletes everything it
  deletes today, in the same order, with the in-flight gate first. `releases/<version>/` stays
  write-once: import never writes it. The stored item shape, `ResultKeys`, `JobLayout`, the runner,
  user-data and the image are untouched, so no stored result changes meaning and comparability is
  unaffected. The extension's stale-copy guard is reused as it is.
- **Closes** U2.
