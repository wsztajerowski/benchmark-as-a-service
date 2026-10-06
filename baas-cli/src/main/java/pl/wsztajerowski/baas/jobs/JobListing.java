package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;
import pl.wsztajerowski.baas.results.ResultsFilters;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What {@code baas jobs list} shows for a job, and which jobs it shows. A stored status that is
 * not terminal is only a claim: the job is in flight while an instance tagged with its id is
 * pending or running, and vanished otherwise. That is decided here, from one
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
     * @param liveByJobId the instance id of every pending or running runner, keyed by the job id
     *                    on its tag
     */
    public static Row resolve(JobItem job, Map<String, String> liveByJobId) {
        String live = liveByJobId.get(job.jobId());
        if (job.isTerminal()) {
            return new Row(job, job.status(), live);
        }
        return new Row(job, live != null ? job.status() : JobStatus.VANISHED, live);
    }

    /**
     * The filter a listing pages with. {@code project} and {@code tags} are exact matches, every
     * named tag required; a job carrying any {@code excludedTags} pair ({@code key=value}) is dropped;
     * {@code inFlightOnly} keeps jobs whose instance is live.
     */
    public static Predicate<JobItem> filter(String project, Map<String, String> tags, List<String> excludedTags,
                                            boolean inFlightOnly, Map<String, String> liveByJobId) {
        List<String[]> excluded = excludedTags == null ? List.of()
            : excludedTags.stream().map(ResultsFilters::pair).toList();
        return job -> (project == null || project.equals(job.project()))
            && tags.entrySet().stream().allMatch(t -> t.getValue().equals(job.tags().get(t.getKey())))
            && excluded.stream().noneMatch(kv -> kv[1].equals(job.tags().get(kv[0])))
            && (!inFlightOnly || resolve(job, liveByJobId).inFlight());
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

    /** The statuses a listing can show, for help text and documentation. */
    public static final Set<String> SHOWN_STATUSES = Set.of(
        JobStatus.LAUNCHING, JobStatus.LAUNCHED, JobStatus.RUNNING, JobStatus.COMPLETED,
        JobStatus.FAILED_PREFIX + "<n>", JobStatus.TIMED_OUT, JobStatus.CANCELLED,
        JobStatus.LAUNCH_FAILED, JobStatus.VANISHED);
}
