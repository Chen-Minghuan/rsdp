package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * RSPU 风格/场景关联表重建的共享实现（4.3 批②：四条链路的逐处副本收敛）。
 *
 * <p>纪律：纯搬移不改行为。本类只做"先删后插"的机械动作与可选汇总审计；
 * 风格/场景码的归一、去重、行构建（dictType/createdAt/isPrimary 语义）一律留在调用方——
 * 各链路的归一规则不同（别名快照/未命中降级采集/职级码跳过），不属于本类职责。</p>
 *
 * <p>审计口径（2.3 统一）：每 RSPU 一条汇总 logUpdate（Map.of("styleCodes"/"sceneCodes", 码列表），
 * 不逐行刷量；audit=false 时不查旧值（省一次 select，新建/编辑路径现状）。</p>
 */
@Component
@RequiredArgsConstructor
public class RspuAssociationHelper {

    private final RspuStyleMapper rspuStyleMapper;
    private final RspuSceneMapper rspuSceneMapper;
    private final AuditLogService auditLogService;

    /**
     * 全量覆盖重写风格关联（先删后插）。
     *
     * @param rspuId   RSPU ID
     * @param newRows  新关联行（调用方已构建好 dictType/createdAt/isPrimary）
     * @param operator 操作人（audit=true 时记审计）
     * @param audit    是否记每 RSPU 一条汇总审计（2.3 口径；false 时不查旧值）
     */
    public void replaceStyles(String rspuId, List<RspuStyle> newRows, String operator, boolean audit) {
        List<String> oldCodes = audit
            ? rspuStyleMapper.selectList(new QueryWrapper<RspuStyle>().eq("rspu_id", rspuId))
                .stream().map(RspuStyle::getStyleCode).toList()
            : List.of();
        rspuStyleMapper.delete(new QueryWrapper<RspuStyle>().eq("rspu_id", rspuId));
        for (RspuStyle row : newRows) {
            rspuStyleMapper.insert(row);
        }
        if (audit) {
            auditLogService.logUpdate("rspu_style", rspuId,
                Map.of("styleCodes", oldCodes),
                Map.of("styleCodes", newRows.stream().map(RspuStyle::getStyleCode).toList()),
                operator);
        }
    }

    /**
     * 全量覆盖重写场景关联（先删后插）。
     *
     * @param rspuId   RSPU ID
     * @param newRows  新关联行（调用方已构建好 dictType/createdAt）
     * @param operator 操作人（audit=true 时记审计）
     * @param audit    是否记每 RSPU 一条汇总审计（2.3 口径；false 时不查旧值）
     */
    public void replaceScenes(String rspuId, List<RspuScene> newRows, String operator, boolean audit) {
        List<String> oldCodes = audit
            ? rspuSceneMapper.selectList(new QueryWrapper<RspuScene>().eq("rspu_id", rspuId))
                .stream().map(RspuScene::getSceneCode).toList()
            : List.of();
        rspuSceneMapper.delete(new QueryWrapper<RspuScene>().eq("rspu_id", rspuId));
        for (RspuScene row : newRows) {
            rspuSceneMapper.insert(row);
        }
        if (audit) {
            auditLogService.logUpdate("rspu_scene", rspuId,
                Map.of("sceneCodes", oldCodes),
                Map.of("sceneCodes", newRows.stream().map(RspuScene::getSceneCode).toList()),
                operator);
        }
    }
}
