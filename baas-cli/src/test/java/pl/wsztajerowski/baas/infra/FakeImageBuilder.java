package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.services.imagebuilder.ImagebuilderClient;
import software.amazon.awssdk.services.imagebuilder.model.Component;
import software.amazon.awssdk.services.imagebuilder.model.ComponentSummary;
import software.amazon.awssdk.services.imagebuilder.model.ComponentVersion;
import software.amazon.awssdk.services.imagebuilder.model.GetComponentRequest;
import software.amazon.awssdk.services.imagebuilder.model.GetComponentResponse;
import software.amazon.awssdk.services.imagebuilder.model.GetImageRequest;
import software.amazon.awssdk.services.imagebuilder.model.GetImageResponse;
import software.amazon.awssdk.services.imagebuilder.model.Image;
import software.amazon.awssdk.services.imagebuilder.model.ImageStatus;
import software.amazon.awssdk.services.imagebuilder.model.ListComponentBuildVersionsRequest;
import software.amazon.awssdk.services.imagebuilder.model.ListComponentBuildVersionsResponse;
import software.amazon.awssdk.services.imagebuilder.model.ListComponentsRequest;
import software.amazon.awssdk.services.imagebuilder.model.ListComponentsResponse;
import software.amazon.awssdk.services.imagebuilder.model.StartImagePipelineExecutionRequest;
import software.amazon.awssdk.services.imagebuilder.model.StartImagePipelineExecutionResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory Image Builder. Only the five calls {@link ImageBuilderService} makes are implemented;
 * everything else inherits the interface default and throws, so a new call site shows up as a
 * failing test rather than a silent no-op.
 */
class FakeImageBuilder implements ImagebuilderClient {

    /** Version → registered component document, as {@code preflightVersion} reads it back. */
    final Map<String, String> registeredComponents = new LinkedHashMap<>();
    final List<String> startedPipelines = new ArrayList<>();
    /** byName queries cannot return a version, so the preflight must never issue one. */
    int byNameQueries;

    String amiId = "ami-unset";
    ImageStatus terminalStatus = ImageStatus.AVAILABLE;
    String failureReason;

    private static final String BUILD_ARN =
        "arn:aws:imagebuilder:eu-central-1:123456789012:image/a1b2c3d4-runner/1.0.0/1";

    @Override
    public StartImagePipelineExecutionResponse startImagePipelineExecution(StartImagePipelineExecutionRequest request) {
        startedPipelines.add(request.imagePipelineArn());
        return StartImagePipelineExecutionResponse.builder().imageBuildVersionArn(BUILD_ARN).build();
    }

    @Override
    public GetImageResponse getImage(GetImageRequest request) {
        var image = Image.builder()
            .arn(request.imageBuildVersionArn())
            .state(ImageBuilderServiceTest.state(terminalStatus, failureReason))
            .outputResources(ImageBuilderServiceTest.amiOutput(amiId))
            .build();
        return GetImageResponse.builder().image(image).build();
    }

    /**
     * Reproduces the behaviour that broke the preflight in production: with {@code byName}, Image
     * Builder collapses every version into one summary row carrying no version and an ARN ending
     * in a literal {@code x.x.x}. Filtering that by version matches nothing.
     */
    @Override
    public ListComponentsResponse listComponents(ListComponentsRequest request) {
        String name = "a1b2c3d4-runner-toolchain";
        String prefix = "arn:aws:imagebuilder:eu-central-1:123456789012:component/" + name + "/";

        if (Boolean.TRUE.equals(request.byName())) {
            byNameQueries++;
            return ListComponentsResponse.builder()
                .componentVersionList(ComponentVersion.builder()
                    .name(name)
                    .arn(prefix + "x.x.x")
                    .build())
                .build();
        }

        var versions = registeredComponents.keySet().stream()
            .map(version -> ComponentVersion.builder()
                .name(name)
                .version(version)
                .arn(prefix + version)
                .build())
            .toList();
        return ListComponentsResponse.builder().componentVersionList(versions).build();
    }

    @Override
    public ListComponentBuildVersionsResponse listComponentBuildVersions(ListComponentBuildVersionsRequest request) {
        var summary = ComponentSummary.builder().arn(request.componentVersionArn() + "/1").build();
        return ListComponentBuildVersionsResponse.builder().componentSummaryList(summary).build();
    }

    @Override
    public GetComponentResponse getComponent(GetComponentRequest request) {
        String version = versionFromBuildArn(request.componentBuildVersionArn());
        return GetComponentResponse.builder()
            .component(Component.builder().version(version).data(registeredComponents.get(version)).build())
            .build();
    }

    /** {@code …/component/<name>/<version>/<build>} → {@code <version>}. */
    private static String versionFromBuildArn(String arn) {
        String[] segments = arn.split("/");
        return segments[segments.length - 2];
    }

    @Override
    public String serviceName() {
        return "imagebuilder";
    }

    @Override
    public void close() {
    }
}
