package com.rsdp.floorplan.parser;

/**
 * 户型图解析请求（CAD 户型导入 P3）。
 *
 * @param fileBytes 文件字节（PDF 为渲染后的 PNG 字节；CAD 为 dwg/dxf 原始字节）
 * @param fileName  文件名（取扩展名/日志用；异步链路传存储对象键亦可）
 * @param hint      用户补充说明（仅视觉识别通道使用），可空
 */
public record FloorPlanParseRequest(byte[] fileBytes, String fileName, String hint) {
}
