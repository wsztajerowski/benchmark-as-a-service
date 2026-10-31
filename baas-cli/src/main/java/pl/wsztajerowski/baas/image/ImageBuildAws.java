package pl.wsztajerowski.baas.image;

import java.io.IOException;
import java.util.Map;

/**
 * Every AWS call one runner-image build makes, in the shape of {@code RunAws} and {@code SetupAws}:
 * {@link RunnerImageBuild} keeps the order — preflight before the stack update, the update before
 * publishing — and a recording fake pins it (review T2).
 */
interface ImageBuildAws extends AutoCloseable {

    boolean stackExists(String stack);

    Map<String, String> stackParameters(String stack);

    Map<String, String> stackOutputs(String stack);

    /** The AL2023 release's AMI in this region; exactly one Amazon-owned match is required. */
    String resolveParent(String amiName, String region);

    /** Refuses, before any stack change, a base version Image Builder already holds with other content. */
    void preflightVersion(String component, String version, String document);

    /** Submits these image parameters; every other deployed parameter is carried forward. */
    void updateStack(String stack, Map<String, String> imageParameters) throws IOException;

    /**
     * Builds or adopts the image, tags it with the JDKs its contract recorded in {@code bucket},
     * repoints the pointer, then retires the replaced AMI. Returns the new AMI.
     */
    String publish(String pipelineArn, String pointer, String label, String parentAmiId, String recipeVersion,
                   String bucket) throws InterruptedException;

    @Override
    void close();
}
