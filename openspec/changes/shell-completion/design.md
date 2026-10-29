# Design

## Context

Picocli 4.7.7, already on `baas-cli`'s classpath and shaded into `baas-cli.jar`, includes
`picocli.AutoComplete`. Its `main` builds the command tree from a class name through the default
factory and writes one script that works in both bash and zsh. `BaasApp` has a public no-arg
constructor, so the shipped jar needs no change. Measured on 2026-10-29 against the reactor jar:

```sh
java -cp baas-cli.jar picocli.AutoComplete -f -n baas -o baas_completion pl.wsztajerowski.baas.BaasApp
```

This produced 1,205 lines with a function for every command in the tree. In stock macOS bash 3.2,
`baas `, `baas jobs `, `baas run --in` and `baas run ` completed to the nouns, the `jobs` verbs,
`--instance-type`, and the four benchmark types (from `RunCommand`'s existing
`completionCandidates`).

The installer already guarantees several things that constrain this design: it is POSIX `sh`,
replaces files atomically, prints a `PATH` hint instead of editing an rc file, and stays out of
`~/.baas` (`cli-distribution` spec). `scripts/tests/install-test.sh` installs a fixture whose jar is
the literal bytes `not-really-a-jar`, so generation always fails in that harness.

## Goals / Non-Goals

**Goals:**
- Tab completion for every command, option and fixed candidate list of the installed release.
- The script cannot drift from the jar it describes.

**Non-Goals:**
- Completing values that are only known at run time: job ids, deployment names under
  `~/.baas/deployments/`, project names.
- Completing anything after `--` (JMH/JCStress arguments).
- Shells other than bash and zsh (fish, PowerShell).
- Completion for reactor builds (`baas-dev`). A developer can run the command above against
  `baas-cli/target/baas-cli.jar` by hand.

## Decisions

### The installer generates the script; the CLI gains no command

`install_jar` is followed by a `write_completion` step, which runs the `AutoComplete` command above
with `"${BAAS_JAVA:-java}"` (already verified as Java 25 by `check_prerequisites`) against the
just-installed `$BAAS_SHARE/baas-cli.jar`. It writes to `$BAAS_SHARE/.baas_completion.$$` and moves
the file into place, using the same trap discipline as `write_shim`. Because the file is regenerated
from the installed jar on every install, an update replaces it as well.

Rejected:
- **A `baas … generate-completion` subcommand (picocli's `GenerateCompletion`)**: it would be a new
  top-level command or a forced place under `config`, and the conventional
  `source <(baas generate-completion)` starts a JVM on a 65 MB jar on every new shell.
- **Generating the script at build time and publishing it as a release asset**: that adds a second
  asset to fetch and checksum, and a build step to `release.yml`, only to get a file the installed
  jar can already produce. It would also need a separate `.sha256`, or it would be the one
  unverified download.

### A generation failure warns, removes the old script, and lets the install succeed

Completion is a convenience; the jar and launcher are the installation. Failing the whole install
over a convenience would also break the harness's fixture jar. A stale script is worse than none,
because it offers commands the installed version no longer has. So on failure the installer deletes
`$BAAS_SHARE/baas_completion`, prints one `warning:` line to stderr (the same form as the `git`
warning), and finishes normally.

### Loading lines are printed on every install, never written

Following the `PATH` precedent, the installer prints the loading lines and never edits rc files. It
prints them on every successful generation, because it cannot tell whether the user's rc already
sources the file. Tracking that, for example by printing only when the file is new, is extra
mechanism for a cosmetic gain. The lines are:

```
# bash (~/.bashrc):
    source ~/.local/share/baas/baas_completion
# zsh (~/.zshrc):
    autoload -U +X compinit && compinit
    autoload -U +X bashcompinit && bashcompinit
    source ~/.local/share/baas/baas_completion
```

The path printed is the real `$BAAS_SHARE`, not a hard-coded `~/.local/share/baas`.

### The file is named `baas_completion`

`baas_completion` is picocli's default name and the name its documentation uses, so a user
searching for help finds the matching instructions. The name also does not suggest a single shell.

### No Java-side test

The install-test CI legs run the real generation on every PR, on both platforms. A `baas-cli` unit
test would duplicate that, and it could not call `AutoComplete.main` the way the installer does
without that `main` calling `System.exit`.

## Risks / Trade-offs

- [A future command without a no-arg constructor breaks generation] → Installs then warn and ship
  no completion instead of failing. The `install-test.yml` assertion turns this into a red PR.
- [The default factory instantiates the command classes, so `BaasApp` construction runs at install
  time] → Construction reads no configuration and calls no AWS API. The check run above wrote
  nothing outside its output file. Task 1.2 confirms this with `~/.baas` absent.
- [Generating takes about a second] → Accepted.
- [A user who added the `source` line and later uninstalls gets an error from that line in every new
  shell] → Same as for any uninstalled tool. The uninstall message names the file it removed.

## Migration Plan

None. The next install or update of a release that carries this change writes the script. Rollback
is reinstalling an earlier release, whose installer neither writes nor deletes the file, so a leftover
script survives until `--uninstall`. That is harmless.
