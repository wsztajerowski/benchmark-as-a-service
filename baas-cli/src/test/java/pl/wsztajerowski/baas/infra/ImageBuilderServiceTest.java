package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.imagebuilder.model.Ami;
import software.amazon.awssdk.services.imagebuilder.model.ImageState;
import software.amazon.awssdk.services.imagebuilder.model.ImageStatus;
import software.amazon.awssdk.services.imagebuilder.model.OutputResources;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageBuilderServiceTest {

    private static final String PIPELINE = "arn:aws:imagebuilder:eu-central-1:123456789012:image-pipeline/a1b2c3d4-runner";
    private static final String POINTER = "/a1b2c3d4/runner/ami-id";
    private static final String PREVIOUS_AMI = "ami-000000000000previous";
    private static final String NEW_AMI = "ami-111111111111new";

    /** Shared so the cross-service ordering — pointer write, then deregister — is visible at all. */
    private final List<String> calls = new java.util.ArrayList<>();
    private final FakeImageBuilder imageBuilder = new FakeImageBuilder();
    private final FakeEc2 ec2 = new FakeEc2(calls);
    private final FakeSsm ssm = new FakeSsm(calls);

    private ImageBuilderService service() {
        return new ImageBuilderService(imageBuilder, ec2, ssm, Duration.ZERO);
    }

    /**
     * The whole reason this ordering is spelled out in the design: retiring first would leave the
     * pointer aimed at a deregistered AMI for the duration of the build, so every run launched in
     * that window fails.
     */
    @Test
    void repointsBeforeRetiringTheImageItReplaces() throws Exception {
        ssm.parameters.put(POINTER, PREVIOUS_AMI);
        imageBuilder.amiId = NEW_AMI;
        ec2.images.put(NEW_AMI, taggedImage(NEW_AMI, "1.1.0", "ami-parent"));
        ec2.images.put(PREVIOUS_AMI, taggedImage(PREVIOUS_AMI, "1.0.0", "ami-parent"));

        service().publish(PIPELINE, POINTER, "1.1.0", "ami-parent");

        assertThat(calls)
            .as("a deregister before the pointer write is a window in which every run fails")
            .containsSubsequence("putParameter:" + NEW_AMI, "deregisterImage:" + PREVIOUS_AMI);
    }

    @Test
    void publishesTheNewAmiAndRetiresTheOldOneWithItsSnapshots() throws Exception {
        ssm.parameters.put(POINTER, PREVIOUS_AMI);
        imageBuilder.amiId = NEW_AMI;
        ec2.images.put(NEW_AMI, taggedImage(NEW_AMI, "1.1.0", "ami-parent"));
        ec2.images.put(PREVIOUS_AMI, imageWithSnapshots(PREVIOUS_AMI, "snap-old"));

        String published = service().publish(PIPELINE, POINTER, "1.1.0", "ami-parent");

        assertThat(published).isEqualTo(NEW_AMI);
        assertThat(ssm.parameters).containsEntry(POINTER, NEW_AMI);
        assertThat(calls)
            .as("a deregistered AMI leaves its snapshots billing indefinitely unless they go too")
            .contains("deregisterImage:" + PREVIOUS_AMI, "deleteSnapshot:snap-old");
    }

    @Test
    void failedBuildLeavesThePointerAndThePreviousImageUntouched() {
        ssm.parameters.put(POINTER, PREVIOUS_AMI);
        imageBuilder.terminalStatus = ImageStatus.FAILED;
        imageBuilder.failureReason = "step InstallToolchain returned 1";

        assertThatThrownBy(() -> service().publish(PIPELINE, POINTER, "1.1.0", "ami-parent"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("step InstallToolchain returned 1");

        assertThat(ssm.parameters)
            .as("a failed build must not strand runs on an image that was never produced")
            .containsEntry(POINTER, PREVIOUS_AMI);
        assertThat(calls).noneMatch(call -> call.startsWith("deregisterImage"));
    }

    @Test
    void firstEverBuildRetiresNothing() throws Exception {
        imageBuilder.amiId = NEW_AMI;
        ec2.images.put(NEW_AMI, taggedImage(NEW_AMI, "1.0.0", "ami-parent"));

        service().publish(PIPELINE, POINTER, "1.0.0", "ami-parent");

        assertThat(ssm.parameters).containsEntry(POINTER, NEW_AMI);
        assertThat(calls).noneMatch(call -> call.startsWith("deregisterImage"));
    }

    @Test
    void identityTagsAreLeftAloneWhenTheDistributionConfigurationAlreadySetThem() throws Exception {
        imageBuilder.amiId = NEW_AMI;
        ec2.images.put(NEW_AMI, taggedImage(NEW_AMI, "1.0.0", "ami-parent"));

        service().publish(PIPELINE, POINTER, "1.0.0", "ami-parent");

        assertThat(calls).noneMatch(call -> call.startsWith("createTags"));
    }

    @Test
    void identityTagsAreAppliedWhenTheImageArrivesWithoutThem() throws Exception {
        imageBuilder.amiId = NEW_AMI;
        ec2.images.put(NEW_AMI, Image.builder().imageId(NEW_AMI).creationDate("2026-08-11T00:00:00Z").build());

        service().publish(PIPELINE, POINTER, "1.0.0", "ami-parent");

        assertThat(calls)
            .as("without them `baas admin image` has no identity to report and results carry no version")
            .contains("createTags:" + NEW_AMI);
    }

    @Test
    void currentImageReportsTheIdentityFromTheAmiTags() {
        ssm.parameters.put(POINTER, NEW_AMI);
        ec2.images.put(NEW_AMI, taggedImage(NEW_AMI, "1.2.0", "ami-parent"));

        assertThat(service().currentImage(POINTER)).hasValueSatisfying(image -> {
            assertThat(image.amiId()).isEqualTo(NEW_AMI);
            assertThat(image.imageVersion()).isEqualTo("1.2.0");
            assertThat(image.parentAmiId()).isEqualTo("ami-parent");
        });
    }

    @Test
    void currentImageIsEmptyWhenNothingHasBeenBuilt() {
        assertThat(service().currentImage(POINTER)).isEmpty();
    }

    /**
     * A pointer surviving its AMI is the state `baas run` has to fail on rather than launch into.
     */
    @Test
    void currentImageIsEmptyWhenThePointerNamesADeregisteredAmi() {
        ssm.parameters.put(POINTER, NEW_AMI);

        assertThat(service().currentImage(POINTER)).isEmpty();
    }

    @Test
    void preflightRejectsAStaleVersionBeforeAnyBuildStarts() {
        imageBuilder.registeredComponents.put("1.0.0", "name: baas-runner-toolchain\n# as published");

        assertThatThrownBy(() -> service()
            .preflightVersion("a1b2c3d4-runner-toolchain", "1.0.0", "name: baas-runner-toolchain\n# edited"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("imageVersion")
            .hasMessageContaining("infra/runner-image.yaml");

        assertThat(imageBuilder.startedPipelines)
            .as("a ~15-minute build the stack update is going to reject anyway")
            .isEmpty();
    }

    /**
     * The preflight's whole job is to look up a registered version, so it must issue a query that
     * can actually return one. Asking Image Builder by name collapses every version into a single
     * row with no version field, the version filter then matches nothing, and the preflight
     * concludes the version is free — letting a doomed build proceed to a stack update that Image
     * Builder rejects on immutability.
     *
     * <p>This escaped the original suite because the fake answered every query the same way. It
     * cost a real 9-minute build to find.
     */
    @Test
    void preflightQueriesComponentsInAWayThatReturnsVersions() {
        imageBuilder.registeredComponents.put("1.0.0", "name: baas-runner-toolchain\n# as published");

        assertThatThrownBy(() -> service()
            .preflightVersion("a1b2c3d4-runner-toolchain", "1.0.0", "name: baas-runner-toolchain\n# edited"))
            .as("a byName query cannot see the registered version, and the collision goes unnoticed")
            .isInstanceOf(IllegalStateException.class);

        assertThat(imageBuilder.byNameQueries)
            .as("byName returns an x.x.x placeholder carrying no version to compare against")
            .isZero();
    }

    @Test
    void preflightAcceptsAnUnchangedVersion() {
        String component = "name: baas-runner-toolchain\n# unchanged";
        imageBuilder.registeredComponents.put("1.0.0", component);

        service().preflightVersion("a1b2c3d4-runner-toolchain", "1.0.0", component);
    }

    @Test
    void preflightAcceptsANewVersion() {
        imageBuilder.registeredComponents.put("1.0.0", "name: baas-runner-toolchain");

        service().preflightVersion("a1b2c3d4-runner-toolchain", "1.1.0", "name: baas-runner-toolchain\n# new");
    }

    private static Image taggedImage(String amiId, String version, String parent) {
        return Image.builder()
            .imageId(amiId)
            .creationDate("2026-08-11T00:00:00Z")
            .tags(
                Tag.builder().key(RunnerImage.VERSION_TAG).value(version).build(),
                Tag.builder().key(RunnerImage.PARENT_TAG).value(parent).build())
            .build();
    }

    private static Image imageWithSnapshots(String amiId, String... snapshotIds) {
        return Image.builder()
            .imageId(amiId)
            .creationDate("2026-08-10T00:00:00Z")
            .blockDeviceMappings(List.of(snapshotIds).stream()
                .map(snapshotId -> BlockDeviceMapping.builder()
                    .deviceName("/dev/xvda")
                    .ebs(EbsBlockDevice.builder().snapshotId(snapshotId).build())
                    .build())
                .toList())
            .build();
    }

    static OutputResources amiOutput(String amiId) {
        return OutputResources.builder().amis(Ami.builder().image(amiId).build()).build();
    }

    static ImageState state(ImageStatus status, String reason) {
        return ImageState.builder().status(status).reason(reason).build();
    }
}
