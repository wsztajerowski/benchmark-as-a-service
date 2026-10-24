package pl.wsztajerowski.baas.deployment.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Step 10: the runner image, offered when it is missing or older than the base this CLI bundles —
 * never built by surprise: unattended only with {@code --build-image}. A build an interrupted run
 * left behind is waited for or adopted rather than repeated.
 */
final class ImageStep implements SetupStep {

    static final String NAME = "image";
    private static final Logger logger = LoggerFactory.getLogger(ImageStep.class);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome run(SetupContext context) throws InterruptedException {
        SetupAws aws = context.aws();
        Optional<SetupAws.RunningBuild> running = aws.runningImageBuild(context.deployment);
        boolean build;
        if (running.isPresent()) {
            String started = CLOCK.format(running.get().started());
            build = context.options.buildImage() || !context.interactive() || context.prompter().confirm(
                "A runner-image build started at " + started + " is still running. Wait for it and publish it? "
                    + "It can take up to 15 minutes", true);
            if (!build) {
                logger.info("Leaving that build running. Once it has finished, "
                    + "`baas --deployment {} admin image build` publishes it without building again.", context.deployment);
                context.imageMissing = true;
                return SetupStep.next();
            }
        } else {
            switch (aws.image(context.deployment)) {
                case SetupAws.Image.Current current -> {
                    logger.info("Runner image: {} (current)", current.label());
                    return SetupStep.next();
                }
                case SetupAws.Image.Behind behind ->
                    logger.info("Runner image {} is built on an older base than this CLI bundles.", behind.label());
                case SetupAws.Image.Absent absent ->
                    logger.info("This deployment has no runner image yet, and `baas run` needs one.");
            }
            build = context.options.buildImage() || context.interactive()
                && context.prompter().confirm("Build the runner image now? It takes about 15 minutes", true);
            if (!build) {
                context.imageMissing = true;
                return SetupStep.next();
            }
        }
        try {
            String amiId = aws.buildImage(context.deployment);
            context.imageBuilt = true;
            logger.info("Runner image published: {}", amiId);
        } catch (IllegalStateException e) {
            return new Outcome.Stop(1, e.getMessage());
        }
        return SetupStep.next();
    }
}
