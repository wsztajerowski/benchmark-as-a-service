package pl.wsztajerowski.entities.jcstress;

import dev.morphia.annotations.Entity;

import java.util.HashMap;
import java.util.Map;

@Entity
public record JCStressResult (
    int totalTests,
    int passedTests,
    String javaVendor,
    String javaVmName,
    String javaVmVendor,
    String javaVmVersion,
    String osArch,
    String osName,
    String osVersion,
    Map<String, String> testsWithFailedResults,
    Map<String, String> testsWithErrorResults,
    Map<String, String> testsWithInterestingResults) {

    public boolean hasUnsuccessfulTests(){
        return totalTests == passedTests;
    }

    public Map<String, String> getAllUnsuccessfulTest(){
        HashMap<String, String> tests = new HashMap<>();
        tests.putAll(testsWithErrorResults);
        tests.putAll(testsWithFailedResults);
        tests.putAll(testsWithInterestingResults);
        return tests;
    }
}
