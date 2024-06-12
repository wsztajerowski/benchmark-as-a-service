# BaaS S3 hook lambda

Lambda downloads from S3 JSON file, re-packing it in HTTP request and sends to GitHub Action API for running a Benchmark workflow.
Lambda is triggering by main BaaS S3 bucket after creating JSON file under `/requests` folder.

This project contains an AWS Lambda maven application with [AWS Java SDK 2.x](https://github.com/aws/aws-sdk-java-v2) dependencies.

## Prerequisites
- [AWS SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/serverless-sam-cli-install.html)
- Docker Compose from root dir up&running

## Development

#### Building the project
```
mvn clean package
```

#### Testing it locally
```bash
sam local invoke -e src/test/resources/events/s3.json --profile localstack -t src/test/resources/template.yaml
```




