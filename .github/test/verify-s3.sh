#!/bin/bash

# Function to parse named parameters
parse_arguments() {
    while [[ "$#" -gt 0 ]]; do
        case $1 in
            --s3-bucket) S3_BUCKET="$2"; shift ;;
            --key) S3_KEY="$2"; shift ;;
            --profile) PROFILE="$2"; shift ;;
            --check-size) CHECK_SIZE=true ;;
            *) echo "Unknown parameter: $1"; exit 1 ;;
        esac
        shift
    done
}

# Parse the input arguments
parse_arguments "$@"

# Validate input arguments
if [ -z "$S3_BUCKET" ] || [ -z "$S3_KEY" ]; then
    echo "Usage: $0 --s3-bucket <bucket> --key <key> [--profile <profile>] [--check-size]"
    exit 1
fi

# Check if the S3 object exists (and optionally check its size)
check_s3_object() {
    echo "Checking if S3 object '$S3_KEY' exists in bucket '$S3_BUCKET'..."

    if [ -z "$PROFILE" ]; then
        AWS_OUTPUT=$(aws s3 ls "s3://$S3_BUCKET/$S3_KEY" 2>/dev/null)
    else
        AWS_OUTPUT=$(aws s3 ls "s3://$S3_BUCKET/$S3_KEY" --profile "$PROFILE" 2>/dev/null)
    fi

    if [ -z "$AWS_OUTPUT" ]; then
        echo "Error: S3 object '$S3_KEY' does NOT exist in bucket '$S3_BUCKET'."
        exit 1
    else
        echo "S3 object '$S3_KEY' exists in bucket '$S3_BUCKET'."
    fi

    if [ "$CHECK_SIZE" = true ]; then
        SIZE=$(echo "$AWS_OUTPUT" | awk '{print $3}')
        if [ "$SIZE" -gt 0 ]; then
            echo "S3 object '$S3_KEY' size is $SIZE bytes (greater than 0)."
        else
            echo "Error: S3 object '$S3_KEY' size is 0 bytes."
            exit 1
        fi
    fi
}

check_s3_object
