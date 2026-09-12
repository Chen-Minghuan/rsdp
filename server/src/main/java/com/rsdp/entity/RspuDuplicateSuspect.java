package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RSPU 疑似同款配对记录实体。
 *
 * <p>向量同款检测命中（相似度 ≥ 阈值）时落结构化记录，供合并工具候选队列与闭环追踪；
 * rspu_master.review_comment 的疑似文本仅作展示。状态机：pending → merged（已合并）/
 * dismissed（人工确认不重复）。</p>
 */
@Data
@TableName("rspu_duplicate_suspect")
public class RspuDuplicateSuspect {

    @TableId(type = IdType.AUTO)
    private Long suspectId;

    /** 被标存疑的新品 */
    private String rspuId;

    /** 召回命中的疑似同款 */
    private String matchedRspuId;

    /** 向量相似度（0~1） */
    private BigDecimal similarity;

    /** pending / merged / dismissed */
    private String status;

    /** 处理人（合并/排除操作人 username） */
    private String resolvedBy;

    private LocalDateTime resolvedAt;

    private LocalDateTime createdAt;
}
