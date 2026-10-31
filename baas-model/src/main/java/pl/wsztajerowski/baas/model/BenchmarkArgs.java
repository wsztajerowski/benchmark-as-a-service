package pl.wsztajerowski.baas.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Map.entry;

/**
 * How a benchmark JAR's own parser — JMH's or JCStress's, both jopt-simple — reads the arguments
 * after {@code baas run <type> … --}. Those arguments reach the JAR verbatim; this reading exists only
 * to find the few BaaS manages ({@link #managed}), the ones that would make a paid job measure nothing
 * ({@link #noRun}), and JMH's {@code -prof} values, which the runner points at the job's artifacts
 * and the CLI records as the {@code profilers} tag. The CLI's refusal, the runner's refusal, the
 * runner's injection and the tag all read through here, so they cannot read one argument three ways.
 *
 * <p>It follows jopt-simple as observed against the fixture JARs (the change's
 * {@code option-lists.md}): an exact name wins, then a prefix that begins exactly one name; a
 * single-dash token that is neither is a cluster of single-letter options, where a value-taking
 * letter takes the rest of the token ({@code -oout.txt} is {@code -o out.txt}). A literal match
 * alone would let {@code -pro async:dir=x} or {@code -ofile} past every check. The option lists
 * are the fixture JARs' {@code -h}, held in step by the runner's contract test.
 */
public final class BenchmarkArgs {

    /** Whether an option takes a value: JMH's are all required, JCStress has optional ones. */
    public enum Arity { NONE, OPTIONAL, REQUIRED }

    /**
     * One option as the tool would read it: its canonical name, its value (null when it has none)
     * and where it sits — {@code valueIndex} is the separate token holding the value, or -1 when the
     * value is attached to the option's own token ({@code -prof=gc}, {@code -oout.txt}) or absent.
     */
    public record Occurrence(String name, String value, int index, int valueIndex) {
    }

    /**
     * A {@code -prof} value: the profiler's name as given, and its option string — what follows the
     * first {@code :}, or null when there is none.
     */
    public record Profiler(String name, String options) {

        /**
         * The name BaaS knows this profiler by: JMH also accepts a profiler's class name, so the two
         * profilers the runner points at its directory are recognised by either. Any other name is
         * kept as given.
         */
        public String canonicalName() {
            return PROFILER_CLASS_NAMES.getOrDefault(name, name);
        }

        static Profiler parse(String value) {
            int colon = value.indexOf(':');
            return colon < 0 ? new Profiler(value, null) : new Profiler(value.substring(0, colon), value.substring(colon + 1));
        }

        /**
         * The option keys as JMH reads them: {@code ;}-separated pieces, each key the text before
         * {@code =} with any leading dashes dropped (JMH puts a {@code -} in front of each piece, so
         * {@code -dir=x} reaches it as {@code --dir=x} and still means {@code dir}).
         */
        public List<String> keys() {
            if (options == null || options.isEmpty()) {
                return List.of();
            }
            List<String> keys = new ArrayList<>();
            for (String piece : options.split(";")) {
                String key = piece.contains("=") ? piece.substring(0, piece.indexOf('=')) : piece;
                key = key.replaceFirst("^-+", "");
                if (!key.isEmpty()) {
                    keys.add(key);
                }
            }
            return keys;
        }

        /** This profiler with {@code extra} appended to its options, after the user's own. */
        public String withOptions(String extra) {
            if (options == null || options.isEmpty()) {
                return name + ":" + extra;
            }
            return name + ":" + options + (options.endsWith(";") ? "" : ";") + extra;
        }
    }

    /** JMH's option that names a profiler. */
    public static final String PROF = "prof";

    /**
     * The profiler option keys the runner sets, by profiler name. {@code dir} is where the profiler
     * writes, which must be the directory the runner uploads; {@code libPath} is the async-profiler
     * baked into the image. The runner appends these to every such {@code -prof}, so a user value for
     * one, in any spelling, gives JMH the key twice — refused at profiler start, failing the job.
     * The check before launch matches the exact key, to say why before anything is paid for.
     */
    public static final Map<String, List<String>> MANAGED_PROFILER_KEYS =
        Map.of("async", List.of("dir", "libPath"), "jfr", List.of("dir"));

    /**
     * The class names JMH resolves those two profilers by as well ({@code -prof <class>}): written so,
     * a JFR recording went unpointed and unuploaded, and async-profiler found no library.
     */
    static final Map<String, String> PROFILER_CLASS_NAMES = Map.of(
        "org.openjdk.jmh.profile.AsyncProfiler", "async",
        "org.openjdk.jmh.profile.JavaFlightRecorderProfiler", "jfr");

    private static final BenchmarkArgs JMH = new BenchmarkArgs("JMH",
        Map.ofEntries(
            entry("i", Arity.REQUIRED), entry("bs", Arity.REQUIRED), entry("r", Arity.REQUIRED),
            entry("wi", Arity.REQUIRED), entry("wbs", Arity.REQUIRED), entry("w", Arity.REQUIRED),
            entry("to", Arity.REQUIRED), entry("t", Arity.REQUIRED), entry("bm", Arity.REQUIRED),
            entry("si", Arity.REQUIRED), entry("gc", Arity.REQUIRED), entry("foe", Arity.REQUIRED),
            entry("v", Arity.REQUIRED), entry("f", Arity.REQUIRED), entry("wf", Arity.REQUIRED),
            entry("o", Arity.REQUIRED), entry("rff", Arity.REQUIRED), entry(PROF, Arity.REQUIRED),
            entry("tg", Arity.REQUIRED), entry("jvm", Arity.REQUIRED), entry("jvmArgs", Arity.REQUIRED),
            entry("jvmArgsAppend", Arity.REQUIRED), entry("jvmArgsPrepend", Arity.REQUIRED),
            entry("tu", Arity.REQUIRED), entry("opi", Arity.REQUIRED), entry("rf", Arity.REQUIRED),
            entry("wm", Arity.REQUIRED), entry("e", Arity.REQUIRED), entry("p", Arity.REQUIRED),
            entry("wmb", Arity.REQUIRED),
            entry("l", Arity.NONE), entry("lp", Arity.NONE), entry("lrf", Arity.NONE),
            entry("lprof", Arity.NONE), entry("h", Arity.NONE)),
        Set.of("rf", "rff", "o"),
        Set.of("h", "l", "lp", "lprof", "lrf"));

    private static final BenchmarkArgs JCSTRESS = new BenchmarkArgs("JCStress",
        Map.ofEntries(
            entry("c", Arity.REQUIRED), entry("hs", Arity.REQUIRED), entry("iters", Arity.REQUIRED),
            entry("jvmArgs", Arity.REQUIRED), entry("jvmArgsPrepend", Arity.REQUIRED),
            entry("m", Arity.REQUIRED), entry("p", Arity.REQUIRED), entry("r", Arity.REQUIRED),
            entry("spinStyle", Arity.REQUIRED), entry("strideCount", Arity.REQUIRED),
            entry("strideSize", Arity.REQUIRED), entry("t", Arity.REQUIRED), entry("time", Arity.REQUIRED),
            entry("af", Arity.OPTIONAL), entry("f", Arity.OPTIONAL), entry("fsm", Arity.OPTIONAL),
            entry("l", Arity.OPTIONAL), entry("pth", Arity.OPTIONAL), entry("sc", Arity.OPTIONAL),
            entry("h", Arity.NONE), entry("v", Arity.NONE), entry("vv", Arity.NONE), entry("vvv", Arity.NONE)),
        Set.of("r"),
        Set.of("h", "l", "p"));

    private final String tool;
    private final Map<String, Arity> options;
    private final Set<String> managed;
    private final Set<String> noRun;

    private BenchmarkArgs(String tool, Map<String, Arity> options, Set<String> managed, Set<String> noRun) {
        this.tool = tool;
        this.options = options;
        this.managed = managed;
        this.noRun = noRun;
    }

    /** The reading for a benchmark type's tool. */
    public static BenchmarkArgs of(BenchmarkType type) {
        return switch (type) {
            case JMH -> JMH;
            case JCSTRESS -> JCSTRESS;
        };
    }

    /** {@code JMH} or {@code JCStress}, for messages. */
    public String tool() {
        return tool;
    }

    /** Every option name the tool's {@code -h} lists, with its arity. */
    public Map<String, Arity> options() {
        return options;
    }

    /** Options whose value BaaS sets itself. */
    public Set<String> managed() {
        return managed;
    }

    /** Options that make the tool print and exit without measuring. */
    public Set<String> noRun() {
        return noRun;
    }

    /** Every option in {@code args}, in order, as the tool would read them. */
    public List<Occurrence> read(List<String> args) {
        List<Occurrence> found = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            if (token.equals("--")) {
                break;
            }
            if (!token.startsWith("-") || token.length() < 2) {
                continue;
            }
            Occurrence occurrence = token.startsWith("--") ? readLong(token, i) : readShort(token, i);
            if (occurrence == null) {
                continue;
            }
            if (occurrence.value() == null && i + 1 < args.size() && takesNextToken(occurrence.name(), args.get(i + 1))) {
                occurrence = new Occurrence(occurrence.name(), args.get(i + 1), i, i + 1);
                i++;
            }
            found.add(occurrence);
        }
        return found;
    }

    /**
     * The first argument BaaS manages, described for a message: {@code -rf}, or
     * {@code dir in -prof async}. Empty when there is none.
     */
    public Optional<String> firstManaged(List<String> args) {
        for (Occurrence occurrence : read(args)) {
            if (managed.contains(occurrence.name())) {
                return Optional.of("-" + occurrence.name());
            }
            if (occurrence.name().equals(PROF) && occurrence.value() != null) {
                Profiler profiler = Profiler.parse(occurrence.value());
                List<String> keys = MANAGED_PROFILER_KEYS.getOrDefault(profiler.canonicalName(), List.of());
                for (String key : profiler.keys()) {
                    if (keys.contains(key)) {
                        return Optional.of(key + " in -prof " + profiler.canonicalName());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The first argument that would make the tool exit without measuring, as {@code -name}. */
    public Optional<String> firstNoRun(List<String> args) {
        return read(args).stream()
            .filter(occurrence -> noRun.contains(occurrence.name()))
            .map(occurrence -> "-" + occurrence.name())
            .findFirst();
    }

    /** Every {@code -prof} value, in order. */
    public List<Profiler> profilers(List<String> args) {
        return read(args).stream()
            .filter(occurrence -> occurrence.name().equals(PROF) && occurrence.value() != null)
            .map(occurrence -> Profiler.parse(occurrence.value()))
            .toList();
    }

    /**
     * The {@code profilers} tag's value: every profiler's {@link Profiler#canonicalName()}, sorted,
     * without duplicates, joined by {@code ,}; empty when nothing is profiled, so the tag is left out
     * rather than set to a stand-in.
     */
    public Optional<String> profilersTag(List<String> args) {
        Set<String> names = profilers(args).stream()
            .map(Profiler::canonicalName)
            .collect(Collectors.toCollection(TreeSet::new));
        return names.isEmpty() ? Optional.empty() : Optional.of(String.join(",", names));
    }

    /**
     * {@code args} with every {@code -prof} value for which {@code extra} returns a non-null option
     * string given those options appended; every other token unchanged. The value is replaced where
     * it was written — its own token, or the tail of {@code -prof=…}.
     */
    public List<String> withProfilerOptions(List<String> args, Function<Profiler, String> extra) {
        List<String> rewritten = new ArrayList<>(args);
        for (Occurrence occurrence : read(args)) {
            if (!occurrence.name().equals(PROF) || occurrence.value() == null) {
                continue;
            }
            Profiler profiler = Profiler.parse(occurrence.value());
            String options = extra.apply(profiler);
            if (options == null) {
                continue;
            }
            String value = profiler.withOptions(options);
            if (occurrence.valueIndex() >= 0) {
                rewritten.set(occurrence.valueIndex(), value);
            } else {
                String token = args.get(occurrence.index());
                rewritten.set(occurrence.index(), token.substring(0, token.length() - occurrence.value().length()) + value);
            }
        }
        return Collections.unmodifiableList(rewritten);
    }

    private Occurrence readLong(String token, int index) {
        String body = token.substring(2);
        String key = body.contains("=") ? body.substring(0, body.indexOf('=')) : body;
        String value = body.contains("=") ? body.substring(body.indexOf('=') + 1) : null;
        String name = resolve(key);
        return name == null ? null : new Occurrence(name, value, index, -1);
    }

    private Occurrence readShort(String token, int index) {
        String body = token.substring(1);
        String key = body.contains("=") ? body.substring(0, body.indexOf('=')) : body;
        String name = resolve(key);
        if (name != null) {
            String value = body.contains("=") ? body.substring(body.indexOf('=') + 1) : null;
            return new Occurrence(name, value, index, -1);
        }
        // A cluster of single-letter options. Only the first that matters is returned: a flag letter
        // is followed by more letters, and the one that takes a value ends the cluster.
        Occurrence first = null;
        for (int j = 0; j < body.length(); j++) {
            String letter = body.substring(j, j + 1);
            Arity arity = options.get(letter);
            if (arity == null) {
                break;
            }
            boolean valueAttached = arity != Arity.NONE && j + 1 < body.length();
            Occurrence occurrence = new Occurrence(letter, valueAttached ? body.substring(j + 1) : null, index, -1);
            if (first == null || isGuarded(letter)) {
                first = occurrence;
            }
            if (isGuarded(letter) || arity != Arity.NONE) {
                return occurrence;
            }
        }
        return first;
    }

    private boolean isGuarded(String name) {
        return managed.contains(name) || noRun.contains(name);
    }

    private boolean takesNextToken(String name, String next) {
        return switch (options.get(name)) {
            case REQUIRED -> true;
            case OPTIONAL -> !next.startsWith("-");
            case NONE -> false;
        };
    }

    /** An exact name, or a prefix that begins exactly one name; null otherwise. */
    private String resolve(String key) {
        if (key.isEmpty()) {
            return null;
        }
        if (options.containsKey(key)) {
            return key;
        }
        List<String> candidates = options.keySet().stream().filter(name -> name.startsWith(key)).toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }
}
