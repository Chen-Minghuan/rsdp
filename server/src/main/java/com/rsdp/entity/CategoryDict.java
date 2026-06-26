package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 字典表实体。
 */
@Data
@TableName("category_dict")
public class CategoryDict {

    @TableField("dict_type")
    private String dictType;

    @TableField("dict_code")
    private String dictCode;

    @TableField("dict_name")
    private String dictName;

    @TableField("dict_name_en")
    private String dictNameEn;

    @TableField("parent_code")
    private String parentCode;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("status")
    private String status;
}
