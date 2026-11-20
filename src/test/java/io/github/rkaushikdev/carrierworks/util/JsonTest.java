package io.github.rkaushikdev.carrierworks.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The JSON text, byte for byte, in the bench's established layout. */
class JsonTest {

    private static Map<String, Object> sample() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("b", Arrays.asList(1, 2.5, null, true));
        body.put("a", Map.of());
        body.put("c", List.of());
        body.put("d", "\u00e9\n\"");
        return body;
    }

    @Test
    void oneLineKeepsInsertionOrderAndEscapesEverythingOutsideAscii() {
        assertThat(Json.write(sample()))
                .isEqualTo("{\"b\": [1, 2.5, null, true], \"a\": {}, \"c\": [], \"d\": \"\\u00e9\\n\\\"\"}");
    }

    @Test
    void indentedTextPutsOneItemOnEachLine() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("b", List.of(1, Map.of("x", "y")));
        body.put("a", Map.of());
        String expected = String.join(
                "\n", "{", "  \"a\": {},", "  \"b\": [", "    1,", "    {", "      \"x\": \"y\"", "    }", "  ]", "}");
        assertThat(Json.writer().indent(2).sortKeys().write(body)).isEqualTo(expected);
    }

    @Test
    void anUnsortedWriterKeepsTheOrderItWasGiven() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("z", 1);
        body.put("a", 2);
        assertThat(Json.write(body)).isEqualTo("{\"z\": 1, \"a\": 2}");
        assertThat(Json.writer().sortKeys().write(body)).isEqualTo("{\"a\": 2, \"z\": 1}");
    }

    @Test
    void nullsAndOptionalsWriteAsNull() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("none", null);
        body.put("empty", Optional.empty());
        body.put("held", Optional.of("x"));
        assertThat(Json.write(body)).isEqualTo("{\"none\": null, \"empty\": null, \"held\": \"x\"}");
    }

    @Test
    void numbersWriteInTheirShortestForm() {
        assertThat(Json.write(List.of(1L, 0.1, 1e16, Double.NaN, Double.POSITIVE_INFINITY)))
                .isEqualTo("[1, 0.1, 1e+16, NaN, Infinity]");
    }

    @Test
    void controlCharactersUseTheirShortEscapes() {
        assertThat(Json.write("\b\f\r\t\u0001\u007f")).isEqualTo("\"\\b\\f\\r\\t\\u0001\\u007f\"");
    }

    @Test
    void somethingThatIsNotJsonIsRefusedUnlessThereIsAFallback() {
        Instant moment = Instant.parse("2026-01-15T02:00:00Z");
        assertThatThrownBy(() -> Json.write(moment)).isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("at", moment);
        body.put("on", LocalDate.of(2026, 1, 15));
        body.put("path", Path.of("var/artifacts"));
        assertThat(Json.writer().textFallback().write(body))
                .isEqualTo(
                        "{\"at\": \"2026-01-15 02:00:00+00:00\", \"on\": \"2026-01-15\", \"path\": \"var/artifacts\"}");
    }

    @Test
    void keysThatAreNotStringsAreWrittenAsTheirText() {
        Map<Object, Object> body = new LinkedHashMap<>();
        body.put(3, "three");
        body.put(true, "yes");
        assertThat(Json.write(body)).isEqualTo("{\"3\": \"three\", \"true\": \"yes\"}");
    }

    @Test
    void readingGivesPlainValuesInOrder() {
        Object read = Json.parse("{\"z\": [1, 2.5, null, true], \"a\": \"\\u00e9\"}");
        assertThat(read).isInstanceOf(Map.class);
        Map<?, ?> body = (Map<?, ?>) read;
        assertThat(new ArrayList<Object>(body.keySet())).containsExactly("z", "a");
        assertThat(new ArrayList<Object>((List<?>) body.get("z"))).containsExactly(1L, 2.5, null, true);
        assertThat(body.get("a")).isEqualTo("\u00e9");
    }

    @Test
    void whatIsWrittenReadsBack() {
        assertThat(Json.parse(Json.writer().indent(2).write(sample()))).isEqualTo(Json.parse(Json.write(sample())));
    }

    @Test
    void textThatIsNotJsonIsRefused() {
        assertThatThrownBy(() -> Json.parse("{not json")).isInstanceOf(Json.MalformedJsonException.class);
        assertThatThrownBy(() -> Json.parse("{} trailing")).isInstanceOf(Json.MalformedJsonException.class);
    }
}
