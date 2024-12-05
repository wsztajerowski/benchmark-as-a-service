#!/bin/bash

# Explicitly set a user-friendly client name
LOGGER_NAME="Exec single benchmark e2e"
# Source logger
source "$(dirname "$0")/testing-scripts/logger.sh"

# Hardcoded values for the test
S3_BUCKET="baas"
AWS_PROFILE="localstack"  # Default AWS profile
MONGO_CONNECTION_STRING="mongodb://localhost:27017/local_test"  # Default MongoDB connection string


# Function to display help
show_help() {
    echo "Usage: act-test.sh [OPTIONS]"
    echo
    echo "Runs the main test script with pre-configured steps."
    echo
    echo "Options:"
    echo "  --mongo-connection-string MongoDB connection string (default: mongodb://localhost:27017/local_test)"
    echo "  --aws-profile            AWS CLI profile to use (default: default)"
    echo "  --help                   Show this help message and exit"
    echo
    echo "Steps performed by this script:"
    echo "  1. Validate required tools."
    echo "  2. Prepare test data by running Maven build (mvn clean verify)."
    echo "  3. Upload a test file to an S3 bucket."
    echo "  4. Run the act command."
    echo "  5. Verify results in S3 and MongoDB."
}

# Parse input arguments for required parameters
parse_arguments() {
    while [[ "$#" -gt 0 ]]; do
        case $1 in
            --mongo-connection-string) MONGO_CONNECTION_STRING="$2"; shift ;;
            --aws-profile) AWS_PROFILE="$2"; shift ;;
            --help) show_help; exit 0 ;;
            *) log ERROR "Unknown parameter: $1"; show_help; exit 1 ;;
        esac
        shift
    done
}

# Parse the input arguments
parse_arguments "$@"

# Step 1: Validate required tools
log INFO "Step 1: Validating required tools..."
./testing-scripts/validate-tools.sh docker:Docker docker-compose:"Docker Compose" act aws:"AWS CLI v2" mongosh:"Mongo Shell" java mvn:Maven || exit 1

# Step 2: Prepare test data (run Maven command)
log INFO "Step 2: Preparing test data..."
log INFO "2.1: Run Maven build on project"
mvn -f ./../../pom.xml -q clean package -DskipTests || {
    log ERROR "Maven command failed.";
    exit 1;
}

RUNNER_FILE_PATH="../../benchmark-runner/target/benchmark-runner.jar"
RUNNER_S3_KEY="s3://$S3_BUCKET/runner.jar"
log INFO "2.2: Uploading runner '$RUNNER_FILE_PATH' to S3 (path: '$RUNNER_S3_KEY')"
aws s3 cp "$RUNNER_FILE_PATH" "$RUNNER_S3_KEY" --profile "$AWS_PROFILE" || {
   log ERROR "S3 copy command failed.";
   exit 1;
}

BENCHMARK_FILE_PATH="../../fake-jmh-benchmarks/target/fake-jmh-benchmarks.jar"
BENCHMARK_S3_KEY="s3://$S3_BUCKET/test-benchmark.jar"
log INFO "2.3: Uploading benchmark '$BENCHMARK_FILE_PATH' to S3 (path: '$BENCHMARK_S3_KEY')"
aws s3 cp "$BENCHMARK_FILE_PATH" "$BENCHMARK_S3_KEY" --profile "$AWS_PROFILE" || {
   log ERROR "S3 copy command failed.";
   exit 1;
}

# Step 3: Run act command and verify exit code
REQUEST_ID=$RANDOM
log INFO "Step 3: Running act command..."
act -W ../workflows/exec-single-benchmark.yml \
--secret-file ./act-config/.secrets \
--var-file ./act-config/.vars \
--input gha-runner-type=ubuntu-latest \
--input benchmark-type=jmh-with-async \
--input request-id=${REQUEST_ID} \
--input benchmark-path=${BENCHMARK_S3_KEY} \
--input runner-path=${RUNNER_S3_KEY} \
--input s3-result-bucket=${S3_BUCKET} \
--input parameters="-f 1 -wi 1 -i 1 --async-additional-param event=cpu --async-additional-param threads=true" | grep --color=always -v '::'
# using ${PIPESTATUS[0]} and  grep --color=always -v '::' is a workaround for do not printing ACT debug output
if [ "${PIPESTATUS[0]}" -ne 0 ]; then
    log ERROR "act command failed."
    exit 1
fi

# Step 4: Run assertions
log INFO "Step 4: Running assertions..."
log INFO "Verifying if async output and flamegraph exist on S3..."
./testing-scripts/verify-s3.sh --profile "$AWS_PROFILE" --bucket "$S3_BUCKET" --key "${REQUEST_ID}/jmh-with-async/output.txt" --check-size || exit 1
./testing-scripts/verify-s3.sh --profile "$AWS_PROFILE" --bucket "$S3_BUCKET" \
--key "${REQUEST_ID}/jmh-with-async/pl.wsztajerowski.fake.Incrementing_Synchronized.incrementUsingSynchronized-Throughput/flame-cpu-forward.html" || exit 1

log INFO "Verifying if document with benchmark results exists in MongoDB..."
./testing-scripts/verify-mongo.sh --connection-string "$MONGO_CONNECTION_STRING" --collection "jmh-benchmarks" --key "_id.requestId" --value "$REQUEST_ID" || exit 1

log INFO "All checks passed successfully!"
