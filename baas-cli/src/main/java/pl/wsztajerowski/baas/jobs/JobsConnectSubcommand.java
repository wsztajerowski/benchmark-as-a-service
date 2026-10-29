package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.cli.JobReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.aws.AwsErrors;
import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.cli.Invocation;
import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.session.OperatorSession;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * An interactive shell on a job's live instance, through Session Manager: no inbound port, no key.
 * Any job of the deployment qualifies, whoever launched it, as long as its instance is pending or
 * running — the instance decides, not the stored status, so a job stuck before {@code running} can
 * be looked at. A session changes nothing about the job: it is not recorded, and it never holds off
 * any of the instance's termination paths.
 */
@Command(
    name = "connect",
    mixinStandardHelpOptions = true,
    description = "Open a shell on a running job's instance through Session Manager. "
        + "Needs session-manager-plugin installed locally.",
    separator = " "
)
public class JobsConnectSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(JobsConnectSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    /** Which deployment, as its operator role; opened by {@link #call()}. */
    private OperatorSession session;

    OperatorSession session() {
        if (session == null) {
            session = OperatorSession.of(Invocation.configService(spec));
        }
        return session;
    }

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    /** Where the plugin is looked for; tests point it elsewhere. */
    String searchPath = System.getenv("PATH");

    /** Runs the plugin with the terminal handed over; tests replace it. */
    SessionLauncher.Runner runner = SessionLauncher.INHERIT_TERMINAL;

    /** How often the instance is looked at while a session is open; tests shorten it. */
    Duration instanceCheck = Duration.ofSeconds(15);

    /** How long the plugin gets to exit on its own once its session was ended. */
    Duration pluginGrace = Duration.ofSeconds(10);

    @Parameters(index = "0", paramLabel = "<jobId>", description = "The job to connect to, as baas jobs list shows it.")
    String jobId;

    /** What connecting reads and starts. Replaced by tests, which have no AWS. */
    interface Sources extends AutoCloseable {
        String region();

        Optional<JobItem> job(String jobId);

        Optional<Ec2ProvisioningService.LiveInstance> liveInstance(String jobId);

        boolean agentOnline(String instanceId);

        /** Whether the instance may still be running: pending, running, or not known to be otherwise. */
        boolean instanceLive(String instanceId);

        /** Throws {@link Refused} when Session Manager has no such document for this deployment. */
        SessionLauncher.Started start(String instanceId, String document);

        void terminate(String sessionId);

        @Override
        void close();

        /** Session Manager refused for want of what setup creates: the document, or the grants. */
        final class Refused extends RuntimeException {
            Refused(String message, Throwable cause) {
                super(message, cause);
            }
        }
    }

    Sources sources(BaasConfig config) {
        return new AwsConnectSources(session().factory(), config);
    }

    @Override
    public Integer call() {
        String notAJobId = JobReference.notAJobId(jobId);
        if (notAJobId != null) {
            throw CommandRefusal.usage(notAJobId);
        }
        // Before any AWS call, the operator-role assumption included: without the plugin there is
        // nothing to attach to, and a session started anyway would linger until its idle timeout.
        Path plugin = SessionPlugin.find(searchPath)
            .orElseThrow(() -> CommandRefusal.refused(SessionPlugin.missing(System.getProperty("os.name"))));
        BaasConfig config = session().config();
        String document = config.names().sessionDocument();

        try (var sources = sources(config)) {
            JobItem job = sources.job(jobId).orElseThrow(() -> CommandRefusal.refused(JobReference.noSuchJob(jobId)));
            var instance = sources.liveInstance(jobId).orElseThrow(() -> CommandRefusal.refused(
                "Job " + jobId + " has no pending or running instance (stored status: " + job.status()
                    + "), so there is nothing to connect to."));
            if (!sources.agentOnline(instance.instanceId())) {
                throw CommandRefusal.refused("Instance " + instance.instanceId() + " of job " + jobId
                    + " is not reachable yet: its Session Manager agent registers about a minute after boot. "
                    + "Retry shortly.");
            }
            console().println(lifetime(job, instance, Instant.now()));
            return attach(sources, plugin, instance.instanceId(), document);
        } catch (Sources.Refused e) {
            throw outdated(e);
        } catch (RuntimeException e) {
            if (AwsErrors.isAccessDenied(e)) {
                throw outdated(e);
            }
            throw e;
        }
    }

    /** The deadline when the job recorded its bound; otherwise only that the instance will end. */
    static String lifetime(JobItem job, Ec2ProvisioningService.LiveInstance instance, Instant now) {
        return WatchdogDeadline.of(instance.launchTime(), job.watchdogSeconds())
            .map(deadline -> "Connecting to " + instance.instanceId() + ". Its watchdog terminates it by "
                + WatchdogDeadline.describe(deadline, now) + ", or earlier when the job finishes; "
                + "the session ends with it.")
            .orElse("Connecting to " + instance.instanceId() + ". It terminates when the job finishes or its "
                + "watchdog fires, and the session ends with it.");
    }

    /**
     * Runs the plugin until it exits, ending the session when the instance does. Session Manager
     * does not close a session whose target terminated, and the plugin has no escape key — in raw
     * mode Ctrl+C goes to a shell that no longer exists — so without this the terminal hangs.
     */
    private int attach(Sources sources, Path plugin, String instanceId, String document) {
        SessionLauncher.Started started = sources.start(instanceId, document);
        SessionLauncher.Attached process;
        try {
            process = runner.start(SessionLauncher.command(plugin, started, sources.region(), instanceId, document));
        } catch (IOException e) {
            endQuietly(sources, started);
            throw CommandRefusal.refused("Could not run " + plugin + ": " + e.getMessage()
                + ". The session was ended.");
        }
        try {
            while (!process.waitFor(instanceCheck)) {
                if (!sources.instanceLive(instanceId)) {
                    // The plugin owns the terminal in raw mode: a bare newline would not return the carriage.
                    console().print("\r\nInstance " + instanceId + " has terminated; ending the session.\r\n");
                    endQuietly(sources, started);
                    if (!process.waitFor(pluginGrace)) {
                        process.destroy();
                    }
                    return 0;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            endQuietly(sources, started);
            process.destroy();
            return 130;
        }
        int exit = process.exitValue();
        if (exit != 0) {
            // The plugin ends a session it attached to; one that failed may have left it open.
            endQuietly(sources, started);
        }
        return exit;
    }

    private static void endQuietly(Sources sources, SessionLauncher.Started started) {
        try {
            sources.terminate(started.sessionId());
        } catch (RuntimeException e) {
            logger.debug("Could not end session {}: {}", started.sessionId(), e.getMessage());
        }
    }

    private static CommandRefusal outdated(RuntimeException cause) {
        logger.debug("Session Manager refused the session", cause);
        return CommandRefusal.refused("The operator role cannot open a session here (" + cause.getMessage()
            + "). If this deployment was set up before `baas jobs connect` existed, re-run "
            + "`baas admin deployment setup` to add the session document and its grants.");
    }

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }
}
