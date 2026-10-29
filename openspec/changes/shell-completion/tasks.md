# Tasks

## 1. Verify blocking assumptions

- [ ] 1.1 Confirm the shaded `baas-cli.jar` carries `picocli/AutoComplete.class` (`unzip -l baas-cli/target/baas-cli.jar | grep AutoComplete`), so the jar can produce its own script
- [ ] 1.2 Run the generator with `HOME` pointed at an empty scratch directory and confirm it exits 0, creates nothing under `$HOME/.baas`, and prints nothing besides the script file
- [ ] 1.3 Confirm the generated script completes `baas jobs ` in zsh with `bashcompinit` loaded (an interactive zsh, or `zsh -i -c` with a `compadd` stub), as well as in bash; record what was used in verify.md

## 2. Installer

- [ ] 2.1 Add `write_completion` to `scripts/install.sh`: generate into `$BAAS_SHARE/.baas_completion.$$` under the same EXIT/INT/TERM trap discipline as `write_shim`, then `mv -f` to `$BAAS_SHARE/baas_completion`; verify with task 3.1
- [ ] 2.2 On a failed generation, remove any existing `$BAAS_SHARE/baas_completion`, print one `warning:` line to stderr, and keep installing; verify with task 3.2
- [ ] 2.3 After a successful generation, print the bash and zsh loading lines, using the real `$BAAS_SHARE`; verify with task 3.1
- [ ] 2.4 Add `$BAAS_SHARE/baas_completion` to `--uninstall`'s removal list; verify with task 3.3
- [ ] 2.5 Mention completion in `usage()` only if it adds an option. It should not, so confirm `--help` is unchanged

## 3. Tests

- [ ] 3.1 `install-test.yml`: after "Install from the fixture", assert that `~/.local/share/baas/baas_completion` exists. Source it in bash, simulate completing `baas jobs `, and assert the candidates include `list` and `terminate`. Assert the install output names the file. Runs on both `ubuntu-latest` and `macos-latest`
- [ ] 3.2 `scripts/tests/install-test.sh`: the fixture jar (`not-really-a-jar`) installs successfully, prints the completion warning, and leaves no `baas_completion`. Pre-seed a stale `baas_completion` and assert it is gone afterwards. Verify with `sh scripts/tests/install-test.sh`
- [ ] 3.3 `install-test.sh` and the `install-test.yml` uninstall step: assert `baas_completion` is removed and `~/.baas` is untouched

## 4. Documentation

- [ ] 4.1 `README.md` install section: one paragraph naming `~/.local/share/baas/baas_completion` and the bash/zsh lines; verify it reads correctly in the rendered README
- [ ] 4.2 CLAUDE.md: in the `scripts/install.sh` bullet, add that the installer generates the completion script from the installed jar (`picocli.AutoComplete` against `BaasApp`'s no-arg constructor), so removing that constructor silently drops completion on every install, and that the script is regenerated on every install and never published
- [ ] 4.3 Check `docs/diagrams/c4-4-deployment.mmd`. If it lists what the installer places in `~/.local/share/baas`, add the script, render it with `mmdc` to the scratchpad, and look at the PNG before committing

## 5. Manual end-to-end check

- [ ] 5.1 Manual: build a fixture release from this branch (as `install-test.yml` does), install it with `BAAS_BASE_URL=file://…` into a scratch `BAAS_SHARE`/`BAAS_BIN`, load the script in your own zsh, and press Tab on `baas `, `baas admin image `, `baas run --` and `baas run `. Record what was offered in verify.md

## 6. Verify

- [ ] 6.1 Run `/opsx:verify` and record the result in `openspec/changes/shell-completion/verify.md`: requirement → code → test → gap table, open warnings as W1, W2…, and any deviation from design or tasks
