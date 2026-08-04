package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 六维标签维度定义实体（V25）。
 *
 * <p>品类 × A-F 维度键 → 标签/说明，替代原前后端双写（SixDimSchemaConfig / sixDimLabels.ts），
 * 新品类只配数据零代码上线。GENERIC 品类为未知品类兜底定义。</p>
 */
@Data
@TableName("six_dim_schema")
public class SixDimSchema {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 品类码（GENERIC 为未知品类兜底定义）。 */
    @TableField("category_code")
    private String categoryCode;

    /** 维度键 A/B/C/D/E/F。 */
    @TableField("dim_key")
    private String dimKey;

    /** 维度标签（如 轮廓形态、靠背/背部特征）。 */
    @TableField("label")
    private String label;

    /** 维度说明（取值范围提示，用于 prompt 与前端展示）。 */
    @TableField("description")
    private String description;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
