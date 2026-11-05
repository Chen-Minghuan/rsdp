package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Excel 导入字段映射模板（标准模板直导，2026-10-14 录入链路改造 P1）。
 *
 * <p>各工厂报价表统一为标准录入模板后表头结构固定：以「清洗后表头有序列表的 SHA-256」
 * 为模板指纹，预览时指纹命中即跳过 AI 映射调用，直接使用模板固化的字段映射与价格列角色。
 * 模板在确认导入时由用户勾选「保存为模板」产生，按指纹 upsert（同指纹覆盖更新）。</p>
 */
@Data
@TableName("excel_import_mapping_template")
public class ExcelImportMappingTemplate {

    @TableId
    private String templateId;

    /** 模板名称（用户命名，如「XX 工厂标准报价模板 v1」）。 */
    private String templateName;

    /** 模板指纹：清洗后表头有序列表的 SHA-256。 */
    private String headerSignature;

    /** 表头 → 标准字段映射（与 excel_import_batch.column_mapping 同构的 JSON 对象）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String columnMapping;

    /** 价格列表头 → 角色（factory/sales/none）JSON 对象，可空。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String priceColumnRoles;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
