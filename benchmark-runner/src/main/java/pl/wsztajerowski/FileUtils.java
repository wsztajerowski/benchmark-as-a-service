package pl.wsztajerowski;

import java.nio.file.Path;

public class FileUtils {

    /**
     * The directory holding the runner's JAR — on the instance {@code /app} — where local storage
     * writes and the default JAR paths resolve. Not the log walk's root: that is the process's working
     * directory ({@code JobLogs}). Anchored on this class, which ships in the same JAR as every other
     * runner class.
     */
    public static Path getWorkingDirectory() {
        return Path.of(FileUtils.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getParent();
    }
}
