package pl.wsztajerowski.baas.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.imagebuilder.ImagebuilderClient;
import software.amazon.awssdk.services.imagebuilder.model.Filter;
import software.amazon.awssdk.services.imagebuilder.model.ImageStatus;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Builds and publishes the single runner AMI.
 *
 * <p>The ordering in {@link #publish} is the part that matters: the pointer is repointed
 * <em>before</em> the replaced image is retired. Retiring first would aim
 * {@code /<prefix>/runner/ami-id} at a deregistered AMI for the whole ~15-minute build, failing
 * every run launched in that window.
 */
public class ImageBuilderService {

    private static final Logger logger = LoggerFactory.getLogger(ImageBuilderService.class);

    /** Terminal states — anything else means the build is still in flight. */
    private static final List<ImageStatus> SUCCEEDED = List.of(ImageStatus.AVAILABLE);
    private static final List<ImageStatus> FAILED =
        List.of(ImageStatus.FAILED, ImageStatus.CANCELLED, ImageStatus.DELETED);

    private final ImagebuilderClient imageBuilder;
    private final Ec2Client ec2;
    private final SsmClient ssm;
    private final Duration pollInterval;

    public ImageBuilderService(ImagebuilderClient imageBuilder, Ec2Client ec2, SsmClient ssm) {
        this(imageBuilder, ec2, ssm, Duration.ofSeconds(30));
    }

    ImageBuilderService(ImagebuilderClient imageBuilder, Ec2Client ec2, SsmClient ssm, Duration pollInterval) {
        this.imageBuilder = imageBuilder;
        this.ec2 = ec2;
        this.ssm = ssm;
        this.pollInterval = pollInterval;
    }

    /**
     * Builds a new image and publishes it, retiring the one it replaces.
     *
     * <p>Every step that could leave the account inconsistent is ordered so that a failure stops
     * before it does damage: a failed build throws before the pointer moves, and nothing is
     * deregistered until the pointer names the replacement.
     *
     * @return the AMI ID now published at {@code parameterName}
     */
    public String publish(String pipelineArn, String parameterName,
                          String imageVersion, String parentAmiId) throws InterruptedException {
        Optional<String> replaced = readPointer(parameterName);
        replaced.ifPresent(amiId -> logger.debug("Current runner AMI: {}", amiId));

        String newAmiId = build(pipelineArn);
        ensureIdentityTags(newAmiId, imageVersion, parentAmiId);

        writePointer(parameterName, newAmiId);
        logger.info("Published {} to {}", newAmiId, parameterName);

        // Only after the repoint. The remaining race — a run that read the old ID before this
        // write and calls RunInstances after the deregister — is accepted: single-operator
        // scale, and it fails loudly as InvalidAMIID.NotFound rather than silently.
        replaced
            .filter(previous -> !previous.equals(newAmiId))
            .ifPresent(this::retire);

        return newAmiId;
    }

    /**
     * Starts the pipeline and blocks until the build reaches a terminal state.
     *
     * @throws IllegalStateException carrying Image Builder's own failure reason, which is the only
     *                               thing that says why a bake failed
     */
    public String build(String pipelineArn) throws InterruptedException {
        String buildArn = imageBuilder.startImagePipelineExecution(r -> r.imagePipelineArn(pipelineArn))
            .imageBuildVersionArn();
        logger.info("Image build started: {}", buildArn);

        while (true) {
            var image = imageBuilder.getImage(r -> r.imageBuildVersionArn(buildArn)).image();
            ImageStatus status = image.state().status();

            if (SUCCEEDED.contains(status)) {
                return image.outputResources().amis().stream()
                    .findFirst()
                    .map(ami -> ami.image())
                    .orElseThrow(() -> new IllegalStateException(
                        "Image build " + buildArn + " reported AVAILABLE but distributed no AMI"));
            }
            if (FAILED.contains(status)) {
                throw new IllegalStateException("Image build %s: %s%s".formatted(
                    status, buildArn,
                    image.state().reason() != null ? " — " + image.state().reason() : ""));
            }

            logger.info("Building image ({})...", image.state().statusAsString());
            Thread.sleep(pollInterval.toMillis());
        }
    }

    /**
     * Fails when the declared version is already registered carrying different content.
     *
     * <p>Image Builder components are immutable at a version, so this would otherwise surface as a
     * stack-update rejection roughly a minute in, naming the resource and not the fix.
     */
    public void preflightVersion(String componentName, String imageVersion, String renderedComponent) {
        registeredComponentData(componentName, imageVersion).ifPresent(registered -> {
            if (!registered.equals(renderedComponent)) {
                throw new IllegalStateException("""
                    Recipe version %s is already registered and its content differs.
                    Bump imageVersion in infra/runner-image.yaml.

                    An Image Builder component is immutable at a given version, so the edit you made \
                    cannot be published under the version it still declares."""
                    .formatted(imageVersion));
            }
            logger.debug("Version {} is registered with identical content — rebuilding it as-is", imageVersion);
        });
    }

    /**
     * The component document registered at this version, or empty when the version is new.
     *
     * <p>Deliberately without {@code byName}: that flag collapses every version of a component into
     * a single summary row whose ARN ends in a literal {@code x.x.x} and which carries no version
     * at all. Filtering that by version matches nothing, so the preflight silently concludes the
     * version is unregistered and lets the build proceed into a stack update that Image Builder
     * then rejects — which is the failure this method exists to prevent. Found by running it.
     */
    Optional<String> registeredComponentData(String componentName, String imageVersion) {
        var versions = imageBuilder.listComponents(r -> r
                .owner("Self")
                .filters(Filter.builder().name("name").values(componentName).build()))
            .componentVersionList();

        return versions.stream()
            .filter(version -> imageVersion.equals(version.version()))
            .findFirst()
            .flatMap(version -> imageBuilder
                .listComponentBuildVersions(r -> r.componentVersionArn(version.arn()))
                .componentSummaryList().stream()
                .findFirst()
                .map(summary -> imageBuilder.getComponent(r -> r.componentBuildVersionArn(summary.arn()))
                    .component().data()));
    }

    /**
     * The AMI's tags are set by the stack's DistributionConfiguration, so this is a backstop for an
     * image built before those tags existed. Without them {@code baas admin image} has no identity
     * to report and {@code baas run} has no {@code imageVersion} to tag results with.
     */
    void ensureIdentityTags(String amiId, String imageVersion, String parentAmiId) {
        var existing = describeImage(amiId).orElseThrow(() -> new IllegalStateException(
            "Image build produced " + amiId + ", but it cannot be described"));

        if (imageVersion.equals(existing.imageVersion()) && parentAmiId.equals(existing.parentAmiId())) {
            return;
        }
        logger.debug("Applying identity tags to {}", amiId);
        ec2.createTags(r -> r.resources(amiId).tags(
            Tag.builder().key(RunnerImage.VERSION_TAG).value(imageVersion).build(),
            Tag.builder().key(RunnerImage.PARENT_TAG).value(parentAmiId).build()));
    }

    /**
     * Deregisters an AMI and deletes the snapshots behind it. Snapshot IDs have to be collected
     * first — once the AMI is deregistered its block device mapping is no longer readable, and the
     * snapshots would sit there billing indefinitely.
     */
    public void retire(String amiId) {
        List<String> snapshots = ec2.describeImages(r -> r.imageIds(amiId)).images().stream()
            .flatMap(image -> image.blockDeviceMappings().stream())
            .filter(mapping -> mapping.ebs() != null && mapping.ebs().snapshotId() != null)
            .map(mapping -> mapping.ebs().snapshotId())
            .toList();

        logger.info("Retiring replaced image {} ({} snapshot(s))", amiId, snapshots.size());
        ec2.deregisterImage(r -> r.imageId(amiId));

        for (String snapshotId : snapshots) {
            try {
                ec2.deleteSnapshot(r -> r.snapshotId(snapshotId));
            } catch (Ec2Exception e) {
                // A leaked snapshot costs cents a month and is visible in the console; failing the
                // whole command here would strand a build that has already succeeded.
                logger.warn("Could not delete snapshot {} of retired image {}: {}",
                    snapshotId, amiId, e.getMessage());
            }
        }
    }

    /** The published image, or empty when no build has completed or the AMI is gone. */
    public Optional<RunnerImage> currentImage(String parameterName) {
        return readPointer(parameterName).flatMap(this::describeImage);
    }

    /** Empty when the AMI does not exist — which is how {@code baas run --ami-id} validates it. */
    public Optional<RunnerImage> describeImage(String amiId) {
        try {
            return ec2.describeImages(r -> r.imageIds(amiId)).images().stream()
                .findFirst()
                .map(image -> {
                    var tags = image.tags().stream()
                        .collect(java.util.stream.Collectors.toMap(Tag::key, Tag::value, (a, b) -> a));
                    return new RunnerImage(
                        image.imageId(),
                        tags.get(RunnerImage.VERSION_TAG),
                        tags.get(RunnerImage.PARENT_TAG),
                        image.creationDate());
                });
        } catch (Ec2Exception e) {
            // A pointer naming a deregistered AMI is InvalidAMIID.NotFound, which is a normal
            // state to report — not an error to propagate.
            logger.debug("Cannot describe {}: {}", amiId, e.getMessage());
            return Optional.empty();
        }
    }

    Optional<String> readPointer(String parameterName) {
        return new SsmService(ssm).getParameterOptional(parameterName);
    }

    void writePointer(String parameterName, String amiId) {
        new SsmService(ssm).putStringParameter(parameterName, amiId);
    }
}
