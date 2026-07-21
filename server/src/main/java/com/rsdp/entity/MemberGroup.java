package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业内分组/部门实体。
 */
@Data
@TableName("member_group")
public class MemberGroup {

    @TableId
    private String groupId;

    private String companyId;

    private String groupName;

    private Boolean enabled;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
