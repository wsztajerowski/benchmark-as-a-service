package pl.wsztajerowski.baas.deployment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A table item as one line of DynamoDB's typed JSON — {@code {"pk":{"S":"RESULT#p"},"score":{"N":"1.5"}}},
 * the shape {@code aws dynamodb put-item --item} reads — and back. Wire shape, not domain shape: every
 * attribute is copied whatever it means, so an item the measurement model does not know survives the
 * round trip, and a bundle stays loadable without {@code baas}. {@code N} stays the string DynamoDB
 * returned, so no number is ever reformatted; {@code B} is base64.
 */
final class ItemJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ItemJson() {
    }

    /** One line, attributes in name order so the same item always encodes the same way. */
    static String encode(Map<String, AttributeValue> item) {
        ObjectNode node = JSON.createObjectNode();
        new TreeMap<>(item).forEach((name, value) -> node.set(name, encode(value)));
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode an item", e);
        }
    }

    static Map<String, AttributeValue> decode(String line) {
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not a JSON object: " + e.getOriginalMessage(), e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Not a JSON object");
        }
        return attributes(node);
    }

    private static JsonNode encode(AttributeValue value) {
        ObjectNode typed = JSON.createObjectNode();
        switch (value.type()) {
            case S -> typed.put("S", value.s());
            case N -> typed.put("N", value.n());
            case B -> typed.put("B", base64(value.b()));
            case BOOL -> typed.put("BOOL", value.bool());
            case NUL -> typed.put("NULL", true);
            case M -> {
                ObjectNode map = typed.putObject("M");
                new TreeMap<>(value.m()).forEach((name, member) -> map.set(name, encode(member)));
            }
            case L -> {
                ArrayNode list = typed.putArray("L");
                value.l().forEach(member -> list.add(encode(member)));
            }
            case SS -> value.ss().forEach(typed.putArray("SS")::add);
            case NS -> value.ns().forEach(typed.putArray("NS")::add);
            case BS -> {
                ArrayNode set = typed.putArray("BS");
                value.bs().forEach(bytes -> set.add(base64(bytes)));
            }
            default -> throw new IllegalArgumentException("Unsupported attribute type: " + value.type());
        }
        return typed;
    }

    private static Map<String, AttributeValue> attributes(JsonNode object) {
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        object.fields().forEachRemaining(field -> attributes.put(field.getKey(), value(field.getValue())));
        return attributes;
    }

    private static AttributeValue value(JsonNode typed) {
        if (typed == null || !typed.isObject() || typed.size() != 1) {
            throw new IllegalArgumentException("An attribute must be an object with exactly one type key: " + typed);
        }
        var field = typed.fields().next();
        JsonNode v = field.getValue();
        return switch (field.getKey()) {
            case "S" -> AttributeValue.fromS(text(v));
            case "N" -> AttributeValue.fromN(text(v));
            case "B" -> AttributeValue.fromB(bytes(v));
            case "BOOL" -> AttributeValue.fromBool(v.booleanValue());
            case "NULL" -> AttributeValue.fromNul(true);
            case "M" -> AttributeValue.fromM(attributes(v));
            case "L" -> {
                List<AttributeValue> list = new ArrayList<>();
                v.forEach(member -> list.add(value(member)));
                yield AttributeValue.fromL(list);
            }
            case "SS" -> AttributeValue.fromSs(texts(v));
            case "NS" -> AttributeValue.fromNs(texts(v));
            case "BS" -> {
                List<SdkBytes> set = new ArrayList<>();
                v.forEach(member -> set.add(bytes(member)));
                yield AttributeValue.fromBs(set);
            }
            default -> throw new IllegalArgumentException("Unknown attribute type: " + field.getKey());
        };
    }

    private static String text(JsonNode v) {
        if (!v.isTextual()) {
            throw new IllegalArgumentException("Expected a string, got: " + v);
        }
        return v.textValue();
    }

    private static List<String> texts(JsonNode v) {
        List<String> values = new ArrayList<>();
        v.forEach(member -> values.add(text(member)));
        return values;
    }

    private static String base64(SdkBytes bytes) {
        return Base64.getEncoder().encodeToString(bytes.asByteArrayUnsafe());
    }

    private static SdkBytes bytes(JsonNode v) {
        return SdkBytes.fromByteArray(Base64.getDecoder().decode(text(v)));
    }
}
