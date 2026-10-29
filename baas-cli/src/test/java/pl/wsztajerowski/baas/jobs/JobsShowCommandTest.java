package pl.wsztajerowski.baas.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.aws.S3UploadService.StoredObject;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;

import java.io.PrintWriter;
import java.io.StringWriter;
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
        {"schemaVersion": 7, "machine": {"imageVersion": "1.3.0", "amiId": "ami-0fce", "instanceType": "c5.2xlarge"},
         "jvm": {"version": "25.0.4", "vendor": "Amazon.com Inc."}}
        """;

    /** The manifest of job 20261026T111802133Z-3a07e193, as schema 7 records it. */
    private static final String FULL_MANIFEST = """
        {"schemaVersion": 7,
         "machine": {"imageVersion": "1.3.0", "amiId": "ami-006bf9067587a5c80", "instanceType": "c5.2xlarge"},
         "cpu": {"model": "Intel(R) Xeon(R) Platinum 8275CL CPU @ 3.00GHz", "arch": "x86_64", "vcpus": "8",
                 "physicalCores": "4", "threadsPerCore": "2", "maxMhz": ""},
         "memory": {"totalMib": "15622", "swapTotalMib": "0"},
         "os": {"version": "Amazon Linux 2023.12.20260803", "kernelRelease": "6.1.177-224.371.amzn2023.x86_64"},
         "jvm": {"version": "openjdk version \\"25.0.4\\" 2026-07-21 LTS", "release": "25.0.4",
                 "vendor": "Amazon.com Inc.", "vendorVersion": "Corretto-25.0.4.7.1", "name": "OpenJDK 64-Bit Server VM"},
         "tools": {"perf": "perf version 6.1.177-224.371.amzn2023.x86_64",
                   "asyncProfiler": "Async-profiler 4.0 built on Apr  8 2025"},
         "tunables": {"perfEventParanoid": "1", "kptrRestrict": "0", "transparentHugepages": "[always] madvise never"}}
        """;

    /** When the live instance in these tests was launched: ten minutes ago. */
    private static final Instant LAUNCHED = Instant.now().minusSeconds(600);

    @TempDir Path dir;

    private final List<String> reads = new ArrayList<>();

    private record Run(int exit, String out, String err) {}

    /** Whether the console the command prints to counts as a person's terminal. */
    private boolean interactive;

    private Run show(JobItem job, String live, String manifest, List<String> keys, String... extra) throws Exception {
        return showObjects(job, live, manifest, keys.stream().map(key -> new StoredObject(key, 100)).toList(), extra);
    }

    private Run showObjects(JobItem job, String live, String manifest, List<StoredObject> objects, String... extra)
        throws Exception {
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
                            public Optional<Ec2ProvisioningService.LiveInstance> liveInstance(String id) {
                                return Optional.ofNullable(live).map(i -> new Ec2ProvisioningService.LiveInstance(i, LAUNCHED));
                            }
                            public Optional<String> manifest(String path) { reads.add(path); return Optional.ofNullable(manifest); }
                            public List<StoredObject> objects(String path) { reads.add(path); return objects; }
                            public void close() { }
                        };
                    }
                };
                command.console = interactive ? Console.withFlags(new PrintWriter(out, true), true, false)
                    : Console.plain(new PrintWriter(out, true));
                return (K) command;
            }
        };
        List<String> line = new ArrayList<>(List.of("jobs", "show", ID));
        line.addAll(List.of(extra));
        var err = new StringWriter();
        int exit = BaasApp.commandLine(new BaasApp(dir), factory).setErr(new PrintWriter(err, true))
            .execute(line.toArray(String[]::new));
        return new Run(exit, out.toString(), err.toString());
    }

    private static JobItem job(String status, String errorCode) {
        return job(status, errorCode, null);
    }

    private static JobItem job(String status, String errorCode, Integer watchdogSeconds) {
        return job(status, errorCode, watchdogSeconds, Map.of("type", "jmh-with-async", "branch", "main"));
    }

    private static JobItem job(String status, String errorCode, Integer watchdogSeconds, Map<String, String> tags) {
        return new JobItem(ID, "baas-e2e", Instant.parse("2026-10-06T16:52:41.024Z"), PATH, "c5.2xlarge",
            status, "i-0a5605864eb16d146", errorCode, tags, null, watchdogSeconds);
    }

    @Test
    void aRunningJobShowsWhenItsWatchdogTerminatesIt() throws Exception {
        var run = show(job(JobStatus.RUNNING, null, 3600), "i-0a5605864eb16d146", MANIFEST, List.of());

        assertThat(run.out()).containsPattern("Terminates +about \\d\\d:\\d\\d UTC \\(in (49|50) min\\), or earlier when the job finishes");
    }

    @Test
    void aJobReservedBeforeTheBoundWasRecordedShowsNoDeadline() throws Exception {
        var run = show(job(JobStatus.RUNNING, null), "i-0a5605864eb16d146", MANIFEST, List.of());

        assertThat(run.out()).doesNotContain("Terminates");
    }

    @Test
    void aFinishedJobShowsNoDeadline() throws Exception {
        var run = show(job(JobStatus.COMPLETED, null, 3600), null, MANIFEST, List.of());

        assertThat(run.out()).doesNotContain("Terminates");
    }

    @Test
    void jsonCarriesTheWatchdogBound() throws Exception {
        var run = show(job(JobStatus.RUNNING, null, 900), "i-0a5605864eb16d146", MANIFEST, List.of(), "--format", "json");

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(run.out(), Map.class);
        assertThat((Map<String, Object>) parsed.get("job")).containsEntry("watchdogSeconds", 900);
    }

    @Test
    void aCompletedJobShowsAllThreeSectionsAndNoMeasurements() throws Exception {
        var run = show(job(JobStatus.COMPLETED, null), null, MANIFEST,
            List.of(PATH + "/environment.json", PATH + "/jmh-result.json", PATH + "/input/benchmark.jar",
                PATH + "/input/runner.jar", PATH + "/Bench.run-Throughput/flame.html"));

        assertThat(run.exit()).isZero();
        assertThat(run.out())
            .contains("Job " + ID + "   completed", "jmh-with-async", "branch=main")
            .contains("Environment (schema 7)", "machine", "imageVersion 1.3.0", "jvm", "version 25.0.4")
            .contains("Artifacts (5)", "environment.json", "input/ (2 files)", "Bench.run-Throughput/ (1 file)")
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

    @Test
    void tagsAlreadyShownAreNotRepeated() throws Exception {
        var run = show(job(JobStatus.COMPLETED, null, null, Map.of("project", "baas-e2e", "type", "jcstress",
            "branch", "main", "commit", "20e8b23", "hidden", "true")), null, MANIFEST, List.of());

        assertThat(run.out())
            .contains("Job " + ID + "   completed · hidden")
            .containsPattern("(?m)^  Tags +branch=main$")
            .containsPattern("(?m)^ {15}commit=20e8b23$")
            .doesNotContain("project=", "type=", "hidden=");
    }

    @Test
    void environmentValuesAreShownForReading() throws Exception {
        var run = show(job(JobStatus.COMPLETED, null), null, FULL_MANIFEST, List.of());

        assertThat(run.out())
            .containsPattern("(?m)^  machine +imageVersion 1.3.0 · amiId ami-006bf9067587a5c80$")
            .containsPattern("(?m)^  cpu +model Intel\\(R\\) Xeon\\(R\\) Platinum 8275CL CPU @ 3.00GHz · arch x86_64 · "
                + "vcpus 8 · physicalCores 4 · threadsPerCore 2$")
            .containsPattern("(?m)^  memory +15.3 GiB · no swap$")
            .containsPattern("(?m)^  jvm +Corretto 25.0.4.7.1 \\(Amazon.com Inc.\\) · name OpenJDK 64-Bit Server VM$")
            .containsPattern("(?m)^  tools +perf 6.1.177-224.371.amzn2023.x86_64 · async-profiler 4.0$")
            .containsPattern("(?m)^  tunables +perfEventParanoid 1 · kptrRestrict 0 · thp always$")
            .doesNotContain("maxMhz", "instanceType c5.2xlarge", "openjdk version");
    }

    @Test
    void anUnknownFieldIsShownAsRecorded() throws Exception {
        var run = show(job(JobStatus.COMPLETED, null), null, FULL_MANIFEST
            .replace("\"name\": \"OpenJDK", "\"flavour\": \"server\", \"name\": \"OpenJDK")
            .replace("Async-profiler 4.0 built on Apr  8 2025", "asprof (custom build)"), List.of());

        assertThat(run.out()).contains("flavour server", "asyncProfiler asprof (custom build)");
    }

    @Test
    void artifactsAreListedOnePerLineWithSizes() throws Exception {
        var run = showObjects(job(JobStatus.COMPLETED, null), null, MANIFEST, List.of(
            new StoredObject(PATH + "/cloud-init-output.log", 12_595),
            new StoredObject(PATH + "/jcstress-output.txt", 4_096),
            new StoredObject(PATH + "/environment.json", 1_130),
            new StoredObject(PATH + "/packages.txt", 18_944),
            new StoredObject(PATH + "/pl.Test.html", 812),
            new StoredObject(PATH + "/pl.Other.html", 2_048),
            new StoredObject(PATH + "/input/benchmark.jar", 5_000_000),
            new StoredObject(PATH + "/input/runner.jar", 452_000)));

        assertThat(run.out())
            .contains("Artifacts (8)")
            .containsPattern("(?m)^  output +cloud-init-output\\.log +12\\.3 KiB$")
            .containsPattern("(?m)^ {15}jcstress-output\\.txt +4\\.0 KiB$")
            .containsPattern("(?m)^  environment +environment\\.json +1\\.1 KiB$")
            .containsPattern("(?m)^  reports +pl\\.Other\\.html +2\\.0 KiB$")
            .containsPattern("(?m)^ {15}pl\\.Test\\.html +812 B$")
            .containsPattern("(?m)^  inputs +input/ \\(2 files\\) +5\\.2 MiB$");
    }

    @Test
    void controlCharactersWrittenByARunnerAreNotPrinted() throws Exception {
        var run = show(job(JobStatus.COMPLETED, null, null, Map.of("branch", "main\u001b]0;pwned\u0007")), null,
            FULL_MANIFEST.replace("Amazon Linux 2023.12.20260803", "Amazon Linux\\u001b[2J 2023"), List.of());

        assertThat(run.out()).doesNotContain("\u001b", "\u0007")
            .contains("branch=main]0;pwned", "version Amazon Linux[2J 2023");
    }

    @Test
    void aManifestWithTrailingContentDoesNotReachTheJsonOutput() throws Exception {
        String forged = MANIFEST.strip() + ", \"job\": {\"status\": \"completed\"}";

        var json = show(job(JobStatus.FAILED_PREFIX + "1", null), null, forged, List.of(), "--format", "json");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(json.out(), Map.class);
        assertThat(json.exit()).isZero();
        assertThat(parsed).containsEntry("environment", null);
        assertThat((Map<String, Object>) parsed.get("job")).containsEntry("status", JobStatus.FAILED_PREFIX + "1");

        var text = show(job(JobStatus.FAILED_PREFIX + "1", null), null, forged, List.of());
        assertThat(text.exit()).isZero();
        assertThat(text.out()).contains("Environment   unreadable");
    }

    @Test
    void jsonEnvironmentIsTheParsedManifest() throws Exception {
        var run = show(job(JobStatus.COMPLETED, null), null, FULL_MANIFEST, List.of(), "--format", "json");

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(run.out(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> environment = (Map<String, Object>) parsed.get("environment");
        assertThat(environment).containsEntry("schemaVersion", 7);
        assertThat((Map<String, Object>) environment.get("memory")).containsEntry("totalMib", "15622");
    }

    @Test
    void aFinishedJobPointsToItsMeasurementsOnStandardError() throws Exception {
        interactive = true;
        var run = show(job(JobStatus.COMPLETED, null), null, MANIFEST, List.of());

        String deployment = pl.wsztajerowski.baas.TestDeployments.DEFAULT;
        assertThat(run.err().lines().toList()).endsWith("Next",
            "  measurements  baas --deployment " + deployment + " query --job-id " + ID,
            "  files         baas --deployment " + deployment + " jobs download " + ID);
        assertThat(run.out()).doesNotContain("Next", "query --job-id");
    }

    @Test
    void aRedirectedJobShowPrintsNoHint() throws Exception {
        var run = show(job(JobStatus.COMPLETED, null), null, MANIFEST, List.of());

        assertThat(run.err()).doesNotContain("Next", "query --job-id");
        assertThat(run.out()).doesNotContain("Next");
    }
}
