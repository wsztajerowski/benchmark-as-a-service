package pl.wsztajerowski.services.options;
import java.nio.file.Path;

public record JmhOutputOptions(
    Path processOutput,
    Path machineReadableOutput) {

    public static JmhOutputOptionsBuilder jmhOutputOptionsBuilder() {
        return new JmhOutputOptionsBuilder();
    }

    public static final class JmhOutputOptionsBuilder {
        private Path processOutput;
        private Path machineReadableOutput;

        private JmhOutputOptionsBuilder() {
        }

        public JmhOutputOptionsBuilder withProcessOutput(Path processOutput) {
            this.processOutput = processOutput;
            return this;
        }

        public JmhOutputOptionsBuilder withMachineReadableOutput(Path machineReadableOutput) {
            this.machineReadableOutput = machineReadableOutput;
            return this;
        }

        public JmhOutputOptions build() {
            return new JmhOutputOptions(processOutput, machineReadableOutput);
        }
    }
}
