package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.RspuPriceSummary;
import com.rsdp.entity.RskuSupply;
import com.rsdp.mapper.RspuPriceSummaryMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RSPU 价格投影汇总服务（{@code rspu_price_summary} 表的唯一写入口）。
 *
 * <p>RSKU 写路径（新建/改价/软删/恢复/导入 upsert/RSPU 级联软删与还原）后调用
 * {@link #recalculate(String)} 重算该 RSPU 的聚合值并 upsert 投影行。重算在 Java 侧做：
 * 查该 RSPU 有效 RSKU → 解密 factory_price → min/max/count → upsert。</p>
 *
 * <p>刻意不用领域事件异步维护：投影与 RSKU 写在同一库，同事务同步重算最简单可靠，
 * 避免提交后异步窗口内列表价与报价不一致（项目既有事件机制用于 pgvector 向量清理）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RspuPriceSummaryService {

    /**
     * 延迟重算作用域（线程级）：作用域内 {@link #recalculate(String)} 只收集 RSPU ID 去重，
     * 不立即查库；作用域 {@link DeferralScope#close()} 时对收集到的 ID 做一次
     * {@link #recalculateBatch(Collection)}。供 Excel AI 导入等批量链路把
     * "每 RSKU 一次重算"合并为"每 RSPU 每批次一次"，投影最终结果不变。
     */
    private static final ThreadLocal<Set<String>> DEFERRED_RSPU_IDS = new ThreadLocal<>();

    private final RspuPriceSummaryMapper rspuPriceSummaryMapper;
    private final RskuSupplyMapper rskuSupplyMapper;

    /**
     * 重算单个 RSPU 的价格投影并 upsert。
     *
     * <p>不加事务注解：单 upsert 语句，在调用方（RSKU 写路径）的事务内执行即随其提交；
     * 无有效 RSKU 时投影行保留（min/max 为 NULL、count 为 0），与"无报价"列表行为一致。</p>
     *
     * <p>处于 {@link #openDeferralScope()} 作用域内时仅登记 ID 延迟到作用域关闭统一重算。</p>
     *
     * @param rspuId RSPU ID
     */
    public void recalculate(String rspuId) {
        Set<String> deferred = DEFERRED_RSPU_IDS.get();
        if (deferred != null) {
            if (StringUtils.hasText(rspuId)) {
                deferred.add(rspuId);
            }
            return;
        }
        doRecalculate(rspuId);
    }

    /**
     * 开启延迟重算作用域（try-with-resources 使用）。支持嵌套：内层作用域关闭时
     * 把收集到的 ID 并入外层而非立即重算。
     *
     * @return 作用域句柄；close 时去重后统一 {@link #recalculateBatch(Collection)}
     */
    public DeferralScope openDeferralScope() {
        Set<String> previous = DEFERRED_RSPU_IDS.get();
        Set<String> bag = new LinkedHashSet<>();
        DEFERRED_RSPU_IDS.set(bag);
        return new DeferralScope(previous, bag);
    }

    /**
     * 延迟重算作用域句柄。close 幂等；异常路径同样会 flush 已提交的收集结果
     * （逐 RSPU 重算失败在 {@link #recalculateBatch} 内隔离，不影响调用方）。
     */
    public final class DeferralScope implements AutoCloseable {
        private final Set<String> previous;
        private final Set<String> bag;
        private boolean closed;

        private DeferralScope(Set<String> previous, Set<String> bag) {
            this.previous = previous;
            this.bag = bag;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            DEFERRED_RSPU_IDS.remove();
            if (previous != null) {
                previous.addAll(bag);
                return;
            }
            recalculateBatch(bag);
        }
    }

    private void doRecalculate(String rspuId) {
        if (!StringUtils.hasText(rspuId)) {
            return;
        }
        // 只取 factory_price 列：避免整实体映射，解密也仅限价格列
        List<RskuSupply> rskus = rskuSupplyMapper.selectList(new QueryWrapper<RskuSupply>()
            .select("factory_price")
            .eq("rspu_id", rspuId));

        BigDecimal min = null;
        BigDecimal max = null;
        for (RskuSupply rsku : rskus) {
            BigDecimal price = rsku.getFactoryPrice();
            if (price == null) {
                continue;
            }
            min = min == null || price.compareTo(min) < 0 ? price : min;
            max = max == null || price.compareTo(max) > 0 ? price : max;
        }

        RspuPriceSummary summary = new RspuPriceSummary();
        summary.setRspuId(rspuId);
        summary.setMinFactoryPrice(min);
        summary.setMaxFactoryPrice(max);
        summary.setActiveRskuCount(rskus.size());
        summary.setUpdatedAt(LocalDateTime.now());

        rspuPriceSummaryMapper.upsert(summary);
    }

    /**
     * 批量重算多个 RSPU 的价格投影（导入等批量写路径收尾时调用）。
     *
     * @param rspuIds RSPU ID 集合（自动去重、忽略空值）
     */
    public void recalculateBatch(Collection<String> rspuIds) {
        if (rspuIds == null || rspuIds.isEmpty()) {
            return;
        }
        Set<String> distinct = rspuIds.stream()
            .filter(StringUtils::hasText)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String rspuId : distinct) {
            try {
                recalculate(rspuId);
            } catch (Exception e) {
                // 单个 RSPU 重算失败不阻断批量导入收尾；投影短暂失准可由下次写路径或回填器修复
                log.error("重算 RSPU 价格投影失败，rspuId={}", rspuId, e);
            }
        }
    }

    /**
     * 批量查询价格投影（产品列表当页最低价/报价数等读路径）。
     *
     * @param rspuIds RSPU ID 列表
     * @return RSPU ID -> 投影行；无投影行（如无报价或未回填）的 RSPU 不在返回 Map 中
     */
    public Map<String, RspuPriceSummary> batchSummaries(List<String> rspuIds) {
        if (rspuIds == null || rspuIds.isEmpty()) {
            return Map.of();
        }
        return rspuPriceSummaryMapper.selectBatchIds(rspuIds).stream()
            .collect(Collectors.toMap(RspuPriceSummary::getRspuId, s -> s, (a, b) -> a));
    }
}
