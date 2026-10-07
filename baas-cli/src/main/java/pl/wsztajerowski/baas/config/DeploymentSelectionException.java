package pl.wsztajerowski.baas.config;

/**
 * No deployment could be chosen from this machine's files: none configured, an unknown name, or two
 * or more with no {@code --deployment}. A usage error whose message already says what to type, so
 * {@code BaasApp} reports it without pointing at a stack trace that would show nothing more.
 */
public class DeploymentSelectionException extends IllegalStateException {

    public DeploymentSelectionException(String message) {
        super(message);
    }
}
