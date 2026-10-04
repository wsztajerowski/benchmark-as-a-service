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
    /** The last describeImages request, so a test can see which owners it asked for. */
    DescribeImagesRequest lastDescribeImages;
    /** When set, describeImages fails with this error code instead of answering. */
    String describeImagesErrorCode;
    /** When set, deregisterImage fails with this error code. */
    String deregisterErrorCode;
    /** When set, deregisterImage fails client-side, as a timeout or an expired session does. */
    RuntimeException deregisterClientFailure;
    /** When set, deleteSnapshot fails with this error code. */
    String deleteSnapshotErrorCode;

    FakeEc2(List<String> calls) {
        this.calls = calls;
    }

    FakeEc2() {
        this(new ArrayList<>());
    }

    @Override
    public DescribeImagesResponse describeImages(DescribeImagesRequest request) {
        if (describeImagesErrorCode != null) {
            throw (Ec2Exception) Ec2Exception.builder()
                .message(describeImagesErrorCode)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(describeImagesErrorCode).build())
                .build();
        }
        lastDescribeImages = request;
        if (request.imageIds().isEmpty()) {
            // A lookup by filter (the parent-release resolution): every filter has to match, and an
            // empty answer is an empty list, never an error.
            // Like EC2: a search leaves deprecated images out unless the request asks for them.
            boolean includeDeprecated = Boolean.TRUE.equals(request.includeDeprecated());
            return DescribeImagesResponse.builder().images(images.values().stream()
                .filter(image -> includeDeprecated || image.deprecationTime() == null
                    || java.time.Instant.parse(image.deprecationTime()).isAfter(java.time.Instant.now()))
                .filter(image -> request.filters().stream().allMatch(filter -> filter.values().contains(
                    switch (filter.name()) {
                        case "name" -> image.name();
                        case "architecture" -> image.architectureAsString();
                        default -> throw new IllegalArgumentException("FakeEc2 cannot filter on " + filter.name());
                    })))
                .toList()).build();
        }
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
        if (deregisterClientFailure != null) {
            throw deregisterClientFailure;
        }
        if (deregisterErrorCode != null) {
            throw (Ec2Exception) Ec2Exception.builder()
                .message(deregisterErrorCode)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(deregisterErrorCode).build())
                .build();
        }
        images.remove(request.imageId());
        return DeregisterImageResponse.builder().build();
    }

    @Override
    public DeleteSnapshotResponse deleteSnapshot(DeleteSnapshotRequest request) {
        calls.add("deleteSnapshot:" + request.snapshotId());
        if (deleteSnapshotErrorCode != null) {
            throw (Ec2Exception) Ec2Exception.builder()
                .message(deleteSnapshotErrorCode)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(deleteSnapshotErrorCode).build())
                .build();
        }
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
