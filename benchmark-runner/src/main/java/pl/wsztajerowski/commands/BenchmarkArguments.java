package pl.wsztajerowski.commands;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import pl.wsztajerowski.baas.model.BenchmarkArgs;
import pl.wsztajerowski.baas.model.BenchmarkType;

import java.util.List;

/**
 * The runner's own check of the arguments after its {@code --}, the same one {@code baas run} makes
 * before launching: a job started without the CLI — a LocalStack script, a hand-run runner — is
 * refused alike, before the benchmark starts and before anything is uploaded.
 */
final class BenchmarkArguments {

    private BenchmarkArguments() {
    }

    static void refuseManagedOrNoRun(CommandSpec spec, BenchmarkType type, List<String> arguments) {
        BenchmarkArgs reading = BenchmarkArgs.of(type);
        reading.firstManaged(arguments).ifPresent(managed -> {
            throw new ParameterException(spec.commandLine(),
                "%s is managed by BaaS and cannot be set after --".formatted(managed));
        });
        if (reading.listsProfilersBesideOthers(arguments)) {
            throw new ParameterException(spec.commandLine(),
                "-lprof lists %s's profilers and exits, ignoring everything else; it must be the only argument after --"
                    .formatted(reading.tool()));
        }
        reading.firstNoRun(arguments).ifPresent(noRun -> {
            throw new ParameterException(spec.commandLine(),
                "%s makes %s print and exit without measuring; the job would measure nothing"
                    .formatted(noRun, reading.tool()));
        });
    }
}
