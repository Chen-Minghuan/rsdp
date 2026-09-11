package com.rsdp.dto.request;

/**
 * Excel 导入方式（按 Sheet/批次生效）。
 *
 * <ul>
 *   <li>SINGLE 单一品类：用户指定一个默认品类（复用 categoryHint），整批默认使用；
 *       行内类别列确定性归一命中时以行内值为准，不进行品类 AI 分类。</li>
 *   <li>MIXED 混合品类：用户指定多个候选品类，AI 只在候选集内逐行预填，人工在数据清洗页确认。</li>
 * </ul>
 *
 * <p>请求中缺省（null）表示未选导入方式的旧路径：维持原有兜底链
 * （行类别列 → Sheet 名归一 → 品类提示 → categoryGuess），向后兼容。</p>
 */
public enum CategoryMode {
    SINGLE,
    MIXED
}
