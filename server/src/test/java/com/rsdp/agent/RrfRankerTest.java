package com.rsdp.agent;

import com.rsdp.agent.domain.RrfRanker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * {@link RrfRanker} 单元测试（k=60 标准公式与排序确定性守卫）。
 */
class RrfRankerTest {

    @Test
    void doubleChannelRankOneShouldScoreTwoOver61() {
        LinkedHashMap<String, Double> fused = RrfRanker.fuse(
            List.of(List.of("A", "B"), List.of("A", "C")), 60);

        // A 在两个通道均 rank=1：score = 1/61 + 1/61
        assertThat(fused.get("A")).isCloseTo(2.0 / 61, offset(1e-9));
        // B/C 各在单通道 rank=2：score = 1/62
        assertThat(fused.get("B")).isCloseTo(1.0 / 62, offset(1e-9));
        assertThat(fused.keySet().iterator().next()).isEqualTo("A");
    }

    @Test
    void itemInBothChannelsShouldOutrankSingleChannelItem() {
        LinkedHashMap<String, Double> fused = RrfRanker.fuse(
            List.of(List.of("S1", "S2", "S3"), List.of("V1", "S3", "V2")), 60);

        // S3：结构化 rank3 + 向量 rank2 → 双通道分高于任何单通道项
        assertThat(fused.get("S3")).isGreaterThan(fused.get("S1"));
        assertThat(fused.get("S3")).isGreaterThan(fused.get("V1"));
    }

    @Test
    void sameScoreShouldTieBreakByBestRankThenId() {
        // X：通道1 rank2；Y：通道2 rank1 —— Y 分更高不作比较；
        // 构造同分：P 在通道1 rank2、Q 在通道2 rank2 → 同分同最优 rank → 字典序
        LinkedHashMap<String, Double> fused = RrfRanker.fuse(
            List.of(List.of("A", "P"), List.of("B", "Q")), 60);

        assertThat(fused.get("P")).isCloseTo(fused.get("Q"), offset(1e-12));
        List<String> order = List.copyOf(fused.keySet());
        assertThat(order.indexOf("P")).isLessThan(order.indexOf("Q"));
    }

    @Test
    void emptyChannelsShouldBeIgnored() {
        LinkedHashMap<String, Double> fused = RrfRanker.fuse(
            List.of(List.of(), List.of("A")), 60);

        assertThat(fused).containsOnlyKeys("A");
        assertThat(RrfRanker.fuse(List.of(List.of(), List.of()), 60)).isEmpty();
    }

    @Test
    void duplicateIdsWithinChannelShouldCountOnce() {
        LinkedHashMap<String, Double> fused = RrfRanker.fuse(
            List.of(List.of("A", "A", "B")), 60);

        // A 只计 rank=1 一次；B 为 rank=2（去重后连续名次）
        assertThat(fused.get("A")).isCloseTo(1.0 / 61, offset(1e-9));
        assertThat(fused.get("B")).isCloseTo(1.0 / 62, offset(1e-9));
    }
}
