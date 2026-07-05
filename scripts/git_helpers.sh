#!/bin/zsh
# Include helper scripts
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/logger.sh"

# Function to get the current Git branch name
get_current_branch() {
    local branch_name
    branch_name=$(git rev-parse --abbrev-ref HEAD)
    if [[ $? -ne 0 ]]; then
        log ERROR "Failed to get current Git branch name." >&2
        return 1
    fi
    echo "$branch_name"
}

# Function to list active remote Git branches
list_active_remote_branches() {
    local branches
    branches=($(git branch -r | grep -v "HEAD ->" | sed 's/origin\///g' | sed 's/^[[:space:]]*//g'))
    if [[ $? -ne 0 ]]; then
        log ERROR "Failed to list active remote Git branches." >&2
        return 1
    fi
    echo "${branches[@]}"
}
