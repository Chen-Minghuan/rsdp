package com.rsdp.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * RSPU 已软删除事件。
 *
 * <p>由 {@link com.rsdp.service.ProductQueryService#deleteProduct(String)} 在事务提交后发布，
 * 监听器负责异步清理 pgvector 中的图片向量记录。</p>
 */
@Getter
@RequiredArgsConstructor
public class RspuDeletedEvent {

    private final String rspuId;
    private final List<String> imageIds;
}
