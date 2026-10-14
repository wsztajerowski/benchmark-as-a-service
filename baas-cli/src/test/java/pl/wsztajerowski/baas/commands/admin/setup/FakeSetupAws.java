package pl.wsztajerowski.baas.commands.admin.setup;

import pl.wsztajerowski.baas.infra.BucketProbe;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory AWS for the walkthrough, written by hand as the module's fakes are. Every call is
 * recorded in order, so a test can say what was — and was not — asked of AWS.
 */
final class FakeSetupAws implements SetupAws {

    static final String ACCOUNT = "123456789012";
    static final String USER = "arn:aws:iam::" + ACCOUNT + ":user/alice";

    final List<String> calls = new ArrayList<>();
    /** The region and profile each instance was built for, as the factory saw them. */
    final List<String> builtFor = new ArrayList<>();

    Caller caller = new Caller(USER, ACCOUNT);
    /** Failures {@code callerIdentity} throws before it answers, one per call. */
    final Deque<RuntimeException> identityFailures = new ArrayDeque<>();
    final Map<String, BucketProbe> buckets = new HashMap<>();
    final Set<String> tables = new HashSet<>();
    /** Answers to successive permission checks; the last one repeats. */
    final Deque<Preflight> preflights = new ArrayDeque<>(List.of(new Preflight.Met()));
    final Map<String, StackStatus> stacks = new HashMap<>();
    final Map<String, Map<String, String>> stackParameters = new HashMap<>();
    Map<String, String> submitted = Map.of();
    RuntimeException awaitCreateFailure;
    /** Failures the next creates or updates throw, one per call. */
    final Deque<RuntimeException> stackFailures = new ArrayDeque<>();
    boolean updateChanges = true;
    int assumeFailuresLeft;
    OidcProvider provider = new OidcProvider.Absent();
    Image image = new Image.Absent();
    Optional<RunningBuild> runningBuild = Optional.empty();
    IllegalStateException buildFailure;

    /** A factory handing out this one fake, recording each region and profile asked for. */
    java.util.function.BiFunction<String, String, SetupAws> factory() {
        return (region, profile) -> {
            builtFor.add(region + "/" + profile);
            return this;
        };
    }

    @Override
    public Caller callerIdentity() {
        calls.add("callerIdentity");
        if (!identityFailures.isEmpty()) {
            throw identityFailures.pop();
        }
        return caller;
    }

    static SdkClientException expiredSession() {
        return SdkClientException.create("The SSO session associated with this profile has expired");
    }

    @Override
    public BucketProbe bucket(String name) {
        calls.add("bucket:" + name);
        return buckets.getOrDefault(name, new BucketProbe.Absent());
    }

    @Override
    public boolean tableExists(String name) {
        calls.add("tableExists:" + name);
        return tables.contains(name);
    }

    @Override
    public Preflight preflight(String callerArn, String policy) {
        calls.add("preflight");
        return preflights.size() > 1 ? preflights.pop() : preflights.peek();
    }

    @Override
    public Optional<StackStatus> stackStatus(String stack) {
        calls.add("stackStatus:" + stack);
        return Optional.ofNullable(stacks.get(stack));
    }

    @Override
    public Optional<StackStatus> awaitSettled(String stack) {
        calls.add("awaitSettled:" + stack);
        StackStatus status = stacks.get(stack);
        if (status == StackStatus.CREATE_IN_PROGRESS) {
            stacks.put(stack, StackStatus.CREATE_COMPLETE);
        } else if (status == StackStatus.ROLLBACK_IN_PROGRESS) {
            stacks.put(stack, StackStatus.ROLLBACK_COMPLETE);
        } else if (status == StackStatus.UPDATE_IN_PROGRESS) {
            stacks.put(stack, StackStatus.UPDATE_COMPLETE);
        }
        return Optional.ofNullable(stacks.get(stack));
    }

    @Override
    public void deleteStack(String stack) {
        calls.add("deleteStack:" + stack);
        stacks.remove(stack);
        stackParameters.remove(stack);
    }

    @Override
    public Map<String, String> stackParameters(String stack) {
        calls.add("stackParameters:" + stack);
        return stackParameters.getOrDefault(stack, Map.of());
    }

    @Override
    public Map<String, String> stackOutputs(String stack) {
        return stacks.containsKey(stack)
            ? Map.of("OperatorRoleArn", "arn:aws:iam::" + ACCOUNT + ":role/" + stack + "-role-operator")
            : Map.of();
    }

    @Override
    public Map<String, String> imageParameters(Map<String, String> deployed) {
        return deployed.isEmpty() ? Map.of("RunnerImageVersion", "1.3.0") : Map.of();
    }

    @Override
    public void submitCreate(String stack, Map<String, String> parameters) {
        calls.add("submitCreate:" + stack);
        if (!stackFailures.isEmpty()) {
            throw stackFailures.pop();
        }
        submitted = new LinkedHashMap<>(parameters);
        stacks.put(stack, StackStatus.CREATE_IN_PROGRESS);
        stackParameters.put(stack, new LinkedHashMap<>(parameters));
    }

    @Override
    public void awaitCreate(String stack) {
        calls.add("awaitCreate:" + stack);
        if (awaitCreateFailure != null) {
            stacks.put(stack, StackStatus.ROLLBACK_COMPLETE);
            throw awaitCreateFailure;
        }
        stacks.put(stack, StackStatus.CREATE_COMPLETE);
    }

    @Override
    public boolean update(String stack, Map<String, String> parameters) {
        calls.add("update:" + stack);
        if (!stackFailures.isEmpty()) {
            throw stackFailures.pop();
        }
        submitted = new LinkedHashMap<>(parameters);
        Map<String, String> merged = new LinkedHashMap<>(stackParameters.getOrDefault(stack, Map.of()));
        merged.putAll(parameters);
        stackParameters.put(stack, merged);
        return updateChanges;
    }

    @Override
    public void assumeRole(String roleArn) {
        calls.add("assumeRole:" + roleArn);
        if (assumeFailuresLeft > 0) {
            assumeFailuresLeft--;
            throw new IllegalStateException("AccessDenied: not authorized to perform sts:AssumeRole");
        }
    }

    @Override
    public OidcProvider oidcProvider(String arn) {
        calls.add("oidcProvider:" + arn);
        return provider;
    }

    @Override
    public void createOidcProvider() {
        calls.add("createOidcProvider");
        provider = new OidcProvider.Present(List.of(STS_AUDIENCE));
    }

    @Override
    public Image image(String deployment) {
        calls.add("image:" + deployment);
        return image;
    }

    @Override
    public Optional<RunningBuild> runningImageBuild(String deployment) {
        calls.add("runningImageBuild:" + deployment);
        return runningBuild;
    }

    @Override
    public String buildImage(String deployment) {
        calls.add("buildImage:" + deployment);
        if (buildFailure != null) {
            throw buildFailure;
        }
        runningBuild = Optional.empty();
        image = new Image.Current("1.3.0");
        return "ami-0123456789abcdef0";
    }

    boolean called(String call) {
        return calls.contains(call);
    }

    boolean calledAny(String prefix) {
        return calls.stream().anyMatch(call -> call.startsWith(prefix));
    }
}
