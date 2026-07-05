#!/bin/zsh
# Include helper scripts
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/logger.sh"

# Function to count objects in an S3 bucket path
count_s3_objects() {
    local s3_path="$1"
    local aws_profile="$2"
    local object_count

    if [[ -n "$aws_profile" ]]; then
        object_count=$(aws --profile "$aws_profile" s3 ls "$s3_path" | wc -l)
    else
        object_count=$(aws s3 ls "$s3_path" | wc -l)
    fi

    if [[ $? -ne 0 ]]; then
        log ERROR "Failed to count objects in S3 bucket path: $s3_path" >&2
        return 1
    fi

    echo "$object_count"
}

# Function to upload a file to an S3 bucket
upload_to_s3() {
    local file_path="$1"
    local s3_path="$2"
    local aws_profile="$3"

    if [[ -n "$aws_profile" ]]; then
        aws --profile "$aws_profile" s3 cp "$file_path" "$s3_path"
    else
        aws s3 cp "$file_path" "$s3_path"
    fi

    if [[ $? -ne 0 ]]; then
        log ERROR "Failed to upload file to S3: $file_path -> $s3_path" >&2
        return 1
    fi
}
