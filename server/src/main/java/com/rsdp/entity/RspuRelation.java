package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RSPU 产品间关系实体。
 * 用于表达原厂搭配、AI 确认搭配或互斥排除关系。
 */
@Data
@TableName("rspu_relation")
public class RspuRelation {

    @TableId
    private String relationId;

    private String anchorRspuId;

    private String relatedRspuId;

    private String relationType;

    private String reason;

    private Integer sortOrder;

    private String status;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}
