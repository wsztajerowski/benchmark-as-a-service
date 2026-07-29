package pl.wsztajerowski.baas.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
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

    private static final Logger logger = LoggerFactory.getLogger(CloudFormationService.class);

    private final CloudFormationClient cf;

    public CloudFormationService(CloudFormationClient cf) {
        this.cf = cf;
    }

    public void createOrUpdateStack(String stackName, String templateBody, Map<String, String> params) {
        List<Parameter> cfParams = params.entrySet().stream()
            .map(e -> Parameter.builder().parameterKey(e.getKey()).parameterValue(e.getValue()).build())
            .toList();

        logger.debug("Stack parameters for {}: {}", stackName, params);

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
                logger.info("Updating stack {}...", stackName);
                cf.waiter().waitUntilStackUpdateComplete(r -> r.stackName(stackName));
            } catch (CloudFormationException e) {
                if (isNoUpdateNeeded(e)) {
                    logger.info("Stack {} is already up to date.", stackName);
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
            logger.info("Creating stack {}...", stackName);
            await(stackName, "create", () -> cf.waiter().waitUntilStackCreateComplete(r -> r.stackName(stackName)));
        }
        logger.info("Stack {} deployed successfully.", stackName);
    }

    public void deleteStack(String stackName) {
        cf.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
        logger.info("Deleting stack {}...", stackName);
        await(stackName, "delete", () -> cf.waiter().waitUntilStackDeleteComplete(r -> r.stackName(stackName)));
        logger.info("Stack {} deleted.", stackName);
    }

    /**
     * A failed waiter reports only that it reached a terminal state — the actual cause lives
     * in the stack events. Without this, a single denied IAM action surfaces as an
     * SdkClientException stack trace naming neither the resource nor the action, and the user
     * has to go and run describe-stack-events by hand to learn anything at all.
     */
    private void await(String stackName, String operation, Runnable waiter) {
        try {
            waiter.run();
        } catch (SdkClientException e) {
            throw new IllegalStateException(failureReport(stackName, operation), e);
        }
    }

    private String failureReport(String stackName, String operation) {
        var report = new StringBuilder("Stack " + stackName + " failed to " + operation + ".");
        try {
            var failures = cf.describeStackEvents(r -> r.stackName(stackName)).stackEvents().stream()
                .filter(event -> event.resourceStatusAsString() != null
                    && event.resourceStatusAsString().endsWith("_FAILED"))
                .filter(event -> event.resourceStatusReason() != null)
                // Cascade noise: these name no cause and bury the one event that does.
                .filter(event -> !event.resourceStatusReason().contains("Resource creation cancelled"))
                .filter(event -> !event.resourceStatusReason().startsWith("The following resource(s) failed"))
                .toList();

            // Events come back newest-first, so the last one is the root cause.
            for (int i = failures.size() - 1; i >= 0; i--) {
                report.append(System.lineSeparator())
                    .append("  ").append(failures.get(i).logicalResourceId())
                    .append(": ").append(failures.get(i).resourceStatusReason());
            }
            if (failures.isEmpty()) {
                report.append(System.lineSeparator())
                    .append("  (no failure events reported — check the CloudFormation console)");
            }
        } catch (RuntimeException e) {
            report.append(System.lineSeparator())
                .append("  (could not read stack events: ").append(e.getMessage()).append(')');
        }
        return report.toString();
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
