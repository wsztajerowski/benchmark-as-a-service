#!/usr/bin/env bash
set -euo pipefail

# Explicitly set a user-friendly client name
# shellcheck disable=SC2034
LOGGER_NAME="Tool runner"
# Include helper scripts
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/logger.sh"
source "$SCRIPT_DIR/utils.sh"

DOCKER_COMPOSE_FILE="../../docker-compose.yaml"
TIMEOUT=120   # default timeout in seconds

show_help() {
    echo "Usage: $(basename "$0") [OPTIONS]"
    echo
    echo "Ensures Docker is running and that containers defined in a docker-compose file"
    echo "are up and healthy. If Docker or the containers are not running, they will be"
    echo "started automatically."
    echo
    echo "Options:"
    echo "  -h, --help              Show this help message and exit"
    echo "  -t, --timeout SECONDS   Max seconds to wait for containers to become healthy"
    echo "                          (default: 120, 0 means wait indefinitely)"
    echo "  -f, --file PATH         Path to docker-compose.yml"
    echo "                          (default: ../../docker-compose.yaml)"
    echo
    echo "Examples:"
    echo "  ./$(basename "$0")"
    echo "  ./$(basename "$0") --file ./my-compose.yml"
    echo "  ./$(basename "$0") --timeout 0"
    echo "  ./$(basename "$0") -t 60 -f ./docker-compose.dev.yml"
}

# --- Parse arguments ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      show_help
      exit 0
      ;;
    -t|--timeout)
      if [[ $# -lt 2 ]]; then
        echo "❌ Missing value for $1"
        exit 1
      fi
      TIMEOUT="$2"
      shift 2
      ;;
    -f|--file)
      if [[ $# -lt 2 ]]; then
        echo "❌ Missing value for $1"
        exit 1
      fi
      DOCKER_COMPOSE_FILE="$2"
      shift 2
      ;;
    -*)
      echo "❌ Unknown option: $1"
      echo "Try '--help' for usage."
      exit 1
      ;;
    *)
      echo "❌ Unexpected argument: $1"
      echo "Try '--help' for usage."
      exit 1
      ;;
  esac
done

# --- Functions to start Docker ---
start_docker_macos() {
  log INFO  "⚠️  Docker is not running. Trying to start Docker Desktop..."
  open -a Docker
  until docker info >/dev/null 2>&1; do
    log INFO  "⏳ Waiting for Docker Desktop to start..."
    sleep 2
  done
  log SUCCESS "✅ Docker Desktop (macOS) is running now."
}

start_docker_linux() {
  echo "⚠️  Docker is not running. Trying to start Docker service..."
  if command -v systemctl >/dev/null 2>&1; then
    sudo systemctl start docker
  elif command -v service >/dev/null 2>&1; then
    sudo service docker start
  else
    log ERROR "❌ Could not detect how to start Docker. Please start it manually."
    exit 1
  fi
  until docker info >/dev/null 2>&1; do
    log INFO  "⏳ Waiting for Docker daemon to start..."
    sleep 2
  done
  log SUCCESS "✅ Docker daemon (Linux) is running now."
}

start_docker_wsl2() {
  log INFO  "⚠️  Docker is not running. Trying to start Docker Desktop on Windows..."
  powershell.exe -Command "Start-Process 'Docker Desktop'" >/dev/null 2>&1 || true
  until docker info >/dev/null 2>&1; do
    log INFO  "⏳ Waiting for Docker Desktop (Windows) to start..."
    sleep 3
  done
  log SUCCESS "✅ Docker Desktop (Windows/WSL2) is running now."
}

# --- Ensure Docker is running ---
if ! docker info >/dev/null 2>&1; then
  case "$(uname -s)" in
    Darwin*) start_docker_macos ;;
    Linux*)
      if grep -qi microsoft /proc/version 2>/dev/null; then
        start_docker_wsl2
      else
        start_docker_linux
      fi
      ;;
    *) log ERROR "❌ Unsupported OS: $(uname -s)"; exit 1 ;;
  esac
else
  log SUCCESS  "✅ Docker is already running."
fi

DOCKER_COMPOSE_FILE=$(normalize_path "$DOCKER_COMPOSE_FILE")
# --- Validate docker compose file ---
if ! docker compose -f "$DOCKER_COMPOSE_FILE" ps >/dev/null 2>&1; then
  log ERROR "❌ Docker Compose file '$DOCKER_COMPOSE_FILE' not valid or not found."
  exit 1
fi

# --- Check containers ---
RUNNING_CONTAINERS=$(docker compose -f "$DOCKER_COMPOSE_FILE" ps -q | \
  xargs -r docker inspect -f '{{.State.Running}}' 2>/dev/null | grep -c true || true)

if [ "$RUNNING_CONTAINERS" -eq 0 ]; then
  log INFO  "⚠️  No containers are running from '$DOCKER_COMPOSE_FILE'. Starting them..."
  docker compose -f "$DOCKER_COMPOSE_FILE" up -d

  log INFO  "⏳ Waiting for containers to become healthy..."
  SECONDS=0
  while true; do
    UNHEALTHY=$(docker compose -f "$DOCKER_COMPOSE_FILE" ps -q | \
      xargs -r docker inspect -f '{{.State.Health.Status}}' 2>/dev/null | \
      grep -vc healthy || true)

    STOPPED=$(docker compose -f "$DOCKER_COMPOSE_FILE" ps -q | \
      xargs -r docker inspect -f '{{.State.Status}}' 2>/dev/null | \
      grep -vc running || true)

    if [ "$UNHEALTHY" -eq 0 ] && [ "$STOPPED" -eq 0 ]; then
      break
    fi

    if [ "$TIMEOUT" -gt 0 ] && [ "$SECONDS" -ge "$TIMEOUT" ]; then
      log ERROR "❌ Timeout reached ($TIMEOUT seconds). Some containers not healthy or stopped."
      exit 1
    fi

    log INFO  "⏳ Still waiting... (unhealthy: $UNHEALTHY, stopped: $STOPPED, elapsed: ${SECONDS}s)"
    sleep 3
  done
  log SUCCESS "✅ All containers are up and healthy."
else
  log SUCCESS "✅ Containers from '$DOCKER_COMPOSE_FILE' are already running."
fi
