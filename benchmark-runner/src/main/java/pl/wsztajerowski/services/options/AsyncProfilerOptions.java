package pl.wsztajerowski.services.options;

import java.nio.file.Path;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public record AsyncProfilerOptions(
    Path asyncPath,
    int asyncInterval,
    String asyncEvent,
    String asyncOutputType,
    Path asyncOutputPath,
    Map<String, String> asyncAdditionalOptions) {

    public static AsyncProfilerOptionsBuilder asyncProfilerOptionsBuilder() {
        return new AsyncProfilerOptionsBuilder();
    }

    public static final class AsyncProfilerOptionsBuilder {
        private Path asyncPath;
        private int asyncInterval;
        private String asyncEvent;
        private String asyncOutputType;
        private Path asyncOutputPath;
        private Map<String, String> asyncAdditionalOptions;

        private AsyncProfilerOptionsBuilder() {
        }

        public AsyncProfilerOptionsBuilder withAsyncPath(Path asyncPath) {
            this.asyncPath = asyncPath;
            return this;
        }

        public AsyncProfilerOptionsBuilder withAsyncInterval(int asyncInterval) {
            this.asyncInterval = asyncInterval;
            return this;
        }

        public AsyncProfilerOptionsBuilder withAsyncEvent(String asyncEvent) {
            this.asyncEvent = asyncEvent;
            return this;
        }

        public AsyncProfilerOptionsBuilder withAsyncOutputType(String asyncOutputType) {
            this.asyncOutputType = asyncOutputType;
            return this;
        }

        public AsyncProfilerOptionsBuilder withAsyncOutputPath(Path asyncOutputPath) {
            this.asyncOutputPath = asyncOutputPath;
            return this;
        }

        public AsyncProfilerOptionsBuilder withAsyncAdditionalOptions(Map<String, String> asyncAdditionalOptions) {
            this.asyncAdditionalOptions = asyncAdditionalOptions;
            return this;
        }

        public AsyncProfilerOptions build() {
            requireNonNull(asyncPath, "asyncPath cannot be null");
            requireNonNull(asyncOutputPath, "asyncOutputPath cannot be null");
            return new AsyncProfilerOptions(asyncPath, asyncInterval, asyncEvent, asyncOutputType, asyncOutputPath, asyncAdditionalOptions);
        }
    }
}
