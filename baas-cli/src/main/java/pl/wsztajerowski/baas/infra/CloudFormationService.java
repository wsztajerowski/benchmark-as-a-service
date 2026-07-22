package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Capability;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.CreateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DeleteStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;
import software.amazon.awssdk.services.cloudformation.model.UpdateStackRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class CloudFormationService {

    private final CloudFormationClient cf;

    public CloudFormationService(CloudFormationClient cf) {
        this.cf = cf;
    }

    public void createOrUpdateStack(String stackName, String templateBody, Map<String, String> params) {
        List<Parameter> cfParams = params.entrySet().stream()
            .map(e -> Parameter.builder().parameterKey(e.getKey()).parameterValue(e.getValue()).build())
            .toList();

        Optional<Stack> existing = describeStack(stackName);

        if (existing.isPresent()) {
            StackStatus status = existing.get().stackStatus();
            if (status == StackStatus.ROLLBACK_COMPLETE) {
                throw new IllegalStateException(
                    "Stack '" + stackName + "' is in ROLLBACK_COMPLETE state and cannot be updated. " +
                    "Delete it first with: baas admin teardown --stack-name " + stackName + " --yes");
            }
            try {
                cf.updateStack(UpdateStackRequest.builder()
                    .stackName(stackName)
                    .templateBody(templateBody)
                    .parameters(cfParams)
                    .capabilities(Capability.CAPABILITY_IAM, Capability.CAPABILITY_NAMED_IAM)
                    .build());
                System.out.println("Updating stack " + stackName + "...");
                cf.waiter().waitUntilStackUpdateComplete(r -> r.stackName(stackName));
            } catch (CloudFormationException e) {
                if (isNoUpdateNeeded(e)) {
                    System.out.println("Stack " + stackName + " is already up to date.");
                    return;
                }
                throw e;
            }
        } else {
            cf.createStack(CreateStackRequest.builder()
                .stackName(stackName)
                .templateBody(templateBody)
                .parameters(cfParams)
                .capabilities(Capability.CAPABILITY_IAM, Capability.CAPABILITY_NAMED_IAM)
                .build());
            System.out.println("Creating stack " + stackName + "...");
            cf.waiter().waitUntilStackCreateComplete(r -> r.stackName(stackName));
        }
        System.out.println("Stack " + stackName + " deployed successfully.");
    }

    public void deleteStack(String stackName) {
        cf.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
        System.out.println("Deleting stack " + stackName + "...");
        cf.waiter().waitUntilStackDeleteComplete(r -> r.stackName(stackName));
        System.out.println("Stack " + stackName + " deleted.");
    }

    public Map<String, String> getStackOutputs(String stackName) {
        return describeStack(stackName)
            .map(stack -> stack.outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue)))
            .orElse(Map.of());
    }

    public boolean stackExists(String stackName) {
        return describeStack(stackName).isPresent();
    }

    private Optional<Stack> describeStack(String stackName) {
        try {
            var response = cf.describeStacks(DescribeStacksRequest.builder()
                .stackName(stackName)
                .build());
            return response.stacks().stream().findFirst();
        } catch (CloudFormationException e) {
            if (isStackDoesNotExist(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static boolean isNoUpdateNeeded(CloudFormationException e) {
        return e.awsErrorDetails().errorMessage().contains("No updates are to be performed");
    }

    private static boolean isStackDoesNotExist(CloudFormationException e) {
        return e.awsErrorDetails().errorMessage().contains("does not exist");
    }
}
