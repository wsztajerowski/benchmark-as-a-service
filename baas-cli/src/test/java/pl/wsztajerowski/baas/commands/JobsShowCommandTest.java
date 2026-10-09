package pl.wsztajerowski.baas.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** `jobs show`: the execution view of one job — item, environment, artifacts — and no measurements. */
class JobsShowCommandTest {

    private static final String ID = "20261006T165241024Z-47516b65";
    private static final String PATH = "jobs/baas-e2e/" + ID;
    private static final String MANIFEST = """
        {"schemaVersion": 6, "machine": {"imageVersion": "1.3.0", "amiId": "ami-0fce", "instanceType": "c5.2xlarge"},
         "jvm": {"version": "25.0.4", "vendor": "Amazon.com Inc."}}
        """;

    @TempDir Path dir;

    private final List<String> reads = new ArrayList<>();

    private record Run(int exit, String out) {}

    private Run show(JobItem job, String live, String manifest, List<String> keys, String... extra) throws Exception {
        pl.wsztajerowski.baas.TestDeployments.write(dir, pl.wsztajerowski.baas.TestDeployments.DEFAULT);
        var out = new StringWriter();
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls != JobsShowSubcommand.class) {
                    return defaults.create(cls);
                }
                var command = new JobsShowSubcommand() {
                    @Override
                    Sources sources(BaasConfig config) {
                        return new Sources() {
                            public Optional<JobItem> job(String id) { return Optional.ofNullable(job); }
                            public Optional<String> liveInstance(String id) { return Optional.ofNullable(live); }
                            public Optional<String> manifest(String path) { reads.add(path); return Optional.ofNullable(manifest); }
                            public List<String> keys(String path) { reads.add(path); return keys; }
                            public void close() { }
                        };
                    }
                };
                command.console = Console.plain(new PrintWriter(out, true));
                return (K) command;
            }
        };
        List<String> line = new ArrayList<>(List.of("jobs", "show", ID));
        line.addAll(List.of(extra));
        int exit = new CommandLine(new BaasApp(dir), factory).setErr(new PrintWriter(new StringWriter(), true))
            .execute(line.toArray(String[]::new));
        return new Run(exit, out.toString());
    }

    private static JobItem job(String status, String errorCode) {
        return new JobItem(ID, "baas-e2e", Instant.parse("2026-10-06T16:52:41.024Z"), PATH, "c5.2xlarge",
            status, "i-0a5605864eb16d146", errorCode, Map.of("type", "jmh-with-async", "branch", "main"), null);
    }

    @Test
    void aCompletedJobShowsAllThreeSectionsAndNoMeasurements() throws Exception {
        var run = show(job(JobStatus.COMPLETED, null), null, MANIFEST,
            List.of(PATH + "/environment.json", PATH + "/jmh-result.json", PATH + "/input/benchmark.jar",
                PATH + "/input/runner.jar", PATH + "/Bench.run-Throughput/flame.html"));

        assertThat(run.exit()).isZero();
        assertThat(run.out())
            .contains("Job " + ID + "   completed", "jmh-with-async", "branch=main")
            .contains("Environment (schema 6)", "machine", "imageVersion 1.3.0", "jvm", "version 25.0.4")
            .contains("Artifacts (5)", "environment.json", "input/ (2)", "Bench.run-Throughput/ (1)")
            .doesNotContain("SCORE");
    }

    @Test
    void aJobThatNeverBootedSaysItHasNoEnvironment() throws Exception {
        var run = show(job(JobStatus.LAUNCH_FAILED, "InsufficientInstanceCapacity"), null, null, List.of());

        assertThat(run.exit()).isZero();
        assertThat(run.out()).contains("launch-failed", "Error", "InsufficientInstanceCapacity")
            .contains("Environment   none").contains("Artifacts   none");
    }

    @Test
    void aVanishedJobShowsBothStatuses() throws Exception {
        var run = show(job(JobStatus.RUNNING, null), null, MANIFEST, List.of());

        assertThat(run.out()).contains("vanished (stored: running)");
    }

    @Test
    void anUnknownIdFailsBeforeAnyS3Read() throws Exception {
        var run = show(null, null, MANIFEST, List.of());

        assertThat(run.exit()).isEqualTo(1);
        assertThat(reads).isEmpty();
    }

    @Test
    void jsonIsOneObjectWithThreeMembers() throws Exception {
        var run = show(job(JobStatus.COMPLETED, null), null, MANIFEST, List.of(PATH + "/environment.json"),
            "--format", "json");

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(run.out(), Map.class);
        assertThat(parsed).containsOnlyKeys("job", "environment", "artifacts");
        assertThat((Map<String, Object>) parsed.get("environment")).containsKey("machine");
    }
}
