package pl.wsztajerowski.baas.deployment;

import pl.wsztajerowski.baas.jobs.Ec2ProvisioningService.LiveRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@link TeardownAws} in memory, recording each call in order; a step fails when told to. */
final class FakeTeardownAws implements TeardownAws {

    final List<String> calls = new ArrayList<>();
    List<LiveRunner> live = List.of();
    Map<String, String> parameters = new HashMap<>();
    List<String> imageLeftovers = List.of();
    RuntimeException failParameters;
    RuntimeException failEmpty;
    RuntimeException failDelete;

    @Override
    public List<LiveRunner> liveRunners(String deployment) {
        calls.add("liveRunners " + deployment);
        return live;
    }

    @Override
    public Map<String, String> stackParameters(String stack) {
        calls.add("stackParameters " + stack);
        if (failParameters != null) throw failParameters;
        return parameters;
    }

    @Override
    public void emptyBucket(String bucket) {
        calls.add("emptyBucket " + bucket);
        if (failEmpty != null) throw failEmpty;
    }

    @Override
    public void deleteStack(String stack) {
        calls.add("deleteStack " + stack);
        if (failDelete != null) throw failDelete;
    }

    @Override
    public List<String> retireImage(String pointer, String recipe) {
        calls.add("retireImage " + pointer);
        return imageLeftovers;
    }
}
