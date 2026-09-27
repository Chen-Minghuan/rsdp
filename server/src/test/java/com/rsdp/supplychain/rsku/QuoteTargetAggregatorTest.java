package com.rsdp.supplychain.rsku;

import com.rsdp.supplychain.rsku.QuoteTargetAggregator.AggregationResult;
import com.rsdp.supplychain.rsku.QuoteTargetAggregator.QuoteComparisonMaterial;
import com.rsdp.supplychain.rsku.QuoteTargetAggregator.QuoteInput;
import com.rsdp.supplychain.rsku.QuoteTargetAggregator.QuoteIssue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QuoteTargetAggregator} 单元测试（v3.3 §38.1 / §77.4，P0-D）。
 */
class QuoteTargetAggregatorTest {

    private static QuoteComparisonMaterial material(String priceHmac, String currency,
                                                    String priceUnit, String quoteDate) {
        return new QuoteComparisonMaterial(
            priceHmac, currency, true, priceUnit, quoteDate, "2026-01-01", null, 10, "件");
    }

    private static QuoteInput quote(String quoteKey, String targetKey, String sourceRef,
                                    QuoteComparisonMaterial material) {
        return new QuoteInput(quoteKey, targetKey, "VAR:x", "F001", "TEXT:AA级布", material, sourceRef);
    }

    @Test
    void identicalQuotesShouldMergeAndEmitOnlyInfoIssue() {
        // §38.1：完全等价重复合并为一个 Quote，保留多 Lineage；DUPLICATE_QUOTE_TARGET 只准 info/non_blocking
        QuoteComparisonMaterial m = material("hmac-1", "CNY", "件", "2026-09-01");
        AggregationResult result = QuoteTargetAggregator.aggregate(List.of(
            quote("QUOTE:k1", "RSKU_TARGET:t1", "g1:r2:c3", m),
            quote("QUOTE:k1", "RSKU_TARGET:t1", "g1:r5:c3", m)));

        assertThat(result.persistableQuotes()).hasSize(1);
        assertThat(result.persistableQuotes().get(0).sourceRefs())
            .containsExactly("g1:r2:c3", "g1:r5:c3");
        assertThat(result.hasBlockers()).isFalse();
        assertThat(result.issues()).hasSize(1);
        QuoteIssue info = result.issues().get(0);
        assertThat(info.code()).isEqualTo(QuoteTargetAggregator.ISSUE_DUPLICATE_QUOTE_TARGET);
        assertThat(info.severity()).isEqualTo("info");
        assertThat(info.scope()).isEqualTo("non_blocking");
        assertThat(info.isBlocker()).isFalse();
    }

    @Test
    void differentMaterialSameTargetShouldBlockPerQuoteKey() {
        // §38.1：币种/单位/日期/价格不同 → MULTIPLE_CURRENT_QUOTES_FOR_RSKU / blocker / quote_blocking
        AggregationResult result = QuoteTargetAggregator.aggregate(List.of(
            quote("QUOTE:k1", "RSKU_TARGET:t1", "g1:r2:c3",
                material("hmac-1", "CNY", "件", "2026-09-01")),
            quote("QUOTE:k2", "RSKU_TARGET:t1", "g1:r2:c4",
                material("hmac-2", "CNY", "件", "2026-09-01"))));

        assertThat(result.persistableQuotes()).isEmpty();
        assertThat(result.hasBlockers()).isTrue();
        assertThat(result.issues()).hasSize(2);
        assertThat(result.issues()).allSatisfy(issue -> {
            assertThat(issue.code()).isEqualTo(QuoteTargetAggregator.ISSUE_MULTIPLE_CURRENT_QUOTES);
            assertThat(issue.severity()).isEqualTo("blocker");
            assertThat(issue.scope()).isEqualTo("quote_blocking");
            // 保留所有冲突来源及其值（不按 Key 去重丢弃差异）
            assertThat(issue.partitions()).hasSize(2);
        });
        assertThat(result.issues().stream().map(QuoteIssue::targetKey))
            .containsExactly("QUOTE:k1", "QUOTE:k2");
    }

    @Test
    void sameQuoteKeyWithDifferentPriceMustStillBlock() {
        // §77.4：相同 quoteKey 但价格不同（未参与 Key 的业务值不同）也必须阻断，Key 去重不能吞掉冲突
        AggregationResult result = QuoteTargetAggregator.aggregate(List.of(
            quote("QUOTE:k1", "RSKU_TARGET:t1", "g1:r2:c3",
                material("hmac-1", "CNY", "件", "2026-09-01")),
            quote("QUOTE:k1", "RSKU_TARGET:t1", "g1:r9:c3",
                material("hmac-9", "CNY", "件", "2026-09-01"))));

        assertThat(result.persistableQuotes()).isEmpty();
        assertThat(result.hasBlockers()).isTrue();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).targetKey()).isEqualTo("QUOTE:k1");
        assertThat(result.issues().get(0).partitions()).hasSize(2);
        assertThat(result.issues().get(0).partitions()
            .stream().flatMap(p -> p.sourceRefs().stream()))
            .containsExactlyInAnyOrder("g1:r2:c3", "g1:r9:c3");
    }

    @Test
    void differentTargetsShouldNotInterfere() {
        // 不同 targetRskuKey 各自独立；一个目标的冲突不影响另一目标落库
        QuoteComparisonMaterial m = material("hmac-1", "CNY", "件", "2026-09-01");
        AggregationResult result = QuoteTargetAggregator.aggregate(List.of(
            quote("QUOTE:a1", "RSKU_TARGET:tA", "g1:r1:c1", m),
            quote("QUOTE:b1", "RSKU_TARGET:tB", "g1:r2:c1", m),
            quote("QUOTE:b2", "RSKU_TARGET:tB", "g1:r2:c2",
                material("hmac-2", "USD", "件", "2026-09-01"))));

        assertThat(result.persistableQuotes()).hasSize(1);
        assertThat(result.persistableQuotes().get(0).representative().targetRskuKey())
            .isEqualTo("RSKU_TARGET:tA");
        assertThat(result.hasBlockers()).isTrue();
    }

    @Test
    void resultShouldBeOrderIndependent() {
        // §77.4：调换重复/冲突报价的源行顺序，合并结果与阻断结论不变
        QuoteComparisonMaterial m1 = material("hmac-1", "CNY", "件", "2026-09-01");
        QuoteComparisonMaterial m2 = material("hmac-2", "CNY", "件", "2026-09-02");
        List<QuoteInput> inputs = new ArrayList<>(List.of(
            quote("QUOTE:k2", "RSKU_TARGET:t1", "g1:r8:c1", m2),
            quote("QUOTE:k1", "RSKU_TARGET:t1", "g1:r3:c1", m1),
            quote("QUOTE:k1", "RSKU_TARGET:t1", "g1:r1:c1", m1)));

        AggregationResult forward = QuoteTargetAggregator.aggregate(inputs);
        Collections.reverse(inputs);
        AggregationResult reversed = QuoteTargetAggregator.aggregate(inputs);
        Collections.shuffle(inputs, new java.util.Random(42));
        AggregationResult shuffled = QuoteTargetAggregator.aggregate(inputs);

        assertThat(reversed.issues()).isEqualTo(forward.issues());
        assertThat(reversed.persistableQuotes()).isEqualTo(forward.persistableQuotes());
        assertThat(shuffled.issues()).isEqualTo(forward.issues());
        assertThat(shuffled.persistableQuotes()).isEqualTo(forward.persistableQuotes());
    }

    @Test
    void nullFieldsDistinguishMaterial() {
        // effectiveFrom null 与有值是不同比较材料（§24.1 null 显式语义延伸到比较材料）
        QuoteComparisonMaterial withDate = material("hmac-1", "CNY", "件", "2026-09-01");
        QuoteComparisonMaterial withoutDate = new QuoteComparisonMaterial(
            "hmac-1", "CNY", true, "件", "2026-09-01", null, null, 10, "件");
        AggregationResult result = QuoteTargetAggregator.aggregate(List.of(
            quote("QUOTE:k1", "RSKU_TARGET:t1", "g1:r1:c1", withDate),
            quote("QUOTE:k1", "RSKU_TARGET:t1", "g1:r2:c1", withoutDate)));
        assertThat(result.hasBlockers()).isTrue();
    }

    @Test
    void emptyInputShouldYieldEmptyResult() {
        AggregationResult result = QuoteTargetAggregator.aggregate(List.of());
        assertThat(result.persistableQuotes()).isEmpty();
        assertThat(result.issues()).isEmpty();
        assertThat(result.hasBlockers()).isFalse();
    }
}
