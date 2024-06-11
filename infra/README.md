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
## Create CloudFormation stack for SSM Parameter Store 

```bash
aws --profile YOUR_AWS_PROFILE \
cloudformation create-stack \
--stack-name baas-ssm \
--template-body file://$(PWD)/cf-template-bootstrap.yaml \
--parameters ParameterKey=GHABenchmarkWorkflowId,ParameterValue=GITHUB_WORKFLOW_ID
```

## Create Main CloudFormation stack 

### without previously existing GitHub OIDC Provider

```bash
aws --profile YOUR_AWS_PROFILE \
cloudformation create-stack \
--stack-name baas-iam \
--template-body file://$(PWD)/cf-template-main.yaml \
--capabilities CAPABILITY_NAMED_IAM
```

### with existing GitHub OIDC Provider

Add to parameters option following value:
```bash
--parameters (...) ParameterKey=OIDCProviderArn,ParameterValue=arn:aws:iam::AWS_ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com 
```

