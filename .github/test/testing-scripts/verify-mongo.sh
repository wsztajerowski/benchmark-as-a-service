#!/bin/bash

# Explicitly set a user-friendly client name
LOGGER_NAME="MongoDB Assertion"

# Include helper scripts
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/logger.sh"

show_help() {
    echo "Usage: verify-mongo.sh [OPTIONS]"
    echo
    echo "Verifies the presence of a document in a MongoDB collection."
    echo
    echo "Options:"
    echo "  --connection-string     MongoDB connection string (default: mongodb://localhost:27017/local_test)"
    echo "  --collection            MongoDB collection to search in (required)"
    echo "  --key                   Field name to search for in the document (required)"
    echo "  --value                 Expected value of the field (optional)"
    echo "  --help                  Show this help message and exit"
    echo
    echo "If both --key and --value are provided, the script checks if a document exists with the specified key and value."
    echo "If only --key is provided, the script checks if a document exists with the specified key."
}

# Function to parse named parameters
parse_arguments() {
    while [[ "$#" -gt 0 ]]; do
        case $1 in
            --connection-string) CONNECTION_STRING="$2"; shift ;;
            --collection) COLLECTION="$2"; shift ;;
            --key) KEY="$2"; shift ;;
            --value) VALUE="$2"; shift ;;
            --help) show_help; exit 0 ;;
            *) log ERROR "Unknown parameter: $1"; exit 1 ;;
        esac
        shift
    done
}

# Parse the input arguments
parse_arguments "$@"

# Validate input arguments
if [ -z "$CONNECTION_STRING" ] || [ -z "$COLLECTION" ] || [ -z "$KEY" ]; then
    echo "Usage: $0 --connection-string <connection> --collection <collection> --key <key> [--value <value>]"
    exit 1
fi

# Check if a document with the provided key (and optional value) exists in MongoDB
check_mongo_document() {
    if [ -z "$VALUE" ]; then
        # Check if document with the provided key exists
        log INFO "Checking if document with key '$KEY' exists in collection '$COLLECTION'..."
        result=$(mongosh "$CONNECTION_STRING" --quiet --eval "db.getCollection('$COLLECTION').findOne({'$KEY': {\$exists: true}})")
    else
        # Check if document with the provided key and value exists
        log INFO "Checking if document with key '$KEY' and value '$VALUE' exists in collection '$COLLECTION'..."
        result=$(mongosh "$CONNECTION_STRING" --quiet --eval "db.getCollection('$COLLECTION').findOne({'$KEY': '$VALUE'})")
    fi

    if [ "$result" != "null" ]; then
        log SUCCESS "Document with key '$KEY' ${VALUE:+and value '$VALUE'} exists in collection '$COLLECTION'."
    else
        log ERROR "Document with key '$KEY' ${VALUE:+and value '$VALUE'} does NOT exist in collection '$COLLECTION'."
        exit 1
    fi
}

check_mongo_document
