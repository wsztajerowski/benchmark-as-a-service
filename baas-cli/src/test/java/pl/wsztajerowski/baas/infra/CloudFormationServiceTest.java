package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;
import software.amazon.awssdk.services.cloudformation.model.UpdateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.UpdateStackResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudFormationServiceTest {

    /** Describes one stack in a fixed status, and records whether an update was ever submitted. */
    private static final class FakeCloudFormation implements CloudFormationClient {
        final StackStatus status;
        boolean updateSubmitted;

        FakeCloudFormation(StackStatus status) {
            this.status = status;
        }

        @Override
        public DescribeStacksResponse describeStacks(DescribeStacksRequest request) {
            return DescribeStacksResponse.builder()
                .stacks(Stack.builder().stackName(request.stackName()).stackStatus(status).build())
                .build();
        }

        @Override
        public UpdateStackResponse updateStack(UpdateStackRequest request) {
            updateSubmitted = true;
            throw new UnsupportedOperationException("not reached in these tests");
        }

        @Override
        public String serviceName() {
            return "cloudformation";
        }

        @Override
        public void close() {
        }
    }

    /**
     * `baas admin deployment setup` sends every existing stack through updateStackParameters, so a first create
     * that rolled back has to be recognised there, before anything is submitted.
     */
    @Test
    void aRolledBackCreateIsRefusedOnTheUpdatePathWithTheWayOut() {
        var cf = new FakeCloudFormation(StackStatus.ROLLBACK_COMPLETE);

        assertThatThrownBy(() -> new CloudFormationService(cf)
            .updateStackParameters("baas-123456789012", "{}", Map.of("RunnerImageVersion", "1.0.0")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ROLLBACK_COMPLETE")
            .hasMessageContaining("baas --deployment baas-123456789012 admin deployment setup")
            .hasMessageNotContaining("teardown");
        assertThat(cf.updateSubmitted).isFalse();
    }

    /** Setup reads the status to tell a rolled-back create, which it recovers, from a live stack. */
    @Test
    void theStatusOfAStackIsReported() {
        assertThat(new CloudFormationService(new FakeCloudFormation(StackStatus.ROLLBACK_COMPLETE))
            .stackStatus("baas-123456789012")).contains(StackStatus.ROLLBACK_COMPLETE);
    }

    /** Carries the given deployed parameters and records the update it is sent. */
    private static final class DeployedStack implements CloudFormationClient {
        final List<String> deployedKeys;
        UpdateStackRequest submitted;

        DeployedStack(String... deployedKeys) {
            this.deployedKeys = List.of(deployedKeys);
        }

        @Override
        public DescribeStacksResponse describeStacks(DescribeStacksRequest request) {
            return DescribeStacksResponse.builder()
                .stacks(Stack.builder().stackName(request.stackName()).stackStatus(StackStatus.UPDATE_COMPLETE)
                    .parameters(deployedKeys.stream()
                        .map(key -> Parameter.builder().parameterKey(key).parameterValue("v").build()).toList())
                    .build())
                .build();
        }

        @Override
        public UpdateStackResponse updateStack(UpdateStackRequest request) {
            submitted = request;
            // Ends the update without a waiter, the way an unchanged stack does.
            throw (CloudFormationException) CloudFormationException.builder()
                .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                    .errorMessage("No updates are to be performed.").build())
                .build();
        }

        @Override
        public String serviceName() {
            return "cloudformation";
        }

        @Override
        public void close() {
        }
    }

    private static final String TEMPLATE = """
        AWSTemplateFormatVersion: "2010-09-09"
        Parameters:
          ResourceNamePrefix:
            Type: String
          GitHubOrg:
            Type: String
            Default: ""
        Resources:
          Bucket:
            Type: AWS::S3::Bucket
        """;

    /**
     * Every existing stack still carries UseExistingVpc and its three companions, which the
     * template no longer declares. CloudFormation refuses an update naming a parameter the
     * template lacks, so carrying them forward would break the first setup and the first image
     * build on every deployment.
     */
    @Test
    void aDeployedParameterTheTemplateNoLongerDeclaresIsNotSent() {
        var cf = new DeployedStack("ResourceNamePrefix", "GitHubOrg", "UseExistingVpc", "ExistingVpcId");

        new CloudFormationService(cf).updateStackParameters("d", TEMPLATE, Map.of("ResourceNamePrefix", "d"));

        assertThat(cf.submitted.parameters()).extracting(Parameter::parameterKey)
            .containsExactlyInAnyOrder("ResourceNamePrefix", "GitHubOrg");
        assertThat(cf.submitted.parameters()).filteredOn(parameter -> parameter.parameterKey().equals("GitHubOrg"))
            .singleElement().satisfies(parameter -> assertThat(parameter.usePreviousValue()).isTrue());
    }

    /** The indentation read agrees with a full parse of the bundled template. */
    @Test
    void theDeclaredParametersAreTheBundledTemplatesParameters() throws Exception {
        @SuppressWarnings("unchecked")
        var parsed = ((Map<String, Object>) InfraFixtures.coreTemplate().get("Parameters")).keySet();

        assertThat(CloudFormationService.declaredParameters(CloudFormationService.coreTemplate()))
            .containsExactlyInAnyOrderElementsOf(parsed)
            .doesNotContain("UseExistingVpc", "ExistingVpcId", "ExistingSubnetId", "ExistingSecurityGroupId");
    }

    /**
     * Every federated stack created before interactive-setup carries GitHubOidcProviderArn, which
     * the template now composes instead. Its first update must not name it, or CloudFormation
     * refuses the whole update — and CI's federation, carried forward beside it, must survive.
     */
    @Test
    void theRemovedProviderArnParameterIsDroppedAndTheFederationKept() throws Exception {
        var cf = new DeployedStack("ResourceNamePrefix", "GitHubOrg", "GitHubRepo", "GitHubOidcProviderArn");

        new CloudFormationService(cf).updateStackParameters("d", CloudFormationService.coreTemplate(),
            Map.of("ResourceNamePrefix", "d"));

        assertThat(cf.submitted.parameters()).extracting(Parameter::parameterKey)
            .contains("GitHubOrg", "GitHubRepo")
            .doesNotContain("GitHubOidcProviderArn");
    }

    @Test
    void permissionFailuresAreRecognisedInEveryShapeCloudFormationRelays() {
        assertThat(List.of(
            "Resource handler returned message: \"User: arn:aws:iam::1:user/d is not authorized to perform: iam:UpdateRole on resource: role x\"",
            "API: ec2:CreateVpc You are not authorized to perform this operation.",
            "Access Denied (Service: S3, Status Code: 403, Request ID: R)",
            "(HandlerErrorCode: AccessDenied)",
            "UnauthorizedOperation"))
            .allSatisfy(reason -> assertThat(CloudFormationService.isPermissionFailure(reason)).as(reason).isTrue());
    }

    /** A taken name or a version clash is not the policy's fault, and must not print it. */
    @Test
    void otherFailuresAreNotPermissionFailures() {
        assertThat(List.of(
            "baas-development already exists",
            "Version 1.3.0 of component baas-development-component-runner-base already exists",
            "You have requested more vCPU capacity than your current vCPU limit"))
            .allSatisfy(reason -> assertThat(CloudFormationService.isPermissionFailure(reason)).as(reason).isFalse());
    }
}
