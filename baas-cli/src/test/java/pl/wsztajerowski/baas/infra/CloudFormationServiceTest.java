package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;
import software.amazon.awssdk.services.cloudformation.model.UpdateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.UpdateStackResponse;

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
}
