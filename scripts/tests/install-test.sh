#!/bin/sh
# POSIX test harness for scripts/install.sh. No framework: the repo has none, and one script
# does not justify adding one. Run from the repository root: sh scripts/tests/install-test.sh
set -u
INSTALLER="${INSTALLER:-scripts/install.sh}"
# Mirrors install.sh's own JAR_NAME constant. Not sourced from it — the harness invokes install.sh
# as a subprocess throughout, never sources it, so this is a second, hand-kept copy.
JAR_NAME=baas-cli.jar
FAILURES=0
CASES=0

run_case() { CASES=$((CASES + 1)); printf '%s ... ' "$1"; }
pass()     { printf 'ok\n'; }
fail()     { FAILURES=$((FAILURES + 1)); printf 'FAIL\n  %s\n' "$1"; }

assert_contains() {
    case "$1" in
        *"$2"*) pass ;;
        *) fail "expected output to contain '$2', got: $1" ;;
    esac
}

assert_fails() {
    if [ "$1" -eq 0 ]; then fail "expected a non-zero exit, got 0"; else pass; fi
}

assert_eq() {
    if [ "$1" = "$2" ]; then pass; else fail "expected '$2', got '$1'"; fi
}

# --- version resolution -------------------------------------------------------

run_case "refuses the placeholder default"
out=$(sh "$INSTALLER" 2>&1); rc=$?
if [ "$rc" -eq 0 ]; then fail "expected refusal from an unreleased installer"
else assert_contains "$out" "--version"; fi

run_case "--version with no value: names the missing option"
out=$(sh "$INSTALLER" --version 2>&1)
assert_contains "$out" "requires a version"

run_case "--version with no value: reports the baked default"
assert_contains "$out" "0.0.0-semantically-released"

run_case "--version with no value exits non-zero"
sh "$INSTALLER" --version >/dev/null 2>&1; assert_fails $?

# --- fixture release ----------------------------------------------------------
# A local file:// "release" is enough to exercise fetch + verify + install without a network or a
# published release. BAAS_REPO is a variable precisely so a fork — or a test — can retarget it.

FIXTURE=$(mktemp -d)
trap 'rm -rf "$FIXTURE"' EXIT
mkdir -p "$FIXTURE/releases/download/v9.9.9-test"
printf 'not-really-a-jar' > "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar"
# store_installer fetches this on every successful install, not only in the case that asserts on
# it, so the fixture needs it from the start.
cp "$INSTALLER" "$FIXTURE/releases/download/v9.9.9-test/install.sh"
if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar" | cut -d' ' -f1 \
        > "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar.sha256"
else
    shasum -a 256 "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar" | cut -d' ' -f1 \
        > "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar.sha256"
fi

SANDBOX=$(mktemp -d)
export BAAS_SHARE="$SANDBOX/share/baas" BAAS_BIN="$SANDBOX/bin"
export BAAS_BASE_URL="file://$FIXTURE"

run_case "installs a verified artifact"
out=$(sh "$INSTALLER" --version 9.9.9-test 2>&1); rc=$?
if [ "$rc" -ne 0 ]; then fail "install failed: $out"
elif [ ! -f "$BAAS_SHARE/baas-cli.jar" ]; then fail "jar not installed"
elif [ ! -x "$BAAS_BIN/baas" ]; then fail "shim not executable"
else pass; fi

run_case "rejects a corrupted artifact and writes nothing"
rm -rf "$SANDBOX"; mkdir -p "$SANDBOX"
printf 'deadbeef' > "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar.sha256"
sh "$INSTALLER" --version 9.9.9-test >/dev/null 2>&1; rc=$?
if [ "$rc" -eq 0 ]; then fail "expected checksum failure"
elif [ -f "$BAAS_SHARE/baas-cli.jar" ]; then fail "jar written despite mismatch"
else pass; fi

run_case "a failed re-verify leaves a good, pre-existing installation intact"
rm -rf "$SANDBOX"; mkdir -p "$SANDBOX"
sha256_fixture=$(command -v sha256sum >/dev/null 2>&1 \
    && sha256sum "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar" | cut -d' ' -f1 \
    || shasum -a 256 "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar" | cut -d' ' -f1)
printf '%s' "$sha256_fixture" > "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar.sha256"
sh "$INSTALLER" --version 9.9.9-test >/dev/null 2>&1
cp "$BAAS_SHARE/baas-cli.jar" "$SANDBOX/good-baas-cli.jar"
printf 'deadbeef' > "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar.sha256"
sh "$INSTALLER" --version 9.9.9-test >/dev/null 2>&1
if cmp -s "$SANDBOX/good-baas-cli.jar" "$BAAS_SHARE/baas-cli.jar"; then pass
else fail "pre-existing jar was modified or removed by the failed re-verify"; fi

# --- prerequisites, PATH reporting and the stored installer -------------------

run_case "stores an installer alongside the jar"
rm -rf "$SANDBOX"; mkdir -p "$SANDBOX"
cp "$INSTALLER" "$FIXTURE/releases/download/v9.9.9-test/install.sh"
# restore the good checksum clobbered by the previous case
sha256_fixture=$(command -v sha256sum >/dev/null 2>&1 \
    && sha256sum "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar" | cut -d' ' -f1 \
    || shasum -a 256 "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar" | cut -d' ' -f1)
printf '%s' "$sha256_fixture" > "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar.sha256"
sh "$INSTALLER" --version 9.9.9-test >/dev/null 2>&1
if [ -f "$BAAS_SHARE/install.sh" ]; then pass; else fail "no stored installer"; fi

run_case "creates only its own directories, never a configuration directory"
created=$(ls "$SANDBOX" | sort | tr '\n' ' ')
assert_eq "$created" "bin share "

run_case "reports PATH without editing shell configuration"
out=$(sh "$INSTALLER" --version 9.9.9-test 2>&1)
assert_contains "$out" "export PATH"

run_case "an unwritable destination fails, naming the path, with no false success"
rm -rf "$SANDBOX"; mkdir -p "$SANDBOX"
chmod 555 "$SANDBOX"
out=$(sh "$INSTALLER" --version 9.9.9-test 2>&1); rc=$?
chmod 755 "$SANDBOX"
if [ "$rc" -eq 0 ]; then fail "expected a failure, got success: $out"
elif printf '%s' "$out" | grep -q "Installed baas"; then fail "false success line printed: $out"
else
    # "Cannot create ..." is install_jar's own guard on its mkdir; a nearby but different guard
    # (the staging mkdir a few lines down) also dies on this same unwritable sandbox with "Cannot
    # write to ...", so asserting on the path alone would pass even with install_jar's own guard
    # deleted. Pin the exact wording so the case is sensitive to that specific guard.
    assert_contains "$out" "Cannot create $BAAS_SHARE"
fi

# --- version comparison and --update --------------------------------------

run_case "orders versions numerically, not lexically"
if sh scripts/tests/version-compare-probe.sh 3.10.0 3.9.0 \
   && ! sh scripts/tests/version-compare-probe.sh 3.9.0 3.10.0 \
   && ! sh scripts/tests/version-compare-probe.sh 2.1.0 2.1.0; then pass
else fail "3.10.0 should outrank 3.9.0, and equal versions are not newer"; fi

run_case "update reports current and downloads nothing"
out=$(BAAS_INSTALLED_VERSION=9.9.9-test BAAS_LATEST_TAG=9.9.9-test sh "$INSTALLER" --update 2>&1)
assert_contains "$out" "current"

run_case "update refuses to downgrade"
out=$(BAAS_INSTALLED_VERSION=9.9.9-test BAAS_LATEST_TAG=0.0.1 sh "$INSTALLER" --update 2>&1)
assert_contains "$out" "newer than"

# --- uninstall ------------------------------------------------------------

run_case "uninstall removes the command"
rm -rf "$SANDBOX"; mkdir -p "$SANDBOX"
sh "$INSTALLER" --version 9.9.9-test >/dev/null 2>&1
CONFIG="$SANDBOX/dot-baas/config.yaml"
mkdir -p "$(dirname "$CONFIG")"; printf 'prefix: sentinel\n' > "$CONFIG"
sh "$INSTALLER" --uninstall >/dev/null 2>&1
if [ -e "$BAAS_BIN/baas" ]; then fail "shim survived"
elif [ -e "$BAAS_SHARE/$JAR_NAME" ]; then fail "jar survived"
else pass; fi

run_case "uninstall leaves the configuration byte-for-byte"
if [ "$(cat "$CONFIG")" = "prefix: sentinel" ]; then pass
else fail "configuration was modified or removed"; fi

# CONFIG lives at $SANDBOX/dot-baas, a sibling of $BAAS_SHARE/$BAAS_BIN that install.sh has no
# knowledge of — it exists only to catch an over-broad delete of the sandbox itself. On its own
# that is weak: an uninstall that deletes nothing at all would also leave it untouched. This case
# asserts the positive shape directly, and in two parts: first that $BAAS_SHARE itself (the leaf
# "baas" this installer owns, one level under a "share" parent that mirrors the real default
# ~/.local/share/baas) is gone — ls on $SANDBOX alone cannot see that, since it is a level deeper —
# and then that $SANDBOX holds exactly what should survive: the untouched dot-baas directory, the
# "share" parent (like "bin", ~/.local/bin's stand-in here — a directory other programs may also
# use, deliberately left alone), and bin itself.
run_case "uninstall removes the now-empty share directory, and nothing more"
if [ -d "$BAAS_SHARE" ]; then fail "share directory not removed: $BAAS_SHARE"
else
    remaining=$(ls "$SANDBOX" | sort | tr '\n' ' ')
    assert_eq "$remaining" "bin dot-baas share "
fi

# This is the case that pins rmdir vs. rm -rf on $BAAS_SHARE directly — the single highest-risk
# line in the uninstall arm, and none of the cases above actually distinguish the two: they never
# leave anything else behind in $BAAS_SHARE for a wrongly-recursive delete to catch. rmdir's whole
# reason for being there is that it fails, harmlessly, on a non-empty directory; rm -rf would not.
run_case "uninstall never removes a non-empty share directory, or what's in it"
rm -rf "$SANDBOX"; mkdir -p "$SANDBOX"
sh "$INSTALLER" --version 9.9.9-test >/dev/null 2>&1
EXTRA="$BAAS_SHARE/user-placed-file.txt"
printf 'not baas to delete\n' > "$EXTRA"
sh "$INSTALLER" --uninstall >/dev/null 2>&1
if [ ! -d "$BAAS_SHARE" ]; then fail "share directory removed despite a file left inside it"
elif [ ! -f "$EXTRA" ]; then fail "a file the user placed in \$BAAS_SHARE was deleted"
else pass; fi

printf '\n%s case(s), %s failure(s)\n' "$CASES" "$FAILURES"
[ "$FAILURES" -eq 0 ]
