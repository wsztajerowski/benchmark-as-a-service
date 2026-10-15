package pl.wsztajerowski.baas.deployment.setup;

import pl.wsztajerowski.baas.session.ConfigService;
import pl.wsztajerowski.baas.console.Console;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/** One walkthrough run against {@link FakeSetupAws}, with scripted answers and no real sleeping. */
final class SetupRun {

    final Path root;
    final FakeSetupAws aws = new FakeSetupAws();
    final StringWriter out = new StringWriter();
    final List<Duration> slept = new ArrayList<>();
    /** Lines the user types, in order; a closed input once they run out. */
    final Deque<String> answers = new ArrayDeque<>();
    /** Every prompt shown, in order — the text before each answer read. */
    final List<String> prompts = new ArrayList<>();

    boolean interactive;
    Optional<String> deployment = Optional.empty();
    String region;
    String profile;
    Path policyFile;
    String githubOrg;
    List<String> githubRepos = List.of();
    boolean revoke;
    boolean buildImage;

    SetupContext context;

    SetupRun(Path root) {
        this.root = root;
    }

    SetupRun interactive(String... typed) {
        interactive = true;
        answers.addAll(Arrays.asList(typed));
        return this;
    }

    SetupRun deployment(String name) {
        deployment = Optional.of(name);
        return this;
    }

    SetupOptions options() {
        return new SetupOptions(deployment, region, profile, policyFile, githubOrg, githubRepos, revoke, buildImage);
    }

    int run() throws Exception {
        var writer = new PrintWriter(out, true);
        var console = Console.withFlags(writer, interactive, false);
        StringBuilder shown = new StringBuilder();
        var recording = Console.withFlags(new PrintWriter(new java.io.Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) {
                shown.append(buffer, offset, length);
                writer.write(buffer, offset, length);
            }

            @Override
            public void flush() {
                writer.flush();
            }

            @Override
            public void close() {
            }
        }, true), interactive, false);
        var prompter = new Prompter(recording, () -> {
            prompts.add(shown.toString());
            shown.setLength(0);
            return answers.isEmpty() ? null : answers.pop();
        });
        context = new SetupContext(options(), new ConfigService(root, deployment), interactive, prompter, console,
            aws.factory(), slept::add);
        return new SetupWalkthrough(SetupSteps.all()).run(context);
    }

    /** Whether any prompt shown so far contains {@code text}. */
    boolean asked(String text) {
        return prompts.stream().anyMatch(prompt -> prompt.contains(text));
    }

    String deploymentFile(String name) throws java.io.IOException {
        return java.nio.file.Files.readString(root.resolve("deployments").resolve(name + ".yaml"));
    }

    boolean hasFile(String name) {
        return java.nio.file.Files.exists(root.resolve("deployments").resolve(name + ".yaml"));
    }
}
