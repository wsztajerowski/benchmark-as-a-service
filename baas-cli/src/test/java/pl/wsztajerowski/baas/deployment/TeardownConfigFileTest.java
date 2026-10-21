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


    /** Runs teardown against a fake AWS: exit 0 completes it, 1 stops it at a live runner. */
    private int teardown(int awsExit, String... global) {
        var aws = new FakeTeardownAws();
        if (awsExit != 0) {
            aws.live = java.util.List.of(new pl.wsztajerowski.baas.jobs.Ec2ProvisioningService.LiveRunner("i-1", "running", null));
        }
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == TeardownCommand.class) {
                    var command = new TeardownCommand();
                    command.awsFor = config -> aws;
                    return (K) command;
                }
                return defaults.create(cls);
            }
        };
        String[] args = new String[global.length + 4];
        System.arraycopy(global, 0, args, 0, global.length);
        System.arraycopy(new String[]{"admin", "deployment", "teardown", "--yes"}, 0, args, global.length, 4);
        return BaasApp.commandLine(new BaasApp(dir), factory)
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(new StringWriter()))
            .execute(args);
    }
}
