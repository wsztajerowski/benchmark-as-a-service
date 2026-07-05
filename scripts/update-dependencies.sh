#!/usr/bin/env bash
set -euo pipefail

# ==============================================================
# CONFIGURATION
# ==============================================================
DEBUG=${DEBUG:-false}  # enable by: DEBUG=true ./update-dependencies.sh
# or: ./update-dependencies.sh --debug
# ==============================================================

# Debug log helper
debug_log() {
  if [[ "$DEBUG" == "true" ]]; then
    echo "🐞 [DEBUG] $*" >&2
  fi
}

# Parse --debug CLI flag
if [[ "${1:-}" == "--debug" ]]; then
  DEBUG=true
fi

# ==============================================================
# Helper: update dependency or plugin
# ==============================================================
update_dependency() {
  local groupId=$1
  local artifactId=$2
  local newVersion=$3

  debug_log "Starting update_dependency for $groupId:$artifactId → $newVersion"

  if [[ "$groupId" == "org.apache.maven.plugins" ]]; then
    echo "🔧 Updating Maven plugin: $groupId:$artifactId → $newVersion"
    debug_log "Running mvn versions:use-plugin-version"
    mvn versions:use-plugin-version -q \
      -DartifactId="$artifactId" \
      -DnewVersion="$newVersion" \
      -DgenerateBackupPoms=false
    debug_log "Plugin update complete for $artifactId"
    return
  fi

  echo "📦 Updating dependency: $groupId:$artifactId → $newVersion"

  # Try to extract version from <dependencies>
  local versionText=""
  debug_log "Searching in <dependencies> for $groupId:$artifactId"
  versionText=$(xmllint --xpath \
    "string(//project//dependencies//dependency[groupId='$groupId' and artifactId='$artifactId']/version/text())" pom.xml 2>/dev/null || echo "")

  # If not found, try <dependencyManagement>
  if [[ -z "$versionText" ]]; then
    debug_log "Not found in <dependencies>. Searching in <dependencyManagement>"
    versionText=$(xmllint --xpath \
      "string(//project//dependencyManagement//dependency[groupId='$groupId' and artifactId='$artifactId']/version/text())" pom.xml 2>/dev/null || echo "")
  fi

  debug_log "Version text found: '${versionText:-<none>}'"

  # Try to detect governing property if still not found
  if [[ -z "$versionText" ]]; then
    debug_log "Version not found. Attempting to detect governing property..."
    IFS=$'\n' read -r -d '' -a props < <(xmllint --xpath "string-join(//project/properties/*/name(), ' ')" pom.xml 2>/dev/null | tr ' ' '\n'; printf '\0')

    declare -a candidates=(
      "${artifactId//./-}.version"
      "${artifactId}.version"
      "${artifactId/-bom/.bom}.version"
      "${artifactId/\\.bom/.bom}.version"
      "${artifactId/\\./-}.bom.version"
    )

    for p in "${props[@]:-}"; do
      candidates+=("$p")
    done

    debug_log "Candidate property names: ${candidates[*]:-<none>}"

    for prop in "${candidates[@]}"; do
      [[ -z "$prop" ]] && continue
      debug_log "Checking property candidate: $prop"
      local propVal=""
      propVal=$(xmllint --xpath "string(//project/properties/*[name()='$prop']/text())" pom.xml 2>/dev/null || echo "")
      if [[ -n "$propVal" ]]; then
        echo "🔍 Assuming property '$prop' governs $groupId:$artifactId (current '$prop'=$propVal)"
        echo "📝 Updating property $prop → $newVersion"
        debug_log "Running mvn versions:set-property for property $prop"
        mvn versions:set-property -q \
          -Dproperty="$prop" \
          -DnewVersion="$newVersion" \
          -DgenerateBackupPoms=false
        debug_log "Property $prop updated successfully"
        return
      fi
    done

    debug_log "No property match found, checking for imported BOM"
    local isImportedBom=""
    isImportedBom=$(xmllint --xpath \
      "string(//project/dependencyManagement/dependencies/dependency[groupId='$groupId' and artifactId='$artifactId' and (type='pom' or scope='import')]/artifactId)" pom.xml 2>/dev/null || echo "")
    if [[ -n "$isImportedBom" ]]; then
      echo "🧩 Updating imported BOM $groupId:$artifactId → $newVersion"
      debug_log "Running mvn versions:set for imported BOM"
      mvn versions:set -q \
        -Dincludes="$groupId:$artifactId" \
        -DnewVersion="$newVersion" \
        -DgenerateBackupPoms=false
      debug_log "Imported BOM updated successfully"
      return
    fi

    echo "⚠️  Could not find version for $groupId:$artifactId in pom.xml (likely inherited from parent or different BOM property)"
    debug_log "No matching entry found in POM for $groupId:$artifactId"
    return
  fi

  # If versionText contains ${...}, extract property name
  if [[ "$versionText" =~ ^\$\{(.+)\}$ ]]; then
    local property="${BASH_REMATCH[1]}"
    echo "🔍 Found property '$property' for $groupId:$artifactId"
    echo "📝 Updating property $property → $newVersion"
    debug_log "Running mvn versions:set-property for property $property"
    mvn versions:set-property -q \
      -Dproperty="$property" \
      -DnewVersion="$newVersion" \
      -DgenerateBackupPoms=false
    debug_log "Property $property updated successfully"
  else
    echo "⚙️  Version is hardcoded → updating inline version"
    debug_log "Running mvn versions:use-dep-version for hardcoded version"
    mvn versions:use-dep-version -q \
      -Dincludes="$groupId:$artifactId" \
      -DdepVersion="$newVersion" \
      -DgenerateBackupPoms=false
    debug_log "Inline version updated successfully"
  fi
}

# ==============================================================
# Helper: test build
# ==============================================================
test_build() {
  echo "🧪 Running mvn clean verify..."
  debug_log "Executing mvn clean verify -B -q"
  if mvn clean verify -B -q; then
    debug_log "Build passed"
    echo "✅ Build successful!"
    return 0
  else
    debug_log "Build failed"
    echo "❌ Build failed!"
    return 1
  fi
}

# ==============================================================
# Main update loop
# ==============================================================
dependencies=(
"org.junit:junit-bom:6.0.1"
"com.amazonaws:aws-lambda-java-events:3.16.1"
"org.apache.maven.plugins:maven-dependency-plugin:3.9.0"
)

for dep in "${dependencies[@]}"; do
  IFS=':' read -r group artifact version <<< "$dep"

  echo "------------------------------------------------------------"
  echo "➡️  Updating $group:$artifact to version $version"
  echo "------------------------------------------------------------"
  debug_log "Processing dependency tuple: group=$group, artifact=$artifact, version=$version"

  update_dependency "$group" "$artifact" "$version"
done

if test_build; then
  echo "🎉 Successfully updated all dependencies!"
else
  echo "⚠️  Build failed, reverting pom.xml..."
  debug_log "Reverting pom.xml due to build failure"
  git checkout pom.xml
  echo "🚫 Stopping script due to failed build."
  exit 1
fi
