package com.rsdp.exception;

/**
 * Preview Revision 乐观锁冲突（设计文档 v3.3 §47）。
 *
 * <p>编辑命令携带 {@code expectedRevision}，若当前 Revision 已被其他编辑推进，
 * 服务端拒绝并返回 409 {@code preview_revision_conflict}；客户端需重新读取
 * 最新 Snapshot 后重试。</p>
 */
public class PreviewRevisionConflictException extends BusinessException {

    public PreviewRevisionConflictException(String batchId, Long expectedRevision, Long currentRevision) {
        super(409, String.format(
            "preview_revision_conflict: batchId=%s, expectedRevision=%s, currentRevision=%s",
            batchId, expectedRevision, currentRevision));
    }
}
