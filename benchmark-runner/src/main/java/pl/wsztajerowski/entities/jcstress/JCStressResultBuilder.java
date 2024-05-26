package pl.wsztajerowski.entities.jcstress;

import java.util.HashMap;
import java.util.Map;

public final class JCStressResultBuilder {
    private int totalTests;
    private int passedTests;
    private String javaVendor;
    private String javaVmName;
    private String javaVmVendor;
    private String javaVmVersion;
    private String osArch;
    private String osName;
    private String osVersion;
    private Map<String, String> testsWithFailedResults = new HashMap<>();
    private Map<String, String> testsWithErrorResults = new HashMap<>();
    private Map<String, String> testsWithInterestingResults = new HashMap<>();

    private JCStressResultBuilder() {
    }

    public static JCStressResultBuilder getJCStressResult() {
        return new JCStressResultBuilder();
    }

    public JCStressResultBuilder withTotalTests(int totalTests) {
        this.totalTests = totalTests;
        return this;
    }

    public JCStressResultBuilder withPassedTests(int passedTests) {
        this.passedTests = passedTests;
        return this;
    }

    public JCStressResultBuilder withJavaVendor(String javaVendor) {
        this.javaVendor = javaVendor;
        return this;
    }

    public JCStressResultBuilder withJavaVmName(String javaVmName) {
        this.javaVmName = javaVmName;
        return this;
    }

    public JCStressResultBuilder withJavaVmVendor(String javaVmVendor) {
        this.javaVmVendor = javaVmVendor;
        return this;
    }

    public JCStressResultBuilder withJavaVmVersion(String javaVmVersion) {
        this.javaVmVersion = javaVmVersion;
        return this;
    }

    public JCStressResultBuilder withOsArch(String osArch) {
        this.osArch = osArch;
        return this;
    }

    public JCStressResultBuilder withOsName(String osName) {
        this.osName = osName;
        return this;
    }

    public JCStressResultBuilder withOsVersion(String osVersion) {
        this.osVersion = osVersion;
        return this;
    }

    public JCStressResultBuilder withTestsWithFailedResults(Map<String, String> testsWithFailedResults) {
        this.testsWithFailedResults = testsWithFailedResults;
        return this;
    }

    public JCStressResultBuilder withTestsWithErrorResults(Map<String, String> testsWithErrorResults) {
        this.testsWithErrorResults = testsWithErrorResults;
        return this;
    }

    public JCStressResultBuilder withTestsWithInterestingResults(Map<String, String> testsWithInterestingResults) {
        this.testsWithInterestingResults = testsWithInterestingResults;
        return this;
    }

    public JCStressResultBuilder addTestWithInterestingResults(String testName, String resultFilePath) {
        this.testsWithInterestingResults.put(testName, resultFilePath);
        return this;
    }

    public JCStressResultBuilder addTestWithFailedResults(String testName, String resultFilePath) {
        this.testsWithFailedResults.put(testName, resultFilePath);
        return this;
    }

    public JCStressResultBuilder addTestWithErrorResults(String testName, String resultFilePath) {
        this.testsWithErrorResults.put(testName, resultFilePath);
        return this;
    }

    public JCStressResult build() {
        return new JCStressResult(totalTests, passedTests, javaVendor, javaVmName, javaVmVendor, javaVmVersion, osArch, osName, osVersion, testsWithFailedResults, testsWithErrorResults, testsWithInterestingResults);
    }
}
