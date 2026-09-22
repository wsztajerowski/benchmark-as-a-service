package pl.wsztajerowski.baas.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What {@code ~/.baas/config.yaml} holds, and — just as importantly — what it does not.
 *
 * <p>Stored: credentials, the region, the installation prefix, and the operator's own preferences.
 * Everything else is either <em>derived</em> from the prefix by the one composition rule, or
 * <em>resolved</em> from the installation's stack at use time.
 *
 * <p>The distinction is not tidiness. A stored bucket or table name can point at one installation
 * while {@code prefix} names another, and a stored subnet or security-group id can outlive the
 * resource it names — replacing {@code RunnerSecurityGroup} moves its id, and a cached copy then
 * addresses a group that no longer exists. Deriving and resolving removes both failures instead of
 * documenting them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BaasConfig {

    /**
     * The installation this machine addresses: {@code baas-<accountId>}, plus {@code -dev} for the
     * development installation. Written by {@code baas admin setup} and {@code baas config sync
     * --name}. No default — a machine that has adopted no installation must say so rather than
     * silently address one.
     */
    private String prefix;

    private AwsConfig aws = new AwsConfig();
    private Ec2Config ec2 = new Ec2Config();
    private RunnerConfig runner = new RunnerConfig();

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }

    public AwsConfig getAws() { return aws; }
    public void setAws(AwsConfig aws) { this.aws = aws; }

    public Ec2Config getEc2() { return ec2; }
    public void setEc2(Ec2Config ec2) { this.ec2 = ec2; }

    public RunnerConfig getRunner() { return runner; }
    public void setRunner(RunnerConfig runner) { this.runner = runner; }

    // ─── Derived names ──────────────────────────────────────────────────────────
    // One rule: <prefix>, or <prefix>-<type>-<name>. Knowing the prefix is enough to predict
    // every name, which is why none of these is stored.

    @JsonIgnore
    public String requirePrefix() {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException("""
                No installation is configured on this machine.
                  Adopt one:  baas config sync --name baas-<accountId>
                  Create one: baas admin setup""");
        }
        return prefix;
    }

    /** The core stack's name. Identical to the prefix — the stack is the installation. */
    @JsonIgnore
    public String stackName() { return requirePrefix(); }

    @JsonIgnore
    public String bucket() { return requirePrefix(); }

    @JsonIgnore
    public String resultsTable() { return requirePrefix() + "-results"; }

    @JsonIgnore
    public String runnerInstanceProfile() { return requirePrefix() + "-profile-runner"; }

    @JsonIgnore
    public String amiParameterPath() { return "/" + requirePrefix() + "/runner/ami-id"; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AwsConfig {
        private String profile;
        private String operatorProfile;
        private String region = "eu-central-1";

        public String getProfile() { return profile; }
        public void setProfile(String profile) { this.profile = profile; }

        public String getOperatorProfile() { return operatorProfile; }
        public void setOperatorProfile(String operatorProfile) { this.operatorProfile = operatorProfile; }

        /**
         * Credential profile for day-to-day commands (run/results/config show), which are
         * meant to run under BaasCliOperatorRole. Deliberately does NOT fall back to
         * {@link #profile} — that field holds the deployer profile written by
         * `baas admin setup`, and silently reusing it would hand every benchmark run
         * iam:CreateRole and cloudformation:*. A null return means "default credential
         * chain", so AWS_PROFILE still works.
         */
        public String resolveOperatorProfile() { return operatorProfile; }

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
    }

    public static class Ec2Config {
        private String defaultInstanceType = "c5.2xlarge";
        private int benchmarkTimeoutSeconds = 7200;
        private int wallClockHardKillSeconds = 7500;

        public String getDefaultInstanceType() { return defaultInstanceType; }
        public void setDefaultInstanceType(String t) { this.defaultInstanceType = t; }

        public int getBenchmarkTimeoutSeconds() { return benchmarkTimeoutSeconds; }
        public void setBenchmarkTimeoutSeconds(int s) { this.benchmarkTimeoutSeconds = s; }

        public int getWallClockHardKillSeconds() { return wallClockHardKillSeconds; }
        public void setWallClockHardKillSeconds(int s) { this.wallClockHardKillSeconds = s; }
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
