# Proposal

## Why

`baas` has a deep command tree (`baas [--deployment X] [admin] <noun> <verb>`) and long option
names (`--benchmark-jar`, `--instance-type`, `--watchdog-margin`). Today none of it completes on Tab,
so users have to remember it or run `--help` every time. Picocli, which the CLI already uses, ships a
generator that turns the command tree into a bash/zsh completion script. Generating it from the
installed jar costs nothing at run time and needs no new code in the CLI.

## What Changes

- `scripts/install.sh` generates a bash/zsh completion script from the jar it has just installed
  and places it beside that jar in `~/.local/share/baas/`. Every install and update regenerates
  it, so it always matches the installed version.
- The installer prints the lines that load the script in bash and in zsh (zsh also needs
  `bashcompinit`). As with `PATH`, it never edits a shell configuration file.
- If generation fails, the installer prints a warning and removes any older script, rather than
  leave one that describes a different version. The install itself still succeeds, because
  completion is a convenience and the CLI works without it.
- `--uninstall` removes the completion script along with the launcher and the jar.
- `install-test.yml` checks on both `ubuntu-latest` and `macos-latest` that the installed script
  completes a subcommand, and that uninstall removes it.

Deliberately **not** changed:
- **No new `baas` command.** A `generate-completion` subcommand would break the rule that nothing
  joins the top level, and the usual `source <(baas generate-completion)` line would start a JVM on
  every new shell.
- **Completion is static.** It covers commands, options and fixed lists such as the benchmark
  types. It does not offer job ids or deployment names, and nothing after `--` is completed.
- **The installer still never touches `~/.baas` or a shell rc file.**

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `cli-distribution`: the installer adds a shell completion script matched to the installed
  version, and uninstallation removes it too.

## Impact

- `scripts/install.sh`, `scripts/tests/install-test.sh`, `.github/workflows/install-test.yml`,
  `README.md` (install section), and CLAUDE.md's note on `scripts/install.sh`.
- No change to `baas-cli` code, the runner, the image, IAM or infrastructure. No AWS cost.
- Install takes one extra JVM start, about a second.
- Closes no open finding.
