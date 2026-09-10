package com.rsdp.util;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConstraintViolations} 单元测试。
 */
class ConstraintViolationsTest {

    private static final String UNIQUE_MESSAGE = "数据已存在，请检查是否重复导入";

    @Test
    void shouldExtractConstraintName() {
        DataIntegrityViolationException e = new DataIntegrityViolationException(
            "insert or update on table \"rspu_scene\" violates foreign key constraint \"rspu_scene_dict_fk\"");
        assertThat(ConstraintViolations.extractConstraintName(e)).isEqualTo("rspu_scene_dict_fk");
        assertThat(ConstraintViolations.extractConstraintName(new DataIntegrityViolationException("no constraint")))
            .isNull();
    }

    @Test
    void uniqueViolation_viaDuplicateKeyException() {
        assertThat(ConstraintViolations.toUserMessage(new DuplicateKeyException("dup"), UNIQUE_MESSAGE))
            .isEqualTo(UNIQUE_MESSAGE);
    }

    @Test
    void uniqueViolation_viaMessageAndSqlState() {
        assertThat(ConstraintViolations.toUserMessage(
            new DataIntegrityViolationException("duplicate key value violates unique constraint \"uk_x\""),
            UNIQUE_MESSAGE)).isEqualTo(UNIQUE_MESSAGE);
        // SQLState 23505（cause 链上的 SQLException）优先于消息文案
        DataIntegrityViolationException withCause = new DataIntegrityViolationException("wrapped",
            new SQLException("duplicate key", "23505"));
        assertThat(ConstraintViolations.toUserMessage(withCause, UNIQUE_MESSAGE)).isEqualTo(UNIQUE_MESSAGE);
    }

    @Test
    void foreignKeyViolation_shouldReportReferenceCheck() {
        DataIntegrityViolationException e = new DataIntegrityViolationException(
            "insert or update on table \"rspu_scene\" violates foreign key constraint \"rspu_scene_dict_fk\"");
        String message = ConstraintViolations.toUserMessage(e, UNIQUE_MESSAGE);
        assertThat(message).contains("数据引用校验失败").contains("rspu_scene_dict_fk").doesNotContain("重复导入");

        // SQLState 23503 判型
        DataIntegrityViolationException withCause = new DataIntegrityViolationException("wrapped",
            new SQLException("fk", "23503"));
        assertThat(ConstraintViolations.toUserMessage(withCause, UNIQUE_MESSAGE))
            .contains("数据引用校验失败");
    }

    @Test
    void otherViolation_shouldReportGenericMessageWithConstraintName() {
        DataIntegrityViolationException e = new DataIntegrityViolationException(
            "null value violates not-null constraint \"rspu_master_category_nn\"");
        String message = ConstraintViolations.toUserMessage(e, UNIQUE_MESSAGE);
        assertThat(message).contains("数据完整性校验失败").contains("rspu_master_category_nn");
    }
}
