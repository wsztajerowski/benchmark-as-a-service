# Create CloudFormation stack for GHA workflow

```NOTE
Run example command from this folder.
``` 

## Create Main CloudFormation stack 

### without previously existing GitHub OIDC Provider

```bash
aws cloudformation deploy \
  --profile YOUR_AWS_PROFILE \
  --template-file cf-template-main.yaml \
  --stack-name baas-main \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides ResourceNamePrefix=RESOURCE_PREFIX
```

### with existing GitHub OIDC Provider

```bash
aws cloudformation deploy \
  --profile YOUR_AWS_PROFILE \
  --template-file cf-template-main.yaml \
  --stack-name baas-main \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides ResourceNamePrefix=RESOURCE_PREFIX OIDCProviderArn=arn:aws:iam::YOUR_AWS_ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com
```
