#!/bin/bash

# --- Normalize path ---
normalize_path() {
  local path="$1"
  if command -v realpath >/dev/null 2>&1; then
    realpath "$path"
  elif command -v grealpath >/dev/null 2>&1; then  # macOS (coreutils installed via brew)
    grealpath "$path"
  else
    # No tool available, return original path
    echo "$path"
  fi
}
