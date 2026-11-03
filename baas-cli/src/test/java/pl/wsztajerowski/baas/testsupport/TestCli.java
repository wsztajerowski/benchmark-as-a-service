package pl.wsztajerowski.baas.testsupport;

import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs a {@code baas} command line the way {@code main} does — the real command tree, its failure
 * handling and exit codes — against a configuration root of the test's own (T5). A command whose
 * collaborators the test replaces is handed in already built with {@link #with}: its port hook set
 * and, where the test reads the payload through a console of its own, {@code context.use(console)};
 * every other command is created as usual. What picocli writes is captured; the logger's
 * {@code System.err} is captured only by {@link #runCapturingLog}, which needs
 * {@code @ResourceLock(Resources.SYSTEM_ERR)} on the test.
 */
public final class TestCli {

    /** The exit code and what the command line printed. */
    public record Run(int exit, String out, String err) {
    }

    private final Path root;
    private final Map<Class<?>, Object> commands = new HashMap<>();

    private TestCli(Path root) {
        this.root = root;
    }

    /** A command line reading its deployments from {@code root}, never {@code ~/.baas}. */
    public static TestCli at(Path root) {
        return new TestCli(root);
    }

    /** {@code command} wherever the tree would create a {@code type}. */
    public <C> TestCli with(Class<C> type, C command) {
        commands.put(type, command);
        return this;
    }

    public Run run(String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        int exit = commandLine().setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true)).execute(args);
        return new Run(exit, out.toString(), err.toString());
    }

    /** {@link #run}, with the logger's output appended to {@link Run#err}. */
    public Run runCapturingLog(String... args) {
        var log = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
        try {
            Run run = run(args);
            return new Run(run.exit(), run.out(), run.err() + log.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(original);
        }
    }

    /** The command tree, for a test that drives picocli itself. */
    public CommandLine commandLine() {
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        return BaasApp.commandLine(new BaasApp(root), new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> type) throws Exception {
                Object command = commands.get(type);
                return command != null ? (K) command : defaults.create(type);
            }
        });
    }
}
