package pl.wsztajerowski.baas.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Whether this CLI may act on a deployment, judged against the release that last ran setup on it —
 * the stack's {@link CoreStack#PARAM_BAAS_CLI_VERSION}. Older is refused, patch level included:
 * an older CLI's setup would submit its older template over the stack, and its readers may not
 * understand what a newer one wrote. Newer runs, with one line naming who records it. Anything the
 * check cannot decide — no stack, nothing recorded, an unreleased build — proceeds.
 *
 * <p>The sessions apply it, so every command that builds clients through one is covered by
 * construction; {@code baas jobs terminate} alone opts out, because stopping a paid instance must
 * never wait on an install.
 */
public final class CliVersionCheck {

    /** Who is asking, which decides what a newer CLI is told. */
    public enum Role {
        /** A day-to-day command: the user asks the deployment's admin. */
        OPERATOR,
        /** {@code baas admin}: the user is the admin, and runs setup. */
        ADMIN,
        /** Setup itself, which is about to record the newer version and says so afterwards. */
        SETUP
    }

    public enum Outcome { UNRELEASED, NONE_RECORDED, CURRENT, AHEAD }

    private static final Logger logger = LoggerFactory.getLogger(CliVersionCheck.class);

    private final String running;
    private final String sourceRepo;
    private final Consumer<String> warnings;

    public CliVersionCheck(String running, String sourceRepo, Consumer<String> warnings) {
        this.running = running;
        this.sourceRepo = sourceRepo;
        this.warnings = warnings;
    }

    /** The check for this build, warning through the logger (standard error). */
    public static CliVersionCheck current() {
        return new CliVersionCheck(BaasVersion.current(), BaasVersion.sourceRepo(), logger::warn);
    }

    /**
     * Compares this CLI with what {@code recorded} returns, which is asked only for a released
     * build. @throws Refused when this CLI is older
     */
    public Outcome check(String deployment, Role role, Supplier<Optional<String>> recorded) {
        if (!BaasVersion.comparable(running)) {
            warnings.accept("Unreleased build (baas %s): not checked against the release deployment %s was set up with."
                .formatted(running, deployment));
            return Outcome.UNRELEASED;
        }
        String version = recorded.get().map(String::strip).filter(v -> !v.isEmpty()).orElse(null);
        if (version == null) {
            return Outcome.NONE_RECORDED;
        }
        int order = BaasVersion.compare(running, version);
        if (order < 0) {
            throw new Refused(refusal(deployment, version));
        }
        if (order == 0) {
            return Outcome.CURRENT;
        }
        String setup = "baas --deployment " + deployment + " admin deployment setup";
        switch (role) {
            case OPERATOR -> warnings.accept(
                "Deployment %s was set up with baas %s; this is baas %s. Ask its admin to record the newer release: %s"
                    .formatted(deployment, version, running, setup));
            case ADMIN -> warnings.accept(
                "Deployment %s was set up with baas %s; this is baas %s. Record it, so older CLIs are refused: %s"
                    .formatted(deployment, version, running, setup));
            case SETUP -> { }
        }
        return Outcome.AHEAD;
    }

    /** This build's version when setup may record it — a released {@code x.y.z} — else empty. */
    public Optional<String> recordable() {
        return BaasVersion.comparable(running) ? Optional.of(running) : Optional.empty();
    }

    private String refusal(String deployment, String recorded) {
        return """
            Deployment %s was set up with baas %s; this is baas %s, which is older. Nothing was done.
              Update baas, then run the command again:
                %s""".formatted(deployment, recorded, running, installCommand(sourceRepo));
    }

    /** The one command that updates any installation: the newest release's own installer. */
    public static String installCommand(String sourceRepo) {
        return "curl -fsSL https://github.com/" + sourceRepo + "/releases/latest/download/install.sh | sh";
    }

    /**
     * This CLI is older than the deployment's recorded release. {@code BaasApp.reportFailure} exits 1
     * with the message alone: it already says what to run, and a stack trace would add nothing.
     */
    public static final class Refused extends RuntimeException {
        Refused(String message) {
            super(message, null, false, false);
        }
    }
}
