#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/get-version-property.sh"

# if [[ $# -lt 2 ]]; then
#   echo "Usage: $0 <groupId> <artifactId> [<baseDir>]"
#   exit 1
# fi

# GROUP_ID="$1"
# ARTIFACT_ID="$2"
# BASE_DIR="${3:-.}"

# xmlstarlet sel -t \
#   -m "/project/dependencyManagement/dependencies/dependency[groupId='$GROUP_ID' and artifactId='$ARTIFACT_ID']" \
#   -v "normalize-space(version)" -n pom.xml


result=$(dependency_property "org.junit" "junit-bom")
if [[ $? -eq 0 ]]; then
  echo "$result"
else
  echo "Error: Could not find property for org.junit:junit-bom" >&2
  exit 1
fi
# -> junit.bom.version

res=$(plugin_property "org.apache.maven.plugins" "maven-compiler-plugin")
echo "$res"
# # -> maven.compiler.plugin.version

# plugin_dependency_property \
#   "org.apache.maven.plugins" "maven-surefire-plugin" \
#   "org.junit.jupiter" "junit-jupiter-engine"
# # -> junit.jupiter.version