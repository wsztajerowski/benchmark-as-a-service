package pl.wsztajerowski.baas.results;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.cli.Invocation;
import pl.wsztajerowski.baas.cli.JobReference;
import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Json;
import pl.wsztajerowski.baas.console.Table;
import pl.wsztajerowski.baas.console.Table.Cell;
import pl.wsztajerowski.baas.console.Table.Column;
import pl.wsztajerowski.baas.model.SecondaryMetric;
import pl.wsztajerowski.baas.model.TagKeys;
import pl.wsztajerowski.baas.results.ResultsDiff.Row;
import pl.wsztajerowski.baas.results.ResultsDiff.Verdict;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.session.OperatorSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.UnaryOperator;

@Command(
    name = "diff",
    mixinStandardHelpOptions = true,
    description = "Compare two jobs' measurements, benchmark by benchmark.",
    footer = {
        "",
        "Each job is named by the id `baas run` printed and `baas jobs list` shows:",
        "  baas results diff 20260724T120000000Z-a3f9c21b 20260811T093000000Z-b7e4d0f2",
        "",
        "Measurements pair by benchmark, mode and params. The delta is B against A; it is",
        "called a regression or an improvement only when the two score ± error intervals do",
        "not overlap (~ when they do, ? when an error is unknown). A pair in different units",
        "is shown, not computed. JMH, JMH with a profiler and JMH with async-profiler compare;",
        "JCStress jobs are not compared, with JMH or with each other.",
        "",
        "The exit status is 0 whenever the jobs were compared, whatever was found. To fail a",
        "build on a regression:",
        "  baas results diff A B --format json | jq -e '[.rows[] | select(.verdict == \"regression\")] | length == 0'",
        "",
        "The environments themselves are compared by `baas jobs diff`."
    }
)
public class ResultsDiffSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ResultsDiffSubcommand.class);

    private static final Set<String> FORMATS = Set.of("table", "json");

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    /** Which deployment, as its operator role; opened by {@link #call()}. */
    private OperatorSession session;

    OperatorSession session() {
        if (session == null) {
            session = OperatorSession.of(Invocation.configService(spec));
        }
        return session;
    }

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    @Parameters(index = "0", paramLabel = "<jobA>", description = "The job compared against (the baseline).")
    String jobA;

    @Parameters(index = "1", paramLabel = "<jobB>", description = "The job compared with it.")
    String jobB;

    @Option(names = "--format", description = "Output format: table (default), json.", defaultValue = "table")
    String format;

    /** The query service for the configured table. Overridden by tests, which have no table. */
    ResultsQueryService openResults(BaasConfig config, String tableName) {
        return new ResultsQueryService(session().factory().dynamoDb(), tableName);
    }

    @Override
    public Integer call() {
        for (String job : new String[]{jobA, jobB}) {
            String notAJobId = JobReference.notAJobId(job);
            if (notAJobId != null) {
                throw CommandRefusal.usage(notAJobId);
            }
        }
        if (jobA.equals(jobB)) {
            throw CommandRefusal.usage("Both arguments name job " + jobA + "; name two different jobs.");
        }
        if (!FORMATS.contains(format.toLowerCase(Locale.ROOT))) {
            throw CommandRefusal.usage("Unknown --format '" + format + "'. Valid: table, json.");
        }
        BaasConfig config = session().config();
        String tableName;
        try {
            tableName = config.resultsTable();
        } catch (IllegalStateException noDeployment) {
            throw CommandRefusal.refused(noDeployment.getMessage());
        }

        List<ResultRow> a;
        List<ResultRow> b;
        try (var results = openResults(config, tableName)) {
            // One at a time, so an unknown first job is reported without looking up the second.
            a = results.queryJob(jobA)
                .orElseThrow(() -> CommandRefusal.refused(JobReference.noSuchJob(jobA)));
            b = results.queryJob(jobB)
                .orElseThrow(() -> CommandRefusal.refused(JobReference.noSuchJob(jobB)));
        }

        String refusal = ResultsDiff.refusal(jobA, a, jobB, b);
        if (refusal != null) {
            throw CommandRefusal.refused(refusal);
        }

        String cpu = ResultsDiff.cpuDifference(a, b);
        if (cpu != null) {
            logger.warn("The jobs ran on different CPU models ({}); a hardware difference can exceed the "
                + "one being compared — repeat a job until both share a CPU model", cpu);
        }
        List<String> drift = ResultsDiff.environmentDifferences(a, b);
        if (!drift.isEmpty()) {
            // A caveat, not a payload line: a redirect of stdout should not keep it, and it changes
            // no row. The environments themselves are jobs diff's to report.
            logger.warn("The jobs measured on different environments ({}) — compare them with "
                + "`baas jobs diff {} {}`", String.join(", ", drift), jobA, jobB);
        }
        String parameters = ResultsDiff.parameterDifference(a, b);
        if (parameters != null) {
            logger.warn("The jobs ran with different parameters after -- ({}), which change the "
                + "error interval and so the verdict", parameters);
        }
        String profilers = ResultsDiff.profilerDifference(a, b);
        if (profilers != null) {
            logger.warn("The jobs ran under different profilers ({}); a profiler perturbs the score "
                + "it measures", profilers);
        }

        List<Row> rows = ResultsDiff.compare(a, b);
        if ("json".equalsIgnoreCase(format)) {
            printJson(console(), a, b, rows);
        } else {
            printTable(console(), a, b, rows, loggingMixin != null && loggingMixin.verbose());
        }
        return 0;
    }

    private static final List<Column> COLUMNS = List.of(
        Column.left("BENCHMARK", 44),
        Column.left("MODE", 6),
        Column.right("A", 28),
        Column.right("B", 28),
        Column.left("UNIT", 16),
        Column.right("Δ", 12),
        Column.left("VERDICT", 12));

    private static final int RULE_WIDTH = 152;

    /**
     * Command payload, so the {@link Console} — see {@code ResultsQuerySubcommand#printJson}. The
     * header comes first and always: instance type and benchmark type are what most often explain a
     * delta, so they are read before any row, and get no verdict, since a larger instance is not a
     * regression.
     */
    void printTable(Console out, List<ResultRow> a, List<ResultRow> b, List<Row> rows, boolean verbose) {
        // Two ids always differ, so only the context lines are emphasised when they do.
        List<SideBySide> header = List.of(
            new SideBySide("", "A", "B", false),
            new SideBySide("JOB", jobA, jobB, false),
            new SideBySide("INSTANCE", ResultsDiff.tag(a, TagKeys.INSTANCE_TYPE), ResultsDiff.tag(b, TagKeys.INSTANCE_TYPE), true),
            new SideBySide("TYPE", ResultsDiff.tag(a, TagKeys.TYPE), ResultsDiff.tag(b, TagKeys.TYPE), true));
        List<SideBySide> tags = new ArrayList<>();
        if (verbose) {
            for (String key : ResultsDiff.tagKeys(a, b)) {
                tags.add(new SideBySide("  " + key, ResultsDiff.tag(a, key), ResultsDiff.tag(b, key), true));
            }
        }
        // One layout for both blocks, so the tags' A and B line up under the header's.
        var layout = SideBySide.layout(header, tags);
        header.forEach(line -> line.print(out, layout));
        out.println("");
        if (!tags.isEmpty()) {
            out.println("TAGS");
            tags.forEach(line -> line.print(out, layout));
            out.println("");
        }
        if (rows.isEmpty()) {
            out.println("Neither job has measurements.");
            return;
        }
        var table = new Table(out, RULE_WIDTH, COLUMNS);
        table.printHeader();
        for (Row row : rows) {
            ResultRow any = row.either();
            String name = ResultsTable.withoutPackage(any.benchmarkName())
                + (any.params().isEmpty() ? "" : " [" + any.paramsKey() + "]");
            table.printRow(List.of(
                Cell.plain(truncate(name, 44)),
                Cell.plain(any.mode() != null ? any.mode() : ""),
                score(out, row.a()),
                score(out, row.b()),
                Cell.plain(truncate(unit(row), 16)),
                delta(out, row),
                verdict(out, row.verdict())));
            if (verbose) {
                if (!any.params().isEmpty()) {
                    out.println("    " + out.faint(String.format("%-28s A %-24s B %s",
                        "params", params(row.a()), params(row.b()))));
                }
                for (String metric : ResultsDiff.metricNames(row)) {
                    out.println("    " + out.faint(String.format("%-28s A %-24s B %s",
                        metric, metric(row.a(), metric), metric(row.b(), metric))));
                }
            }
        }
    }

    /**
     * One line of the header or the tags block: a label, then A's value and B's in two columns that
     * every such line shares. A differing value is emphasised, so the difference is seen before any
     * row is read.
     */
    private record SideBySide(String label, String a, String b, boolean emphasiseDifference) {

        private static final int LABEL_MAX = 24;
        private static final String GAP = "  ";

        private record Layout(int label, int value) {}

        /**
         * The label column fits the longest label (up to {@link #LABEL_MAX}); the two value columns
         * share one width, the longest value's, but never more than lets the line fit the table's
         * rule. A longer value wraps inside its column instead of the terminal wrapping the line.
         */
        @SafeVarargs
        static Layout layout(List<SideBySide>... blocks) {
            int label = 0;
            int value = 0;
            for (List<SideBySide> block : blocks) {
                for (SideBySide line : block) {
                    label = Math.max(label, line.label.length());
                    value = Math.max(value, Math.max(orUnknown(line.a).length(), orUnknown(line.b).length()));
                }
            }
            label = Math.min(label, LABEL_MAX) + GAP.length();
            int fit = (RULE_WIDTH - label - GAP.length()) / 2;
            return new Layout(label, Math.min(value, fit));
        }

        void print(Console out, Layout layout) {
            String l = orUnknown(a);
            String r = orUnknown(b);
            UnaryOperator<String> style = emphasiseDifference && !l.equals(r) ? out::bold : UnaryOperator.identity();
            List<String> labels = wrap(label, layout.label - GAP.length());
            List<String> lefts = wrap(l, layout.value);
            List<String> rights = wrap(r, layout.value);
            int lines = Math.max(labels.size(), Math.max(lefts.size(), rights.size()));
            for (int i = 0; i < lines; i++) {
                String labelPart = i < labels.size() ? labels.get(i) : "";
                String left = i < lefts.size() ? lefts.get(i) : "";
                String right = i < rights.size() ? rights.get(i) : "";
                // Padded, then styled: an escape sequence must not count towards the width.
                String line = pad(labelPart, layout.label) + style.apply(left)
                    + (right.isEmpty() ? "" : " ".repeat(layout.value - left.length()) + GAP + style.apply(right));
                out.println(line.stripTrailing());
            }
        }

        private static String pad(String text, int width) {
            return text + " ".repeat(Math.max(0, width - text.length()));
        }
    }

    /**
     * Breaks a value into lines of at most {@code width}, at spaces where it can and mid-word where
     * a word alone is longer.
     */
    static List<String> wrap(String text, int width) {
        if (text.length() <= width || width <= 0) {
            return List.of(text);
        }
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            while (word.length() > width) {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (line.isEmpty()) {
                line.append(word);
            } else if (line.length() + 1 + word.length() <= width) {
                line.append(' ').append(word);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String orUnknown(String value) {
        return value == null ? "n/a" : value;
    }

    private static String params(ResultRow row) {
        return row == null ? "n/a" : row.paramsKey();
    }

    private static Cell score(Console out, ResultRow row) {
        if (row == null) {
            return Cell.plain("—");
        }
        String error = Double.isFinite(row.scoreError()) ? String.format("%.3f", row.scoreError()) : "n/a";
        String text = String.format("%.3f", row.score()) + " ± " + error;
        return Double.isFinite(row.score()) && Double.isFinite(row.scoreError())
            ? Cell.plain(text) : new Cell(text, out::faint);
    }

    private static String unit(Row row) {
        if (row.verdict() == Verdict.UNITS_DIFFER) {
            return row.a().scoreUnit() + " → " + row.b().scoreUnit();
        }
        return row.either().scoreUnit();
    }

    private static Cell delta(Console out, Row row) {
        if (row.a() == null || row.b() == null || row.verdict() == Verdict.UNITS_DIFFER) {
            return Cell.plain("");
        }
        return Double.isFinite(row.deltaPercent())
            ? Cell.plain(String.format("%+.1f %%", row.deltaPercent())) : new Cell("n/a", out::faint);
    }

    private static Cell verdict(Console out, Verdict verdict) {
        return switch (verdict) {
            case REGRESSION -> new Cell(verdict.label, out::red);
            case IMPROVEMENT -> new Cell(verdict.label, out::green);
            default -> Cell.plain(verdict.label);
        };
    }

    private static String metric(ResultRow row, String name) {
        SecondaryMetric value = row == null ? null : row.secondaryMetrics().get(name);
        if (value == null) {
            return "n/a";
        }
        return String.format("%.3f", value.score())
            + (value.unit() == null || value.unit().isEmpty() ? "" : " " + value.unit());
    }

    /**
     * One object: both jobs, then a row per pair or one-sided measurement. Never coloured, numbers
     * through {@link Json#number} ({@code Locale.ROOT}, non-finite as {@code null}).
     */
    void printJson(Console out, List<ResultRow> a, List<ResultRow> b, List<Row> rows) {
        out.println("{");
        out.println("  \"a\":" + jobJson(jobA, a) + ",");
        out.println("  \"b\":" + jobJson(jobB, b) + ",");
        out.println("  \"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ResultRow any = row.either();
            out.println("    {\"benchmark\":" + Json.string(any.benchmarkName())
                + ",\"mode\":" + Json.string(any.mode())
                + ",\"params\":" + Json.object(any.params())
                + ",\"a\":" + sideJson(row.a())
                + ",\"b\":" + sideJson(row.b())
                + ",\"deltaPercent\":" + Json.number(row.deltaPercent())
                + ",\"verdict\":" + Json.string(row.verdict().json) + "}"
                + (i < rows.size() - 1 ? "," : ""));
        }
        out.println("  ]");
        out.println("}");
    }

    private static String jobJson(String jobId, List<ResultRow> rows) {
        return "{\"jobId\":" + Json.string(jobId)
            + ",\"instanceType\":" + Json.string(ResultsDiff.tag(rows, TagKeys.INSTANCE_TYPE))
            + ",\"type\":" + Json.string(ResultsDiff.tag(rows, TagKeys.TYPE))
            + ",\"tags\":" + Json.object(rows.isEmpty() ? java.util.Map.of() : rows.get(0).tags()) + "}";
    }

    private static String sideJson(ResultRow row) {
        if (row == null) {
            return "null";
        }
        return "{\"score\":" + Json.number(row.score())
            + ",\"error\":" + Json.number(row.scoreError())
            + ",\"unit\":" + Json.string(row.scoreUnit())
            + ",\"secondaryMetrics\":" + ResultsQuerySubcommand.metricsJson(row.secondaryMetrics()) + "}";
    }

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
