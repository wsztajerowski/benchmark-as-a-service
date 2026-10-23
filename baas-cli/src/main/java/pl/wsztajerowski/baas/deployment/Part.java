package pl.wsztajerowski.baas.deployment;

import pl.wsztajerowski.baas.cli.CommandRefusal;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a bundle can hold, named the same on export and import. Each name is what {@code --parts}
 * takes and what the manifest records; each description is what both commands' help prints.
 */
enum Part {

    RESULTS("results", Help.RESULTS),
    RECORDINGS("recordings", Help.RECORDINGS),
    EXTENSION("extension", Help.EXTENSION);

    /** Compile-time constants, so both commands' {@code @Command} help can carry them verbatim. */
    static final class Help {
        static final String RESULTS = "the results table: every measurement and every job item, exactly as stored";
        static final String RECORDINGS = "every job's folder in the bucket, jobs/<project>/<jobId>/: process output, "
            + "jmh-result.json, environment.json, packages.txt, logs, profiler output and the job's input/ JARs";
        static final String EXTENSION = "the runner-image extension the deployment's stack holds, with its base marker";

        /** One line per part, for a {@code description} array. */
        static final String RESULTS_LINE = "  results     " + RESULTS;
        static final String RECORDINGS_LINE = "  recordings  " + RECORDINGS;
        static final String EXTENSION_LINE = "  extension   " + EXTENSION;

        /** Said by both commands, so nobody expects a bundle to rebuild a deployment by itself. */
        static final String NEVER_INCLUDED = "A bundle never holds the pinned runner JARs (releases/), the image "
            + "build logs (image-builds/), the runner AMI, the stack, or this machine's configuration of the "
            + "deployment: setup, the first job and `admin image build` recreate those.";

        private Help() {
        }
    }

    final String id;
    final String description;

    Part(String id, String description) {
        this.id = id;
        this.description = description;
    }

    /** A comma-separated list of part names; an unknown name is a usage error, before any AWS call. */
    static Set<Part> parse(String names) {
        EnumSet<Part> parts = EnumSet.noneOf(Part.class);
        for (String name : names.split(",")) {
            String trimmed = name.strip().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                continue;
            }
            parts.add(Arrays.stream(values()).filter(p -> p.id.equals(trimmed)).findFirst()
                .orElseThrow(() -> CommandRefusal.usage("Unknown part '" + name.strip() + "'. The parts are: "
                    + ids(EnumSet.allOf(Part.class)) + ".")));
        }
        if (parts.isEmpty()) {
            throw CommandRefusal.usage("--parts names no part. The parts are: " + ids(EnumSet.allOf(Part.class)) + ".");
        }
        return parts;
    }

    static Part byId(String id) {
        return Arrays.stream(values()).filter(p -> p.id.equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown part in manifest: " + id));
    }

    static String ids(Set<Part> parts) {
        return parts.stream().map(p -> p.id).collect(Collectors.joining(", "));
    }

}
