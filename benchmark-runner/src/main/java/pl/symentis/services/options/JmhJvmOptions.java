package pl.symentis.services.options;

public record JmhJvmOptions(
    String jvm,
    String jvmArgs,
    String jvmArgsAppend,
    String jvmArgsPrepend) {

    public static JmhJvmOptionsBuilder jmhJvmOptionsBuilder() {
        return new JmhJvmOptionsBuilder();
    }

    public static final class JmhJvmOptionsBuilder {
        private String jvm;
        private String jvmArgs;
        private String jvmArgsAppend;
        private String jvmArgsPrepend;

        private JmhJvmOptionsBuilder() {
        }

        public JmhJvmOptionsBuilder withJvm(String jvm) {
            this.jvm = jvm;
            return this;
        }

        public JmhJvmOptionsBuilder withJvmArgs(String jvmArgs) {
            this.jvmArgs = jvmArgs;
            return this;
        }

        public JmhJvmOptionsBuilder withJvmArgsAppend(String jvmArgsAppend) {
            this.jvmArgsAppend = jvmArgsAppend;
            return this;
        }

        public JmhJvmOptionsBuilder withJvmArgsPrepend(String jvmArgsPrepend) {
            this.jvmArgsPrepend = jvmArgsPrepend;
            return this;
        }

        public JmhJvmOptions build() {
            return new JmhJvmOptions(jvm, jvmArgs, jvmArgsAppend, jvmArgsPrepend);
        }
    }
}
