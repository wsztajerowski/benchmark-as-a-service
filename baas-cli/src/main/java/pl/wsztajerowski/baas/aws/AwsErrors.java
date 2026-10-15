package pl.wsztajerowski.baas.aws;

import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.util.Set;

/** How an AWS failure is classified, the same way wherever a command has to decide what it means. */
public final class AwsErrors {

    private static final Set<String> ACCESS_DENIED_CODES =
        Set.of("AccessDenied", "AccessDeniedException", "UnauthorizedOperation");

    private AwsErrors() {
    }

    /** Whether the failure, or anything that caused it, is a permissions refusal from AWS. */
    public static boolean isAccessDenied(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof AwsServiceException aws && aws.awsErrorDetails() != null
                && ACCESS_DENIED_CODES.contains(aws.awsErrorDetails().errorCode())) {
                return true;
            }
        }
        return false;
    }
}
