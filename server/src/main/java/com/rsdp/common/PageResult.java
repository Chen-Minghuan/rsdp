package com.rsdp.common;

import lombok.Data;

import java.util.List;

/**
 * 统一分页响应结果。
 *
 * @param <T> 行数据类型
 */
@Data
public class PageResult<T> {

    private Long total;
    private Long page;
    private Long size;
    private List<T> rows;

    public static <T> PageResult<T> of(long total, long page, long size, List<T> rows) {
        PageResult<T> result = new PageResult<>();
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        result.setRows(rows);
        return result;
    }

    public static <T> PageResult<T> from(com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> page) {
        return of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }
}
