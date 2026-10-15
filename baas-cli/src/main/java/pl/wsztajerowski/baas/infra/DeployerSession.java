package pl.wsztajerowski.baas.infra;

import pl.wsztajerowski.baas.config.BaasConfig;

/**
 * The deployer's clients — the {@code OperatorSession} counterpart for {@code baas admin}. Unlike
 * the operator, the deployer's credentials are used as they are: the deployer policy grants what
 * setup, teardown and image builds need, and nothing assumes a role on the way. One place builds
 * them, so the four admin commands cannot each resolve the profile or the region their own way.
 */
public final class DeployerSession {

    private DeployerSession() {
    }

    /** For a configured deployment: its stored region and deployer profile (the default chain when unset). */
    public static AwsClientFactory factory(BaasConfig config) {
        return factory(config.getAws().resolveRegion(), config.getAws().getDeployerProfile());
    }

    /** For a region and profile named explicitly — setup, before the deployment's file exists. */
    public static AwsClientFactory factory(String region, String profile) {
        return new AwsClientFactory(region, AwsClientFactory.profile(profile));
    }
}
