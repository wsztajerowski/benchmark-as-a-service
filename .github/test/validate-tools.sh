#!/bin/bash

# Function to validate if a tool is installed
validate_tool() {
    command -v "$1" >/dev/null 2>&1 || {
        echo >&2 "Error: $1 is not installed. Please install it to proceed.";
        exit 1;
    }
}

# List of required tools
required_tools=("docker" "docker-compose" "act" "aws" "mongosh" "java" "mvn")

# Validate all required tools
echo "Validating required tools..."
for tool in "${required_tools[@]}"; do
    validate_tool "$tool"
    echo "$tool is installed."
done
