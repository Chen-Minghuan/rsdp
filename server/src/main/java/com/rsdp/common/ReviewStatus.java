package com.rsdp.common;

import lombok.Getter;

/**
 * 产品复核状态。
 *
 * <p>数据库当前存储中文展示值，代码层通过枚举隔离，便于后续统一为英文值。</p>
 */
@Getter
public enum ReviewStatus {

    PENDING("待复核"),
    APPROVED("已确认"),
    REJECTED("存疑");

    private final String dbValue;

    ReviewStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /**
     * 根据数据库存储值查找枚举。
     *
     * @param dbValue 数据库存储值
     * @return 枚举值；找不到时返回 {@code null}
     */
    public static ReviewStatus fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (ReviewStatus status : values()) {
            if (status.dbValue.equals(dbValue)) {
                return status;
            }
        }
        return null;
    }
}
