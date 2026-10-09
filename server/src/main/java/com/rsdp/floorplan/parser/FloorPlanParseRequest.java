package com.rsdp.floorplan.parser;

/**
 * 户型图解析请求（CAD 户型导入 P3）。
 *
 * @param fileBytes 文件字节（PDF 为渲染后的 PNG 字节；CAD 为 dwg/dxf 原始字节）
 * @param fileName  文件名（取扩展名/日志用；异步链路传存储对象键亦可）
 * @param hint      用户补充说明（仅视觉识别通道使用），可空
 * @param targetId  目标 Scene ID（CAD Scene Parser 升级·target-scene 切换重解析时携带，
 *                  multipart 附带 targetId 字段；首次自动解析为 null），可空
 */
public record FloorPlanParseRequest(byte[] fileBytes, String fileName, String hint, String targetId) {

    /**
     * 兼容构造（无目标 Scene，首次自动解析/视觉通道）。
     *
     * @param fileBytes 文件字节
     * @param fileName  文件名
     * @param hint      用户补充说明，可空
     */
    public FloorPlanParseRequest(byte[] fileBytes, String fileName, String hint) {
        this(fileBytes, fileName, hint, null);
    }
}
