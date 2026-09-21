package com.rsdp.agent.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RRF（Reciprocal Rank Fusion）融合器：多通道排名列表 → 融合分有序 Map。
 *
 * <p>公式：{@code score(id) = Σ_channel 1 / (k + rank_channel(id))}，k 标准值 60。
 * 只按排名融合，不依赖各通道分数口径（结构化通道没有可比分数也能参与）。
 * 排序确定性：融合分降序 → 单通道最优 rank 升序 → id 字典序。</p>
 */
public final class RrfRanker {

    private RrfRanker() {
    }

    /**
     * 融合多个排名通道。
     *
     * @param rankedChannels 各通道按名次排列的 id 列表（首位 rank=1）；空列表通道忽略
     * @param k              RRF 常数（标准值 60）
     * @return id → 融合分，按名次排序（LinkedHashMap 保序）
     */
    public static LinkedHashMap<String, Double> fuse(List<List<String>> rankedChannels, int k) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Integer> bestRank = new HashMap<>();
        for (List<String> channel : rankedChannels) {
            if (channel == null) {
                continue;
            }
            Set<String> seen = new HashSet<>();
            int rank = 0;
            for (String id : channel) {
                if (id == null || !seen.add(id)) {
                    continue;
                }
                rank++;
                scores.merge(id, 1.0 / (k + rank), Double::sum);
                bestRank.merge(id, rank, Math::min);
            }
        }
        return scores.entrySet().stream()
            .sorted((e1, e2) -> {
                int cmp = Double.compare(e2.getValue(), e1.getValue());
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Integer.compare(bestRank.get(e1.getKey()), bestRank.get(e2.getKey()));
                if (cmp != 0) {
                    return cmp;
                }
                return e1.getKey().compareTo(e2.getKey());
            })
            .collect(Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }
}
