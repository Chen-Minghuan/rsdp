package com.rsdp.supplychain.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SnapshotHasher} / {@link SnapshotHashMaterial} 单元测试（§47.1.3 / §47.1.6，
 * §77.3 confirmed snapshot hash 校验）。
 */
class SnapshotHasherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SnapshotHashMaterial sampleMaterial() {
        return SnapshotHashMaterial.builder(1, "BATCH-001", 7)
            .sourceSheetIndex(0)
            .sourceSheetName("沙发")
            .categoryPolicy("MIXED", null, List.of("SOFA", "CHAIR"))
            .mappingHash("mapping-hash-1")
            .normalizerVersion("normalizer-v1")
            .addGroup("GROUP-B", 1, "payload-hash-b")
            .addGroup("GROUP-A", 1, "payload-hash-a")
            .build();
    }

    @Test
    void snapshotHash_shouldBeDeterministic() {
        String expected = SnapshotHasher.snapshotHash(sampleMaterial());
        assertEquals(64, expected.length());
        for (int i = 0; i < 50; i++) {
            assertEquals(expected, SnapshotHasher.snapshotHash(sampleMaterial()));
        }
    }

    @Test
    void snapshotHash_shouldBeIndependentOfGroupInsertionOrder() {
        SnapshotHashMaterial reversed = SnapshotHashMaterial.builder(1, "BATCH-001", 7)
            .sourceSheetIndex(0)
            .sourceSheetName("沙发")
            .categoryPolicy("MIXED", null, List.of("SOFA", "CHAIR"))
            .mappingHash("mapping-hash-1")
            .normalizerVersion("normalizer-v1")
            .addGroup("GROUP-A", 1, "payload-hash-a")
            .addGroup("GROUP-B", 1, "payload-hash-b")
            .build();
        assertEquals(SnapshotHasher.snapshotHash(sampleMaterial()),
            SnapshotHasher.snapshotHash(reversed));
    }

    @Test
    void snapshotHash_shouldChangeWhenAnyBusinessFieldChanges() {
        String base = SnapshotHasher.snapshotHash(sampleMaterial());

        SnapshotHashMaterial otherCategory = SnapshotHashMaterial.builder(1, "BATCH-001", 7)
            .sourceSheetIndex(0).sourceSheetName("沙发")
            .categoryPolicy("MIXED", null, List.of("SOFA", "BED"))
            .mappingHash("mapping-hash-1").normalizerVersion("normalizer-v1")
            .addGroup("GROUP-B", 1, "payload-hash-b")
            .addGroup("GROUP-A", 1, "payload-hash-a")
            .build();
        assertNotEquals(base, SnapshotHasher.snapshotHash(otherCategory));

        SnapshotHashMaterial otherPayload = SnapshotHashMaterial.builder(1, "BATCH-001", 7)
            .sourceSheetIndex(0).sourceSheetName("沙发")
            .categoryPolicy("MIXED", null, List.of("SOFA", "CHAIR"))
            .mappingHash("mapping-hash-1").normalizerVersion("normalizer-v1")
            .addGroup("GROUP-B", 1, "payload-hash-b")
            .addGroup("GROUP-A", 1, "payload-hash-CHANGED")
            .build();
        assertNotEquals(base, SnapshotHasher.snapshotHash(otherPayload));

        SnapshotHashMaterial otherVersion = SnapshotHashMaterial.builder(2, "BATCH-001", 7)
            .sourceSheetIndex(0).sourceSheetName("沙发")
            .categoryPolicy("MIXED", null, List.of("SOFA", "CHAIR"))
            .mappingHash("mapping-hash-1").normalizerVersion("normalizer-v1")
            .addGroup("GROUP-B", 1, "payload-hash-b")
            .addGroup("GROUP-A", 1, "payload-hash-a")
            .build();
        assertNotEquals(base, SnapshotHasher.snapshotHash(otherVersion));
    }

    @Test
    void material_shouldExcludeHashAndRuntimeFieldsByConstruction() {
        String canonical = CanonicalSerializer.canonical(sampleMaterial().toCanonicalJson());
        assertFalse(canonical.contains("snapshotHash"));
        assertFalse(canonical.contains("snapshot_hash"));
        assertFalse(canonical.contains("createdAt"));
        assertFalse(canonical.contains("createdBy"));
        assertFalse(canonical.contains("lease"));
        assertFalse(canonical.contains("heartbeat"));
        assertFalse(canonical.contains("status"));
    }

    @Test
    void material_shouldKeepExplicitNulls() {
        SnapshotHashMaterial material = SnapshotHashMaterial.builder(1, "BATCH-001", 1)
            .sourceSheetIndex(0)
            .categoryPolicy("SINGLE", "SOFA", List.of())
            .mappingHash("m").normalizerVersion("n")
            .addGroup("G", 1, "p")
            .build();
        String canonical = CanonicalSerializer.canonical(material.toCanonicalJson());
        assertTrue(canonical.contains("\"sourceSheetName\":null"));
        assertTrue(canonical.contains("\"categoryHint\":\"SOFA\""));
    }

    @Test
    void hash_shouldMatchFixedVectorOfCanonicalRules() throws Exception {
        // 锁定 canonical 协议跨语言可复现：{"a":"茶","b":1.5} 的 SHA-256（UTF-8）
        JsonNode node = MAPPER.readTree("{\"b\":1.50,\"a\":\"茶\"}");
        assertEquals("73fb1478b75d7db2a41b9e967ac06cc813f13199c6c73876a4f0989d89de23e2",
            SnapshotHasher.hash(1, node));
    }

    @Test
    void hash_shouldRejectNonPositiveVersion() {
        JsonNode node = MAPPER.createObjectNode();
        assertThrows(IllegalArgumentException.class, () -> SnapshotHasher.hash(0, node));
        assertThrows(IllegalArgumentException.class, () -> SnapshotHasher.hash(-1, node));
        assertThrows(IllegalArgumentException.class,
            () -> SnapshotHashMaterial.builder(0, "B", 1));
    }

    @Test
    void groupEntry_shouldValidateArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> new SnapshotHashMaterial.GroupEntry("G", 0, "p"));
        assertThrows(NullPointerException.class,
            () -> new SnapshotHashMaterial.GroupEntry(null, 1, "p"));
        assertThrows(NullPointerException.class,
            () -> new SnapshotHashMaterial.GroupEntry("G", 1, null));
    }

    @Test
    void matches_shouldUseConstantTimeComparison() {
        String hash = SnapshotHasher.snapshotHash(sampleMaterial());
        assertTrue(SnapshotHasher.matches(hash, SnapshotHasher.snapshotHash(sampleMaterial())));
        char flipped = hash.charAt(0) == '0' ? '1' : '0';
        assertFalse(SnapshotHasher.matches(hash, flipped + hash.substring(1)));
        assertFalse(SnapshotHasher.matches(hash, null));
        assertFalse(SnapshotHasher.matches(null, hash));
    }
}
