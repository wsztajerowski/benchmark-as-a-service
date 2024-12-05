# **E2E test scripts**

This repository contains a set of Bash scripts designed to assist with validating dependencies, executing test workflows, and verifying results in S3 and MongoDB. The scripts are modular, extensible, and include logging for better traceability.

---

## **Prerequisites**
- **Bash**: Ensure your system supports Bash scripting.
- **Required Tools**: Install the tools validated by the scripts, such as `docker`, `aws`, `mongosh`, `maven`, etc.

---

## **E2E test scripts** 
### **`exec-single-benchmark-e2e-test.sh`**
E2E test for [exec-single-benchmark.yml](..%2Fworkflows%2Fexec-single-benchmark.yml) GitHub Actions workflow.

Script orchestrating the validation of tools, preparation of test data, execution of workflows, and result verification.

#### **Usage**
```bash
./exec-single-benchmark-e2e-test.sh [OPTIONS]
```

#### **Options**
- `--mongo-connection-string`: MongoDB connection string (default: `mongodb://localhost:27017/local_test`).
- `--aws-profile`: AWS CLI profile to use (default: `localstack`).
- `--help`: Displays help information.

#### **Steps**
1. Validates required tools using `validate-tools.sh`.
2. Prepares test data by running a Maven build (`mvn clean verify`).
3. Uploads a file to an S3 bucket using `aws s3 cp`.
4. Executes the `act` command.
5. Verifies results in S3 using `verify-s3.sh` and MongoDB using `verify-mongo.sh`.

#### **Examples**

Run the full workflow:
```bash
./exec-single-benchmark-e2e-test.sh 
```

Output:
```plaintext
[Exec single benchmark e2e] [INFO] 2024-12-05 12:30:00: Validating required tools...
[Exec single benchmark e2e] [SUCCESS] 2024-12-05 12:30:01: All tools validated successfully.
[Exec single benchmark e2e] [INFO] 2024-12-05 12:30:10: Running Maven build...
[Exec single benchmark e2e] [SUCCESS] 2024-12-05 12:30:20: Maven build completed.
[Exec single benchmark e2e] [INFO] 2024-12-05 12:30:30: Uploading test file to S3...
[Exec single benchmark e2e] [SUCCESS] 2024-12-05 12:30:35: File uploaded to S3 successfully.
[Exec single benchmark e2e] [INFO] 2024-12-05 12:30:40: Running act command...
[Exec single benchmark e2e] [SUCCESS] 2024-12-05 12:30:50: act command executed successfully.
[Exec single benchmark e2e] [INFO] 2024-12-05 12:31:00: Verifying results in S3...
[S3 Verification] [SUCCESS] 2024-12-05 12:31:05: Object test-file exists in bucket test-bucket and its size is 512 bytes.
[Exec single benchmark e2e] [INFO] 2024-12-05 12:31:10: Verifying results in MongoDB...
[Mongo Verification] [SUCCESS] 2024-12-05 12:31:15: Document with id 12345 exists in collection test-collection.
```

---

## **Helper scripts Overview**

### **1. `validate-tools.sh`**
This script validates the presence of required tools for the project.

#### **Usage**
```bash
./testing-scripts/validate-tools.sh [OPTIONS] TOOL[:NAME] ...
```

#### **Options**
- `--help`: Displays help information.

#### **Arguments**
- `TOOL[:NAME]`: Specify tools to check, optionally with a descriptive name.
    - Example: `docker:Docker`, `java:"Java Runtime Environment"`

#### **Examples**
Validate Docker, AWS CLI, Maven and Azure:
```bash
./testing-scripts/validate-tools.sh docker:Docker aws:AWS-CLI mvn:Maven azure:Azure
```

Output:
```plaintext
[Validate Tools] [INFO] 2024-12-05 12:00:00: Validating required tools...
[Validate Tools] [SUCCESS] 2024-12-05 12:00:01: Docker is installed.
[Validate Tools] [SUCCESS] 2024-12-05 12:00:02: AWS-CLI is installed.
[Validate Tools] [SUCCESS] 2024-12-05 12:00:03: Maven is installed.
```

---

### **2. `verify-mongo.sh`**
This script verifies if a document exists in a MongoDB collection based on specified criteria.

#### **Usage**
```bash
./testing-scripts/verify-mongo.sh [OPTIONS]
```

#### **Options**
- `--connection-string`: MongoDB connection string (required).
- `--collection`: MongoDB collection to search in (required).
- `--key`: Field name to search for in the document (required).
- `--value`: Expected value of the field (optional).
- `--help`: Displays help information.

#### **Examples**

Check if a document exists with a specific key and value:
```bash
./testing-scripts/verify-mongo.sh --connection-string mongodb://localhost:27017/local_test --collection users --key email --value test@example.com
```

Check if a document exists with a specific key:
```bash
./testing-scripts/verify-mongo.sh --connection-string mongodb://localhost:27017/local_test --collection users --key email
```

Output:
```plaintext
[Mongo Verification] [SUCCESS] 2024-12-05 12:10:00: Document with email exists in collection users.
```

---

### **3. `verify-s3.sh`**
This script checks the presence of an object in an S3 bucket and verifies its size.

#### **Usage**
```bash
./testing-scripts/verify-s3.sh [OPTIONS]
```

#### **Options**
- `--profile`: AWS CLI profile to use.
- `--bucket`: Name of the S3 bucket (required).
- `--key`: Key (path) of the object in the S3 bucket (required).
- `--check-size`: Additional check for non-zero size.
- `--help`: Displays help information.

#### **Examples**

Verify an S3 object exists:
```bash
./testing-scripts/verify-s3.sh --profile localstack --bucket baas --key test-request.json
```

Output:
```plaintext
[S3 Assertion] [SUCCESS] 2024-12-05 17:05:48: S3 object 'test-request.json' exists in bucket 'baas'.
```

---

### **4. `logger.sh`**
Provides a simple and reusable logging framework for consistent log formatting across scripts.

#### **Config**
Include logger in shell script with setting logger name:
```bash
# Explicitly set a user-friendly client name
LOGGER_NAME="S3 Assertion"

# Source logger
source "$(dirname "$0")/logger.sh"
```
#### **Usage**
The logger is sourced in each script. Use the `log` function with the following syntax:
```bash
log LEVEL "Message"
```

#### **Levels**
- `INFO`
- `SUCCESS`
- `WARNING`
- `ERROR`

#### **Example**
```bash
log INFO "Starting the script..."
log SUCCESS "Operation completed successfully."
log ERROR "An error occurred."
```

Output:
```plaintext
[Script Name] [INFO] 2024-12-05 12:00:00: Starting the script...
[Script Name] [SUCCESS] 2024-12-05 12:00:05: Operation completed successfully.
[Script Name] [ERROR] 2024-12-05 12:00:10: An error occurred.
```

---