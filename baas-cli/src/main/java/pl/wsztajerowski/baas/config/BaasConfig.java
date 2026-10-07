package pl.wsztajerowski.baas.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What {@code ~/.baas/config.yaml} holds, and — just as importantly — what it does not.
 *
 * <p>Stored: credentials, the region, the deployment prefix, and the operator's own preferences.
 * Everything else is either <em>derived</em> from the prefix by the one composition rule, or
 * <em>resolved</em> from the deployment's stack at use time.
 *
 * <p>The distinction is not tidiness. A stored bucket or table name can point at one deployment
 * while {@code prefix} names another, and a stored subnet or security-group id can outlive the
 * resource it names — replacing {@code RunnerSecurityGroup} moves its id, and a cached copy then
 * addresses a group that no longer exists. Deriving and resolving removes both failures instead of
 * documenting them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BaasConfig {

    /**
     * The deployment this machine addresses: {@code baas-<accountId>}, plus {@code -dev} for the
     * development deployment. Written by {@code baas admin deployment setup} and {@code baas config sync
     * --name}. No default — a machine that has adopted no deployment must say so rather than
     * silently address one.
     */
    private String prefix;

    private AwsConfig aws = new AwsConfig();
    private Ec2Config ec2 = new Ec2Config();
    private RunnerConfig runner = new RunnerConfig();
    private GitConfig git = new GitConfig();

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }

    public AwsConfig getAws() { return aws; }
    public void setAws(AwsConfig aws) { this.aws = aws; }

    public Ec2Config getEc2() { return ec2; }
    public void setEc2(Ec2Config ec2) { this.ec2 = ec2; }

    public RunnerConfig getRunner() { return runner; }
    public void setRunner(RunnerConfig runner) { this.runner = runner; }

    public GitConfig getGit() { return git; }
    public void setGit(GitConfig git) { this.git = git; }

    // ─── Derived names ──────────────────────────────────────────────────────────
    // One rule: <prefix>, or <prefix>-<type>-<name>. Knowing the prefix is enough to predict
    // every name, which is why none of these is stored.

    @JsonIgnore
    public String requirePrefix() {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException("""
                No deployment is configured on this machine.
                  Adopt one:  baas config sync --deployment baas-<accountId>
                  Create one: baas admin deployment setup""");
        }
        return prefix;
    }

    /** Every name composed from this deployment's prefix. */
    @JsonIgnore
    public DeploymentNames names() { return DeploymentNames.of(requirePrefix()); }

    /** The core stack's name. Identical to the prefix — the stack is the deployment. */
    @JsonIgnore
    public String stackName() { return names().stack(); }

    @JsonIgnore
    public String bucket() { return names().bucket(); }

    @JsonIgnore
    public String resultsTable() { return names().resultsTable(); }

    @JsonIgnore
    public String runnerInstanceProfile() { return names().runnerInstanceProfile(); }

    @JsonIgnore
    public String amiParameterPath() { return names().amiPointer(); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AwsConfig {
        public static final String DEFAULT_REGION = "eu-central-1";

        private String deployerProfile;
        private String operatorProfile;
        /** Only what the file says; null when it says nothing. See {@link #resolveRegion()}. */
        private String region;

        /**
         * Credential profile for {@code baas admin} — the deployer's, written by {@code admin
         * deployment setup --deployer-profile}. Read under its old key {@code aws.profile} too, which
         * files from before the rename carry; the next save writes the new key only.
         */
        public String getDeployerProfile() { return deployerProfile; }
        @JsonAlias("profile")
        public void setDeployerProfile(String deployerProfile) { this.deployerProfile = deployerProfile; }

        public String getOperatorProfile() { return operatorProfile; }
        public void setOperatorProfile(String operatorProfile) { this.operatorProfile = operatorProfile; }

        /**
         * Credential profile for day-to-day commands (run/results/config show), which are
         * meant to run under BaasCliOperatorRole. Deliberately does NOT fall back to
         * {@link #deployerProfile} — that field holds the deployer profile written by
         * `baas admin deployment setup`, and silently reusing it would hand every benchmark job
         * iam:CreateRole and cloudformation:*. A null return means "default credential
         * chain", so AWS_PROFILE still works.
         */
        public String resolveOperatorProfile() { return operatorProfile; }

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }

        /**
         * The region every command uses: the file's {@code aws.region}, else {@code AWS_REGION},
         * else {@value #DEFAULT_REGION}. The file's value is the deployment's own region —
         * {@code admin deployment setup} writes the one it deployed to, {@code config sync} the one it found
         * the deployment's bucket in — so it wins. The environment and the default matter only
         * before any deployment is adopted: where {@code admin deployment setup} and
         * {@code admin deployment setup} deploy or render its policy, and where {@code config sync} starts
         * looking (any region finds the bucket).
         *
         * <p>Resolved, never stored: {@link #getRegion()} stays the file's own value, so saving a
         * configuration cannot copy an environment variable into the file and pin it there.
         */
        public String resolveRegion() {
            return resolveRegion(System.getenv());
        }

        String resolveRegion(java.util.Map<String, String> environment) {
            if (region != null && !region.isBlank()) return region;
            String fromEnvironment = environment.get("AWS_REGION");
            if (fromEnvironment != null && !fromEnvironment.isBlank()) return fromEnvironment.strip();
            return DEFAULT_REGION;
        }
    }

    public static class Ec2Config {
        private String defaultInstanceType = "c5.2xlarge";
        private int benchmarkTimeoutSeconds = 7200;
        private int watchdogMarginSeconds = DEFAULT_WATCHDOG_MARGIN_SECONDS;

        public String getDefaultInstanceType() { return defaultInstanceType; }
        public void setDefaultInstanceType(String t) { this.defaultInstanceType = t; }

        public int getBenchmarkTimeoutSeconds() { return benchmarkTimeoutSeconds; }
        public void setBenchmarkTimeoutSeconds(int s) { this.benchmarkTimeoutSeconds = s; }

        /**
         * Added to the benchmark timeout, never an absolute bound: an absolute one could be left
         * below a raised timeout, and the watchdog then killed a benchmark still inside its budget.
         * A leftover {@code wallClockHardKillSeconds} key is ignored as unknown, so old files load.
         */
        public int getWatchdogMarginSeconds() { return watchdogMarginSeconds; }
        public void setWatchdogMarginSeconds(int s) { this.watchdogMarginSeconds = s; }
    }

    public static final int DEFAULT_WATCHDOG_MARGIN_SECONDS = 300;

    /**
     * The watchdog counts from launch, the benchmark timeout from JVM start, so the margin has to
     * cover boot plus the final upload. Below this the watchdog can terminate the instance before
     * its final status is recorded, and the job looks like it vanished.
     */
    public static final int MIN_WATCHDOG_MARGIN_SECONDS = 60;

    /**
     * Off by default: git is consulted only when the operator opts in, and then only for the
     * project name — {@code baas run} from the benchmark JAR's repository, {@code baas results query}
     * from the working directory's. {@code branch} and {@code commit} are never derived.
     */
    public static class GitConfig {
        private boolean resolveProject;

        public boolean isResolveProject() { return resolveProject; }
        public void setResolveProject(boolean resolveProject) { this.resolveProject = resolveProject; }
    }

    /**
     * Where the version-pinned runner JAR is fetched from when {@code releases/<version>/} is not
     * yet seeded. Configuration rather than a string baked into the user-data script, so a fork can
     * point at its own releases (finding A7).
     */
    public static class RunnerConfig {
        private String sourceRepo = "wsztajerowski/benchmark-as-a-service";

        public String getSourceRepo() { return sourceRepo; }
        public void setSourceRepo(String sourceRepo) { this.sourceRepo = sourceRepo; }
    }
}
