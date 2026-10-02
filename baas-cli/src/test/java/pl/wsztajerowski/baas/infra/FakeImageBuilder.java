package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.services.imagebuilder.ImagebuilderClient;
import software.amazon.awssdk.services.imagebuilder.model.Component;
import software.amazon.awssdk.services.imagebuilder.model.ComponentSummary;
import software.amazon.awssdk.services.imagebuilder.model.ComponentVersion;
import software.amazon.awssdk.services.imagebuilder.model.DeleteImageRequest;
import software.amazon.awssdk.services.imagebuilder.model.DeleteImageResponse;
import software.amazon.awssdk.services.imagebuilder.model.ImageSummary;
import software.amazon.awssdk.services.imagebuilder.model.ImageVersion;
import software.amazon.awssdk.services.imagebuilder.model.ListImageBuildVersionsRequest;
import software.amazon.awssdk.services.imagebuilder.model.ListImageBuildVersionsResponse;
import software.amazon.awssdk.services.imagebuilder.model.ListImagesRequest;
import software.amazon.awssdk.services.imagebuilder.model.ListImagesResponse;
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
    /**
     * Versions returned per page, mirroring the real 25 cap. 0 means "all in one page", which is
     * what every test that does not care about pagination wants.
     */
    int pageSize;
    int pagesServed;

    /**
     * Image records: image version ARN ({@code …/image/<recipe>/<version>}) → its build version
     * ARNs. Listed by {@code listImages}/{@code listImageBuildVersions}, removed by
     * {@code deleteImage}; both listings honour {@link #pageSize}.
     */
    final Map<String, List<String>> imageRecords = new LinkedHashMap<>();
    final List<String> deletedImages = new ArrayList<>();
    /** Names passed to the listImages name filter, so a test can see which recipe was asked for. */
    final List<String> imageNameFilters = new ArrayList<>();

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

        var all = registeredComponents.keySet().stream()
            .map(version -> ComponentVersion.builder()
                .name(name)
                .version(version)
                .arn(prefix + version)
                .build())
            .toList();

        pagesServed++;
        if (pageSize <= 0) {
            return ListComponentsResponse.builder().componentVersionList(all).build();
        }

        // Image Builder caps a page at 25 and hands back a nextToken. A caller that reads only the
        // first response sees a truncated version list and cannot tell that it is truncated.
        int from = request.nextToken() == null ? 0 : Integer.parseInt(request.nextToken());
        int to = Math.min(from + pageSize, all.size());
        var page = ListComponentsResponse.builder().componentVersionList(all.subList(from, to));
        if (to < all.size()) {
            page.nextToken(Integer.toString(to));
        }
        return page.build();
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
    public ListImagesResponse listImages(ListImagesRequest request) {
        String name = request.filters().stream()
            .filter(filter -> "name".equals(filter.name()))
            .flatMap(filter -> filter.values().stream())
            .findFirst().orElse(null);
        imageNameFilters.add(name);
        var versions = imageRecords.keySet().stream()
            .filter(arn -> name == null || arn.contains(":image/" + name + "/"))
            .map(arn -> ImageVersion.builder().arn(arn).name(name).build())
            .toList();
        var page = page(versions, request.nextToken());
        var response = ListImagesResponse.builder().imageVersionList(page.items());
        return (page.next() != null ? response.nextToken(page.next()) : response).build();
    }

    @Override
    public ListImageBuildVersionsResponse listImageBuildVersions(ListImageBuildVersionsRequest request) {
        var builds = imageRecords.getOrDefault(request.imageVersionArn(), List.of()).stream()
            .map(arn -> ImageSummary.builder().arn(arn).build())
            .toList();
        var page = page(builds, request.nextToken());
        var response = ListImageBuildVersionsResponse.builder().imageSummaryList(page.items());
        return (page.next() != null ? response.nextToken(page.next()) : response).build();
    }

    @Override
    public DeleteImageResponse deleteImage(DeleteImageRequest request) {
        String arn = request.imageBuildVersionArn();
        deletedImages.add(arn);
        imageRecords.values().forEach(builds -> builds.remove(arn));
        return DeleteImageResponse.builder().imageBuildVersionArn(arn).build();
    }

    private record Page<T>(List<T> items, String next) {}

    private <T> Page<T> page(List<T> all, String token) {
        if (pageSize <= 0) {
            return new Page<>(all, null);
        }
        int from = token == null ? 0 : Integer.parseInt(token);
        int to = Math.min(from + pageSize, all.size());
        return new Page<>(all.subList(from, to), to < all.size() ? Integer.toString(to) : null);
    }

    @Override
    public String serviceName() {
        return "imagebuilder";
    }

    @Override
    public void close() {
    }
}
