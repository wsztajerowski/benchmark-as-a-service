# Create CloudFormation stack for GHA workflow

```NOTE
Run example command from this folder.
``` 

## Manually create SecretString Param in SSM Parameter Store

Due to open issue in CloudFormation, SecureString Parameter is still not supported:
https://github.com/aws-cloudformation/cloudformation-coverage-roadmap/issues/82

Generated GitHub token should be a fine-grained token, with Read and Write access to actions permissions

Example for AWS CLI:
```bash
aws --profile YOUR_AWS_PROFILE \
ssm put-parameter \
--name "/baas/github/token" \
--type SecureString \
--value GITHUB_TOKEN
```
## Create CloudFormation bootstrap stack 

```bash
aws --profile YOUR_AWS_PROFILE \
cloudformation create-stack \
--stack-name baas-bootstrap \
--template-body file://$(PWD)/cf-template-bootstrap.yaml \
--parameters ParameterKey=GHABenchmarkWorkflowId,ParameterValue=GITHUB_WORKFLOW_ID ParameterKey=DeploymentPrefix,ParameterValue=RESOURCE_PREFIX
```

## Create Main CloudFormation stack 

### without previously existing GitHub OIDC Provider

```bash
aws cloudformation deploy \
  --profile YOUR_AWS_PROFILE \
  --template-file cf-template-main.yaml \
  --stack-name baas-main \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides ResourceNamePrefix=RESOURCE_PREFIX S3LambdaBucketName=S3_BUCKET_NAME_FROM_BOOTSTRAP_STACK_OUTPUTS
```

### with existing GitHub OIDC Provider

Add to parameters option following value:
```bash
aws cloudformation deploy \
  --profile YOUR_AWS_PROFILE \
  --template-file cf-template-main.yaml \
  --stack-name baas-main \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides ResourceNamePrefix=RESOURCE_PREFIX S3LambdaBucketName=S3_BUCKET_NAME_FROM_BOOTSTRAP_STACK_OUTPUTS OIDCProviderArn=arn:aws:iam::YOUR_AWS_ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com 
```

