package pl.wsztajerowski.baas.deployment.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.session.ConfigService;
import pl.wsztajerowski.baas.session.DeploymentNameRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Step 1: which deployment, from this machine's files and {@code --deployment} alone, before any
 * AWS call — an existing deployment supplies the stored profile the next step offers.
 */
final class DeploymentStep implements SetupStep {

    static final String NAME = "deployment";
    private static final Logger logger = LoggerFactory.getLogger(DeploymentStep.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome run(SetupContext context) {
        List<String> configured = context.configs.deployments();
        Optional<String> named = context.options.deployment();
        if (named.isPresent()) {
            String name = named.get();
            if (configured.contains(name)) {
                adopt(context, name);
            } else {
                Optional<String> violation = DeploymentNameRule.violation(name);
                context.config = new BaasConfig();
                if (violation.isPresent()) {
                    // A name some composed resource cannot carry would fail minutes into a deploy.
                    if (!context.interactive()) {
                        return new Outcome.Stop(2, "Deployment name '" + name + "' " + violation.get() + ". Nothing was done.");
                    }
                    logger.warn("Deployment name '{}' {}. Choose another.", name, violation.get());
                } else {
                    context.deployment = name;
                    context.config.setPrefix(name);
                }
            }
        } else if (configured.isEmpty()) {
            context.config = new BaasConfig();
        } else if (configured.size() == 1) {
            adopt(context, configured.getFirst());
        } else if (!context.interactive()) {
            try {
                context.configs.loadForSetup();
            } catch (IllegalStateException e) {
                return new Outcome.Stop(2, e.getMessage());
            }
        } else {
            List<String> choices = new ArrayList<>(configured);
            choices.add("a new deployment");
            Optional<Integer> chosen = context.prompter().choose("Which deployment?", choices);
            if (chosen.isEmpty()) {
                return new Outcome.Stop(1, "Nothing was changed.");
            }
            if (chosen.get() == configured.size()) {
                context.config = new BaasConfig();
            } else {
                adopt(context, configured.get(chosen.get()));
            }
        }
        return SetupStep.next();
    }

    private static void adopt(SetupContext context, String name) {
        context.config = new ConfigService(context.configs.root(), Optional.of(name)).loadForSetup();
        context.existing = true;
        context.deployment = name;
        context.storedProfile = context.config.getAws().getDeployerProfile();
        logger.info("Deployment: {} (configured on this machine)", name);
    }
}
