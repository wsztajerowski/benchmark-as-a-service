package pl.wsztajerowski.baas.config;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BaasConfig {

    private String prefix = "baas";
    private AwsConfig aws = new AwsConfig();
    private Ec2Config ec2 = new Ec2Config();
    private BenchmarkConfig benchmark = new BenchmarkConfig();

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }

    public AwsConfig getAws() { return aws; }
    public void setAws(AwsConfig aws) { this.aws = aws; }

    public Ec2Config getEc2() { return ec2; }
    public void setEc2(Ec2Config ec2) { this.ec2 = ec2; }

    public BenchmarkConfig getBenchmark() { return benchmark; }
    public void setBenchmark(BenchmarkConfig benchmark) { this.benchmark = benchmark; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AwsConfig {
        private String profile;
        private String region = "eu-central-1";
        private String bucket;
        private String subnetId;
        private String securityGroupId;
        private String vpcId;
        private String runnerInstanceProfileName;
        private String coreStackName = "baas-main";

        public String getProfile() { return profile; }
        public void setProfile(String profile) { this.profile = profile; }

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }

        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }

        public String getSubnetId() { return subnetId; }
        public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

        public String getSecurityGroupId() { return securityGroupId; }
        public void setSecurityGroupId(String securityGroupId) { this.securityGroupId = securityGroupId; }

        public String getVpcId() { return vpcId; }
        public void setVpcId(String vpcId) { this.vpcId = vpcId; }

        public String getRunnerInstanceProfileName() { return runnerInstanceProfileName; }
        public void setRunnerInstanceProfileName(String name) { this.runnerInstanceProfileName = name; }

        public String getCoreStackName() { return coreStackName; }
        public void setCoreStackName(String coreStackName) { this.coreStackName = coreStackName; }
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

    public static class BenchmarkConfig {
        private String asyncProfilerVersion = "4.0";
        private String jarPath = "jmh-benchmarks/target/jmh-benchmarks.jar";

        public String getAsyncProfilerVersion() { return asyncProfilerVersion; }
        public void setAsyncProfilerVersion(String v) { this.asyncProfilerVersion = v; }

        public String getJarPath() { return jarPath; }
        public void setJarPath(String p) { this.jarPath = p; }
    }
}
