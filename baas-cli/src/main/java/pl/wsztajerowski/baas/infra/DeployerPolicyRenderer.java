package pl.wsztajerowski.baas.infra;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Renders {@code infra/deployer-policy.json} for one specific caller.
 *
 * <p>The file on disk is a template, never a policy: every resource it names is derived from the
 * caller's account, region and ARN-hash prefix, so there is no correct wildcard form to attach.
 * {@code baas admin setup} prints the rendered output when the caller is missing something.
 */
public class DeployerPolicyRenderer {

    private static final String POLICY_TEMPLATE = "/templates/deployer-policy.json";
    private static final Pattern UNRESOLVED = Pattern.compile("\\$\\{[A-Z_]+}");

    public String render(String accountId, String region, String prefix) {
        String rendered = load(POLICY_TEMPLATE)
            .replace("${ACCOUNT_ID}", accountId)
            .replace("${REGION}", region)
            .replace("${PREFIX}", prefix);

        // A surviving placeholder would be attached verbatim and silently match nothing.
        var leftover = UNRESOLVED.matcher(rendered);
        if (leftover.find()) {
            throw new IllegalStateException(
                "deployer-policy.json still contains " + leftover.group() + " after rendering");
        }
        return rendered;
    }

    private String load(String classpathResource) {
        try (InputStream is = getClass().getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new IllegalStateException(classpathResource + " is not on the classpath");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
