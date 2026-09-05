package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 搭配方案主表实体。
 */
@Data
@TableName("scheme")
public class Scheme {

    @TableId
    private String schemeId;
    private String schemeName;
    private String roomType;
    private BigDecimal budgetLimit;
    private BigDecimal totalPrice;
    private Integer factoryCount;
    private Integer maxLeadTimeDays;
    private Integer itemCount;
    private String status;
    private String projectId;
    private Boolean isTemplate;

    /** 来源户型图分析批次（V36，方案溯源），可空。 */
    private String analysisId;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String templateTags;

    /** 画布布局 JSON（搭配画布，V41）：{"<schemeItemId>":{x,y,scale,z}}，可空=未保存画布布局。 */
    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String canvasLayout;

    /** 方案分享开关（V42，开启后公开只读视图 /api/v1/public/schemes/{schemeId} 可访问） */
    private Boolean shareEnabled;

    /** 方案分享过期时间（V42，null=永久有效；关闭分享时清空） */
    private LocalDateTime shareExpireAt;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
