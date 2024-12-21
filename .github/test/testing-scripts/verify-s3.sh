#!/bin/bash

# Explicitly set a user-friendly client name
LOGGER_NAME="S3 Assertion"

# Include helper scripts
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/logger.sh"

show_help() {
    echo "Usage: verify-s3.sh [OPTIONS]"
    echo
    echo "Verifies the presence and size of an object in an S3 bucket."
    echo
    echo "Options:"
    echo "  --profile               AWS CLI profile to use"
    echo "  --bucket                Name of the S3 bucket (required)"
    echo "  --key                   Key (path) of the object in the S3 bucket (required)"
    echo "  --check-size            Additional check for non-zero size"
    echo "  --help                  Show this help message and exit"
    echo
    echo "The script checks if the specified object exists in the bucket and verifies its size is greater than 0."
}

# Function to parse named parameters
parse_arguments() {
    while [[ "$#" -gt 0 ]]; do
        case $1 in
            --bucket) S3_BUCKET="$2"; shift ;;
            --key) S3_KEY="$2"; shift ;;
            --profile) PROFILE="$2"; shift ;;
            --check-size) CHECK_SIZE=true ;;
            --help) show_help; exit 0 ;;
            *) log ERROR "Unknown parameter: $1"; exit 1 ;;
        esac
        shift
    done
}

# Parse the input arguments
parse_arguments "$@"

# Validate input arguments
if [ -z "$S3_BUCKET" ] || [ -z "$S3_KEY" ]; then
    log INFO "Usage: $0 --s3-bucket <bucket> --key <key> [--profile <profile>] [--check-size]"
    exit 1
fi

# Check if the S3 object exists (and optionally check its size)
check_s3_object() {

    log INFO "Checking if S3 object '$S3_KEY' exists in bucket '$S3_BUCKET'..."

    if [ -z "$PROFILE" ]; then
        AWS_OUTPUT=$(aws s3 ls "s3://$S3_BUCKET/$S3_KEY" 2>/dev/null)
    else
        AWS_OUTPUT=$(aws s3 ls "s3://$S3_BUCKET/$S3_KEY" --profile "$PROFILE" 2>/dev/null)
    fi

    if [ -z "$AWS_OUTPUT" ]; then
        log ERROR "S3 object '$S3_KEY' does NOT exist in bucket '$S3_BUCKET'."
        exit 1
    else
        log SUCCESS "S3 object '$S3_KEY' exists in bucket '$S3_BUCKET'."
    fi

    if [ "$CHECK_SIZE" = true ]; then
        SIZE=$(echo "$AWS_OUTPUT" | awk '{print $3}')
        if [ "$SIZE" -gt 0 ]; then
            log SUCCESS "S3 object '$S3_KEY' size is $SIZE bytes (greater than 0)."
        else
            log ERROR "S3 object '$S3_KEY' size is 0 bytes."
            exit 1
        fi
    fi
}

check_s3_object
