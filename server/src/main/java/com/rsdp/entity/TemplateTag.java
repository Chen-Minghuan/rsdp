package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 方案模板标签实体（受控字典；scheme.template_tags 存名称 JSON，以名称为业务键）。
 */
@Data
@TableName("template_tag")
public class TemplateTag {

    @TableId
    private String tagId;

    private String tagName;

    private Integer sortOrder;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
