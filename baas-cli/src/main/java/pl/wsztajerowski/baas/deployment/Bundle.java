package pl.wsztajerowski.baas.deployment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The on-disk layout of an export: one file or directory per part, and a manifest written last, so
 * that a directory without one is an export that did not finish. Nothing else names these paths.
 */
final class Bundle {

    /** The layout's version. A CLI reads every version it knows and refuses a newer one by name. */
    static final int SCHEMA_VERSION = 1;

    static final String RESULTS_FILE = "results.jsonl";
    static final String RECORDINGS_DIR = "recordings";
    static final String EXTENSION_FILE = "runner-image-extension.yaml";
    static final String MANIFEST_FILE = "manifest.json";

    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * Where the bundle came from and what it holds. {@code extensionHash} is null both when the
     * extension was not exported and when none was deployed; {@code parts} tells the two apart.
     */
    record Manifest(
        int schemaVersion,
        String deployment,
        String account,
        String region,
        String exportedAt,
        String cliVersion,
        List<String> parts,
        boolean withoutInputs,
        long items,
        long measurementItems,
        long jobItems,
        long objects,
        long bytes,
        String extensionHash) {

        Set<Part> partSet() {
            var set = java.util.EnumSet.noneOf(Part.class);
            parts.forEach(id -> set.add(Part.byId(id)));
            return set;
        }
    }

    private final Path root;

    Bundle(Path root) {
        this.root = root;
    }

    Path root() {
        return root;
    }

    Path results() {
        return root.resolve(RESULTS_FILE);
    }

    Path recordings() {
        return root.resolve(RECORDINGS_DIR);
    }

    Path extension() {
        return root.resolve(EXTENSION_FILE);
    }

    Path manifestFile() {
        return root.resolve(MANIFEST_FILE);
    }

    /** Whether the directory is absent or empty: the only places an export writes into. */
    boolean isEmptyOrAbsent() throws IOException {
        if (!Files.exists(root)) {
            return true;
        }
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries.findAny().isEmpty();
        }
    }

    /** Written to a temporary name and moved, so a manifest is never seen half-written. */
    void writeManifest(Manifest manifest) throws IOException {
        Path partial = root.resolve(MANIFEST_FILE + ".partial");
        JSON.writeValue(partial.toFile(), manifest);
        Files.move(partial, manifestFile(), StandardCopyOption.ATOMIC_MOVE);
    }

    Optional<Manifest> readManifest() throws IOException {
        if (!Files.isRegularFile(manifestFile())) {
            return Optional.empty();
        }
        return Optional.of(JSON.readValue(manifestFile().toFile(), Manifest.class));
    }
}
