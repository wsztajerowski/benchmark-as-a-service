package pl.wsztajerowski.baas.model.dynamodb;

import pl.wsztajerowski.baas.model.JobStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The condition every job-item write after the reservation carries. Two runtimes evaluate it: the
 * CLI's recorder, and the instance's {@code job_status} in user-data, which receives this exact
 * expression rendered into the script. It lives in the model, not in either of them, so neither can
 * redefine "terminal" without the other — the vocabulary itself comes from {@link JobStatus}.
 */
public final class JobItemConditions {

    private static final String FAILED_PREFIX_VALUE = ":failedPrefix";

    /**
     * The values {@link #NOT_TERMINAL} names, by placeholder: one per {@link JobStatus#EXACT_TERMINAL}
     * status, then the {@code failed:} prefix. Generated, so a terminal status added to
     * {@link JobStatus} reaches the guard the CLI and the instance both evaluate.
     */
    public static final Map<String, String> NOT_TERMINAL_VALUES = notTerminalValues();

    /**
     * True unless the stored status is terminal. {@code failed:<n>} is matched by prefix, so exit
     * codes need no enumeration; {@code status} is a reserved word, hence {@code #status}.
     */
    public static final String NOT_TERMINAL =
        "(attribute_not_exists(#status) OR NOT (#status IN ("
            + String.join(", ", NOT_TERMINAL_VALUES.keySet().stream().filter(k -> !k.equals(FAILED_PREFIX_VALUE)).toList())
            + ") OR begins_with(#status, " + FAILED_PREFIX_VALUE + ")))";

    /**
     * The item exists — a write never creates a job item; only the CLI's reservation does. This is
     * user-data's spelling; the CLI's recorder writes the same check through an attribute-name
     * placeholder, {@code attribute_exists(#pk)}, which its other conditions also use.
     */
    public static final String EXISTS = "attribute_exists(" + MeasurementItemMapper.PK + ")";

    private JobItemConditions() {
    }

    private static Map<String, String> notTerminalValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < JobStatus.EXACT_TERMINAL.size(); i++) {
            values.put(":terminal" + i, JobStatus.EXACT_TERMINAL.get(i));
        }
        values.put(FAILED_PREFIX_VALUE, JobStatus.FAILED_PREFIX);
        return Collections.unmodifiableMap(values);
    }
}
