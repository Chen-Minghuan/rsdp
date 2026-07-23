package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 未归一值采集实体（dict_unresolved_value）。
 *
 * <p>导入/录入时字典解析未命中的工厂原文自动计数采集，
 * 供运营在治理页面归并（写 dict_alias 自学习 + 历史回填）或忽略。</p>
 */
@Data
@TableName("dict_unresolved_value")
public class DictUnresolvedValue {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 字典类型：size/color/material（可扩展 style 等） */
    private String dictType;

    /** 未归一的工厂原文 */
    private String rawValue;

    /** 累计出现次数 */
    private Integer occurrenceCount;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;

    /** 最近出现的导入批次 */
    private String lastBatchId;

    /** 最近操作人 */
    private String lastUsername;

    /** 状态：pending/resolved/ignored */
    private String status;

    /** 归并后的字典码（resolved 时填写） */
    private String resolvedCode;

    private String resolvedBy;

    private LocalDateTime resolvedAt;
}
