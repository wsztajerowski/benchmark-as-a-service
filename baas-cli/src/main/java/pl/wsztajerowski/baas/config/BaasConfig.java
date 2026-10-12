package pl.wsztajerowski.baas.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What a deployment's file, {@code ~/.baas/deployments/<name>.yaml}, holds — and, just as
 * importantly, what it does not.
 *
 * <p>Stored: the credential profiles and preferences the user chose, each only once it has been set,
 * and the region setup or sync recorded. Not stored: the deployment's name, which is the file's name;
 * every resource name, which is derived from it; and every default, which is supplied when read, so a
 * changed default reaches every machine instead of staying frozen in the files written before it.
 *
 * <p>The region is the one fact about AWS kept here. It cannot be derived without a call, and it does
 * not change while the deployment exists, so remembering it costs nothing; a machine with no file
 * finds it from the bucket instead.
 *
 * <p>Jackson reads and writes the fields, never the getters: a getter answers with the default, and
 * serialising it would write the default back into the file.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BaasConfig {

    /**
     * The deployment this configuration belongs to — taken from the file's name, or from the operator
     * role on a machine with no file. Never written: a name stored inside the file could disagree with
     * the file's own name.
     */
    @JsonIgnore
    private String prefix;

    @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = EmptySection.class)
    private AwsConfig aws = new AwsConfig();
    @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = EmptySection.class)
    private Ec2Config ec2 = new Ec2Config();
    @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = EmptySection.class)
    private GitConfig git = new GitConfig();

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }

    public AwsConfig getAws() { return aws; }
    public Ec2Config getEc2() { return ec2; }
    public GitConfig getGit() { return git; }

    // ─── Derived names ──────────────────────────────────────────────────────────
    // One rule: <prefix>, or <prefix>-<type>-<name>. Knowing the name is enough to predict
    // every resource name, which is why none of these is stored.

    public String requirePrefix() {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException("""
                No deployment is configured on this machine.
                  Adopt one:  baas --deployment <name> config sync
                  Create one: baas admin deployment setup""");
        }
        return prefix;
    }

    /** Every name composed from this deployment's name. */
    public DeploymentNames names() { return DeploymentNames.of(requirePrefix()); }

    /** The core stack's name. Identical to the deployment's name — the stack is the deployment. */
    public String stackName() { return names().stack(); }

    public String bucket() { return names().bucket(); }

    public String resultsTable() { return names().resultsTable(); }

    public String runnerInstanceProfile() { return names().runnerInstanceProfile(); }

    public String amiParameterPath() { return names().amiPointer(); }

    /** A part of the file that is left out entirely when nothing in it has been set. */
    interface Section {
        boolean isEmpty();
    }

    /** Jackson's {@code CUSTOM} inclusion filter: a value it "equals" is not written. */
    public static final class EmptySection {
        @Override
        public boolean equals(Object value) {
            return value instanceof Section section && section.isEmpty();
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AwsConfig implements Section {
        public static final String DEFAULT_REGION = "eu-central-1";

        private String deployerProfile;
        private String operatorProfile;
        /** Only what the file says; null when it says nothing. See {@link #resolveRegion()}. */
        private String region;

        /**
         * Credential profile for {@code baas admin}, used as is — the deployer is a policy on a
         * principal, not a role to assume. Written by {@code admin deployment setup
         * --deployer-aws-profile}.
         */
        public String getDeployerProfile() { return deployerProfile; }
        public void setDeployerProfile(String deployerProfile) { this.deployerProfile = deployerProfile; }

        /**
         * Credential profile a day-to-day command takes its <em>source</em> credentials from; the
         * command then runs as the deployment's operator role whatever this profile resolves to.
         */
        public String getOperatorProfile() { return operatorProfile; }
        public void setOperatorProfile(String operatorProfile) { this.operatorProfile = operatorProfile; }

        /**
         * Where a day-to-day command's source credentials come from: the operator profile, else the
         * deployer profile, else (null) the default credential chain. Falling back to the deployer is
         * safe because the source is only ever the input to assuming the operator role — no call is
         * made with it beyond identifying the caller, so deployer rights never reach a day-to-day
         * command.
         */
        public String operatorSourceProfile() {
            return operatorProfile != null ? operatorProfile : deployerProfile;
        }

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }

        /** Whether the file records the deployment's region. */
        public boolean hasRegion() {
            return region != null && !region.isBlank();
        }

        /**
         * The file's {@code aws.region}, else {@code AWS_REGION}, else {@value #DEFAULT_REGION}. The
         * file's value is the deployment's own region, recorded by setup or sync, so it wins. The
         * environment and the default only choose where setup creates a new deployment and where a
         * machine with no file starts looking for the deployment's bucket.
         *
         * <p>Resolved, never stored: {@link #getRegion()} stays the file's own value, so saving a
         * configuration cannot copy an environment variable into the file and pin it there.
         */
        public String resolveRegion() {
            return resolveRegion(System.getenv());
        }

        public String resolveRegion(java.util.Map<String, String> environment) {
            if (hasRegion()) return region;
            String fromEnvironment = environment.get("AWS_REGION");
            if (fromEnvironment != null && !fromEnvironment.isBlank()) return fromEnvironment.strip();
            return DEFAULT_REGION;
        }

        @Override
        public boolean isEmpty() {
            return deployerProfile == null && operatorProfile == null && region == null;
        }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Ec2Config implements Section {
        public static final String DEFAULT_INSTANCE_TYPE = "c5.2xlarge";
        public static final int DEFAULT_BENCHMARK_TIMEOUT_SECONDS = 7200;

        private String defaultInstanceType;
        private Integer benchmarkTimeoutSeconds;
        private Integer watchdogMarginSeconds;

        public String getDefaultInstanceType() {
            return defaultInstanceType != null ? defaultInstanceType : DEFAULT_INSTANCE_TYPE;
        }
        public void setDefaultInstanceType(String t) { this.defaultInstanceType = t; }
        public boolean isDefaultInstanceTypeSet() { return defaultInstanceType != null; }

        public int getBenchmarkTimeoutSeconds() {
            return benchmarkTimeoutSeconds != null ? benchmarkTimeoutSeconds : DEFAULT_BENCHMARK_TIMEOUT_SECONDS;
        }
        public void setBenchmarkTimeoutSeconds(int s) { this.benchmarkTimeoutSeconds = s; }
        public boolean isBenchmarkTimeoutSecondsSet() { return benchmarkTimeoutSeconds != null; }

        /**
         * Added to the benchmark timeout, never an absolute bound: an absolute one could be left
         * below a raised timeout, and the watchdog then killed a benchmark still inside its budget.
         */
        public int getWatchdogMarginSeconds() {
            return watchdogMarginSeconds != null ? watchdogMarginSeconds : DEFAULT_WATCHDOG_MARGIN_SECONDS;
        }
        public void setWatchdogMarginSeconds(int s) { this.watchdogMarginSeconds = s; }
        public boolean isWatchdogMarginSecondsSet() { return watchdogMarginSeconds != null; }

        @Override
        public boolean isEmpty() {
            return defaultInstanceType == null && benchmarkTimeoutSeconds == null && watchdogMarginSeconds == null;
        }
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
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GitConfig implements Section {
        private Boolean resolveProject;

        public boolean isResolveProject() { return Boolean.TRUE.equals(resolveProject); }
        public void setResolveProject(boolean resolveProject) { this.resolveProject = resolveProject; }
        public boolean isResolveProjectSet() { return resolveProject != null; }

        @Override
        public boolean isEmpty() {
            return resolveProject == null;
        }
    }
}
