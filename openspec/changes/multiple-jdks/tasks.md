# Tasks

## 1. Verify blocking assumptions

- [ ] 1.1 In an `amazonlinux:2023` container, install `java-25-amazon-corretto-devel` at the version
      `infra/runner-image.yaml` pins and confirm `jcmd` is in the same `bin` as the `java` that
      `alternatives` selects (`$(dirname $(readlink -f $(which java)))/jcmd`); record it in `verify.md`. If it
      is not, stop and revisit "The base installs Corretto's full JDK"
- [ ] 1.2 In the same container, run the fixture JAR with `-prof jfr` on that Corretto and confirm a
      `profile.jfr` is written (no "Cannot find jcmd anywhere"); record it
- [ ] 1.3 Unpack a Temurin 26 archive under `/opt/jdks/temurin-26` there and run the fixture JAR as
      `/opt/jdks/temurin-26/bin/java -jar … -f 1`, with Corretto as `java` on `PATH`; confirm from JMH's
      `# VM invoker` that the fork ran on Temurin, and that JCStress (`-m sanity -t <one test>`) does too;
      record both
- [ ] 1.4 Confirm an EC2 AMI tag value accepts 256 characters including `,` and `.` (AWS documents it;
      record the limit with its source)

## 2. Naming unification (BREAKING)

- [ ] 2.1 `TagKeys`: `JDK` → `JDK_VERSION` (`jdkVersion`), `JVM_VENDOR` → `JDK_VENDOR` (`jdkVendor`), add
      `JDK_ID` (`jdkId`), all machine-observed and reserved; `TagKeysTest` and `RunCommandTest` cover the
      reserved list
- [ ] 2.2 `UserDataScriptBuilder`: manifest group `jvm` → `jdk` with `vmName`, `schemaVersion` 8, tag
      placeholders for the new keys; `UserDataScriptBuilderTest` pins the rendered names and the size bound
- [ ] 2.3 `EnvironmentManifest.GROUPS`, `EnvironmentView` (`jdk.vendorVersion`, `jdk.id`), `jobs show` and
      `jobs diff` read the new group names; their tests updated
- [ ] 2.4 `ResultsDiff.WARNED_TAGS` and every other reader of the old tag names (`git grep -e '"jdk"'
      -e jvmVendor` in `src/main`) use the new keys; a grep finds no old name outside JMH's own fields

## 3. The base image

- [ ] 3.1 `infra/runner-image.yaml`: Corretto package → `java-25-amazon-corretto-devel` at the same build,
      `imageVersion` bumped; `RunnerImageDefinitionTest` passes
- [ ] 3.2 `RunnerImageRenderer` base: link Corretto as `/opt/jdks/corretto-25` and install
      `baas-install-jdk` on `PATH` (download, `sha256sum -c`, unpack into a temporary directory, refuse an
      existing id or no `bin/jcmd`, rename into place); renderer tests pin the script and that the base stays
      within the component size limit
- [ ] 3.3 The helper refuses a wrong digest, an existing id and a JRE without `jcmd`: shell tests run the
      rendered script against local archives (a container or `bats`-free shell harness) and record exit
      codes
- [ ] 3.4 Contract: check every `/opt/jdks/<id>` (id format and length, `bin/java` runs, `bin/jcmd` exists,
      `corretto-25` present, ids within 256 characters) and write `image-builds/<label>/jdks.json`
      (`[{id, release, vendor, default}]`, default first); renderer tests pin both
- [ ] 3.5 Update the extension starter text: how to add a JDK with `baas-install-jdk`, that `/opt/jdks` is
      what `--jdk` selects, and that `java` on `PATH` is the runner's

## 4. Publishing the image's JDKs

- [ ] 4.1 `ImageBuilderService.publish` (build and adoption alike) reads `jdks.json` and tags the AMI
      `baas-jdks`; a missing list retires the image and names it; `ImageBuildAws` gains the S3 read;
      `RunnerImageBuildSequenceTest` pins the order (read and tag before the pointer moves)
- [ ] 4.2 `RunnerImage` carries the tag's ids; `ImageShowSubcommand` lists the JDKs from `jdks.json` with
      release, vendor and the default marked; tests for an image with and without the tag

## 5. Selecting a JDK

- [ ] 5.1 `RunnerArgs`/`baas-model`: a `--jdk` runner option and the default id `corretto-25` defined
      once; `RunnerArgsContractTest` passes
- [ ] 5.2 `RunCommand`: `--jdk` (before `--`, completion from nothing static), checked against the image's
      `baas-jdks` in the existing lookup; unknown id → exit 2 naming the image and the ids, before upload;
      an image without the tag refuses any `--jdk` and launches without one; unit tests on `JobLaunch` with
      `FakeRunAws` cover all three
- [ ] 5.3 `UserDataScriptBuilder`: pass the id; read `jdk` from `/opt/jdks/<id>/bin/java` and `runnerJdk`
      from `java` on `PATH`; tag `jdkId`; on an image without selection, fill `jdk` from `java` on `PATH` with
      `id`/`home` empty and no `jdkId`; tests render both
- [ ] 5.4 Runner: `JmhSubcommand`/`JCStressSubcommand` take `--jdk`, `BenchmarkProcessBuilder` starts the
      benchmark with `/opt/jdks/<id>/bin/java` (or `java` when no id), refusing an id not found there before
      running; unit tests on the command line, and an IT with a second JDK directory (the CI JDK linked under
      a temporary `/opt/jdks`-like root through a runner option or property used by tests only)
- [ ] 5.5 `BenchmarkArgs`: `-jvm` managed for JMH in every spelling, the refusal naming `--jdk`; unit tests
      in `baas-model`, the runner and the CLI; `BenchmarkArgsContractIT` still passes
- [ ] 5.6 The `-lprof` listing job passes `--jdk` like any job; `RunCommandTest` covers `--jdk x jmh -- -lprof`

## 6. `results diff` CPU warning

- [ ] 6.1 `ResultsDiff`: a `cpuModel` difference becomes its own warning, before the environment one,
      naming both models; `jdkId` joins `WARNED_TAGS`; `ResultsDiffTest` covers both lines and that stdout is
      unchanged

## 7. Docs and findings

- [ ] 7.1 `.claude/rules/results-store.md` (tags, manifest groups, the JDK/JVM naming rule),
      `runner-image.md` (`-devel`, `/opt/jdks`, the helper, `jdks.json`, `baas-jdks`), `benchmark-runner.md`
      (`-jvm` managed, `--jdk`, the perfasm gap), `user-data.md` (what reads which `java`)
- [ ] 7.2 `CLAUDE.md`: the Accepted-risks row quoting `jdk: 25.0.4 → 25.0.3` uses the new field name; add
      the JDK/JVM naming rule only if a rules file cannot hold it
- [ ] 7.3 `README.md`: `--jdk` in *Run a benchmark*, and comparing JDKs (one job per JDK, then
      `results diff`, reading its JDK and CPU-model warnings)
- [ ] 7.6 `README.md`: a *Working with the image extension* section, linked from *Build the runner
      image* and pointing to `infra/README.md` for the full workflow rather than repeating it:
      - the round trip: `admin image show --extension > ext.yaml`, edit, `admin image build --extension
        ext.yaml`; what the `# baas-extension-base:` marker guards; ASCII only, the 4096-byte limit, never
        a credential; teardown saving the extension beside the config
      - `baas-install-jdk <id> <url> <sha256>`: what it does and refuses (existing id including
        `corretto-25`, wrong digest, no `bin/jcmd`), the id format and length, and a complete one-step
        extension adding Temurin 26 as the example
      - tips: take the digest from the vendor's published `.sha256` rather than computing it from a
        download; try the snippet in an `amazonlinux:2023` container before a ~15-minute build; leave
        `java` on `PATH` and `alternatives` alone (they are the runner's), installing only under
        `/opt/jdks`; after the build, check `admin image show` lists the JDK before using `--jdk`; a JDK
        the contract rejects fails the build and the previous image keeps serving; each extra JDK adds
        ~200 MB to the snapshot
- [ ] 7.4 `docs/diagrams/baas-run.mmd`, `baas-build-image.mmd`, `baas-results.mmd`: render each edited file
      with `mmdc` and look at the PNG before committing
- [ ] 7.5 `docs/review/open-findings.md`: delete PT1 (closed); widen PT2 to jobs where only some benchmarks
      failed under `-foe false` (exit 0, result file holding only the successful variants, observed locally
      2026-10-31); add the perfasm save-location gap as a finding

## 8. End-to-end verification (manual — no automated test covers the `baas run` path)

- [ ] 8.1 On `baas-development`, with the reactor CLI: replace the Temurin extension with one using
      `baas-install-jdk temurin-26 …`, build the image, and confirm `baas admin image show` lists
      `corretto-25 (default)` and `temurin-26`, and the AMI carries `baas-jdks=corretto-25,temurin-26`
- [ ] 8.2 Run the same fixture benchmark with no `--jdk` and with `--jdk temurin-26`: tags `jdkId`,
      `jdkVersion`, `jdkVendor` and the manifest's `jdk`/`runnerJdk` describe Corretto and Temurin
      respectively, and `results diff` between them shows the JDK warning (and the CPU one when the hosts
      differ)
- [ ] 8.3 `-prof jfr` on `corretto-25` and on `temurin-26`: both store a measurement and upload `profile.jfr`
- [ ] 8.4 `--jdk temruin-26` exits 2 before any upload, listing the ids; `-- MyBenchmark -jvm /x` exits 2
      naming `--jdk`
- [ ] 8.5 `--jdk temurin-26 jcstress -- -m sanity` completes with `jdkId=temurin-26`
- [ ] 8.6 Comparability: the fixture benchmark without `--jdk` on the new base against the old base
      (1.4.0), at least three jobs each on one instance type, recording every score, the spread and each
      job's `cpuModel`; investigate any difference beyond the spread between jobs on the same CPU model
- [ ] 8.7 Second deployment (the change touches the runner image): with a deployer for a second name, run
      setup, `admin image build`, one `--jdk` job and teardown (`infra/README.md`, *A second deployment*);
      record it in `verify.md`
- [ ] 8.8 CI's e2e on the PR runs the new CLI against the default deployment's old image: confirm it passes,
      jobs carrying no `jdkId`

## 9. Rollout (after release, with the user)

- [ ] 9.1 For `baas-381492019823` and `baas-development`: export if wanted, erase current results and job
      data, run setup with 9.0.0 (raises the CLI version floor), build the image; record in `verify.md`

## 10. Verify

- [ ] 10.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory — a requirement →
      code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation from design or
      tasks
