package pl.wsztajerowski.baas.model;

/**
 * The {@code benchmark-runner} options user-data passes. The CLI renders them into the script and
 * the runner declares them in its picocli annotations; both read the names from here, so a renamed
 * option fails to compile on one side instead of failing a paid job on the instance. Constants, not
 * an enum, because annotation attributes need compile-time constants.
 */
public final class RunnerArgs {

    public static final String JOB_ID = "--job-id";
    public static final String CREATED_AT = "--created-at";
    public static final String RESULT_PATH = "--result-path";
    public static final String S3_BUCKET = "--s3-bucket";
    public static final String BENCHMARK_PATH = "--benchmark-path";
    public static final String RESULTS_TABLE = "--results-table";
    public static final String TAG = "--tag";
    public static final String PROJECT = "--project";

    private RunnerArgs() {
    }
}
