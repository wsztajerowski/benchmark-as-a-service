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
        assertThat(calls).doesNotContain("deregisterImage:" + PREVIOUS_AMI);
    }

    /**
     * Found live: a build that fails its test stage — the contract — has already registered its AMI,
     * and Image Builder leaves it, snapshot and all. Nothing else names it.
     */
    @Test
    void anImageThatFailedItsTestsIsRetiredAndThePointerKept() {
        ssm.parameters.put(POINTER, PREVIOUS_AMI);
        imageBuilder.terminalStatus = ImageStatus.FAILED;
        imageBuilder.failureReason = "component-contract failed";
        imageBuilder.amiId = NEW_AMI;
        ec2.images.put(NEW_AMI, imageWithSnapshots(NEW_AMI, "snap-failed"));
        ec2.images.put(PREVIOUS_AMI, taggedImage(PREVIOUS_AMI, "1.0.0", "ami-parent"));

        assertThatThrownBy(() -> service().publish(PIPELINE, POINTER, "1.1.0", "ami-parent"))
            .hasMessageContaining("component-contract failed");

        assertThat(calls)
            .contains("deregisterImage:" + NEW_AMI, "deleteSnapshot:snap-failed")
            .doesNotContain("deregisterImage:" + PREVIOUS_AMI);
        assertThat(ssm.parameters).containsEntry(POINTER, PREVIOUS_AMI);
    }

    /**
     * Review P14: only FAILED, CANCELLED and DELETED used to end the wait, so a status outside
     * both lists polled forever. Anything that is neither AVAILABLE nor in progress now fails.
     */
    @Test
    void aStatusThatIsNeitherDoneNorInProgressEndsTheWait() {
        ssm.parameters.put(POINTER, PREVIOUS_AMI);
        for (ImageStatus status : List.of(ImageStatus.DEPRECATED, ImageStatus.DISABLED, ImageStatus.UNKNOWN_TO_SDK_VERSION)) {
            imageBuilder.terminalStatus = status;

            assertThatThrownBy(() -> service().publish(PIPELINE, POINTER, "1.1.0", "ami-parent"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Image build");
        }
        assertThat(ssm.parameters).containsEntry(POINTER, PREVIOUS_AMI);
    }

    @Test
    void firstEverBuildRetiresNothing() throws Exception {
        imageBuilder.amiId = NEW_AMI;
        ec2.images.put(NEW_AMI, taggedImage(NEW_AMI, "1.0.0", "ami-parent"));

        service().publish(PIPELINE, POINTER, "1.0.0", "ami-parent");

        assertThat(ssm.parameters).containsEntry(POINTER, NEW_AMI);
        assertThat(calls).noneMatch(call -> call.startsWith("deregisterImage"));
    }

    /**
     * Retirement runs after the pointer already names the new image, so everything that matters
     * has succeeded by the time it can fail. An AMI someone already removed by hand leaves nothing
     * to retire; failing the command for that would report a good build as broken and invite a
     * second ~15-minute rebuild.
     */
    @Test
    void aReplacedImageThatIsAlreadyGoneDoesNotFailTheBuild() throws Exception {
        ssm.parameters.put(POINTER, PREVIOUS_AMI);
        // Deliberately absent from ec2.images: describeImages answers InvalidAMIID.NotFound.
        imageBuilder.amiId = NEW_AMI;
        ec2.images.put(NEW_AMI, taggedImage(NEW_AMI, "1.1.0", "ami-parent"));

        String published = service().publish(PIPELINE, POINTER, "1.1.0", "ami-parent");

        assertThat(published).isEqualTo(NEW_AMI);
        assertThat(ssm.parameters)
            .as("the build succeeded and the pointer moved — cleanup failing afterwards changes neither")
            .containsEntry(POINTER, NEW_AMI);
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

    /**
     * Only a missing AMI means "no image". A denied describe used to be reported as one too, which
     * sends the operator off to rebuild an image that exists when the fix is a permission.
     */
    @Test
    void aDeniedDescribeIsAnErrorNotAMissingImage() {
        ssm.parameters.put(POINTER, NEW_AMI);
        ec2.describeImagesErrorCode = "UnauthorizedOperation";

        assertThatThrownBy(() -> service().currentImage(POINTER))
            .isInstanceOf(software.amazon.awssdk.services.ec2.model.Ec2Exception.class)
            .hasMessageContaining("UnauthorizedOperation");
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

    /**
     * Guards the API contract, not an observed failure. The stack replaces the Component on every
     * version bump and deletes its predecessor, so only one version is registered at a time and
     * the first page always holds it. This pins the query against the day that stops being true —
     * reading a single page cannot tell "not registered" from "not on this page", which is the
     * same blindness as {@code byName} above and fails the same way: a doomed build starts.
     */
    @Test
    void preflightFindsAVersionBeyondTheFirstPage() {
        for (int minor = 0; minor <= 30; minor++) {
            imageBuilder.registeredComponents.put("1." + minor + ".0", "# published " + minor);
        }
        imageBuilder.pageSize = 25;

        assertThatThrownBy(() -> service()
            .preflightVersion("a1b2c3d4-runner-toolchain", "1.30.0", "# edited"))
            .as("version 1.30.0 is registered, just not on the first page")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("1.30.0");

        assertThat(imageBuilder.pagesServed)
            .as("one request means only the first 25 versions were ever considered")
            .isGreaterThan(1);
        assertThat(imageBuilder.startedPipelines).isEmpty();
    }

    @Test
    void preflightAcceptsAnUnchangedVersion() {
        String component = "name: baas-runner-toolchain\n# unchanged";
        imageBuilder.registeredComponents.put("1.0.0", component);

        service().preflightVersion("a1b2c3d4-runner-toolchain", "1.0.0", component);
    }

    /**
     * Image Builder strips the trailing newline from a component document when it stores it, and
     * {@code RunnerImageRenderer.renderComponent()} ends with one because its template is a Java
     * text block. An exact comparison therefore reported "content differs" for content that was
     * byte-identical apart from that newline — and it blocked every build on a fresh installation,
     * because `baas admin setup` registers the component before `build-image` ever runs. Found
     * against a live account, not in this suite.
     */
    @Test
    void preflightIgnoresATrailingNewlineTheRegistryStrips() {
        imageBuilder.registeredComponents.put("1.0.0", "name: baas-runner-toolchain\n# unchanged");

        service().preflightVersion(
            "a1b2c3d4-runner-toolchain", "1.0.0", "name: baas-runner-toolchain\n# unchanged\n");
    }

    /** Only trailing whitespace is forgiven — a real edit must still be caught. */
    @Test
    void preflightStillRejectsAnEditThatIsNotJustTrailingWhitespace() {
        imageBuilder.registeredComponents.put("1.0.0", "name: baas-runner-toolchain\n# unchanged");

        assertThatThrownBy(() -> service().preflightVersion(
            "a1b2c3d4-runner-toolchain", "1.0.0", "name: baas-runner-toolchain\n# edited\n"))
            .isInstanceOf(IllegalStateException.class);
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

    // ─── retireInstallation: what teardown leaves behind ─────────────────────────

    private static final String RECIPE = "a1b2c3d4-recipe-runner";
    private static final String RECORDS = "arn:aws:imagebuilder:eu-central-1:123456789012:image/" + RECIPE;

    private void recordBuilds(String version, int count) {
        var builds = new java.util.ArrayList<String>();
        for (int i = 1; i <= count; i++) {
            builds.add(RECORDS + "/" + version + "/" + i);
        }
        imageBuilder.imageRecords.put(RECORDS + "/" + version, builds);
    }

    private static long remaining(java.util.Map<String, List<String>> records) {
        return records.values().stream().mapToLong(List::size).sum();
    }

    @Test
    void retiringAnInstallationRemovesThePointerTheAmiItsSnapshotsAndEveryRecord() {
        ssm.parameters.put(POINTER, NEW_AMI);
        ec2.images.put(NEW_AMI, imageWithSnapshots(NEW_AMI, "snap-current"));
        recordBuilds("1.2.0", 3);
        recordBuilds("1.1.0", 1);
        String otherInstallation = "arn:aws:imagebuilder:eu-central-1:123456789012:image/a1b2c3d4-dev-recipe-runner/1.2.0";
        imageBuilder.imageRecords.put(otherInstallation, new java.util.ArrayList<>(List.of(otherInstallation + "/1")));

        List<String> leftovers = service().retireInstallation(POINTER, RECIPE);

        assertThat(leftovers).isEmpty();
        assertThat(ssm.parameters).doesNotContainKey(POINTER);
        assertThat(ec2.images).doesNotContainKey(NEW_AMI);
        assertThat(calls).contains("deregisterImage:" + NEW_AMI, "deleteSnapshot:snap-current");
        assertThat(imageBuilder.imageRecords.get(otherInstallation))
            .as("another installation's records are not this teardown's to delete")
            .containsExactly(otherInstallation + "/1");
        assertThat(imageBuilder.deletedImages).hasSize(4);
        assertThat(imageBuilder.imageNameFilters).containsOnly(RECIPE);
    }

    @Test
    void noPointerIsNotAnErrorAndTheRecordsStillGo() {
        recordBuilds("1.2.0", 1);

        assertThat(service().retireInstallation(POINTER, RECIPE)).isEmpty();
        assertThat(calls).noneMatch(call -> call.startsWith("deregisterImage:"));
        assertThat(remaining(imageBuilder.imageRecords)).isZero();
    }

    /** What build-image's own retirement can leave: a pointer surviving its AMI (fix F5). */
    @Test
    void aPointerNamingAnAmiThatIsAlreadyGoneIsStillDeleted() {
        ssm.parameters.put(POINTER, NEW_AMI);

        assertThat(service().retireInstallation(POINTER, RECIPE)).isEmpty();
        assertThat(ssm.parameters).doesNotContainKey(POINTER);
        assertThat(calls).noneMatch(call -> call.startsWith("deregisterImage:"));
    }

    /**
     * The pointer is what would let a later setup launch an inherited image, so it goes even when
     * its AMI cannot — and the AMI that stays is named, with the command that removes it.
     */
    @Test
    void aFailedDeregisterIsALeftoverAndThePointerAndRecordsStillGo() {
        ssm.parameters.put(POINTER, NEW_AMI);
        ec2.images.put(NEW_AMI, imageWithSnapshots(NEW_AMI, "snap-current"));
        ec2.deregisterErrorCode = "UnauthorizedOperation";
        recordBuilds("1.2.0", 2);

        List<String> leftovers = service().retireInstallation(POINTER, RECIPE);

        assertThat(leftovers).singleElement().asString()
            .contains(NEW_AMI, "aws ec2 deregister-image --image-id " + NEW_AMI);
        assertThat(ssm.parameters).doesNotContainKey(POINTER);
        assertThat(remaining(imageBuilder.imageRecords)).isZero();
    }

    @Test
    void recordsSpreadOverSeveralPagesAreAllDeleted() {
        imageBuilder.pageSize = 2;
        recordBuilds("1.2.0", 5);
        recordBuilds("1.1.0", 3);
        recordBuilds("1.0.0", 1);

        assertThat(service().retireInstallation(POINTER, RECIPE)).isEmpty();
        assertThat(imageBuilder.deletedImages).hasSize(9);
        assertThat(remaining(imageBuilder.imageRecords)).isZero();
    }

    /** W4: teardown's closing notice must not claim a snapshot it left behind. */
    @Test
    void aSnapshotThatCannotBeDeletedIsALeftover() {
        ssm.parameters.put(POINTER, NEW_AMI);
        ec2.images.put(NEW_AMI, imageWithSnapshots(NEW_AMI, "snap-stuck"));
        ec2.deleteSnapshotErrorCode = "InvalidSnapshot.InUse";

        assertThat(service().retireInstallation(POINTER, RECIPE)).singleElement().asString()
            .contains("snap-stuck", NEW_AMI, "aws ec2 delete-snapshot --snapshot-id snap-stuck");
        assertThat(ssm.parameters).doesNotContainKey(POINTER);
    }
}
