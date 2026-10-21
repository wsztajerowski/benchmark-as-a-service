package pl.wsztajerowski.baas.deployment;

import pl.wsztajerowski.baas.jobs.Ec2ProvisioningService.LiveRunner;

import java.util.List;
import java.util.Map;

/**
 * Every AWS call {@code baas admin deployment teardown} makes, under deployer credentials, in the
 * shape of {@code RunAws} and {@code SetupAws}: the command keeps the order, which is what the
 * teardown invariants are about, and a recording fake can pin it (review T2).
 */
interface TeardownAws {

    /** Pending or running runners of this deployment, by their {@code baas-deployment} tag. */
    List<LiveRunner> liveRunners(String deployment);

    Map<String, String> stackParameters(String stack);

    /** Deletes every object; CloudFormation will not delete a non-empty bucket. */
    void emptyBucket(String bucket);

    /** Deletes the stack and waits for it to be gone. */
    void deleteStack(String stack);

    /** Retires the image built outside the stack; returns what could not be removed, never throws for it. */
    List<String> retireImage(String pointer, String recipe);
}
