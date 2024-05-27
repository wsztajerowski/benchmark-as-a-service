# Create CloudFormation stack for GHA workflow

```NOTE
Run example command from this folder.
``` 

## Create IAM stack without previously existing GitHub OIDC Provider

```bash
aws --profile YOUR_AWS_PROFILE \
cloudformation create-stack \
--stack-name baas-iam \
--template-body file://$(PWD)/cf-template-gha.yaml \
--capabilities CAPABILITY_NAMED_IAM
```

## Create IAM stack with existing GitHub OIDC Provider

```bash
aws --profile YOUR_AWS_PROFILE \
cloudformation create-stack \
--stack-name baas-iam \
--template-body file://$(PWD)/cf-template-gha.yaml \
--parameters ParameterKey=OIDCProviderArn,ParameterValue=arn:aws:iam::AWS_ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com \
--capabilities CAPABILITY_NAMED_IAM
```

## Create S3 bucket

```bash
aws --profile YOUR_AWS_PROFILE \
cloudformation create-stack \
--stack-name baas-s3-bucket \
--template-body file://$(PWD)/cf-template-s3-bucket.yaml 
```
