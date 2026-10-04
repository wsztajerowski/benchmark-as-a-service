package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.Image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParentImageResolverTest {

    private static final String RELEASE = "al2023-ami-2023.12.20260803.3-kernel-6.1-x86_64";

    private final FakeEc2 ec2 = new FakeEc2();
    private final ParentImageResolver resolver = new ParentImageResolver(ec2);

    @Test
    void resolvesTheReleaseToItsImageInTheRegion() {
        ec2.images.put("ami-07a5b367e8dc8bd92", image("ami-07a5b367e8dc8bd92", RELEASE, "x86_64"));
        ec2.images.put("ami-0arm", image("ami-0arm", RELEASE.replace("x86_64", "arm64"), "arm64"));

        assertThat(resolver.resolve(RELEASE, "us-east-1")).isEqualTo("ami-07a5b367e8dc8bd92");
        assertThat(ec2.lastDescribeImages.owners())
            .as("only Amazon's own image of the release is the release; anyone may name an AMI alike")
            .containsExactly("amazon");
    }

    /**
     * U37. AWS deprecates public AL2023 AMIs on a schedule, and a search omits deprecated ones by
     * default — the pinned release would stop resolving on its deprecation date although it still
     * launches by ID.
     */
    @Test
    void aDeprecatedReleaseStillResolves() {
        ec2.images.put("ami-070cc8ab883065d64", image("ami-070cc8ab883065d64", RELEASE, "x86_64")
            .toBuilder().deprecationTime("2026-01-01T00:00:00.000Z").build());

        assertThat(resolver.resolve(RELEASE, "eu-central-1")).isEqualTo("ami-070cc8ab883065d64");
    }

    @Test
    void aReleaseNotPublishedInTheRegionIsRefusedByName() {
        assertThatThrownBy(() -> resolver.resolve(RELEASE, "ap-south-2"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(RELEASE)
            .hasMessageContaining("not published in ap-south-2");
    }

    @Test
    void anAmbiguousReleaseIsRefusedRatherThanPickedFrom() {
        ec2.images.put("ami-a", image("ami-a", RELEASE, "x86_64"));
        ec2.images.put("ami-b", image("ami-b", RELEASE, "x86_64"));

        assertThatThrownBy(() -> resolver.resolve(RELEASE, "eu-central-1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("resolves to 2 images in eu-central-1")
            .hasMessageContaining("ami-a, ami-b");
    }

    private static Image image(String id, String name, String architecture) {
        return Image.builder().imageId(id).name(name).architecture(architecture).build();
    }
}
