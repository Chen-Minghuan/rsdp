package com.rsdp.supplychain.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CanonicalSerializer} 单元测试（§47.1.1 Canonical JSON 规则）。
 */
class CanonicalSerializerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void canonical_shouldSortObjectKeys() throws Exception {
        JsonNode node = MAPPER.readTree("{\"b\":1,\"a\":2,\"c\":{\"z\":1,\"y\":2}}");
        assertEquals("{\"a\":2,\"b\":1,\"c\":{\"y\":2,\"z\":1}}", CanonicalSerializer.canonical(node));
    }

    @Test
    void canonical_shouldBeIndependentOfMapInsertionOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 1);
        first.put("a", "茶");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", "茶");
        second.put("b", 1);
        assertEquals(CanonicalSerializer.canonical(first), CanonicalSerializer.canonical(second));
        assertEquals("{\"a\":\"茶\",\"b\":1}", CanonicalSerializer.canonical(first));
    }

    @Test
    void canonical_shouldNormalizeNumbersToPlainDecimal() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("a", new BigDecimal("1.50"));
        node.put("b", new BigDecimal("0.10"));
        node.put("c", new BigDecimal("1E+3"));
        node.put("d", new BigDecimal("0.000"));
        node.put("e", new BigDecimal("-0.00"));
        node.put("f", new BigDecimal("1E-10"));
        node.put("g", 1850.0);
        node.put("h", new BigDecimal("123456789012345678901234567890"));
        assertEquals("{\"a\":1.5,\"b\":0.1,\"c\":1000,\"d\":0,\"e\":0,\"f\":0.0000000001,"
            + "\"g\":1850,\"h\":123456789012345678901234567890}", CanonicalSerializer.canonical(node));
    }

    @Test
    void canonicalDecimal_shouldStripTrailingZerosAndRejectNull() {
        assertEquals("1.5", CanonicalSerializer.canonicalDecimal(new BigDecimal("1.500")));
        assertEquals("0", CanonicalSerializer.canonicalDecimal(new BigDecimal("0.000")));
        assertEquals("0", CanonicalSerializer.canonicalDecimal(new BigDecimal("-0")));
        assertEquals("720", CanonicalSerializer.canonicalDecimal(new BigDecimal("720.0")));
        assertThrows(NullPointerException.class, () -> CanonicalSerializer.canonicalDecimal(null));
    }

    @Test
    void canonical_shouldRejectNaNAndInfinity() {
        ObjectNode nan = MAPPER.createObjectNode();
        nan.set("v", DoubleNode.valueOf(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> CanonicalSerializer.canonical(nan));
        ObjectNode inf = MAPPER.createObjectNode();
        inf.set("v", DoubleNode.valueOf(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> CanonicalSerializer.canonical(inf));
    }

    @Test
    void canonical_shouldKeepExplicitNullAndBoolean() throws Exception {
        JsonNode node = MAPPER.readTree("{\"n\":null,\"t\":true,\"f\":false}");
        assertEquals("{\"f\":false,\"n\":null,\"t\":true}", CanonicalSerializer.canonical(node));
    }

    @Test
    void canonical_shouldPreserveArrayOrder() throws Exception {
        JsonNode node = MAPPER.readTree("{\"arr\":[3,1,2,\"b\",\"a\"]}");
        assertEquals("{\"arr\":[3,1,2,\"b\",\"a\"]}", CanonicalSerializer.canonical(node));
    }

    @Test
    void canonical_shouldEscapeStringsDeterministically() {
        ArrayNode arr = MAPPER.createArrayNode();
        arr.add("say \"hi\" \\ end");
        arr.add("line1\nline2\t");
        arr.add("\u0000\u001f");
        assertEquals("[\"say \\\"hi\\\" \\\\ end\",\"line1\\nline2\\t\",\"\\u0000\\u001f\"]",
            CanonicalSerializer.canonical(arr));
    }

    @Test
    void canonicalBytes_shouldBeUtf8() {
        Map<String, Object> map = Map.of("name", "茶色");
        assertEquals("{\"name\":\"茶色\"}",
            new String(CanonicalSerializer.canonicalBytes(map), StandardCharsets.UTF_8));
    }

    @Test
    void canonical_shouldRejectBinaryNode() {
        ObjectNode node = MAPPER.createObjectNode();
        node.set("blob", BinaryNode.valueOf(new byte[]{1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> CanonicalSerializer.canonical(node));
    }

    @Test
    void canonical_shouldRejectNullInput() {
        assertThrows(NullPointerException.class, () -> CanonicalSerializer.canonical((JsonNode) null));
        assertThrows(NullPointerException.class, () -> CanonicalSerializer.canonicalBytes((JsonNode) null));
        assertThrows(NullPointerException.class, () -> CanonicalSerializer.canonicalBytes((Map<String, ?>) null));
    }

    @Test
    void canonical_shouldBeDeterministicAcrossRepeatedCalls() throws Exception {
        JsonNode node = MAPPER.readTree(
            "{\"k2\":[1.50,null,{\"b\":true,\"a\":\"茶\"}],\"k1\":\"v\"}");
        String expected = CanonicalSerializer.canonical(node);
        for (int i = 0; i < 100; i++) {
            assertEquals(expected, CanonicalSerializer.canonical(node));
        }
        assertEquals("{\"k1\":\"v\",\"k2\":[1.5,null,{\"a\":\"茶\",\"b\":true}]}",
            CanonicalSerializer.canonical(node));
    }

    @Test
    void canonical_shouldSerializeNestedCollectionsFromJavaValues() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("list", List.of(new BigDecimal("2.0"), "x"));
        map.put("nested", Map.of("y", 1, "x", 2));
        assertEquals("{\"list\":[2,\"x\"],\"nested\":{\"x\":2,\"y\":1}}",
            CanonicalSerializer.canonical(map));
    }
}
