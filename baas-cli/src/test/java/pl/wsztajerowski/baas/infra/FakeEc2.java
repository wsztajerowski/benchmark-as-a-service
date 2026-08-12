package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.CreateTagsResponse;
import software.amazon.awssdk.services.ec2.model.DeleteSnapshotRequest;
import software.amazon.awssdk.services.ec2.model.DeleteSnapshotResponse;
import software.amazon.awssdk.services.ec2.model.DeregisterImageRequest;
import software.amazon.awssdk.services.ec2.model.DeregisterImageResponse;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Image;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory EC2 image surface. Mutating calls are appended to a log shared with {@link FakeSsm},
 * because the invariant under test spans both services: the pointer write has to precede the
 * deregister, and either fake alone can only show its own half.
 */
class FakeEc2 implements Ec2Client {

    final Map<String, Image> images = new LinkedHashMap<>();
    final List<String> calls;

    FakeEc2(List<String> calls) {
        this.calls = calls;
    }

    FakeEc2() {
        this(new ArrayList<>());
    }

    @Override
    public DescribeImagesResponse describeImages(DescribeImagesRequest request) {
        var found = request.imageIds().stream().filter(images::containsKey).map(images::get).toList();
        if (found.isEmpty()) {
            // What EC2 actually returns for a deregistered AMI, and the case `baas run` must
            // fail on rather than launch into.
            throw (Ec2Exception) Ec2Exception.builder()
                .message("The image id '" + request.imageIds() + "' does not exist")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InvalidAMIID.NotFound").build())
                .build();
        }
        return DescribeImagesResponse.builder().images(found).build();
    }

    @Override
    public DeregisterImageResponse deregisterImage(DeregisterImageRequest request) {
        calls.add("deregisterImage:" + request.imageId());
        images.remove(request.imageId());
        return DeregisterImageResponse.builder().build();
    }

    @Override
    public DeleteSnapshotResponse deleteSnapshot(DeleteSnapshotRequest request) {
        calls.add("deleteSnapshot:" + request.snapshotId());
        return DeleteSnapshotResponse.builder().build();
    }

    @Override
    public CreateTagsResponse createTags(CreateTagsRequest request) {
        calls.add("createTags:" + String.join(",", request.resources()));
        return CreateTagsResponse.builder().build();
    }

    @Override
    public String serviceName() {
        return "ec2";
    }

    @Override
    public void close() {
    }
}
