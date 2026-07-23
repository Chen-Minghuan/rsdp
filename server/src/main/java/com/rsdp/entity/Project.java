package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设计项目实体。
 */
@Data
@TableName("project")
public class Project {

    @TableId
    private String projectId;
    private String projectName;
    private String projectType;
    private String companyName;
    private String ownerId;
    private String status;
    private String remark;

    /** 画布分享开关（开启后公开只读视图 /s/{projectId} 可访问） */
    private Boolean shareEnabled;

    /** 分享过期时间（null=永久有效） */
    private LocalDateTime shareExpireAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
