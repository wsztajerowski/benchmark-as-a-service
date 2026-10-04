package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Image;

import java.util.List;

/**
 * Resolves the base's parent — one exact AL2023 release, named by its AMI name — to that release's
 * AMI ID in the installation's region.
 *
 * <p>Not a selector: the name pins one release, so this answers "which ID does this release have
 * here", never "what is newest". Exactly one Amazon-owned image must match; anything else is
 * refused before a stack change is submitted, because a wrong parent would otherwise surface
 * minutes into a bake as a package that does not exist.
 */
public class ParentImageResolver {

    private final Ec2Client ec2;

    public ParentImageResolver(Ec2Client ec2) {
        this.ec2 = ec2;
    }

    public String resolve(String amiName, String region) {
        List<Image> images = ec2.describeImages(r -> r
                .owners("amazon")
                // A search omits deprecated AMIs unless asked, and AWS deprecates public AL2023 AMIs
                // on a schedule — the pinned parent's is 2026-11-01. Deprecation only hides an AMI
                // from searches; it still launches by ID. Without this a pinned release would stop
                // resolving on that date, and every setup and build-image with it.
                .includeDeprecated(true)
                .filters(
                    Filter.builder().name("name").values(amiName).build(),
                    Filter.builder().name("architecture").values("x86_64").build()))
            .images();

        if (images.isEmpty()) {
            throw new IllegalStateException("""
                The runner image's parent release %s is not published in %s: no Amazon-owned \
                x86_64 image has that name there."""
                .formatted(amiName, region));
        }
        if (images.size() > 1) {
            throw new IllegalStateException("""
                The runner image's parent release %s resolves to %d images in %s (%s); exactly one \
                is required, or the parent would be chosen arbitrarily."""
                .formatted(amiName, images.size(), region,
                    String.join(", ", images.stream().map(Image::imageId).toList())));
        }
        return images.getFirst().imageId();
    }
}
