package com.rsdp.event;

import com.rsdp.service.vector.ProductVectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * RSPU 删除事件监听器，负责异步清理 pgvector 向量。
 *
 * <p>将向量清理与业务事务解耦，避免存储调用拖长或阻塞数据库事务。
 * 监听事务提交后的事件，确保数据库删除完成后再清理向量。
 * 图片物理删除时数据库已级联清除向量行，此处为软删/级联兜底场景，删除幂等。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RspuDeletedEventListener {

    private final ProductVectorStore productVectorStore;

    /**
     * 异步删除 RSPU 关联的图片向量。
     *
     * @param event RSPU 删除事件
     */
    @Async("taskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRspuDeleted(RspuDeletedEvent event) {
        List<String> imageIds = event.getImageIds();
        if (imageIds == null || imageIds.isEmpty()) {
            log.info("RSPU 无关联图片向量需要清理，rspuId={}", event.getRspuId());
            return;
        }

        try {
            productVectorStore.deleteByImageIds(imageIds);
            log.info("已删除 RSPU 向量，rspuId={}，数量={}", event.getRspuId(), imageIds.size());
        } catch (Exception e) {
            log.error("删除 RSPU 向量失败，rspuId={}，imageIds={}", event.getRspuId(), imageIds, e);
        }
    }
}
