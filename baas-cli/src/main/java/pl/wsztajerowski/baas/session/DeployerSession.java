package pl.wsztajerowski.baas.session;

import pl.wsztajerowski.baas.aws.AwsClientFactory;
import pl.wsztajerowski.baas.aws.CloudFormationService;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * The deployer's clients — the {@code OperatorSession} counterpart for {@code baas admin}. Unlike
 * the operator, the deployer's credentials are used as they are: the deployer policy grants what
 * setup, teardown and image builds need, and nothing assumes a role on the way. One place builds
 * them, so the admin commands cannot each resolve the profile or the region their own way.
 */
public final class DeployerSession {

    /**
     * Deployments this process has already checked. Teardown builds a second factory for its export,
     * and the check's call and its hint belong to the command, not to each factory.
     */
    private static final Set<String> CHECKED = ConcurrentHashMap.newKeySet();

    private DeployerSession() {
    }

    /**
     * For a configured deployment: its stored region and deployer profile (the default chain when
     * unset). Checks this CLI against the release the deployment records first
     * ({@link CliVersionCheck}) — eagerly, so an older CLI is refused at the command's start, before
     * anything is read or deleted. @throws CliVersionCheck.Refused when this CLI is older
     */
    public static AwsClientFactory factory(BaasConfig config) {
        return factory(config, CliVersionCheck.current(), DeployerSession::recordedCliVersion);
    }

    static AwsClientFactory factory(BaasConfig config, CliVersionCheck check,
                                    BiFunction<AwsClientFactory, String, Optional<String>> recorded) {
        var factory = factory(config.getAws().resolveRegion(), config.getAws().getDeployerProfile());
        String deployment = config.requirePrefix();
        if (!CHECKED.contains(deployment)) {
            check.check(deployment, CliVersionCheck.Role.ADMIN, () -> recorded.apply(factory, config.stackName()));
            CHECKED.add(deployment);
        }
        return factory;
    }

    /**
     * For a region and profile named explicitly — setup, before the deployment's file exists. Not
     * checked here: setup checks the recorded release itself, once it knows whether a stack exists.
     */
    public static AwsClientFactory factory(String region, String profile) {
        return new AwsClientFactory(region, AwsClientFactory.profile(profile));
    }

    private static Optional<String> recordedCliVersion(AwsClientFactory factory, String stack) {
        try (var cf = factory.cloudFormation()) {
            return Optional.ofNullable(new CloudFormationService(cf).getStackParameters(stack)
                .get(CoreStack.PARAM_BAAS_CLI_VERSION));
        }
    }

    /** For tests, which check deployments of the same name more than once in one JVM. */
    static void forgetChecks() {
        CHECKED.clear();
    }
}
