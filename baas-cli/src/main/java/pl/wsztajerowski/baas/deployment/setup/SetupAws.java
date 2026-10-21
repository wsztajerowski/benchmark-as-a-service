package pl.wsztajerowski.baas.deployment.setup;

import pl.wsztajerowski.baas.aws.BucketProbe;
import pl.wsztajerowski.baas.image.ImageBuilderService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every AWS call setup makes, answering in terms setup reasons about rather than SDK types. One
 * port for all the steps: caller identity and the bucket probe are needed by several, and a fake
 * per step would repeat them. The real adapter is {@link AwsSetupAws}; tests use a hand-written
 * fake.
 */
public interface SetupAws {

    record Caller(String arn, String account) {}

    /** The deployer-permission check. {@link NotChecked} is never shown as met. */
    sealed interface Preflight {
        record Met() implements Preflight {}
        record Denied(List<String> actions) implements Preflight {}
        record NotChecked() implements Preflight {}
    }

    /** The account's GitHub OIDC identity provider. */
    sealed interface OidcProvider {
        record Absent() implements OidcProvider {}
        record Present(List<String> clientIds) implements OidcProvider {}
    }

    /** The deployment's runner image, against the base bundled with this CLI. */
    sealed interface Image {
        record Absent() implements Image {}
        /** Built on an older base than the one bundled. */
        record Behind(String label) implements Image {}
        record Current(String label) implements Image {}
    }

    /** A build of the deployment's pipeline that has not finished. */
    record RunningBuild(Instant started) {}

    /** The issuer every GitHub Actions workload token names. */
    String GITHUB_ISSUER = "token.actions.githubusercontent.com";
    /** The audience the operator role's trust condition requires. */
    String STS_AUDIENCE = "sts.amazonaws.com";

    static String providerArn(String account) {
        return "arn:aws:iam::" + account + ":oidc-provider/" + GITHUB_ISSUER;
    }

    /** @throws software.amazon.awssdk.core.exception.SdkException when the credentials cannot be used */
    Caller callerIdentity();

    BucketProbe bucket(String name);

    boolean tableExists(String name);

    Preflight preflight(String callerArn, String policy);

    /**
     * The three answers setup acts on (review K13): none, so it creates; rolled back, which holds
     * nothing, so it deletes and creates again; or present, so it updates.
     */
    enum StackState {
        ABSENT, ROLLED_BACK, PRESENT;

        /** Setup is creating the stack, not updating one. */
        public boolean creating() {
            return this != PRESENT;
        }
    }

    StackState stackState(String stack);

    /** Waits out any {@code *_IN_PROGRESS} status, then answers as {@link #stackState}. */
    StackState awaitSettled(String stack) throws InterruptedException;

    void deleteStack(String stack);

    Map<String, String> stackParameters(String stack);

    Map<String, String> stackOutputs(String stack);

    /**
     * The runner-image parameters a stack submission carries: the deployed ones where they exist,
     * the bundled base and a freshly resolved parent otherwise — which on a create is everything.
     */
    Map<String, String> imageParameters(Map<String, String> deployed);

    /** Returns once CloudFormation accepted the create. */
    void submitCreate(String stack, Map<String, String> parameters);

    /** @throws pl.wsztajerowski.baas.aws.CloudFormationService.StackOperationException when the create fails */
    void awaitCreate(String stack);

    /** @return whether anything changed */
    boolean update(String stack, Map<String, String> parameters);

    /** @throws RuntimeException when the role cannot be assumed */
    void assumeRole(String roleArn);

    OidcProvider oidcProvider(String arn);

    /** Creates the GitHub provider with the STS audience only: no thumbprint, no tags. */
    void createOidcProvider();

    Image image(String deployment);

    Optional<RunningBuild> runningImageBuild(String deployment);

    /**
     * Builds the image, waiting for or adopting an interrupted build as {@link ImageBuilderService}
     * does. @throws IllegalStateException with the reason when the build is refused or fails
     */
    String buildImage(String deployment) throws InterruptedException;
}
