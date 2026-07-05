#!/usr/bin/env bash
set -euo pipefail

POM=${POM:-pom.xml}
NS="http://maven.apache.org/POM/4.0.0"

# -------------------------
# XPath prefix collections
# -------------------------

# All locations where regular or managed dependencies can appear
DEPENDENCY_BASES=(
  "//pom:project/pom:dependencies/pom:dependency"
  "//pom:project/pom:dependencyManagement/pom:dependencies/pom:dependency"
  "//pom:project/pom:profiles/pom:profile/pom:dependencies/pom:dependency"
  "//pom:project/pom:profiles/pom:profile/pom:dependencyManagement/pom:dependencies/pom:dependency"
)

# All locations where plugins are declared (build / pluginManagement / reporting / profiles)
PLUGIN_BASES=(
  "//pom:project/pom:build/pom:plugins/pom:plugin"
  "//pom:project/pom:build/pom:pluginManagement/pom:plugins/pom:plugin"
  "//pom:project/pom:reporting/pom:plugins/pom:plugin"
  "//pom:project/pom:profiles/pom:profile/pom:build/pom:plugins/pom:plugin"
  "//pom:project/pom:profiles/pom:profile/pom:build/pom:pluginManagement/pom:plugins/pom:plugin"
  "//pom:project/pom:profiles/pom:profile/pom:reporting/pom:plugins/pom:plugin"
)

# -------------------------
# Helpers
# -------------------------

first_xpath_match() {
  local xpath=$1
  xmlstarlet sel -N pom="$NS" \
    -t -m "$xpath" -v . -n "$POM" 2>/dev/null |
    sed '/^$/d' | head -n1
}

extract_property() {
  local value=$1
  [[ $value =~ ^\$\{([^}]+)\}$ ]] || return 1
  printf '%s\n' "${BASH_REMATCH[1]}"
  return 0
}

# Build "path1 suffix | path2 suffix | ..."
join_xpath() {
  local suffix=$1; shift
  local paths=("$@")
  local parts=()

  for base in "${paths[@]}"; do
    parts+=("${base}${suffix}")
  done

  (IFS='|'; printf '%s' "${parts[*]}")
}

# -------------------------
# Public functions
# -------------------------

dependency_property() {
  local gid=$1 aid=$2
  local suffix="[pom:groupId='$gid' and pom:artifactId='$aid']/pom:version/text()"
  local xpath
  xpath=$(join_xpath "$suffix" "${DEPENDENCY_BASES[@]}")
  local placeholder
  placeholder=$(first_xpath_match "$xpath")
  [[ -n $placeholder ]] || return 2
  extract_property "$placeholder"
}

plugin_property() {
  local gid=$1 aid=$2
  local suffix="[pom:groupId='$gid' and pom:artifactId='$aid']/pom:version/text()"
  local xpath
  xpath=$(join_xpath "$suffix" "${PLUGIN_BASES[@]}")
  local placeholder
  placeholder=$(first_xpath_match "$xpath")
  [[ -n $placeholder ]] || return 2
  extract_property "$placeholder"
}

plugin_dependency_property() {
  local pgid=$1 paid=$2 dgid=$3 daid=$4
  local suffix="[pom:groupId='$pgid' and pom:artifactId='$paid']/pom:dependencies/pom:dependency[pom:groupId='$dgid' and pom:artifactId='$daid']/pom:version/text()"
  local xpath
  xpath=$(join_xpath "$suffix" "${PLUGIN_BASES[@]}")
  local placeholder
  placeholder=$(first_xpath_match "$xpath")
  [[ -n $placeholder ]] || return 2
  extract_property "$placeholder"
}
