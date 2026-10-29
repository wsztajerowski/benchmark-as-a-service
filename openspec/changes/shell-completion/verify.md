# Verification: shell-completion

Verified 2026-10-29 on macOS 15 (Darwin 24.6), zsh 5.9, bash 3.2.57. `openspec validate
shell-completion --strict` passes. `sh scripts/tests/install-test.sh`: 32 cases, 0 failures.

## Summary

| Dimension    | Status |
|--------------|--------|
| Completeness | 15/15 tasks (6.1 is this file), 2 requirements |
| Correctness  | 2/2 requirements implemented, 8/8 scenarios covered (one by construction, see table) |
| Coherence    | Design followed. Two deviations noted below, neither changes behaviour |

## Requirement → code → test

| Requirement / scenario | Code | Test | Gap |
|---|---|---|---|
| **Completion for the installed version** | `scripts/install.sh:167` `write_completion`, called at `:227` | | |
| Subcommands complete after installation | `picocli.AutoComplete` against `$BAAS_SHARE/baas-cli.jar` | `install-test.yml` "The installed completion script completes subcommands" (bash on both runners, zsh where present), real jar | — |
| An update replaces the completion script | stage + `mv -f` (`:171`, `:178`) | `install-test.sh` "an update replaces an existing completion script" (fake java) | Replacement by a *different real version* is not exercised; it holds by construction |
| Loading instructions are printed, never written | `:228`–`:232`, only `printf` (no write to any rc file anywhere in the script) | `install-test.sh` "a generated completion script lands beside the jar, with the lines that load it"; `install-test.yml` "Install from the fixture" greps the output | — |
| A failed generation does not fail the installation | `:182`–`:185` | `install-test.sh` "a jar that cannot generate completion still installs, warning, and drops a stale script" | — |
| *(design)* no staging file left behind | trap + `rm -f "$stage"` | `install-test.sh` "completion generation leaves no staging file behind" | — |
| **Uninstallation removes exactly what was installed** (modified) | `:242` | | |
| Uninstallation removes the completion script | as above | `install-test.sh` "uninstall removes the completion script"; `install-test.yml` uninstall step | — |
| Removes the command / scoped / earlier installer copy | unchanged | existing cases, all passing | — |

Mutation check: three edits to a copy of `install.sh` were each caught by the new cases: keeping the
stale script on failure, dropping the script from the uninstall list, and suppressing the hints.

## Blocking assumptions (section 1)

- 1.1 `picocli/AutoComplete.class` is in the shaded jar.
- 1.2 With `HOME` pointed at an empty directory, generation exits 0, prints nothing and creates only
  its `-o` file. No `~/.baas` is created.
- 1.3 Checked in both shells:
  - **bash 3.2:** checked by calling `_complete_baas` with `COMP_WORDS` set.
  - **zsh 5.9:** checked two ways.
    - Real Tab presses in an interactive `zsh -i` driven through `zsh/zpty`, with the printed
      `compinit`/`bashcompinit`/`source` lines in `.zshrc`.
    - Calling `_complete_baas` under the emulation `bashcompinit` applies (`emulate -L sh; setopt
      kshglob noshglob braceexpand`). Without it, zsh's 1-based arrays make the script's
      `currentPositionalIndex` fail with `failed to compile regex`. That only happens when the
      function is called by hand, never on a real Tab, and the CI zsh check calls it the same way
      bashcompinit does.

## Manual end-to-end check (5.1)

I built a fixture release from this branch (`versions:set 9.9.9-ci`, `package -pl baas-cli -am`),
installed it with `BAAS_BASE_URL=file://…` into scratch directories, and pressed Tab in an
interactive zsh:

| Typed | Result |
|---|---|
| `baas run --inst<TAB>` | `baas run --instance-type ` |
| `baas jobs ter<TAB>` | `baas jobs terminate ` |
| `baas run <TAB>` | `baas run j` (common prefix) |
| `baas `, `baas admin image `, `baas results ` | lists not rendered by `zpty`; candidates from the function: `admin config jobs results run query` / `build show` / `query diff` |

I also ran the four `install-test.yml` steps (install, version, completion, uninstall) locally,
copied verbatim from the workflow file, with a sandboxed `HOME`. All passed, including the zsh
branch.

## Warnings

- **W1**: The candidate *lists* (ambiguous prefixes) were not seen rendered in a real terminal,
  only through the function's output and `zpty`'s unique and common-prefix completions. To close
  it, press Tab twice on `baas ` in your own zsh after loading the script.
- **W2**: Only CI covers the `ubuntu-latest` leg (GNU userland, bash 5); nothing ran locally on
  Linux. The PR's Installer check is the evidence.

## Deviations from design / tasks

- **Hint position:** the design describes `write_completion` as following `install_jar`. It
  actually runs after `write_shim` and after the `Installed baas …` line. The hints then print
  under the success line and above the update hint, and a failed generation's warning follows
  the success it does not undo.
- **CI step order:** in `install-test.yml`, the uninstall step now runs *before* "A corrupted
  checksum fails the install". The corrupted-checksum step deletes `~/.local/share/baas`, which
  would have made the new completion-removal assertion pass vacuously.
- **Extra test:** the harness gained a fake `java` (via `BAAS_JAVA`) to reach the success path,
  because its fixture jar is not a jar. The design's "no Java-side test" decision is unaffected.

## Final assessment

No critical issues. Two warnings. Ready for archive once the PR's Installer check is green on both
runners (W2).
