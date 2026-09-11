#!/bin/sh
# Probe for install.sh's version_newer(). Exits 0 when $1 is strictly newer than $2.
set -u
BAAS_PROBE=1 . ./scripts/install.sh
version_newer "$1" "$2"
