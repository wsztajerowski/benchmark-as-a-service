# Proposal

## Why

Nothing stops an older `baas` from operating a deployment that a newer one set up. The worst case is
an older CLI's `admin deployment setup`, which submits its own, older template and silently reverts
the stack — the CLI-wide form of the base revert ADR 0004 closed for the image (U39). The milder
cases are the ones the README can only warn about in prose ("Upgrade every `baas` that points at the
deployment"): an older CLI misreading items a newer one writes. And when a teammate *is* behind,
nothing tells them, or tells them how to catch up.

## What Changes

- The core stack records the version of the CLI that last ran `baas admin deployment setup` on it,
  in a new stack parameter. Only setup writes it; every other stack update carries it forward.
- Every command that reaches AWS reads that version first and **refuses** when the running CLI is
  older, patch level included — exit 1, before any other AWS call, printing the one command that
  updates any installation (`curl … /releases/latest/download/install.sh | sh`). `baas jobs
  terminate` is exempt: stopping a paid instance never waits on an install. Local `config list`,
  `config show` and `config set` make no AWS call and are unaffected.
- A CLI **newer** than the recorded version runs, and prints one stderr hint naming the next step:
  under `admin`, run setup; elsewhere, ask the deployment's admin to.
- An unreleased build (`0.0.0-semantically-released`: CI's reactor build, a developer's alias) is not
  checked and says so on stderr. Setup from one leaves the recorded version as it was.
- After its stack step, setup announces when it raised the recorded version — CLIs older than the new
  one now refuse the deployment. It asks nothing.
- **BREAKING** (installer): `install.sh --update` and the installer copy stored beside the CLI are
  removed. Updating is re-running the published installer, which the refusal prints. `--uninstall`
  still removes a stored copy an earlier release left behind.
- The stale CLAUDE.md claim that every `baas` is an alias onto a reactor build is corrected.

The installer change does not make the release `major`: the CLI's command line and the stack's
contract stay compatible, and an older stored installer's `--update` keeps working, because it hands
off to the newest release's installer in plain install mode.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `cli-distribution`: the update mode and the stored installer copy are removed; uninstallation still
  removes a stored copy left by an earlier release.
- `core-stack-provisioning`: the core stack declares the recorded CLI version as a parameter, written
  only by setup.
- `cli-command-structure`: every command that reaches AWS checks the running CLI against the recorded
  version — refusing an older one, hinting for a newer one, warning for an unreleased one — except
  `jobs terminate`.
- `guided-setup`: setup refuses an older CLI before changing anything, records its own version in the
  stack step, and announces a raised version.

## Impact

- **Code:** `baas-cli` — the two sessions (`OperatorSession`, `DeployerSession`) gain the check, so
  every command that builds clients through them is covered; `jobs terminate` uses an unchecked
  factory. `BaasVersion` gains a numeric comparison. Setup's names and stack steps read and write the
  parameter. `scripts/install.sh` loses its update mode, `store_installer`, `installed_version`,
  `latest_tag`, `version_newer` and two test seams; `scripts/tests/version-compare-probe.sh` is
  deleted and `install-test.sh` / `install-test.yml` lose their update cases.
- **Infrastructure:** `cf-template-core.yaml` gains one parameter, `BaasCliVersion`, `Default: ""`,
  constrained to `x.y.z` or empty. It creates no resource. No IAM change: the operator role already
  holds `cloudformation:DescribeStacks` on its own stack, the deployer policy too.
- **Cost:** none. One extra `DescribeStacks` per command (`baas run` already makes one); no new
  resource, no standing cost.
- **Docs:** README (install/update), `infra/README.md`, CLAUDE.md (the installer invariant, the
  reactor-alias line), the setup sequence diagram.
- **Release notes:** CLIs released before this change ignore the parameter, so it protects nobody
  until every operator has updated once and an admin has run setup with the new release.
- **Deliberately not changed:** the runner, the image and user-data — results stay comparable with
  every earlier run. How a reactor build obtains its runner (`--runner-jar` stays required). The
  deployer and operator policies. `admin image build`'s own base-version refusal (ADR 0004), which
  still applies on top. No `baas update` command and no self-update: the command tree gains nothing.
  No check against GitHub's newest release — only against the deployment.
- **Findings:** closes none in `docs/review/open-findings.md`; it hardens the README's upgrade
  warning and generalises ADR 0004's direction rule (U39) from the image base to the whole CLI.
