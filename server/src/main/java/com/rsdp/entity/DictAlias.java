package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字典别名实体（dict_alias）。
 *
 * <p>工厂方言叫法（如「茶桌」「主椅」）→ 字典码的持久化映射，
 * Excel 导入确认后自学习积累，后续导入直接命中别名，不再调 AI。</p>
 */
@Data
@TableName("dict_alias")
public class DictAlias {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 字典类型，如 category/style/material。
     */
    private String dictType;

    /**
     * 别名（工厂方言叫法）。
     */
    private String aliasName;

    /**
     * 归一后的字典码。
     */
    private String dictCode;

    /**
     * 来源：ai_confirmed / manual。
     */
    private String source;

    /**
     * 创建人。
     */
    private String createdBy;

    /**
     * 创建时间。
     */
    private LocalDateTime createdAt;
}
