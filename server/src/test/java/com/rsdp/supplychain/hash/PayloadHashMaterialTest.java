package com.rsdp.supplychain.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PayloadHashMaterial} 单元测试（设计文档 v3.3 §47.1.2 PayloadHashMaterial）。
 */
class PayloadHashMaterialTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode payload(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void hash_shouldBeDeterministicForSameInput() {
        JsonNode p = payload("{\"name\":\"沙发\",\"rows\":[{\"sourceRowIndex\":27,\"finalCategoryCode\":\"DINING_CHAIR\"}]}");

        String h1 = PayloadHashMaterial.of(1, p).hash();
        String h2 = PayloadHashMaterial.of(1, p).hash();

        assertEquals(h1, h2);
        assertTrue(h1.matches("[0-9a-f]{64}"));
    }

    @Test
    void hash_shouldBeIndependentOfPayloadKeyOrder() {
        // canonical 序列化固定 key 字典序（§47.1.1 第 2 条）
        JsonNode p1 = payload("{\"a\":1,\"b\":2}");
        JsonNode p2 = payload("{\"b\":2,\"a\":1}");

        assertEquals(PayloadHashMaterial.of(1, p1).hash(), PayloadHashMaterial.of(1, p2).hash());
    }

    @Test
    void hash_shouldTreatNumericallyEqualDecimalsAsIdentical() {
        // 数字规范十进制：1.0 与 1 字节一致（§47.1.1 第 3 条）
        JsonNode p1 = payload("{\"price\":1.0}");
        JsonNode p2 = payload("{\"price\":1}");

        assertEquals(PayloadHashMaterial.of(1, p1).hash(), PayloadHashMaterial.of(1, p2).hash());
    }

    @Test
    void hash_shouldChangeWhenPayloadChanges() {
        JsonNode p1 = payload("{\"finalCategoryCode\":\"DINING_CHAIR\"}");
        JsonNode p2 = payload("{\"finalCategoryCode\":\"DINING_TABLE\"}");

        assertNotEquals(PayloadHashMaterial.of(1, p1).hash(), PayloadHashMaterial.of(1, p2).hash());
    }

    @Test
    void hash_shouldChangeWhenHashVersionChanges() {
        JsonNode p = payload("{\"a\":1}");

        assertNotEquals(PayloadHashMaterial.of(1, p).hash(), PayloadHashMaterial.of(2, p).hash());
    }

    @Test
    void sensitiveValues_shouldBeSortedByValuePathBeforeHashing() {
        // §47.1.2：敏感值条目按 value_path 排序，调用方传入顺序不影响结果
        JsonNode p = payload("{\"factoryPrice\":{\"__sensitiveRef\":\"SENS-1\"}}");
        PayloadHashMaterial.SensitiveValueEntry e1 =
            new PayloadHashMaterial.SensitiveValueEntry("rows.0.factoryPrice", "SENS-1", "a".repeat(64), 1);
        PayloadHashMaterial.SensitiveValueEntry e2 =
            new PayloadHashMaterial.SensitiveValueEntry("rows.1.factoryPrice", "SENS-2", "b".repeat(64), 1);

        String h1 = PayloadHashMaterial.of(1, p, List.of(e1, e2)).hash();
        String h2 = PayloadHashMaterial.of(1, p, List.of(e2, e1)).hash();

        assertEquals(h1, h2);
    }

    @Test
    void sensitiveHmacChange_shouldChangeHash_butCiphertextFree() {
        // 敏感值 HMAC 变化会改变 payload_hash；Material 不含密文，重加密不产生漂移（§47.1.2）
        JsonNode p = payload("{\"factoryPrice\":{\"__sensitiveRef\":\"SENS-1\"}}");
        PayloadHashMaterial.SensitiveValueEntry v1 =
            new PayloadHashMaterial.SensitiveValueEntry("rows.0.factoryPrice", "SENS-1", "a".repeat(64), 1);
        PayloadHashMaterial.SensitiveValueEntry v2 =
            new PayloadHashMaterial.SensitiveValueEntry("rows.0.factoryPrice", "SENS-1", "c".repeat(64), 1);

        assertNotEquals(PayloadHashMaterial.of(1, p, List.of(v1)).hash(),
            PayloadHashMaterial.of(1, p, List.of(v2)).hash());
    }

    @Test
    void constructor_shouldRejectInvalidInput() {
        assertThrows(IllegalArgumentException.class,
            () -> PayloadHashMaterial.of(0, payload("{}")));
        assertThrows(NullPointerException.class,
            () -> PayloadHashMaterial.of(1, null));
        assertThrows(NullPointerException.class,
            () -> PayloadHashMaterial.of(1, payload("{}"), null));
        assertThrows(IllegalArgumentException.class,
            () -> new PayloadHashMaterial.SensitiveValueEntry("p", "SENS-1", "a".repeat(64), 0));
    }
}
