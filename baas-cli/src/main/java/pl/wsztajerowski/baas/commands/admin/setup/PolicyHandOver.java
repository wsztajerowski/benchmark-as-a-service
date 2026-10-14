package pl.wsztajerowski.baas.commands.admin.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gives the deployer policy to whoever attaches it: the file or standard output, and the IAM
 * administrator's commands, filled in. Used by the permission step and by the two later steps
 * that find a stale policy only when a call is refused.
 */
final class PolicyHandOver {

    private static final Logger logger = LoggerFactory.getLogger(PolicyHandOver.class);
    static final String DEFAULT_FILE = "policy.json";

    private PolicyHandOver() {}

    /**
     * Unattended, the policy is the payload on standard output, so {@code setup > policy.json} hands
     * it over; {@code --policy-file} has written it already. Interactively, without the option, the
     * user chooses: saved to a file (the default) or printed.
     */
    static void handOver(SetupContext context) {
        Path file = context.options.policyFile();
        boolean printed = false;
        if (file == null && context.interactive()) {
            if (context.policySavedTo == null) {
                String answer = context.prompter().ask(
                    "Save the deployer policy to a file, or type - to print it", DEFAULT_FILE);
                if ("-".equals(answer)) {
                    context.payload().println(context.policy);
                    printed = true;
                } else {
                    context.policySavedTo = Path.of(answer);
                }
            }
            if (context.policySavedTo != null) {
                // Written again on every hand-over: a name changed since renders another document.
                if (!write(context.policySavedTo, context.policy)) {
                    context.payload().println(context.policy);
                    printed = true;
                }
                file = context.policySavedTo;
            }
        } else if (file == null) {
            context.payload().println(context.policy);
            printed = true;
        }
        String where = file != null
            ? "It is in " + file + "."
            : printed && context.interactive() ? "It is printed above; save it as " + DEFAULT_FILE + "."
            : "It is printed on standard output (redirect it: > " + DEFAULT_FILE + ").";
        logger.error("Attach the deployer policy rendered for account {}, region {}, deployment {}. {}\n{}",
            context.caller.account(), context.region, context.deployment, where,
            administratorCommands(context.caller.account(), context.deployment, context.caller.arn(),
                file != null ? file.toString() : DEFAULT_FILE));
    }

    /** Writes the policy; {@code false}, with the reason logged, when it cannot. */
    static boolean write(Path file, String policy) {
        try {
            Files.writeString(file, policy + System.lineSeparator());
            logger.info("Deployer policy written to {}", file);
            return true;
        } catch (IOException e) {
            logger.error("Cannot write the deployer policy to {}: {}", file, e.getMessage());
            return false;
        }
    }

    /**
     * The IAM administrator's commands, filled in. Both the first attachment and the upgrade are
     * given, because the deployer cannot read customer-managed policies to tell which applies.
     * Customer-managed and named per deployment: two deployments' documents exceed the 5120
     * characters IAM shares across every inline policy on a principal.
     */
    static String administratorCommands(String accountId, String deployment, String callerArn, String file) {
        String name = "BaasCliDeployerPolicy-" + deployment;
        String arn = "arn:aws:iam::" + accountId + ":policy/" + name;
        return """
              For an IAM administrator, first time:
                aws iam create-policy --policy-name %s --policy-document file://%s
                %s
              To update an attached policy after a CLI upgrade:
                aws iam create-policy-version --policy-arn %s --policy-document file://%s --set-as-default"""
            .formatted(name, file, attachCommand(callerArn, arn), arn, file);
    }

    private static String attachCommand(String callerArn, String policyArn) {
        String resource = callerArn.substring(callerArn.lastIndexOf(':') + 1);
        if (resource.startsWith("user/")) {
            String user = resource.substring(resource.lastIndexOf('/') + 1);
            return "aws iam attach-user-policy --user-name " + user + " --policy-arn " + policyArn;
        }
        String role = resource.startsWith("assumed-role/")
            ? resource.substring("assumed-role/".length()).split("/")[0]
            : resource.substring(resource.lastIndexOf('/') + 1);
        if (role.startsWith("AWSReservedSSO_")) {
            return "(" + role + " belongs to IAM Identity Center: add " + policyArn
                + " to its permission set instead of attaching it to the role)";
        }
        return "aws iam attach-role-policy --role-name " + role + " --policy-arn " + policyArn;
    }

    /** What a step says while waiting for the policy, in either mode. */
    static String attachThen(SetupContext context) {
        return context.interactive()
            ? "Once the policy is attached, press Enter: setup checks the permissions again and carries on. "
                + "IAM can take a few seconds to apply a new policy."
            : "Once an IAM administrator has attached it, run setup again. Nothing was created.";
    }
}
