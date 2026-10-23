# Spec Delta

## Purpose

Moves a deployment's data — its results table, its jobs' recordings in S3 and its runner-image
extension — to a local bundle and back, so that a deployment's history can outlive its teardown and
be loaded into another deployment.

## ADDED Requirements

### Requirement: Export writes a bundle of selectable parts
`baas admin deployment export` SHALL write the data of the deployment `--deployment` resolves to into
a local directory, the *bundle*. The data SHALL be divided into three parts, selected with
`--parts <part>[,<part>…]` and all three by default:

- `results` — the results table: every item it holds, measurement items and job items alike;
- `recordings` — every job's S3 prefix, `jobs/<project>/<jobId>/`, with everything a job wrote or
  was given there: process output, the verbatim result JSON, the environment manifest, the package
  list, logs, profiler artifacts and the job's `input/` JARs;
- `extension` — the runner-image extension the deployment's stack holds.

The command's help SHALL describe each part in those terms, and SHALL state that the bundle never
contains the pinned runner JARs under `releases/`, the image build logs under `image-builds/`, the
runner AMI, the stack, or this machine's configuration of the deployment. `--without-inputs` SHALL
leave every job's `input/` sub-prefix out of `recordings`. An unknown part name SHALL be refused
before any AWS call.

#### Scenario: Everything by default
- **WHEN** `baas admin deployment export` runs for a deployment holding measurements, jobs and an extension
- **THEN** the bundle holds the results, every job's recordings and the extension, and its manifest lists all three parts

#### Scenario: One part alone
- **WHEN** `baas admin deployment export --parts results` runs
- **THEN** the bundle holds the results and a manifest listing only `results`, and no object is downloaded from the bucket

#### Scenario: Inputs left out
- **WHEN** `baas admin deployment export --parts recordings --without-inputs` runs
- **THEN** no key under any job's `input/` is downloaded, and every other key under `jobs/` is

#### Scenario: Pinned runners and build logs are never exported
- **WHEN** the bucket holds `releases/7.2.0/benchmark-runner.jar` and objects under `image-builds/`
- **THEN** the bundle contains none of them, whatever `--parts` says

#### Scenario: The help explains the parts
- **WHEN** `baas admin deployment export --help` is rendered
- **THEN** it names `results`, `recordings` and `extension`, says what each contains, and names what a bundle never contains

#### Scenario: An unknown part
- **WHEN** `baas admin deployment export --parts results,tables` runs
- **THEN** the command exits 2 naming `tables` and the three valid parts, and makes no AWS call

### Requirement: A bundle has one fixed layout and a manifest written last
A bundle SHALL hold, for the parts exported:

- `results.jsonl` — one line per table item, each line the item's attributes in DynamoDB's typed JSON
  encoding, exactly as stored, with no attribute dropped, decoded or re-encoded through the
  measurement model;
- `recordings/<key>` — each exported object at its bucket key;
- `runner-image-extension.yaml` — the extension, preceded by the base-marker line naming its hash,
  so that a later push of the file is judged by the stale-copy guard; absent when the deployment
  holds no extension;
- `manifest.json` — a schema version, the source deployment, account and region, the export
  instant, the CLI version, the parts exported, whether inputs were left out, the item count with
  measurement items and job items separated, the object count and total bytes, and the extension's
  hash or that none was deployed.

The manifest SHALL be written only after every selected part has been written and checked: the item
count against the items written, and every object's local size against its listed size. A directory
without `manifest.json` SHALL therefore be an incomplete export.

#### Scenario: The results file carries the stored shape
- **WHEN** a measurement item with a `params` map and a non-finite score stored as absent is exported
- **THEN** its line in `results.jsonl` holds the same attributes with the same DynamoDB types, and no attribute the measurement model does not name is missing

#### Scenario: Job items are exported with the measurements
- **WHEN** the table holds measurement items and job items
- **THEN** both are in `results.jsonl`, and the manifest counts each kind separately

#### Scenario: An interrupted export leaves no manifest
- **WHEN** an export is interrupted after `results.jsonl` was written and while recordings are downloading
- **THEN** the directory holds no `manifest.json`

#### Scenario: A size mismatch fails the export
- **WHEN** a downloaded object's local size differs from its listed size
- **THEN** the export exits non-zero naming the key, and writes no manifest

#### Scenario: No extension deployed
- **WHEN** `extension` is exported from a deployment holding no extension
- **THEN** the bundle has no extension file and the manifest records that none was deployed

### Requirement: Export only reads, and writes only into a new directory
Export SHALL make no write to any AWS resource. It SHALL write into `--output-dir <dir>`, or by
default `./baas-export-<deployment>-<UTC instant, ISO basic>`, and SHALL refuse, before any AWS call,
a directory that exists and is not empty. It SHALL refuse to write any object whose key, resolved
under the bundle, would fall outside it. It SHALL need no terminal, and SHALL report on completion the
bundle's path and the manifest's counts.

#### Scenario: A non-empty directory is refused
- **WHEN** `baas admin deployment export --output-dir old-bundle` runs and `old-bundle` holds files
- **THEN** the command exits non-zero naming the directory, and makes no AWS call

#### Scenario: A key escaping the bundle is refused
- **WHEN** the bucket holds a key under `jobs/` that resolves outside the bundle directory
- **THEN** that object is not written outside the bundle, and the export fails naming the key

#### Scenario: Export runs unattended
- **WHEN** `baas admin deployment export` runs with standard input and output redirected
- **THEN** it completes without asking anything

### Requirement: Export and import run under the deployer's credentials
Export and import SHALL use the deployment's deployer credentials as every `baas admin` command
does, and SHALL NOT assume the operator role. When AWS refuses one of their calls for want of a
permission, the command SHALL name the action refused and SHALL name `baas admin deployment setup`
as the way to obtain the deployer policy that grants it.

#### Scenario: A deployment set up before this change
- **WHEN** `baas admin deployment export` runs under a deployer policy attached before export existed
- **THEN** the command exits non-zero naming `dynamodb:Scan` and `baas admin deployment setup`

### Requirement: Import loads a bundle into an existing deployment without overwriting
`baas admin deployment import <bundle>` SHALL load a bundle into the deployment `--deployment`
resolves to, never into the deployment the manifest names. It SHALL refuse, before any write, a
bundle without `manifest.json`, a manifest whose schema version it does not know, and a deployment
whose results table or bucket does not exist, naming `baas admin deployment setup` for the last.
`--parts <part>[,<part>…]` SHALL select which of the bundle's parts are imported, taking the same
part names as export and all the parts the bundle holds by default; a part not selected SHALL NOT be
written or acted on. An unknown part name SHALL be refused before any AWS call, and naming a part the
bundle lacks SHALL be refused before any write. The command's help SHALL describe the parts as
export's help does.

Every item SHALL be written only if no item with its key exists, and every object only if no object
with its key exists. Nothing SHALL be overwritten, and no option SHALL make it overwrite. Import SHALL
report how many items and objects it wrote and how many it skipped as already present. It SHALL never
write under `releases/`.

Before writing, import SHALL state the bundle's source deployment, account and region, the target
deployment, account and region, and the parts and counts it will load, and SHALL require the target
deployment's name typed back on an interactive terminal. Without a terminal it SHALL refuse and write
nothing.

#### Scenario: Restore into a fresh deployment
- **WHEN** a deployment is exported, torn down and set up again, and the bundle is imported into it
- **THEN** `baas results query` and `baas jobs list` show the exported measurements and jobs, and `baas jobs download <jobId>` fetches an exported job's recordings

#### Scenario: Re-running an interrupted import
- **WHEN** an import is interrupted and run again with the same bundle
- **THEN** the second run writes only what the first did not, and reports the rest as skipped

#### Scenario: Merging into a live deployment keeps its newer data
- **WHEN** a bundle holding an item whose key the target already holds is imported
- **THEN** the target's item is unchanged, and the item is counted as skipped

#### Scenario: Import into another deployment
- **WHEN** a bundle exported from `baas-123456789012` is imported with `--deployment wiktor-dev`
- **THEN** both deployments are named before the prompt, the user must type `wiktor-dev`, and the data is written to `wiktor-dev`'s table and bucket

#### Scenario: Incomplete bundle refused
- **WHEN** `baas admin deployment import` is given a directory without `manifest.json`
- **THEN** the command exits non-zero saying the export did not complete, and writes nothing

#### Scenario: Target not set up
- **WHEN** the target deployment's results table does not exist
- **THEN** the command exits non-zero naming `baas admin deployment setup`, and writes nothing

#### Scenario: No terminal
- **WHEN** `baas admin deployment import <bundle>` runs without an interactive terminal
- **THEN** it refuses and writes nothing

#### Scenario: Importing one part of a full bundle
- **WHEN** `baas admin deployment import <bundle> --parts results` is given a bundle holding all three parts
- **THEN** the prompt names only `results` and its counts, the table is loaded, and no object is written to the bucket and no extension command is printed

#### Scenario: Importing the extension alone
- **WHEN** `baas admin deployment import <bundle> --parts extension` runs
- **THEN** no item and no object is written, and only the extension hand-off is performed

#### Scenario: An unknown part on import
- **WHEN** `baas admin deployment import <bundle> --parts tables` runs
- **THEN** the command exits 2 naming `tables` and the three valid parts, and makes no AWS call

#### Scenario: A part the bundle lacks
- **WHEN** `--parts recordings` is given for a bundle exported with `--parts results`
- **THEN** the command exits non-zero naming the parts the bundle holds, and writes nothing

### Requirement: Import hands the extension to the image build rather than pushing it
When the imported parts include `extension` and the bundle holds an extension file, import SHALL NOT
change the target's stack. It SHALL name the bundle's extension file and print the
`baas admin image build --extension <file>` command for the target deployment. When the target
already holds an extension with the same hash, it SHALL say so and print no command.

#### Scenario: Extension into a fresh deployment
- **WHEN** a bundle with an extension is imported into a deployment that holds none
- **THEN** import prints the `admin image build --extension` command naming the bundle's file, and running it is accepted by the stale-copy guard

#### Scenario: The same extension is already deployed
- **WHEN** the target holds the extension whose hash the manifest records
- **THEN** import says the extension is already deployed and prints no build command

#### Scenario: A different extension is deployed
- **WHEN** the target holds a different extension
- **THEN** import prints the build command, and that push is refused by the stale-copy guard naming the deployed hash
