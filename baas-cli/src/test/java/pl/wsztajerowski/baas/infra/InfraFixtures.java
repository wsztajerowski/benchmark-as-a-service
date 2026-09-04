package pl.wsztajerowski.baas.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Loads the CloudFormation templates and IAM policy documents under {@code infra/}
 * from the test classpath, so their structure can be asserted on like any other code.
 */
final class InfraFixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InfraFixtures() {
    }

    static Map<String, Object> coreTemplate() {
        return loadYaml("/templates/cf-template-core.yaml");
    }

    static Map<String, Object> ciTemplate() {
        return loadYaml("/infra/cf-template-ci.yaml");
    }

    /**
     * The rendered policy, not the template — the file on disk carries {@code ${…}} placeholders
     * and is never attached in that form, so asserting on it directly would test nothing real.
     */
    static Map<String, Object> deployerPolicy() {
        return parseJson(new DeployerPolicyRenderer().render(ACCOUNT_ID, REGION, PREFIX));
    }

    static final String ACCOUNT_ID = "123456789012";
    static final String REGION = "eu-central-1";
    static final String PREFIX = "a1b2c3d4";

    static Map<String, Object> operatorPolicy() {
        return loadJson("/infra/operator-policy.json");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resource(Map<String, Object> template, String logicalId) {
        var resources = (Map<String, Object>) template.get("Resources");
        var resource = (Map<String, Object>) resources.get(logicalId);
        if (resource == null) {
            throw new AssertionError("No resource '" + logicalId + "' in template. Present: " + resources.keySet());
        }
        return resource;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> properties(Map<String, Object> template, String logicalId) {
        return (Map<String, Object>) resource(template, logicalId).get("Properties");
    }

    /** Flattens every {@code Action} entry (string or list) across a policy document's statements. */
    @SuppressWarnings("unchecked")
    static Set<String> actions(Map<String, Object> policyDocument) {
        var statements = (List<Map<String, Object>>) policyDocument.get("Statement");
        Set<String> actions = new TreeSet<>();
        for (Map<String, Object> statement : statements) {
            Object action = statement.get("Action");
            if (action instanceof String single) {
                actions.add(single);
            } else {
                actions.addAll((List<String>) action);
            }
        }
        return actions;
    }

    /**
     * Whether a policy permits an action, accounting for the {@code Service:Verb*} wildcards the
     * deployer policy uses to stay inside IAM's document size limit.
     *
     * <p>Asserting on literal action strings would make every wildcard look like a removed
     * permission, so these tests state what the policy lets a caller <em>do</em> instead of how it
     * happens to be spelled. Effect is not consulted — the document contains only Allows.
     */
    static boolean grants(Map<String, Object> policyDocument, String action) {
        return actions(policyDocument).stream().anyMatch(granted -> granted.equals(action)
            || (granted.endsWith("*") && action.startsWith(granted.substring(0, granted.length() - 1))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String classpathResource) {
        try (InputStream is = open(classpathResource)) {
            return new Yaml(new IntrinsicTolerantConstructor()).load(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadJson(String classpathResource) {
        try (InputStream is = open(classpathResource)) {
            return JSON.readValue(is, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String json) {
        try {
            return JSON.readValue(json, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every {@code Resource} entry (string or list) across a policy document's statements. */
    @SuppressWarnings("unchecked")
    static Set<String> resources(Map<String, Object> policyDocument) {
        var statements = (List<Map<String, Object>>) policyDocument.get("Statement");
        Set<String> resources = new TreeSet<>();
        for (Map<String, Object> statement : statements) {
            Object resource = statement.get("Resource");
            if (resource instanceof String single) {
                resources.add(single);
            } else {
                resources.addAll((List<String>) resource);
            }
        }
        return resources;
    }

    private static InputStream open(String classpathResource) {
        InputStream is = InfraFixtures.class.getResourceAsStream(classpathResource);
        if (is == null) {
            throw new AssertionError(
                classpathResource + " is not on the test classpath — check the <testResources> block in baas-cli/pom.xml");
        }
        return is;
    }

    /**
     * CloudFormation's short-form intrinsics (!Sub, !Ref, !GetAtt, !If, !Select, !GetAZs)
     * are unknown tags to a stock YAML parser. Collapse each to its argument value, which
     * is enough to assert on template structure.
     */
    private static class IntrinsicTolerantConstructor extends SafeConstructor {

        IntrinsicTolerantConstructor() {
            super(new LoaderOptions());
            yamlConstructors.put(null, new CollapseIntrinsic());
        }

        private class CollapseIntrinsic extends AbstractConstruct {
            @Override
            public Object construct(Node node) {
                return switch (node) {
                    case ScalarNode scalar -> scalar.getValue();
                    case SequenceNode sequence -> constructSequence(sequence);
                    case MappingNode mapping -> constructMapping(mapping);
                    default -> null;
                };
            }
        }
    }
}
