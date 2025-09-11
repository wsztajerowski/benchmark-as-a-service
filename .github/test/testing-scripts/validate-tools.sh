#!/bin/bash

# Explicitly set a user-friendly client name
LOGGER_NAME="Tool validator"
# Include helper scripts
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/logger.sh"

# Function to display help
show_help() {
    echo "Usage: validate-tools.sh [OPTIONS] TOOL[:NAME] ..."
    echo
    echo "Validates the presence of required tools provided as arguments."
    echo
    echo "Options:"
    echo "  --help                  Show this help message and exit"
    echo
    echo "Arguments:"
    echo "  TOOL[:NAME]             Specify tools to check, optionally with a display name."
    echo "                          Example: 'docker:Docker' or 'java:\"Java Runtime Environment\"'"
    echo
    echo "Example:"
    echo "  ./validate-tools.sh docker:Docker aws:AWS-CLI java:\"Java Runtime Environment\""
}

# Function to check if a tool is installed
check_tool() {
    local tool=$1
    local tool_name=$2

    if command -v "$tool" &> /dev/null; then
        log SUCCESS "$tool_name is installed."
    else
        log ERROR "$tool_name is not installed. Please install $tool_name."
        exit 1
    fi
}

# Parse options and arguments
if [[ "$1" == "--help" || $# -eq 0 ]]; then
    show_help
    exit 0
fi

# Validate tools
log INFO "Validating required tools..."
for arg in "$@"; do
    if [[ "$arg" == *":"* ]]; then
        tool=${arg%%:*}        # Extract tool (before ':')
        tool_name=${arg#*:}    # Extract name (after ':')
    else
        tool=$arg
        tool_name=$arg
    fi

    # Remove surrounding quotes from tool_name, if any
    tool_name=$(echo "$tool_name" | sed 's/^"//' | sed 's/"$//')

    check_tool "$tool" "$tool_name"
done

log SUCCESS "All specified tools are installed."
