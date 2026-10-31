---
paths:
  - ".github/**"
  - "scripts/**"
---

# CI, release and the installer

Only `e2e-cloud-test.yml` and `install-test.yml` exercise real behaviour; nothing else in CI renders or runs it.

- **`e2e-cloud-test.yml` drives `baas run` end to end, and it is the only thing that does.** Two
  `ubuntu-latest` CI jobs, each launching its own instance: `benchmark` runs `jmh` with
  `-prof async:…;output=flamegraph` against `fake-jmh-benchmarks` on the real runner AMI, so a bad
  bake fails CI rather than surviving it, and fails unless a flame graph names the benchmark method — a
  graph *file* alone passed while `flamegraph,jfr` wrote empty ones (E1, closed by
  `benchmark-args-pass-through`); `jcstress` runs `fake-stress-tests` in JCStress sanity mode
  (`-- -m sanity`) and asserts the
  stored item's shape only, never pass/fail counts — the fixture's `TestWithForbiddenResults` races
  nondeterministically. It is path-filtered on `pull_request` plus `workflow_dispatch` because it
  provisions a paid instance per CI job per triggering event, and it tags
  itself `hidden=true` — which is why `queryByJobId` carries no hidden
  filter. **The path filter bounds it per PR, not per push:** GitHub evaluates a `pull_request`
  path filter against the PR's cumulative diff, so once a PR touches `baas-cli/**` *every*
  subsequent push to it launches another instance, including one that only edits an unrelated
  workflow. Accepted (2026-09-20) — cents per push, and a measurement per revision is worth
  having. `cancel-in-progress` stays `false` deliberately: cancelling mid-`baas run` can kill the
  CLI before its shutdown hook fires, leaving the shell watchdog as the only termination layer,
  which is the red-CI-job-and-full-bill failure this design exists to remove. In-process,
  `JobLaunchTest` and `RunCommandLaunchTest` drive whole launches, success path included, against
  `FakeRunAws`; what only this job proves is the real AWS behind the port.
- **`scripts/install.sh` is the one script CI does invoke.** `release.yml`'s `prepareCmd` `sed`s the
  released version into it and publishes it as a release asset, but `install-test.yml` never installs
  that asset: it builds a fixture release in the CI job (`BAAS_BASE_URL: file://…/fixture`) and runs
  the working-tree installer against it, on `ubuntu-latest` and `macos-latest`. No CI job exercises
  a published installer or the release-time `sed` bake — those job only during a real release. The
  other utilities under `scripts/` still have no CI coverage. **It also generates the bash/zsh
  completion script** (`~/.local/share/baas/baas_completion`) by running `picocli.AutoComplete`
  against the jar it just installed. That goes through `BaasApp`'s public no-arg constructor, and
  through a default-constructible class for every subcommand: lose either and every install only
  warns and ships no completion. `install-test.yml` is what turns that into a red PR. The script is
  regenerated on every install and never published — there is no `baas` command for it, since
  `source <(baas …)` would start a JVM in every new shell.
- **The installer installs released artifacts only, and the repository copy refuses.**
  `scripts/install.sh` carries `BAAS_VERSION_DEFAULT`, rewritten at release time by `release.yml`'s
  `prepareCmd` and never committed back. A checkout copy holds the placeholder and exits naming
  `--version`, the same no-fallback stance `RunCommand` takes on an unreleased build. There is no
  `--update` and no installer copy stored beside the jar (`cli-version-floor`): updating is running
  the newest release's published installer, the line the version refusal prints, so the script
  installing version X is always version X's own. `--uninstall` still deletes a stored
  `install.sh` an earlier release left; that copy's `--update` keeps working, because it hands off
  to the newest installer in plain install mode.

GHA values whose origin isn't obvious from the workflow files. All are plain `vars.`, no secrets:
a role ARN is not sensitive, and nothing else is left to hold.

| Name | Source |
|---|---|
| `OPERATOR_ROLE_ARN` | Core stack output `OperatorRoleArn` — the role CI federates into directly |
| `AWS_REGION` | Any region, for the credential exchange only — the CLI finds the deployment's region from its bucket. CI names no deployment: the CLI takes it from the operator role |
