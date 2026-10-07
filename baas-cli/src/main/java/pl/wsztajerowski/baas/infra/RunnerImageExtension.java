package pl.wsztajerowski.baas.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The operator's extension to the runner image: a raw AWSTOE component document, stored in the
 * stack exactly as written, with no BaaS schema.
 *
 * <p>A file on a laptop is only a working copy of it. Its first line records which deployed
 * extension it was pulled from — the base marker — and a push whose marker no longer names the
 * deployed extension is refused, because the stack keeps only the current value: an overwritten
 * extension cannot be recovered from AWS. The marker is never stored and never hashed.
 */
public final class RunnerImageExtension {

    public static final String MARKER_PREFIX = "# baas-extension-base:";
    public static final String NONE = "none";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** The extension travels as a stack parameter, which CloudFormation caps at this. */
    public static final int LIMIT_BYTES = RunnerImageRenderer.CFN_PARAMETER_LIMIT_BYTES;

    /**
     * What {@code baas admin deployment setup} writes and a pull of a deployment with no extension prints.
     * Comments only, so pushing it unchanged deploys no extension.
     */
    static final String STARTER = """
        # Runner image extension: an AWSTOE component document, run after the BaaS base and before
        # the BaaS contract. Install anything here; it is stored in the deployment's stack as
        # written, comments included, and counts against a 4096-byte limit. ASCII only.
        #
        # The contract fails the bake if the image no longer has: Java at or above the runner's
        # version on PATH, the aws CLI, async-profiler under /app/async-profiler, a perf matching the
        # running kernel, kernel.perf_event_paranoid <= 1 and kernel.kptr_restrict = 0. Everything
        # else (another JDK vendor, transparent hugepages, swap) is yours to change, and every job
        # records what it measured on in environment.json.
        #
        # Never put a credential here: anyone who can describe the stack can read this document.
        #
        # Keep the first line. It names the extension this file was based on, and a push from a
        # copy that has since been replaced by someone else is refused.
        #
        # Push:  baas admin image build --extension <this file>
        # Pull:  baas admin image show --extension > <this file>
        #
        # name: runner-extension
        # schemaVersion: 1.0
        # phases:
        #   - name: build
        #     steps:
        #       - name: InstallBpftrace
        #         action: ExecuteBash
        #         inputs:
        #           commands:
        #             - dnf install -y bpftrace
        """;

    private RunnerImageExtension() {
    }

    /** A file split into its base marker, if any, and the content that would be stored. */
    public record WorkingCopy(Optional<String> baseMarker, String content) {
    }

    /**
     * Strips a leading base marker. A document of nothing but comments and blank lines is no
     * extension at all, and is returned as empty content.
     *
     * <p>Trailing whitespace is dropped: CloudFormation returns a parameter without its trailing
     * newline, and content that is not stored as sent would look changed on every comparison.
     */
    public static WorkingCopy parse(String fileText) {
        Optional<String> marker = Optional.empty();
        String body = fileText;
        int firstLineEnd = fileText.indexOf('\n');
        String firstLine = firstLineEnd < 0 ? fileText : fileText.substring(0, firstLineEnd);
        if (firstLine.startsWith(MARKER_PREFIX)) {
            marker = Optional.of(firstLine.substring(MARKER_PREFIX.length()).strip());
            body = firstLineEnd < 0 ? "" : fileText.substring(firstLineEnd + 1);
        }
        boolean commentsOnly = body.lines().map(String::strip)
            .allMatch(line -> line.isEmpty() || line.startsWith("#"));
        return new WorkingCopy(marker, commentsOnly ? "" : body.stripTrailing());
    }

    /**
     * The first eight hex characters of the SHA-256 of the content, trailing whitespace excluded —
     * Image Builder drops a trailing newline when it stores a document, and a hash that moved with
     * it would label one extension two ways. The same content yields the same hash everywhere.
     */
    public static String hash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.stripTrailing().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** {@code none} for no extension, its hash otherwise — the value a marker names. */
    public static String identity(String content) {
        return content.isEmpty() ? NONE : hash(content);
    }

    /** The working copy a pull prints: the marker, then the deployed content or the starter. */
    public static String withMarker(String deployedContent) {
        String body = deployedContent.isEmpty() ? STARTER : deployedContent + "\n";
        return MARKER_PREFIX + " " + identity(deployedContent) + "\n" + body;
    }

    /**
     * Step names grouped by phase, in document order — the readable side of an opaque hash. Empty
     * when the document does not parse as an AWSTOE component: it is reported, not validated, here.
     */
    public static Map<String, List<String>> stepsByPhase(String content) {
        Map<String, List<String>> steps = new LinkedHashMap<>();
        try {
            JsonNode phases = YAML.readTree(content).path("phases");
            for (JsonNode phase : phases) {
                List<String> names = new ArrayList<>();
                phase.path("steps").forEach(step -> names.add(step.path("name").asText("?")));
                steps.put(phase.path("name").asText("?"), names);
            }
        } catch (IOException e) {
            return Map.of();
        }
        return steps;
    }

    /**
     * Refuses content that cannot travel as a stack parameter, before anything is submitted.
     *
     * <p>Non-ASCII is refused because the stack is the record, and it does not read back as written:
     * {@code DescribeStacks} returns each non-ASCII character as {@code ?}, although the component
     * itself receives it intact. Found live: an em dash in a comment made every reader — the pull,
     * the stale-push guard, the change detection and {@code baas admin image show} — hash different bytes
     * from the ones the image was baked and labelled with.
     */
    public static void requireStorable(String content) {
        String[] lines = content.split("\n", -1);
        for (int line = 0; line < lines.length; line++) {
            for (int column = 0; column < lines[line].length(); column++) {
                char c = lines[line].charAt(column);
                if (c > 0x7E || (c < 0x20 && c != '\t' && c != '\r')) {
                    throw new IllegalStateException(
                        "The extension has a non-ASCII character (U+%04X) at line %d, column %d. It is stored as a "
                            .formatted((int) c, line + 1, column + 1)
                            + "stack parameter, which reads back with such characters replaced by '?', so it "
                            + "would not be stored as written. Replace it with ASCII.");
                }
            }
        }
        requireFits(content);
    }

    /** Refuses content larger than a stack parameter can carry. */
    static void requireFits(String content) {
        int size = content.getBytes(StandardCharsets.UTF_8).length;
        if (size > LIMIT_BYTES) {
            throw new IllegalStateException(
                "The extension is %d bytes; it is stored as a stack parameter, which CloudFormation caps at %d."
                    .formatted(size, LIMIT_BYTES));
        }
    }

    /**
     * Refuses a push based on a copy of the extension that has since been replaced. With no
     * extension deployed, any file is accepted — that is what lets a file kept across a teardown be
     * pushed to the new deployment. With one deployed, the file must have been pulled from it.
     */
    public static void requireCurrent(WorkingCopy file, String deployedContent) {
        if (deployedContent.isEmpty()) {
            return;
        }
        String deployed = hash(deployedContent);
        if (file.baseMarker().filter(deployed::equals).isEmpty()) {
            throw new IllegalStateException("""
                The deployment's extension is %s, but this file %s.
                  Someone may have pushed since you pulled; pushing would discard their extension,
                  and the stack keeps no earlier copy. Pull the deployed one, re-apply your edit,
                  and push again:
                    baas admin image show --extension > <file>"""
                .formatted(deployed, file.baseMarker()
                    .map(marker -> "was pulled from " + marker)
                    .orElse("carries no '" + MARKER_PREFIX + "' line")));
        }
    }
}
