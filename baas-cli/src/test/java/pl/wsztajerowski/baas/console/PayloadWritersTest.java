package pl.wsztajerowski.baas.console;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The one way every command writes a JSON or CSV value. */
class PayloadWritersTest {

    @Test
    void absentIsTheNullLiteralNotTheEmptyString() {
        assertThat(Json.string(null)).isEqualTo("null");
        assertThat(Json.string("")).isEqualTo("\"\"");
    }

    @Test
    void everyCharacterJsonForbidsIsEscaped() {
        assertThat(Json.string("a\"b\\c\nd\re\tf\u0001")).isEqualTo("\"a\\\"b\\\\c\\nd\\re\\tf\\u0001\"");
    }

    @Test
    void objectsAreKeySortedSoARowAlwaysSerialisesTheSame() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("source", "ci");
        tags.put("branch", "main");
        assertThat(Json.object(tags)).isEqualTo("{\"branch\":\"main\",\"source\":\"ci\"}");
    }

    @Test
    void numbersIgnoreTheLocaleAndHaveNoNonFiniteForm() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("pl-PL"));
            assertThat(Json.number(1.5)).isEqualTo("1.5");
            assertThat(Csv.number(1.5)).isEqualTo("1.5");
        } finally {
            Locale.setDefault(before);
        }
        assertThat(Json.number(Double.NaN)).isEqualTo("null");
        assertThat(Csv.number(Double.POSITIVE_INFINITY)).isEmpty();
    }

    /** W3: an {@code avgt} score in {@code s/op} is nanosecond-scale; a fixed six decimals printed it as zero. */
    @Test
    void numbersKeepEverySignificantDigitWithoutAnExponent() {
        assertThat(Json.number(2.345e-9)).isEqualTo("0.000000002345");
        assertThat(Csv.number(2.345e-9)).isEqualTo("0.000000002345");
        assertThat(Json.number(10632008.341)).isEqualTo("10632008.341");
        assertThat(Json.number(1e21)).isEqualTo("1000000000000000000000");
        assertThat(Json.number(0.0)).isEqualTo("0");
    }

    @Test
    void aCsvFieldIsAlwaysQuotedWithQuotesDoubled() {
        assertThat(Csv.field(Csv.tags(Map.of("b", "x,\"y\"", "a", "1")))).isEqualTo("\"a=1;b=x,\"\"y\"\"\"");
    }

    @Test
    void elapsedTimeDropsTheHourUntilThereIsOne() {
        assertThat(Elapsed.format(75)).isEqualTo("1m 15s");
        assertThat(Elapsed.format(3600)).isEqualTo("1h 00m 00s");
    }
}
