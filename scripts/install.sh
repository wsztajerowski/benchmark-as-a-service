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

VERSION=$(resolve_version) || exit 1
