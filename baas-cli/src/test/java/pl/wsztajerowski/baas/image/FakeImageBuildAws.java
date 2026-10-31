package pl.wsztajerowski.baas.image;

import pl.wsztajerowski.baas.session.CoreStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@link ImageBuildAws} in memory, recording each call in order. */
final class FakeImageBuildAws implements ImageBuildAws {

    final List<String> calls = new ArrayList<>();
    boolean stackExists = true;
    final Map<String, String> parameters = new HashMap<>();
    final Map<String, String> outputs = new HashMap<>(Map.of(CoreStack.OUTPUT_RUNNER_IMAGE_PIPELINE_ARN, "arn:pipeline"));
    RuntimeException failPreflight;
    Map<String, String> submitted;
    boolean closed;

    @Override
    public boolean stackExists(String stack) {
        calls.add("stackExists");
        return stackExists;
    }

    @Override
    public Map<String, String> stackParameters(String stack) {
        calls.add("stackParameters");
        return parameters;
    }

    @Override
    public Map<String, String> stackOutputs(String stack) {
        calls.add("stackOutputs");
        return outputs;
    }

    @Override
    public String resolveParent(String amiName, String region) {
        calls.add("resolveParent");
        return "ami-parent";
    }

    @Override
    public void preflightVersion(String component, String version, String document) {
        calls.add("preflightVersion " + version);
        if (failPreflight != null) throw failPreflight;
    }

    @Override
    public void updateStack(String stack, Map<String, String> imageParameters) {
        calls.add("updateStack");
        submitted = imageParameters;
    }

    @Override
    public String publish(String pipelineArn, String pointer, String label, String parentAmiId, String recipeVersion,
                          String bucket) {
        calls.add("publish " + pipelineArn + " " + pointer + " " + bucket);
        return "ami-new";
    }

    @Override
    public void close() {
        closed = true;
    }
}
