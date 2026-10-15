package pl.wsztajerowski.baas;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package boundaries ADR 0009 sets, checked against the sources rather than kept by convention.
 *
 * <ul>
 *   <li>{@code baas} (the root) is the composition root: it registers commands, and nothing imports it.</li>
 *   <li>The kernel — {@code cli}, {@code console}, {@code session}, {@code aws} — is what every slice
 *       may use, and it imports no slice.</li>
 *   <li>The slices are the nouns of the command tree. They import each other only along
 *       deployment → jobs → {image, results}: setup builds the image and teardown retires it and
 *       finds in-flight runners; {@code run} looks up the image and prints its own measurements.
 *       {@code config} imports no slice.</li>
 *   <li>picocli belongs to commands, the root and {@code cli}; command classes do not touch the AWS
 *       SDK — they parse and print, and their use cases talk to AWS.</li>
 * </ul>
 *
 * A new top-level package fails here until it is given a place, which is the point.
 */
class SliceBoundaryTest {

    private static final Path SOURCES = Path.of("src/main/java/pl/wsztajerowski/baas");
    private static final Pattern IMPORT = Pattern.compile(
        "^import (?:static )?pl\\.wsztajerowski\\.baas\\.(?!model\\b)((?:[a-z]+\\.)*)([A-Z]\\w*)", Pattern.MULTILINE);

    private static final String ROOT = "";
    private static final Set<String> KERNEL = Set.of("cli", "console", "session", "aws");
    /** What each kernel package may import from the rest of the kernel. */
    private static final Map<String, Set<String>> KERNEL_ORDER = Map.of(
        "console", Set.of(),
        "aws", Set.of(),
        "session", Set.of("aws"),
        "cli", Set.of("session", "console"));
    /** What each slice may import from the other slices. */
    private static final Map<String, Set<String>> SLICE_ORDER = Map.of(
        "deployment", Set.of("jobs", "image"),
        "jobs", Set.of("image", "results"),
        "image", Set.of(),
        "results", Set.of(),
        "config", Set.of());

    private record Source(String unit, String className, String text) {}

    private static List<Source> sources() {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(f -> f.toString().endsWith(".java")).map(f -> {
                Path relative = SOURCES.relativize(f);
                String unit = relative.getNameCount() == 1 ? ROOT : relative.getName(0).toString();
                String name = relative.getFileName().toString().replace(".java", "");
                try {
                    return new Source(unit, name, Files.readString(f));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code importer -> imported} for every project import that leaves the importer's unit. */
    private static List<String> crossings(Source source) {
        List<String> found = new ArrayList<>();
        Matcher m = IMPORT.matcher(source.text());
        while (m.find()) {
            String path = m.group(1);
            String target = path.isEmpty() ? ROOT : path.substring(0, path.indexOf('.'));
            if (!target.equals(source.unit())) {
                found.add(target);
            }
        }
        return found;
    }

    private static boolean isCommand(Source source) {
        return source.className().endsWith("Command") || source.className().endsWith("Subcommand");
    }

    @Test
    void everyTopLevelPackageHasAPlace() {
        assertThat(sources()).extracting(Source::unit).allSatisfy(unit ->
            assertThat(unit.equals(ROOT) || KERNEL.contains(unit) || SLICE_ORDER.containsKey(unit))
                .as("package %s is neither the root, the kernel nor a slice", unit).isTrue());
    }

    @Test
    void nothingImportsTheCompositionRoot() {
        List<String> offenders = sources().stream()
            .filter(s -> !s.unit().equals(ROOT))
            .filter(s -> crossings(s).contains(ROOT))
            .map(s -> s.unit() + "." + s.className()).toList();
        assertThat(offenders).isEmpty();
    }

    @Test
    void theKernelImportsNoSliceAndKeepsItsOwnOrder() {
        List<String> offenders = new ArrayList<>();
        for (Source s : sources()) {
            if (!KERNEL.contains(s.unit())) continue;
            for (String target : crossings(s)) {
                if (!KERNEL_ORDER.get(s.unit()).contains(target)) {
                    offenders.add(s.unit() + "." + s.className() + " -> " + target);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void slicesImportEachOtherOnlyAlongTheOrder() {
        List<String> offenders = new ArrayList<>();
        for (Source s : sources()) {
            if (!SLICE_ORDER.containsKey(s.unit())) continue;
            for (String target : crossings(s)) {
                boolean allowed = KERNEL.contains(target) || SLICE_ORDER.get(s.unit()).contains(target);
                if (!allowed) {
                    offenders.add(s.unit() + "." + s.className() + " -> " + target);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void picocliBelongsToCommandsTheRootAndCli() {
        List<String> offenders = sources().stream()
            .filter(s -> s.text().contains("\nimport picocli."))
            .filter(s -> !isCommand(s) && !s.unit().equals(ROOT) && !s.unit().equals("cli"))
            .map(s -> s.unit() + "." + s.className()).toList();
        assertThat(offenders).isEmpty();
    }

    @Test
    void commandClassesDoNotTouchTheAwsSdk() {
        List<String> offenders = sources().stream()
            .filter(SliceBoundaryTest::isCommand)
            .filter(s -> s.text().contains("\nimport software.amazon."))
            .map(s -> s.unit() + "." + s.className()).toList();
        assertThat(offenders).isEmpty();
    }
}
