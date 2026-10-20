package pl.wsztajerowski.baas.model;

import java.util.List;
import java.util.Optional;

/**
 * The {@code benchmark-runner} options the CLI and the runner share: user-data passes all but
 * {@link #PROJECT} (the project travels as a tag), and the runner declares them in its picocli
 * annotations; both read the names from here, so a renamed
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
    public static final String JOB_ID_SHORT = "-id";
    public static final String S3_SERVICE_ENDPOINT = "--s3-service-endpoint";
    public static final String DYNAMODB_ENDPOINT = "--dynamodb-endpoint";
    public static final String MONGO_CONNECTION_STRING = "--mongo-connection-string";
    public static final String MONGO_CONNECTION_STRING_SHORT = "-m";

    /**
     * Every runner option a benchmark's own arguments must not name. User-data passes the
     * benchmark's arguments after the CLI's options, and picocli keeps the last value, so a
     * {@code --tag imageVersion=…} or {@code --project} there would override what the CLI guards,
     * and the rest fail on the instance after it has booted. The endpoints and the Mongo option are
     * here though the CLI never passes them: on a BaaS instance they could only aim the job away
     * from its deployment.
     */
    public static final List<String> SET_BY_THE_CLI = List.of(
        JOB_ID, JOB_ID_SHORT, CREATED_AT, RESULT_PATH, S3_BUCKET, S3_SERVICE_ENDPOINT, BENCHMARK_PATH,
        RESULTS_TABLE, DYNAMODB_ENDPOINT, TAG, PROJECT, MONGO_CONNECTION_STRING, MONGO_CONNECTION_STRING_SHORT);

    private RunnerArgs() {
    }

    /**
     * The first of {@code benchmarkArgs} that names an option in {@link #SET_BY_THE_CLI}, as the
     * option's name — written alone ({@code --tag k=v}) or with its value attached
     * ({@code --tag=k=v}).
     */
    public static Optional<String> firstSetByTheCli(List<String> benchmarkArgs) {
        for (String arg : benchmarkArgs) {
            for (String option : SET_BY_THE_CLI) {
                if (arg.equals(option) || arg.startsWith(option + "=")) {
                    return Optional.of(option);
                }
            }
        }
        return Optional.empty();
    }
}
