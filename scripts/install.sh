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

die() { printf '%s\n' "$*" >&2; exit 1; }

usage() {
    cat <<USAGE
baas installer (installs $BAAS_VERSION_DEFAULT by default)

  install.sh                    install the default version
  install.sh --version <v>      install a specific version
  install.sh --update           install the newest release if newer than the installed one
  install.sh --uninstall        remove baas, leaving ~/.baas untouched

Environment: BAAS_VERSION, BAAS_REPO, BAAS_SHARE, BAAS_BIN, BAAS_JAVA
USAGE
}

MODE=install
REQUESTED_VERSION="${BAAS_VERSION:-}"

# BAAS_PROBE lets a test source this script to get its function definitions (version_newer, in
# particular) without running the installer — set by scripts/tests/version-compare-probe.sh and by
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
        --update)    MODE=update; shift ;;
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
# without this guard sourcing under BAAS_PROBE would exit before version_newer is ever reachable.
# Also scoped to `install`: --update resolves its own target version through latest_tag(), and
# --uninstall needs none, so resolving one unconditionally would make an unreleased checkout's
# placeholder refusal block --update and --uninstall for no reason.
if [ -z "${BAAS_PROBE:-}" ] && [ "$MODE" = install ]; then
    VERSION=$(resolve_version) || exit 1
fi

BAAS_BASE_URL="${BAAS_BASE_URL:-https://github.com/$BAAS_REPO}"

asset_url() { printf '%s/releases/download/v%s/%s' "$BAAS_BASE_URL" "$1" "$2"; }

fetch() {
    # -L because release assets redirect to a CDN host. $3, when given, replaces the generic
    # "Nothing was installed." trailer — some call sites (store_installer) fire after the CLI
    # itself is already on disk and working, where that claim would be false.
    curl -fsSL "$1" -o "$2" || die "Could not fetch $1
${3:-Nothing was installed.}"
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
    actual=$(sha256_of "$1")
    expected=$(tr -d ' \t\n\r' < "$2")
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
    # shellcheck disable=SC2064
    trap "rm -rf '$stage'" EXIT INT TERM

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
    # .baas.$$ file behind in $BAAS_BIN.
    # shellcheck disable=SC2064
    trap "rm -f '$stage'" EXIT INT TERM
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

store_installer() {
    # NOT `cp "$0"`: in the dominant path the script arrives on stdin through `curl | sh`, where
    # $0 is `sh` and there is no file to copy. Downloading the pinned installer is correct in every
    # path and guarantees the stored copy is the released one for exactly this version.
    version="$1"
    stage="$BAAS_SHARE/.install.$$"
    # Same cleanup discipline as install_jar and write_shim: an interrupt mid-write must not leave
    # a stray .install.$$ file behind in $BAAS_SHARE.
    # shellcheck disable=SC2064
    trap "rm -f '$stage'" EXIT INT TERM
    # By the time this runs, install_jar and write_shim have already succeeded — the CLI is on
    # disk and working. A failure here must say so; "Nothing was installed" would be false.
    partial_msg="baas $version is installed at $BAAS_BIN/baas and is working, but the updater copy
could not be stored at $BAAS_SHARE/install.sh, so --update is unavailable until this succeeds.
Re-run this installer (it is idempotent) to fix it."
    fetch "$(asset_url "$version" install.sh)" "$stage" "$partial_msg"
    chmod +x "$stage" || die "$partial_msg"
    mv -f "$stage" "$BAAS_SHARE/install.sh" || die "$partial_msg"
    trap - EXIT INT TERM
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

# Field-wise and numeric. A string comparison ranks 3.9.0 above 3.10.0, which is the classic
# version-sort bug; sort -V would avoid it but is GNU-only in practice.
version_newer() {
    a="$1"; b="$2"
    [ "$a" != "$b" ] || return 1
    i=1
    while [ "$i" -le 3 ]; do
        fa=$(printf '%s' "$a" | cut -d. -f"$i")
        fb=$(printf '%s' "$b" | cut -d. -f"$i")
        fa=${fa:-0}; fb=${fb:-0}
        case "$fa$fb" in
            *[!0-9]*) die "Cannot compare versions $a and $b. Pass --version <v> explicitly." ;;
        esac
        [ "$fa" -eq "$fb" ] || { [ "$fa" -gt "$fb" ]; return $?; }
        i=$((i + 1))
    done
    return 1
}

latest_tag() {
    # One request. BAAS_LATEST_TAG is a test seam; nothing else sets it.
    [ -z "${BAAS_LATEST_TAG:-}" ] || { printf '%s' "$BAAS_LATEST_TAG"; return 0; }
    url=$(curl -fsSLI -o /dev/null -w '%{url_effective}' \
        "$BAAS_BASE_URL/releases/latest") || die "Could not determine the newest release."
    tag=${url##*/tag/}
    [ "$tag" != "$url" ] || die "Could not determine the newest release from: $url"
    printf '%s' "${tag#v}"
}

installed_version() {
    # baas --version reads Implementation-Version out of the jar manifest — the same value that
    # pins the runner JAR, so it cannot drift from what is actually installed. A VERSION marker
    # file would avoid this parsing but could disagree with the jar beside it.
    # BAAS_INSTALLED_VERSION is a test seam; nothing else sets it. The real parsing path is
    # covered by the CI job, which builds a genuine shaded jar and asserts the exact output.
    [ -z "${BAAS_INSTALLED_VERSION:-}" ] || { printf '%s' "$BAAS_INSTALLED_VERSION"; return 0; }
    [ -x "$BAAS_BIN/baas" ] || return 1
    "$BAAS_BIN/baas" --version 2>/dev/null | awk 'NR==1 {print $2}'
}

# Guarded like the two regions above: sourcing under BAAS_PROBE must define functions only.
if [ -z "${BAAS_PROBE:-}" ]; then
case "$MODE" in
    install)
        check_prerequisites
        install_jar "$VERSION"
        write_shim
        store_installer "$VERSION"
        printf 'Installed baas %s to %s\n' "$VERSION" "$BAAS_BIN/baas"
        printf '(this installer is pinned to %s — re-fetch it for a newer release)\n' "$VERSION"
        ;;
    update)
        current=$(installed_version) || die \
"No installed baas found at $BAAS_BIN/baas, so there is nothing to update.
Install it first:
  curl -fsSL https://github.com/$BAAS_REPO/releases/latest/download/install.sh | sh"
        [ -n "$current" ] || die "Could not read the installed version. Nothing was changed."
        newest=$(latest_tag) || exit 1

        if [ "$current" = "$newest" ]; then
            printf 'baas %s is current. Nothing to do.\n' "$current"
            exit 0
        fi
        if version_newer "$current" "$newest"; then
            printf 'Installed baas %s is newer than the newest release (%s). Nothing changed.\n' \
                "$current" "$newest"
            printf 'Use --version %s to install it deliberately.\n' "$newest"
            exit 0
        fi

        # Hand off rather than install. The script that installs version X must always be version
        # X's own script: if a later release moves the layout, an older installer would place the
        # newer jar wrongly, which is exactly what baking the version was meant to prevent.
        printf 'Updating baas %s -> %s\n' "$current" "$newest"
        exec sh -c "curl -fsSL '$BAAS_BASE_URL/releases/download/v$newest/install.sh' | sh"
        ;;
esac
fi
