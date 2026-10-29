package pl.wsztajerowski.baas.results;

import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.TagKeys;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * {@code baas results diff}'s comparison, apart from how it is read and printed: which two jobs can
 * be compared at all, how their measurements pair up, and how each pair's change is judged.
 */
final class ResultsDiff {

    /**
     * How one row reads. {@code label} is the table's text, {@code json} the JSON value; only the
     * first two say a change is real.
     */
    enum Verdict {
        REGRESSION("regression", "regression"),
        IMPROVEMENT("improvement", "improvement"),
        INDISTINGUISHABLE("~", "indistinguishable"),
        UNKNOWN("?", "unknown"),
        UNITS_DIFFER("units differ", "units-differ"),
        ONLY_IN_A("only in A", "only-in-a"),
        ONLY_IN_B("only in B", "only-in-b");

        final String label;
        final String json;

        Verdict(String label, String json) {
            this.label = label;
            this.json = json;
        }
    }

    /**
     * One row: a pair, or a measurement one job has and the other lacks ({@code a} or {@code b}
     * null). {@code deltaPercent} is B against A, NaN when it cannot be computed.
     */
    record Row(ResultRow a, ResultRow b, double deltaPercent, Verdict verdict) {
        ResultRow either() {
            return a != null ? a : b;
        }
    }

    /**
     * The tags a reader most often needs to explain a delta are printed in the header instead; these
     * are the rest of what the instance observed, diagnosed in full by {@code baas jobs diff}.
     */
    static final List<String> WARNED_TAGS = List.of(
        TagKeys.IMAGE_VERSION, TagKeys.JDK, TagKeys.JVM_VENDOR, TagKeys.CPU_MODEL, TagKeys.CPU_ARCH);

    private static final Comparator<ResultRow> ORDER = Comparator
        .comparing(ResultRow::benchmarkName, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(ResultRow::mode, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(ResultRow::paramsKey);

    private ResultsDiff() {
    }

    /**
     * Why the two jobs cannot be compared, or {@code null} when they can. Decided from the stored
     * kind alone, before anything is paired: a job runs one benchmark type, so its measurements share
     * one kind, and a job without measurements has none — it neither trips the guard nor passes it,
     * and its partner's rows are reported one-sided.
     */
    static String refusal(String jobA, List<ResultRow> a, String jobB, List<ResultRow> b) {
        Set<MeasurementKind> kindsA = kinds(a);
        Set<MeasurementKind> kindsB = kinds(b);
        for (var side : List.of(Map.entry(jobA, kindsA), Map.entry(jobB, kindsB))) {
            if (side.getValue().size() > 1) {
                // No runner writes one; picking either kind would compare half a job silently.
                return "Job " + side.getKey() + " holds measurements of more than one library ("
                    + libraries(side.getValue()) + "), which no runner writes. Not comparing it.";
            }
        }
        if (!kindsA.isEmpty() && !kindsB.isEmpty() && !kindsA.equals(kindsB)) {
            return "Job " + jobA + " is " + libraries(kindsA) + " and job " + jobB + " is " + libraries(kindsB)
                + ": measurements from different libraries are not comparable.";
        }
        if (kindsA.contains(MeasurementKind.JCSTRESS) || kindsB.contains(MeasurementKind.JCSTRESS)) {
            // JCStress stores a verdict per job, not a score, and its fixtures race: comparing two
            // needs its own rules. Until then each job's verdict is one lookup away.
            return "Comparing JCStress jobs is not supported yet. Read each job's verdict with "
                + "`baas results query --job-id " + jobA + "` and `baas results query --job-id " + jobB + "`.";
        }
        return null;
    }

    /**
     * Pairs by benchmark, mode and params — the identity {@code --best-per} groups on, less the
     * project: each side is one job, so two projects' same-named benchmarks are the comparison asked
     * for (a fork, a renamed repository). Pairs first, then A's one-sided rows, then B's.
     */
    static List<Row> compare(List<ResultRow> a, List<ResultRow> b) {
        Map<String, ResultRow> byIdentityB = new LinkedHashMap<>();
        b.stream().sorted(ORDER).forEach(row -> byIdentityB.put(identity(row), row));
        List<Row> pairs = new ArrayList<>();
        List<Row> onlyA = new ArrayList<>();
        for (ResultRow left : a.stream().sorted(ORDER).toList()) {
            ResultRow right = byIdentityB.remove(identity(left));
            if (right == null) {
                onlyA.add(new Row(left, null, Double.NaN, Verdict.ONLY_IN_A));
            } else {
                pairs.add(pair(left, right));
            }
        }
        List<Row> rows = new ArrayList<>(pairs);
        rows.addAll(onlyA);
        byIdentityB.values().forEach(right -> rows.add(new Row(null, right, Double.NaN, Verdict.ONLY_IN_B)));
        return rows;
    }

    static Row pair(ResultRow a, ResultRow b) {
        if (!Objects.equals(a.scoreUnit(), b.scoreUnit())) {
            // Never computed across units: a converted or subtracted number here would be the one
            // confidently wrong figure the command could print.
            return new Row(a, b, Double.NaN, Verdict.UNITS_DIFFER);
        }
        return new Row(a, b, deltaPercent(a.score(), b.score()), judge(a, b));
    }

    /** B against A, in percent; NaN when A is zero or either score is unknown. */
    static double deltaPercent(double a, double b) {
        if (!Double.isFinite(a) || !Double.isFinite(b) || a == 0) {
            return Double.NaN;
        }
        return (b - a) / Math.abs(a) * 100;
    }

    /**
     * Interval overlap: a change is called only when {@code score ± error} of the two do not touch.
     * Conservative — it calls some real changes {@code ~} — but it never colours noise, and needs no
     * threshold. Direction is the mode's, decided as {@code --best-per} decides it.
     */
    static Verdict judge(ResultRow a, ResultRow b) {
        if (!Double.isFinite(a.score()) || !Double.isFinite(b.score())
            || !Double.isFinite(a.scoreError()) || !Double.isFinite(b.scoreError())) {
            return Verdict.UNKNOWN;
        }
        if (Math.abs(a.score() - b.score()) <= Math.abs(a.scoreError()) + Math.abs(b.scoreError())) {
            return Verdict.INDISTINGUISHABLE;
        }
        boolean bIsBetter = ResultsGrouping.lowerIsBetter(a.mode()) ? b.score() < a.score() : b.score() > a.score();
        return bIsBetter ? Verdict.IMPROVEMENT : Verdict.REGRESSION;
    }

    /**
     * A job's value of a tag, from its first measurement — every measurement of one job carries the
     * same tags — or null for a job without measurements.
     */
    static String tag(List<ResultRow> rows, String key) {
        return rows.isEmpty() ? null : rows.get(0).tag(key);
    }

    /**
     * The machine-observed tags, outside the header, on which the two jobs disagree, as
     * {@code key: a → b}; empty when they agree or a side has no measurements to say.
     */
    static List<String> environmentDifferences(List<ResultRow> a, List<ResultRow> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return List.of();
        }
        List<String> differences = new ArrayList<>();
        for (String key : WARNED_TAGS) {
            String left = tag(a, key);
            String right = tag(b, key);
            if (!Objects.equals(left, right)) {
                differences.add(key + ": " + orAbsent(left) + " → " + orAbsent(right));
            }
        }
        return differences;
    }

    private static String orAbsent(String value) {
        return value == null ? "(absent)" : value;
    }

    private static String identity(ResultRow row) {
        return row.benchmarkName() + "\u0000" + row.mode() + "\u0000" + row.paramsKey();
    }

    private static Set<MeasurementKind> kinds(List<ResultRow> rows) {
        Set<MeasurementKind> kinds = EnumSet.noneOf(MeasurementKind.class);
        rows.forEach(row -> kinds.add(row.kind()));
        return kinds;
    }

    private static String libraries(Set<MeasurementKind> kinds) {
        return kinds.stream().map(ResultsDiff::library).collect(Collectors.joining(" and "));
    }

    private static String library(MeasurementKind kind) {
        return switch (kind) {
            case JMH -> "JMH";
            case JCSTRESS -> "JCStress";
        };
    }

    /** Sorted by name, so two sides' metric lines line up. */
    /**
     * Every tag key either job carries, sorted, without the two the header already shows. A job's
     * measurements share one set of tags, so the first row speaks for the job.
     */
    static List<String> tagKeys(List<ResultRow> a, List<ResultRow> b) {
        var keys = new TreeMap<String, Boolean>();
        if (!a.isEmpty()) a.get(0).tags().keySet().forEach(k -> keys.put(k, true));
        if (!b.isEmpty()) b.get(0).tags().keySet().forEach(k -> keys.put(k, true));
        keys.remove(TagKeys.INSTANCE_TYPE);
        keys.remove(TagKeys.TYPE);
        return List.copyOf(keys.keySet());
    }

    static List<String> metricNames(Row row) {
        var names = new TreeMap<String, Boolean>();
        if (row.a() != null) row.a().secondaryMetrics().keySet().forEach(n -> names.put(n, true));
        if (row.b() != null) row.b().secondaryMetrics().keySet().forEach(n -> names.put(n, true));
        return List.copyOf(names.keySet());
    }
}
