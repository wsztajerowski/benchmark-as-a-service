package pl.wsztajerowski.baas.image;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `baas run` resolves its AMI before it uploads or launches anything, so every failure here costs
 * nothing. These assert the resolution itself; that it runs first is pinned by
 * {@code JobLaunchTest.withNoRunnerImageNothingIsUploadedOrReserved}.
 */
class RunnerImageResolutionTest {

    private static final String PREFIX = "a1b2c3d4";
    private static final String POINTER = "/a1b2c3d4/runner/ami-id";

    private final List<String> calls = new ArrayList<>();
    private final FakeEc2 ec2 = new FakeEc2(calls);
    private final FakeSsm ssm = new FakeSsm(calls);

    private ImageBuilderService images() {
        return new ImageBuilderService(new FakeImageBuilder(), ec2, ssm);
    }

    @Test
    void aMissingPointerResolvesToNothing() {
        assertThat(images().currentImage(pl.wsztajerowski.baas.session.DeploymentNames.of(PREFIX).amiPointer()))
            .as("with no image built, `baas run` must stop before provisioning anything")
            .isEmpty();
        assertThat(calls).isEmpty();
    }

    /** A pointer can outlive its AMI — a build that was retired, or a hand-deregistered image. */
    @Test
    void aPointerNamingADeregisteredAmiResolvesToNothing() {
        ssm.parameters.put(POINTER, "ami-gone");

        assertThat(images().currentImage(pl.wsztajerowski.baas.session.DeploymentNames.of(PREFIX).amiPointer())).isEmpty();
    }

    @Test
    void resolvesThePublishedImage() {
        ssm.parameters.put(POINTER, "ami-current");
        ec2.images.put("ami-current", tagged("ami-current", "1.2.0"));

        assertThat(images().currentImage(pl.wsztajerowski.baas.session.DeploymentNames.of(PREFIX).amiPointer()))
            .hasValueSatisfying(image -> {
                assertThat(image.amiId()).isEqualTo("ami-current");
                assertThat(image.imageVersion()).isEqualTo("1.2.0");
            });
    }

    private static Image tagged(String amiId, String version) {
        return Image.builder()
            .imageId(amiId)
            .creationDate("2026-08-11T00:00:00Z")
            .tags(Tag.builder().key(RunnerImage.VERSION_TAG).value(version).build())
            .build();
    }
}
