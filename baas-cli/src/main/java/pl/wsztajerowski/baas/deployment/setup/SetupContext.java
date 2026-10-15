package pl.wsztajerowski.baas.deployment.setup;

import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.session.ConfigService;
import pl.wsztajerowski.baas.console.Console;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Everything the run has found and been told so far. The one mutable object of the walkthrough:
 * steps read earlier answers from here and never from each other.
 */
final class SetupContext {

    /** Sleeps between retries; replaced in tests so they take no real time. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** GitHub federation: the organisation and the repository names under it. */
    record Federation(String org, List<String> repos) {
        Federation {
            repos = List.copyOf(repos);
        }

        String describe() {
            return repos.stream().map(repo -> org + "/" + repo).reduce((a, b) -> a + ", " + b).orElse(org);
        }
    }

    final SetupOptions options;
    final ConfigService configs;
    private final boolean interactive;
    private final Prompter prompter;
    private final Console payload;
    private final BiFunction<String, String, SetupAws> awsFactory;
    final Sleeper sleeper;

    // ─── Step 1: which deployment ───
    BaasConfig config;
    /** A file for it exists on this machine. */
    boolean existing;
    /** Null until a new deployment is named. */
    String deployment;

    // ─── Step 2: credentials ───
    String storedProfile;
    /** Null means the default credential chain. */
    String profile;
    boolean profileAsked;
    SetupAws.Caller caller;

    // ─── Step 3: name and region ───
    String region;

    // ─── Step 4: permissions ───
    String policy;
    /** Where the policy was saved this run, so a later hand-over names it rather than asking again. */
    Path policySavedTo;
    SetupAws.Preflight preflight;

    // ─── Step 5: names; the stack's status as found before anything changed ───
    Optional<StackStatus> stackStatus = Optional.empty();

    // ─── Step 6: GitHub Actions ───
    /** What the stack trusts now; empty when it trusts no repository. */
    Optional<Federation> deployedFederation = Optional.empty();
    /** What the run will submit; empty means carry the deployed values forward. */
    Optional<Federation> federation = Optional.empty();
    boolean federationAsked;
    boolean revoke;
    boolean federationChanged;
    boolean providerToCreate;

    // ─── Steps 7–11 ───
    boolean planConfirmed;
    boolean created;
    boolean stackChanged;
    boolean providerCreated;
    boolean imageBuilt;
    boolean imageMissing;
    boolean fileWritten;
    String operatorRoleArn;

    private SetupAws aws;
    private String awsRegion;
    private String awsProfile;

    SetupContext(SetupOptions options, ConfigService configs, boolean interactive, Prompter prompter,
                 Console payload, BiFunction<String, String, SetupAws> awsFactory, Sleeper sleeper) {
        this.options = options;
        this.configs = configs;
        this.interactive = interactive;
        this.prompter = prompter;
        this.payload = payload;
        this.awsFactory = awsFactory;
        this.sleeper = sleeper;
    }

    boolean interactive() {
        return interactive;
    }

    Prompter prompter() {
        return prompter;
    }

    /** Standard output, for payload only — the policy. */
    Console payload() {
        return payload;
    }

    /** The region in use: the deployment's once known, else the option's or the default. */
    String currentRegion() {
        if (region != null) {
            return region;
        }
        if (options.region() != null) {
            return options.region();
        }
        return config != null ? config.getAws().resolveRegion() : new BaasConfig().getAws().resolveRegion();
    }

    /** AWS for the current region and profile, rebuilt only when either changes. */
    SetupAws aws() {
        String r = currentRegion();
        if (aws == null || !Objects.equals(r, awsRegion) || !Objects.equals(profile, awsProfile)) {
            aws = awsFactory.apply(r, profile);
            awsRegion = r;
            awsProfile = profile;
        }
        return aws;
    }

    boolean creating() {
        return stackStatus.isEmpty() || stackStatus.get() == StackStatus.ROLLBACK_COMPLETE;
    }

    /** Whether this run created or changed anything a repeat would reproduce. */
    boolean changedAnything() {
        return created || stackChanged || providerCreated || imageBuilt || federationChanged
            || !Objects.equals(profile, storedProfile);
    }
}
