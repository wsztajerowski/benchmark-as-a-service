package pl.wsztajerowski.baas.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Hands a started session to {@code session-manager-plugin}, with the arguments the AWS CLI passes
 * it: the StartSession response, the region, the operation, an empty profile, the request, and the
 * SSM endpoint. The response carries only the session id, its stream URL and a single-use stream
 * token. The operator role's credentials live only inside this JVM, so they reach neither the
 * child's arguments nor its environment.
 */
final class SessionLauncher {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A session {@code StartSession} opened: what the plugin needs to attach to it. */
    record Started(String sessionId, String streamUrl, String tokenValue) {}

    /** Starts a process with the terminal handed over; replaced by tests. */
    interface Runner {
        Attached start(List<String> command) throws IOException;
    }

    /** The running plugin, as much of {@link Process} as connecting needs. */
    interface Attached {
        /** Whether it exited within {@code timeout}. */
        boolean waitFor(Duration timeout) throws InterruptedException;

        int exitValue();

        void destroy();
    }

    static final Runner INHERIT_TERMINAL = command -> {
        Process process = new ProcessBuilder(command).inheritIO().start();
        return new Attached() {
            public boolean waitFor(Duration timeout) throws InterruptedException {
                return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            public int exitValue() { return process.exitValue(); }
            public void destroy() { process.destroy(); }
        };
    };

    private SessionLauncher() {}

    static List<String> command(Path plugin, Started session, String region, String instanceId, String document) {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("SessionId", session.sessionId());
        response.put("TokenValue", session.tokenValue());
        response.put("StreamUrl", session.streamUrl());
        Map<String, String> request = new LinkedHashMap<>();
        request.put("Target", instanceId);
        request.put("DocumentName", document);
        return List.of(plugin.toString(), json(response), region, "StartSession", "", json(request),
            "https://ssm." + region + ".amazonaws.com");
    }

    private static String json(Map<String, String> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
