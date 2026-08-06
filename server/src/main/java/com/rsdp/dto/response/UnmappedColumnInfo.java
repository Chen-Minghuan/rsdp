package com.rsdp.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel AI 导入预览中未被映射到系统字段的列信息。
 */
@Data
public class UnmappedColumnInfo {

    /**
     * 原始表头（清洗/消歧后）。
     */
    private String header;

    /**
     * 该列前 N 个非空样例值（按列顺序）。
     */
    private List<String> sampleValues = new ArrayList<>();
}
