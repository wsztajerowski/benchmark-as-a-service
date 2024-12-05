#!/bin/bash

# Function to parse named parameters
parse_arguments() {
    while [[ "$#" -gt 0 ]]; do
        case $1 in
            --connection-string) CONNECTION_STRING="$2"; shift ;;
            --collection) COLLECTION="$2"; shift ;;
            --key) KEY="$2"; shift ;;
            --value) VALUE="$2"; shift ;;
            *) echo "Unknown parameter: $1"; exit 1 ;;
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
        echo "Checking if document with key '$KEY' exists in collection '$COLLECTION'..."
        result=$(mongosh "$CONNECTION_STRING" --quiet --eval "db.getCollection('$COLLECTION').findOne({'$KEY': {\$exists: true}})")
    else
        # Check if document with the provided key and value exists
        echo "Checking if document with key '$KEY' and value '$VALUE' exists in collection '$COLLECTION'..."
        result=$(mongosh "$CONNECTION_STRING" --quiet --eval "db.getCollection('$COLLECTION').findOne({'$KEY': '$VALUE'})")
    fi

    if [ "$result" != "null" ]; then
        echo "Document with key '$KEY' ${VALUE:+and value '$VALUE'} exists in collection '$COLLECTION'."
    else
        echo "Document with key '$KEY' ${VALUE:+and value '$VALUE'} does NOT exist in collection '$COLLECTION'."
        exit 1
    fi
}

check_mongo_document
