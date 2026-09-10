#!/bin/sh
# POSIX test harness for scripts/install.sh. No framework: the repo has none, and one script
# does not justify adding one. Run from the repository root: sh scripts/tests/install-test.sh
set -u
INSTALLER="${INSTALLER:-scripts/install.sh}"
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

# --- version resolution -------------------------------------------------------

run_case "refuses the placeholder default"
out=$(sh "$INSTALLER" 2>&1); rc=$?
if [ "$rc" -eq 0 ]; then fail "expected refusal from an unreleased installer"
else assert_contains "$out" "--version"; fi

run_case "reports the baked default when --version has no value"
out=$(sh "$INSTALLER" --version 2>&1); rc=$?
assert_contains "$out" "0.0.0-semantically-released"

run_case "--version with no value exits non-zero"
sh "$INSTALLER" --version >/dev/null 2>&1; assert_fails $?

printf '\n%s case(s), %s failure(s)\n' "$CASES" "$FAILURES"
[ "$FAILURES" -eq 0 ]
