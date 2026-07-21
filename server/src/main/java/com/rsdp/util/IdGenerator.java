package com.rsdp.util;

import java.util.UUID;

/**
 * 业务主键生成器。
 *
 * <p>统一使用完整 UUID（大写、带连字符）作为业务主键，避免早期 8 位截断 UUID 的碰撞风险。</p>
 */
public final class IdGenerator {

    private IdGenerator() {
        // 工具类禁止实例化
    }

    /**
     * 生成 RSPU 主键。
     *
     * @return {@code RSPU-<完整 UUID>}
     */
    public static String rspuId() {
        return generate("RSPU");
    }

    /**
     * 生成 RSKU 主键。
     *
     * @return {@code RSKU-<完整 UUID>}
     */
    public static String rskuId() {
        return generate("RSKU");
    }

    /**
     * 生成收藏记录主键。
     *
     * @return {@code FAV-<完整 UUID>}
     */
    public static String favoriteId() {
        return generate("FAV");
    }

    /**
     * 生成设计项目主键。
     *
     * @return {@code PROJ-<完整 UUID>}
     */
    public static String projectId() {
        return generate("PROJ");
    }

    /**
     * 生成搭配方案主键。
     *
     * @return {@code SCHEME-<完整 UUID>}
     */
    public static String schemeId() {
        return generate("SCHEME");
    }

    /**
     * 生成设计订单主键。
     *
     * @return {@code ORD-<完整 UUID>}
     */
    public static String orderId() {
        return generate("ORD");
    }

    /**
     * 生成图片资源主键。
     *
     * @return {@code IMG-<完整 UUID>}
     */
    public static String imageId() {
        return generate("IMG");
    }

    /**
     * 生成异步任务主键。
     *
     * @return {@code TASK-<完整 UUID>}
     */
    public static String taskId() {
        return generate("TASK");
    }

    /**
     * 生成通用批次主键。
     *
     * @return {@code BATCH-<完整 UUID>}
     */
    public static String batchId() {
        return generate("BATCH");
    }

    /**
     * 生成 Excel 导入批次主键。
     *
     * @return {@code EXCEL-<完整 UUID>}
     */
    public static String excelBatchId() {
        return generate("EXCEL");
    }

    /**
     * 生成 AI 识别记录主键。
     *
     * @return {@code REC-<完整 UUID>}
     */
    public static String recognitionId() {
        return generate("REC");
    }

    /**
     * 生成 RSPU 搭配关系主键。
     *
     * @return {@code REL-<完整 UUID>}
     */
    public static String relationId() {
        return generate("REL");
    }

    /**
     * 生成用户主键。
     *
     * @return {@code USER-<完整 UUID>}
     */
    public static String userId() {
        return generate("USER");
    }

    /**
     * 通用主键生成。
     *
     * @param prefix 业务前缀（不带连字符）
     * @return {@code <prefix>-<完整 UUID>}
     */
    public static String generate(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().toUpperCase();
    }
}
