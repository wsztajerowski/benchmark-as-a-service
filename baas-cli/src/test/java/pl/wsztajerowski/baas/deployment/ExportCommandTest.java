package pl.wsztajerowski.baas.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.TestDeployments;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExportCommandTest {

    private static final String D = TestDeployments.DEFAULT;

    @TempDir
    Path dir;

    private final FakeExportAws aws = new FakeExportAws();
    private final StringWriter out = new StringWriter();

    private int baas(String... args) throws Exception {
        TestDeployments.write(dir, D);
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == ExportCommand.class) {
                    var command = new ExportCommand();
                    command.awsFor = config -> aws;
                    return (K) command;
                }
                return defaults.create(cls);
            }
        };
        return BaasApp.commandLine(new BaasApp(dir), factory)
            .setOut(new PrintWriter(out))
            .setErr(new PrintWriter(new StringWriter()))
            .execute(args);
    }

    @Test
    void exportsIntoTheNamedDirectoryAndReportsTheCounts() throws Exception {
        aws.table.add(FakeExportAws.job("2026-10-23T09:06:42.860Z#j"));
        Path bundle = dir.resolve("b");

        assertThat(baas("admin", "deployment", "export", "--output-dir", bundle.toString())).isZero();

        assertThat(bundle.resolve("manifest.json")).exists();
        assertThat(out.toString()).contains("Exported " + D, "1 items: 0 measurements, 1 jobs", "extension   none deployed",
            "admin deployment import " + bundle);
    }

    @Test
    void anUnknownPartIsRefusedBeforeAnyAwsCall() throws Exception {
        assertThat(baas("admin", "deployment", "export", "--parts", "results,tables", "-o", dir.resolve("b").toString()))
            .isEqualTo(2);
        assertThat(aws.calls).isEmpty();
        assertThat(dir.resolve("b")).doesNotExist();
    }

    @Test
    void theHelpDescribesEveryPartAndWhatIsNeverIncluded() throws Exception {
        assertThat(baas("admin", "deployment", "export", "--help")).isZero();

        assertThat(out.toString())
            .contains("results", "every measurement and every job item")
            .contains("recordings", "jobs/<project>/<jobId>/")
            .contains("extension", "base marker")
            .contains("releases/", "image-builds/")
            .contains("--without-inputs");
    }

    @Test
    void theDeploymentNounListsExport() throws Exception {
        assertThat(baas("admin", "deployment")).isZero();
        assertThat(out.toString()).contains("export");
        assertThat(Files.exists(dir.resolve("b"))).isFalse();
    }
}
