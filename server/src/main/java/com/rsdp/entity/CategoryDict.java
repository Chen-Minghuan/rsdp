package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 字典表实体。
 *
 * <p>数据库主键为复合主键 (dict_type, dict_code)。MyBatis-Plus 仅支持单一主键，
 * 因此将 dict_type 标注为 {@code @TableId}，业务查询仍通过 QueryWrapper 按复合条件操作。</p>
 */
@Data
@TableName("category_dict")
public class CategoryDict {

    @TableId("dict_type")
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
