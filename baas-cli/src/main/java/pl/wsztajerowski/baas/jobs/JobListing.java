package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;
import pl.wsztajerowski.baas.results.ResultsFilters;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What {@code baas jobs list} shows for a job, and which jobs it shows. A stored status that is
 * not terminal is only a claim: the job is in flight while an instance tagged with its id is
 * pending or running, or while it is younger than {@link #VANISH_GRACE}, and vanished otherwise. That is decided here, from one
 * {@code DescribeInstances} of the live runners, and never written back.
 */
public final class JobListing {

    /** One row: the job and its status as resolved now. */
    public record Row(JobItem job, String status, String liveInstanceId) {
        public boolean inFlight() {
            return JobStatus.isInFlight(status);
        }
    }

    private JobListing() {}

    /**
     * How long a job keeps its stored status with no visible instance. Its instance may not be
     * requested yet ({@code launching}) or not yet returned by {@code DescribeInstances}: far longer
     * than both, far shorter than any real job. A CLI that died mid-launch shows {@code launching}
     * this long, then {@code vanished}.
     */
    public static final Duration VANISH_GRACE = Duration.ofMinutes(5);

    /**
     * @param liveByJobId the instance id of every pending or running runner, keyed by the job id
     *                    on its tag
     * @param now         the instant the listing is resolved at; a job younger than
     *                    {@link #VANISH_GRACE} is never {@code vanished}
     */
    public static Row resolve(JobItem job, Map<String, String> liveByJobId, Instant now) {
        String live = liveByJobId.get(job.jobId());
        if (job.isTerminal() || live != null || job.createdAt().plus(VANISH_GRACE).isAfter(now)) {
            return new Row(job, job.status(), live);
        }
        return new Row(job, JobStatus.VANISHED, null);
    }

    /**
     * The filter a listing pages with. {@code project} and {@code tags} are exact matches, every
     * named tag required; a job carrying any {@code excludedTags} pair ({@code key=value}) is dropped;
     * {@code inFlightOnly} keeps jobs whose resolved status is in flight.
     */
    public static Predicate<JobItem> filter(String project, Map<String, String> tags, List<String> excludedTags,
                                            boolean inFlightOnly, Map<String, String> liveByJobId, Instant now) {
        List<String[]> excluded = excludedTags == null ? List.of()
            : excludedTags.stream().map(ResultsFilters::pair).toList();
        return job -> (project == null || project.equals(job.project()))
            && tags.entrySet().stream().allMatch(t -> t.getValue().equals(job.tags().get(t.getKey())))
            && excluded.stream().noneMatch(kv -> kv[1].equals(job.tags().get(kv[0])))
            && (!inFlightOnly || resolve(job, liveByJobId, now).inFlight());
    }

    /** What {@code --sort-by} accepts on {@code jobs list}. */
    public static final List<String> SORT_FIELDS = List.of("created", "status", "project");

    /**
     * The shared ordering: newest first unless {@code --sort-by} names another field, reversed by
     * {@code --asc}; ties fall back to newest first, then to the job id.
     */
    public static List<Row> sorted(List<Row> rows, String field, boolean ascending) {
        Comparator<Row> created = Comparator.comparing((Row row) -> row.job().createdAt());
        Comparator<Row> primary = switch (field == null ? "created" : field) {
            case "status" -> Comparator.comparing(Row::status);
            case "project" -> Comparator.comparing((Row row) -> row.job().project() == null ? "" : row.job().project());
            default -> created;
        };
        Comparator<Row> order = ascending ? primary : primary.reversed();
        return rows.stream()
            .sorted(order.thenComparing(created.reversed()).thenComparing(row -> row.job().jobId()))
            .toList();
    }

}
