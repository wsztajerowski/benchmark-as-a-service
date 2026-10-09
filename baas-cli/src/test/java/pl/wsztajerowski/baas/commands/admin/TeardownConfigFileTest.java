package pl.wsztajerowski.baas.commands.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.TestDeployments;
import pl.wsztajerowski.baas.config.BaasConfig;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A completed teardown removes that deployment's file, and only a completed one. */
class TeardownConfigFileTest {

    @TempDir
    Path dir;

    @Test
    void aCompletedTeardownRemovesOnlyItsDeploymentsFile() throws Exception {
        Path main = TestDeployments.write(dir, TestDeployments.DEFAULT);
        Path dev = TestDeployments.write(dir, "wiktor-dev");
        String mainBefore = Files.readString(main);

        int exit = teardown(0, "--deployment", "wiktor-dev");

        assertThat(exit).isZero();
        assertThat(dev).doesNotExist();
        assertThat(Files.readString(main)).isEqualTo(mainBefore);
    }

    @Test
    void theLastDeploymentsFileGoesToo() throws Exception {
        Path only = TestDeployments.write(dir, TestDeployments.DEFAULT);

        assertThat(teardown(0)).isZero();
        assertThat(only).doesNotExist();
    }

    @Test
    void aTeardownThatStoppedKeepsTheFile() throws Exception {
        Path only = TestDeployments.write(dir, TestDeployments.DEFAULT);

        assertThat(teardown(1)).isEqualTo(1);
        assertThat(only).exists();
    }

    /** R13: a copy of another deployment's file would aim the teardown at that deployment. */
    @Test
    void aCopiedFileStopsTheTeardownBeforeAnythingIsDeleted() throws Exception {
        Path main = TestDeployments.write(dir, TestDeployments.DEFAULT);
        Path copy = main.resolveSibling("wiktor-dev.yaml");
        Files.copy(main, copy);

        int exit = teardown(-99, "--deployment", "wiktor-dev");

        assertThat(exit).as("refused, and the AWS steps (which would exit -99) never ran").isNotIn(0, -99);
        assertThat(main).exists();
        assertThat(copy).exists();
    }

    /** Runs teardown with its AWS steps replaced by {@code awsExit}. */
    private int teardown(int awsExit, String... global) {
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == TeardownCommand.class) {
                    return (K) new TeardownCommand() {
                        @Override
                        int removeDeployment(BaasConfig config, String resolvedStack) {
                            return awsExit;
                        }
                    };
                }
                return defaults.create(cls);
            }
        };
        String[] args = new String[global.length + 4];
        System.arraycopy(global, 0, args, 0, global.length);
        System.arraycopy(new String[]{"admin", "deployment", "teardown", "--yes"}, 0, args, global.length, 4);
        return new CommandLine(new BaasApp(dir), factory)
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(new StringWriter()))
            .execute(args);
    }
}
