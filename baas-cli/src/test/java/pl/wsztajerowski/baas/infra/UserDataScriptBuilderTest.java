package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserDataScriptBuilderTest {

    private String script() {
        String encoded = new UserDataScriptBuilder().build(
            "eu-central-1", "baas-a1b2c3d4", "a1b2c3d4", "jmh",
            "jmh-20260724_120000", "main/jmh/20260724_120000", 7200, 7500,
            "4.0", null, List.of("MyBenchmark", "-f", "1"));
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    @Test
    void shipsCloudInitLogToS3BeforeTerminating() {
        String script = script();

        assertThat(script)
            .as("the instance self-terminates, so an unshipped log is a log that no longer exists")
            .contains("/var/log/cloud-init-output.log")
            .contains("${RESULT_PATH}/cloud-init-output.log");

        assertThat(script.indexOf("cloud-init-output.log"))
            .as("the upload must happen before the terminate call")
            .isLessThan(script.lastIndexOf("terminate-instances"));
    }

    /**
     * The watchdog fires on the paths where the log matters most — a deadlocked JVM or a
     * hung download — and it terminates the instance without ever reaching the normal
     * upload further down the script. `baas run` points users at that S3 key, so it has
     * to exist on this path too.
     */
    @Test
    void watchdogShipsTheLogBeforeHardKillingTheInstance() {
        String script = script();

        int watchdogStart = script.indexOf("# Layer 1: background watchdog");
        int watchdogTerminate = script.indexOf("terminate-instances", watchdogStart);
        int uploadInWatchdog = script.indexOf("cloud-init-output.log", watchdogStart);

        assertThat(watchdogStart).isNotNegative();
        assertThat(uploadInWatchdog)
            .as("the watchdog kills the instance without reaching the normal upload")
            .isNotNegative()
            .isLessThan(watchdogTerminate);
    }

    @Test
    void passesBenchmarkParametersThrough() {
        assertThat(script()).contains("export BENCHMARK_PARAMETERS='MyBenchmark -f 1'");
    }
}
