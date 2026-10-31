package pl.wsztajerowski.baas.model;

import java.util.regex.Pattern;

/**
 * The JDKs a job can select, as the CLI, the runner, user-data and the image contract all name them:
 * every one is a directory {@code /opt/jdks/<id>} on the runner image, and {@code baas run --jdk <id>}
 * starts the benchmark with its {@code bin/java}. The base links its Corretto there as
 * {@link #DEFAULT_ID}; an extension adds more with {@code baas-install-jdk}.
 */
public final class Jdks {

    /** Where every selectable JDK lives on the runner image. */
    public static final String ROOT = "/opt/jdks";

    /** The base's Corretto, and the JDK a job without {@code --jdk} runs on. */
    public static final String DEFAULT_ID = "corretto-25";

    /**
     * Lower-case, a letter or digit first, then letters, digits, {@code .} and {@code -}: an id is a
     * directory name, a tag value and part of an AMI tag joined by {@code ,}, so it can hold nothing
     * that is special to any of them, and no {@code /} or leading {@code .} that would leave the root.
     */
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9.-]*");

    public static final int MAX_ID_LENGTH = 32;

    private Jdks() {
    }

    public static boolean isValidId(String id) {
        return id != null && id.length() <= MAX_ID_LENGTH && ID_PATTERN.matcher(id).matches();
    }
}
