package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 推荐候选清单实体。
 */
@Data
@TableName("scheme_candidate")
public class SchemeCandidate {

    @TableId
    private String candidateId;

    private String recommendRequestId;
    private String rspuId;
    private String rskuId;

    private BigDecimal score;
    private String aiReason;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String matchFactors;

    private String status;
    private String createdBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
