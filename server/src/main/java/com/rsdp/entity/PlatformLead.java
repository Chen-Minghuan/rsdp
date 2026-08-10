package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 官网留资线索实体（platform_lead，V34）。
 *
 * <p>用户端官网 CTA/表单/AI 户型搭配入口写入；管理端「留资线索」页分配跟进。
 * 无逻辑删除，线索生命周期由 status 流转（pending → contacted → done）。</p>
 */
@Data
@TableName("platform_lead")
public class PlatformLead {

    /** 来源：AI 户型搭配。 */
    public static final String SOURCE_AI_MATCH = "ai_match";
    /** 来源：官网表单。 */
    public static final String SOURCE_SITE_FORM = "site_form";
    /** 来源：设计服务预约。 */
    public static final String SOURCE_DESIGN_BOOKING = "design_booking";

    /** 状态：待跟进。 */
    public static final String STATUS_PENDING = "pending";
    /** 状态：已联系。 */
    public static final String STATUS_CONTACTED = "contacted";
    /** 状态：已完成。 */
    public static final String STATUS_DONE = "done";

    @TableId
    private String leadId;

    private String name;

    private String phone;

    /** 来源：ai_match / site_form / design_booking。 */
    private String source;

    /** 意向描述（自由文本）。 */
    private String intent;

    /** 预算区间（如 1-3万）。 */
    private String budget;

    /** 跟进状态：pending / contacted / done。 */
    private String status;

    /** 跟进人（管理端分配，存用户名）。 */
    private String assignee;

    /** 跟进记录 JSON 数组（追加式）。 */
    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String followLog;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
