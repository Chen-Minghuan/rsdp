package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 同款产品合并请求（决策点②共享主档模型：把重复副本合并到目标 RSPU）。
 *
 * <p>合并行为：目标字段已有值不动、仅补空缺（takeSourceFields 可显式指定改取副本值）；
 * 变体按 (尺寸,颜色,材质) key 自动映射/改挂；RSKU 冲突必须逐条人工裁决；
 * 副本在全部数据迁出后软删（回收站可见）。</p>
 */
@Data
public class RspuMergeRequest {

    /** 副本 RSPU ID（合并完成后软删） */
    @NotBlank(message = "副本产品 ID 不能为空")
    private String sourceRspuId;

    /** 目标 RSPU ID（保留的主档） */
    @NotBlank(message = "目标产品 ID 不能为空")
    private String targetRspuId;

    /**
     * 字段冲突人工选择：指定这些字段取副本值覆盖目标（白名单字段之外的一律忽略）。
     * 缺省/null = 目标全部保留，仅补空缺。
     */
    private List<String> takeSourceFields;

    /**
     * RSKU 冲突裁决：副本 rskuId → "keepSource"（留副本报价，目标旧报价软删）/
     * "keepTarget"（留目标报价，副本报价软删）。存在冲突且未提供裁决时合并被拒绝。
     */
    private Map<String, String> rskuConflictResolutions;
}
