#!/bin/bash
# Explicitly set a user-friendly client name
LOGGER_NAME="GHA Workflow"
# Include helper scripts
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/logger.sh"

# Function to print usage/help message
print_usage() {
    cat << EOF
Usage: $(basename "$0") --run-id RUN_ID
Monitor GitHub Actions workflow execution until completion.

Options:
    -h, --help              Show this help message
    -id, --run-id RUN_ID    Specify the workflow run ID to monitor

Example:
    $(basename "$0") --run-id 1234567890
EOF
}

# Parse command line arguments
RUN_ID=""

while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            print_usage
            exit 0
            ;;
        -id|--run-id)
            if [[ -n "$2" ]] && [[ "$2" =~ ^[0-9]+$ ]]; then
                RUN_ID="$2"
                shift 2
            else
                echo "Error: Run ID must be provided and must be a number"
                print_usage
                exit 1
            fi
            ;;
        *)
            echo "Error: Unknown option $1"
            print_usage
            exit 1
            ;;
    esac
done

# Validate that RUN_ID is provided
if [ -z "$RUN_ID" ]; then
    log ERROR "Run ID is required"
    print_usage
    exit 1
fi

log INFO "Monitoring workflow run ID: $RUN_ID"

# Get the start time
START_TIME=$(date +%s)

# Monitor the workflow status
while true; do
    sleep 15
    # Get the status of the run
    STATUS=$(gh run view --repo wsztajerowski/benchmark-as-a-service "$RUN_ID" --json status --jq .status)

    if [ "$STATUS" = "completed" ]; then
        # Get the conclusion of the run
        CONCLUSION=$(gh run view --repo wsztajerowski/benchmark-as-a-service "$RUN_ID" --json conclusion --jq .conclusion)

        if [ "$CONCLUSION" = "success" ]; then
            # Just break the loop on success - script will continue execution
            break
        else
            # Print the error logs
            gh run view --repo wsztajerowski/benchmark-as-a-service "$RUN_ID" --log | cat
            log ERROR "Workflow failed!"
            exit 1
        fi
    elif [ "$STATUS" = "failed" ]; then
        gh run view --repo wsztajerowski/benchmark-as-a-service "$RUN_ID" --log | cat
        log ERROR "Workflow failed!"
        exit 1
    fi

    log INFO "Workflow is still running... waiting"
done

# Calculate total time
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
log SUCCESS "Workflow completed successfully!"
log INFO "Total time: $DURATION seconds"

# Script continues here with any additional commands you want to add after successful completion