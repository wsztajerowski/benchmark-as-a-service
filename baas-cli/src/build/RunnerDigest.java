import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Writes the SHA-256 of the runner JAR this reactor built into a properties file the CLI JAR
 * carries, so a released CLI can tell whether the runner in a deployment's bucket is its own. Run
 * by the build before resources are processed, as a single-file program: no plugin hashes a file.
 *
 * <p>A runner JAR that is absent (the CLI built on its own) yields an empty digest. Such a build
 * cannot launch on the pinned runner anyway, and a release checks the digest it shipped.
 *
 * <p>Usage: {@code java RunnerDigest.java <runner.jar> <out.properties>}
 */
public class RunnerDigest {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        Path out = Path.of(args[1]);
        String digest = Files.isRegularFile(jar)
            ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)))
            : "";
        Files.createDirectories(out.getParent());
        Files.writeString(out, "# The runner JAR built beside this CLI. Read by BaasVersion.runnerSha256().\n"
            + "runner.sha256=" + digest + "\n");
    }
}
