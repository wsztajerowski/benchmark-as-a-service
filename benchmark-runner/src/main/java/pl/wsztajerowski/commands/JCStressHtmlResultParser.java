package pl.wsztajerowski.commands;

import pl.wsztajerowski.entities.jcstress.JCStressResult;
import pl.wsztajerowski.entities.jcstress.JCStressResultBuilder;

import java.nio.file.Path;
import java.util.List;

import static java.lang.Integer.parseInt;
import static pl.wsztajerowski.commands.HtmlParser.getHtmlParser;
import static pl.wsztajerowski.entities.jcstress.JCStressResultBuilder.getJCStressResult;

public class JCStressHtmlResultParser {

    private final HtmlParser htmlParser;
    private final Path s3Prefix;

    JCStressHtmlResultParser(HtmlParser htmlParser, Path s3Prefix) {
        this.htmlParser = htmlParser;
        this.s3Prefix = s3Prefix;
    }

    public static JCStressHtmlResultParser getJCStressHtmlResultParser(Path resultFilepath, Path s3Prefix) {
        HtmlParser htmlParser = getHtmlParser(resultFilepath);
        return new JCStressHtmlResultParser(htmlParser, s3Prefix);
    }

    public List<String> getTestsWithFailedResults() {
        return getTestsWithResults("FAILED");
    }

    public List<String> getTestsWithErrorResults() {
        return getTestsWithResults("ERROR");
    }

    public List<String> getTestsWithInterestingResults() {
        return getTestsWithResults("INTERESTING");
    }

    public int getPassedTests() {
        return getOverallRate()[0];
    }

    public int getTotalTests() {
        return getOverallRate()[1];
    }

    public String getJavaVendor() {
        return getEnvironmentProperty("java.vendor");
    }

    public String getJavaVmName() {
        return getEnvironmentProperty("java.vm.name");
    }

    public String getJavaVmVendor() {
        return getEnvironmentProperty("java.vm.vendor");
    }

    public String getJavaVmVersion() {
        return getEnvironmentProperty("java.vm.version");
    }

    public String getOsArch() {
        return getEnvironmentProperty("os.arch");
    }

    public String getOsName() {
        return getEnvironmentProperty("os.name");
    }

    public String getOsVersion() {
        return getEnvironmentProperty("os.version");
    }

    private String getEnvironmentProperty(String propertyName) {
        String selector = "td:containsOwn(" + propertyName + ")";
        return htmlParser
            .getTextOfNextSibling(selector);
    }

    private List<String> getTestsWithResults(String resultType) {
        String selector = "h3:containsOwn(" + resultType + ") ~ table";
        return htmlParser
            .getParserForSelector(selector)
            .orElseGet(HtmlParser::getEmptyParser)
            .getListValuesForSelector("td > a");
    }

    private int[] getOverallRate() {
        String overallRateText = htmlParser
            .getTextOfParent("b:containsOwn(Overall pass rate)");
        String[] rate = overallRateText
            .replace("Overall pass rate: ", "")
            .split("/");
        return new int[]{
            parseInt(rate[0]),
            parseInt(rate[1])
        };
    }

    public JCStressResult parse() {
        JCStressResultBuilder jcStressResultBuilder = getJCStressResult()
            .withTotalTests(this.getTotalTests())
            .withPassedTests(this.getPassedTests())
            .withJavaVendor(this.getJavaVendor())
            .withJavaVmVendor(this.getJavaVmVendor())
            .withJavaVmName(this.getJavaVmName())
            .withJavaVmVersion(this.getJavaVmVersion())
            .withOsArch(this.getOsArch())
            .withOsName(this.getOsName())
            .withOsVersion(this.getOsVersion());
        getTestsWithErrorResults()
            .forEach(test -> jcStressResultBuilder.addTestWithErrorResults(test, createS3Path(test)));
        getTestsWithFailedResults()
            .forEach(test -> jcStressResultBuilder.addTestWithFailedResults(test, createS3Path(test)));
        getTestsWithInterestingResults()
            .forEach(test -> jcStressResultBuilder.addTestWithInterestingResults(test, createS3Path(test)));
        return jcStressResultBuilder
            .build();
    }

    private String createS3Path(String testName) {
        String testOutputFile = testName + ".html";
        return s3Prefix.resolve(testOutputFile).toString();
    }

}
