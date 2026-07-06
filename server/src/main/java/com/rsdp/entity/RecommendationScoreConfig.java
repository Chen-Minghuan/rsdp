package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推荐打分配置实体。
 */
@Data
@TableName("recommendation_score_config")
public class RecommendationScoreConfig {

    @TableId
    private String configId;

    private String configKey;
    private String name;
    private String description;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String weights;

    private Boolean isDefault;
    private Boolean isActive;
    private String createdBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
