#!/bin/sh
# baas installer. POSIX sh only — this runs on stock macOS as well as Linux.
#
# The version below is rewritten at release time by release.yml's prepareCmd, so the published
# installer names the release that published it and installs exactly that. The repository copy
# keeps the placeholder and refuses, mirroring pom.xml and RunCommand's refusal on an unreleased
# build: a checkout is the developer's special case, and it says so.
set -u

BAAS_VERSION_DEFAULT=0.0.0-semantically-released
PLACEHOLDER=0.0.0-semantically-released

BAAS_REPO="${BAAS_REPO:-wsztajerowski/benchmark-as-a-service}"
BAAS_SHARE="${BAAS_SHARE:-$HOME/.local/share/baas}"
BAAS_BIN="${BAAS_BIN:-$HOME/.local/bin}"
JAR_NAME=baas-cli.jar
COMPLETION_NAME=baas_completion

die() { printf '%s\n' "$*" >&2; exit 1; }

usage() {
    cat <<USAGE
baas installer (installs $BAAS_VERSION_DEFAULT by default)

  install.sh                    install the default version
  install.sh --version <v>      install a specific version
  install.sh --uninstall        remove baas, leaving ~/.baas untouched

To update, run the newest release's installer:
  curl -fsSL https://github.com/$BAAS_REPO/releases/latest/download/install.sh | sh

Environment: BAAS_VERSION, BAAS_REPO, BAAS_SHARE, BAAS_BIN, BAAS_JAVA
USAGE
}

MODE=install
REQUESTED_VERSION="${BAAS_VERSION:-}"

# BAAS_PROBE lets a test source this script to get its function definitions (sha256_of and verify)
# without running the installer — set by scripts/tests/version-compare-probe.sh and by
# nothing else. Every top-level executable region is guarded by it; function definitions are not,
# since defining a function has no side effect.
if [ -z "${BAAS_PROBE:-}" ]; then
while [ $# -gt 0 ]; do
    case "$1" in
        --version)
            # The error doubles as the version report, so no separate print-version flag is needed.
            [ $# -ge 2 ] || die "--version requires a version, e.g. --version 1.2.0
(this installer installs $BAAS_VERSION_DEFAULT by default)"
            REQUESTED_VERSION="$2"; shift 2 ;;
        --uninstall) MODE=uninstall; shift ;;
        -h|--help)   usage; exit 0 ;;
        *)           die "Unknown option: $1. Try --help." ;;
    esac
done
fi

resolve_version() {
    if [ -n "$REQUESTED_VERSION" ]; then
        printf '%s' "$REQUESTED_VERSION"
        return 0
    fi
    [ "$BAAS_VERSION_DEFAULT" != "$PLACEHOLDER" ] || die \
"This installer is an unreleased copy from the repository, so it names no release to install.
Pass --version <v>, or fetch the published installer:
  curl -fsSL https://github.com/$BAAS_REPO/releases/latest/download/install.sh | sh"
    printf '%s' "$BAAS_VERSION_DEFAULT"
}

# Guarded like the argument loop above: resolve_version() dies on the checked-in placeholder, and
# without this guard sourcing under BAAS_PROBE would exit before any function is reachable. Also
# scoped to `install`: --uninstall needs no version, so resolving one unconditionally would make an
# unreleased checkout's placeholder refusal block it for no reason.
if [ -z "${BAAS_PROBE:-}" ] && [ "$MODE" = install ]; then
    VERSION=$(resolve_version) || exit 1
fi

BAAS_BASE_URL="${BAAS_BASE_URL:-https://github.com/$BAAS_REPO}"

asset_url() { printf '%s/releases/download/v%s/%s' "$BAAS_BASE_URL" "$1" "$2"; }

fetch() {
    # -L because release assets redirect to a CDN host.
    curl -fsSL "$1" -o "$2" || die "Could not fetch $1
Nothing was installed."
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        # A missing tool is a failure, not a skip — the same rule the runner JAR lives under.
        die "No SHA-256 utility found (looked for sha256sum and shasum). Nothing was installed."
    fi
}

verify() {
    # sha256_of dies (via `die`, in a subshell here) when no hashing tool exists; without the
    # `|| exit 1`, only that subshell would exit, $actual would come back empty, and verify would
    # carry on to print a second, bogus "Checksum mismatch ... actual: (empty)" underneath the
    # real error.
    actual=$(sha256_of "$1") || exit 1
    # Same reasoning applies here in principle, but install_jar never reaches this with a missing
    # $2: fetch() already dies on a missing checksum asset before verify() is called. Guarded
    # anyway so a redirection failure can't double-print the way the line above just did.
    expected=$(tr -d ' \t\n\r' < "$2") || exit 1
    [ "$actual" = "$expected" ] || die "Checksum mismatch for $(basename "$1").
  expected: $expected
  actual:   $actual
Nothing was installed."
}

install_jar() {
    version="$1"
    mkdir -p "$BAAS_SHARE" || die "Cannot create $BAAS_SHARE. Nothing was installed."
    mkdir -p "$BAAS_BIN" || die "Cannot create $BAAS_BIN. Nothing was installed."
    # The staging directory sits INSIDE the destination directory on purpose: mv is only atomic
    # within one filesystem, and a rename is what lets a `baas run` already in flight keep its own
    # inode. Writing in place would corrupt a JVM that is currently babysitting a paid EC2 instance.
    stage="$BAAS_SHARE/.stage.$$"
    mkdir -p "$stage" || die "Cannot write to $BAAS_SHARE"
    # EXIT alone must not exit (it would recurse into the trap in some shells and, worse, would
    # turn a plain successful return into a non-zero exit); INT/TERM must both clean up AND stop
    # right there — POSIX only terminates a shell on a signal when no trap overrides the default
    # action, so without the explicit exit the script runs on into the now-deleted $stage.
    # shellcheck disable=SC2064
    trap "rm -rf '$stage'" EXIT
    # shellcheck disable=SC2064
    trap "rm -rf '$stage'; exit 1" INT TERM

    fetch "$(asset_url "$version" "$JAR_NAME")" "$stage/$JAR_NAME"
    fetch "$(asset_url "$version" "$JAR_NAME.sha256")" "$stage/$JAR_NAME.sha256"
    verify "$stage/$JAR_NAME" "$stage/$JAR_NAME.sha256"

    mv -f "$stage/$JAR_NAME" "$BAAS_SHARE/$JAR_NAME"
    rm -rf "$stage"
    trap - EXIT INT TERM
}

write_shim() {
    stage="$BAAS_BIN/.baas.$$"
    # Same cleanup discipline as install_jar: an interrupt mid-write must not leave a stray
    # .baas.$$ file behind in $BAAS_BIN, and INT/TERM must also stop the script right there rather
    # than run on against the file the trap just removed.
    # shellcheck disable=SC2064
    trap "rm -f '$stage'" EXIT
    # shellcheck disable=SC2064
    trap "rm -f '$stage'; exit 1" INT TERM
    cat > "$stage" <<SHIM
#!/bin/sh
# Generated by the baas installer. The Java runtime is resolved through PATH so that baas and the
# build that produced the benchmark JAR agree on a JVM; BAAS_JAVA overrides it. The guard is a
# shell builtin, so it forks nothing on any invocation.
command -v "\${BAAS_JAVA:-java}" >/dev/null 2>&1 || {
  echo "baas needs Java 25 on PATH, or BAAS_JAVA pointing at one." >&2; exit 1; }
exec "\${BAAS_JAVA:-java}" -jar "$BAAS_SHARE/$JAR_NAME" "\$@"
SHIM
    rc=$?
    [ "$rc" -eq 0 ] || die "Cannot write to $BAAS_BIN"
    chmod +x "$stage" || die "Cannot write to $BAAS_BIN"
    mv -f "$stage" "$BAAS_BIN/baas" || die "Cannot write to $BAAS_BIN"
    trap - EXIT INT TERM
}

write_completion() {
    # Generated from the jar just installed rather than published beside it: the script then
    # describes exactly the command tree this installation runs, and there is no second asset to
    # verify. picocli's generator builds the tree through BaasApp's no-arg constructor.
    stage="$BAAS_SHARE/.$COMPLETION_NAME.$$"
    # shellcheck disable=SC2064
    trap "rm -f '$stage'" EXIT
    # shellcheck disable=SC2064
    trap "rm -f '$stage'; exit 1" INT TERM
    if "${BAAS_JAVA:-java}" -cp "$BAAS_SHARE/$JAR_NAME" picocli.AutoComplete -f -n baas \
            -o "$stage" pl.wsztajerowski.baas.BaasApp >/dev/null 2>&1 \
        && mv -f "$stage" "$BAAS_SHARE/$COMPLETION_NAME"; then
        rc=0
    else
        # Completion is a convenience, so this never fails the install — but a script left from an
        # earlier installation would complete commands this version may not have.
        rm -f "$stage" "$BAAS_SHARE/$COMPLETION_NAME"
        printf 'warning: could not generate shell completion; baas is installed without it.\n' >&2
        rc=1
    fi
    trap - EXIT INT TERM
    return "$rc"
}

java_major() {
    # java -version writes to stderr, and reports "1.8.0_402" for 8 but "25.0.4" for modern
    # releases — both shapes have to parse.
    raw=$("${BAAS_JAVA:-java}" -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9][0-9]*\)\.*.*/\1/p')
    [ -n "$raw" ] || return 1
    if [ "$raw" = "1" ]; then printf '8'; else printf '%s' "$raw"; fi
}

check_prerequisites() {
    command -v "${BAAS_JAVA:-java}" >/dev/null 2>&1 \
        || die "baas needs Java 25. No java found on PATH (set BAAS_JAVA to override).
Nothing was installed."
    major=$(java_major) || die "Could not determine the Java version. Nothing was installed."
    [ "$major" -ge 25 ] 2>/dev/null \
        || die "baas needs Java 25; found Java $major. Nothing was installed."

    command -v git >/dev/null 2>&1 \
        || printf 'warning: git not found. baas run derives project, commit and branch from git;\n         pass --project, --commit and --branch instead.\n' >&2

    case ":$PATH:" in
        *":$BAAS_BIN:"*) ;;
        *) printf '\n%s is not on your PATH. Add it:\n\n    export PATH="%s:$PATH"\n\n' \
               "$BAAS_BIN" "$BAAS_BIN" ;;
    esac
}

# Guarded like the two regions above: sourcing under BAAS_PROBE must define functions only.
if [ -z "${BAAS_PROBE:-}" ]; then
case "$MODE" in
    install)
        check_prerequisites
        install_jar "$VERSION"
        write_shim
        printf 'Installed baas %s to %s\n' "$VERSION" "$BAAS_BIN/baas"
        # Printed, never written into an rc file — the same stance as the PATH hint. Printed on
        # every install because the installer cannot tell whether the rc already sources it.
        if write_completion; then
            printf '\nTab completion: %s. Load it from your shell rc:\n' "$BAAS_SHARE/$COMPLETION_NAME"
            printf '  bash (~/.bashrc):\n    source "%s"\n' "$BAAS_SHARE/$COMPLETION_NAME"
            printf '  zsh (~/.zshrc):\n    autoload -U +X compinit && compinit\n'
            printf '    autoload -U +X bashcompinit && bashcompinit\n    source "%s"\n\n' \
                "$BAAS_SHARE/$COMPLETION_NAME"
        fi
        printf 'To update later: curl -fsSL https://github.com/%s/releases/latest/download/install.sh | sh\n' \
            "$BAAS_REPO"
        ;;
    uninstall)
        removed=0
        # $BAAS_SHARE/install.sh is no longer written: earlier releases stored a copy of their
        # installer there for an --update mode that is gone. Removing it is what leaves an
        # installation made by one of them clean.
        for target in "$BAAS_BIN/baas" "$BAAS_SHARE/$JAR_NAME" "$BAAS_SHARE/$COMPLETION_NAME" \
                "$BAAS_SHARE/install.sh"; do
            if [ -e "$target" ]; then rm -f "$target"; removed=$((removed + 1)); fi
        done
        # rmdir, never rm -rf: it removes the directory only when this installer's files were the
        # last things in it, so anything a user put there survives. BAAS_BIN (e.g. ~/.local/bin) is
        # not touched the same way — it is a shared directory other programs install into, and
        # nothing here ever creates it purely for baas's own use the way $BAAS_SHARE is.
        [ ! -d "$BAAS_SHARE" ] || rmdir "$BAAS_SHARE" 2>/dev/null || true
        if [ "$removed" -eq 0 ]; then
            printf 'No baas installation found under %s.\n' "$BAAS_SHARE"
        else
            printf 'Removed baas. Your configuration in ~/.baas was not touched.\n'
        fi
        ;;
esac
fi
