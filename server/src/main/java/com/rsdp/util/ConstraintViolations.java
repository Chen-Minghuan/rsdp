package com.rsdp.util;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据完整性约束冲突解析工具（2.7）。
 *
 * <p>导入链路的行级事务把 {@link DataIntegrityViolationException} 翻译成用户可读文案。
 * 此前一律翻译成「重复导入」，掩盖了外键引用失败（如场景/风格脏值撞 category_dict 复合外键）
 * 的真实原因。本工具按冲突类型细分：</p>
 * <ul>
 *   <li>唯一冲突（SQLState 23505 / {@link DuplicateKeyException} / duplicate key 消息）→ 由各链路
 *       传入的既有「重复导入」文案；</li>
 *   <li>外键引用冲突（SQLState 23503 / foreign key 消息）→ 「数据引用校验失败」类文案；</li>
 *   <li>其它（非空/检查约束等）→ 通用文案，附约束名便于定位。</li>
 * </ul>
 *
 * <p>判型优先走 cause 链上的 {@link SQLException#getSQLState()}（数据库厂商标准码，
 * 不依赖英文堆栈文案）；取不到 SQLState 时（如手工构造的异常）回退消息关键字。
 * 约束名提取沿用项目既有先例（RspuVariantService 的 uk_variant_attrs 消息匹配范式，
 * 统一收敛到本类的正则提取）。</p>
 */
public final class ConstraintViolations {

    /** Postgres 错误消息中的约束名：violates xxx constraint "name"。 */
    private static final Pattern CONSTRAINT_NAME_PATTERN = Pattern.compile("constraint \"([^\"]+)\"");

    /** Postgres SQLState：唯一约束冲突。 */
    private static final String SQLSTATE_UNIQUE = "23505";
    /** Postgres SQLState：外键引用冲突。 */
    private static final String SQLSTATE_FOREIGN_KEY = "23503";

    private ConstraintViolations() {
    }

    /**
     * 提取异常消息中的约束名。
     *
     * @param e 数据完整性异常
     * @return 约束名；提取不到返回 null
     */
    public static String extractConstraintName(DataIntegrityViolationException e) {
        String message = e.getMessage();
        if (message == null) {
            return null;
        }
        Matcher matcher = CONSTRAINT_NAME_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 是否唯一约束冲突。
     */
    public static boolean isUniqueViolation(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        String sqlState = sqlStateOf(e);
        if (sqlState != null) {
            return SQLSTATE_UNIQUE.equals(sqlState);
        }
        String message = e.getMessage();
        return message != null
            && (message.contains("duplicate key") || message.contains("unique constraint")
                || message.contains("Duplicate entry"));
    }

    /**
     * 是否外键引用冲突。
     */
    public static boolean isForeignKeyViolation(DataIntegrityViolationException e) {
        String sqlState = sqlStateOf(e);
        if (sqlState != null) {
            return SQLSTATE_FOREIGN_KEY.equals(sqlState);
        }
        String message = e.getMessage();
        return message != null && message.contains("foreign key");
    }

    /**
     * 生成用户可读的约束冲突文案。
     *
     * @param e                     数据完整性异常
     * @param uniqueConflictMessage 唯一冲突时使用的文案（各链路保持既有口径）
     * @return 用户可读文案
     */
    public static String toUserMessage(DataIntegrityViolationException e, String uniqueConflictMessage) {
        if (isUniqueViolation(e)) {
            return uniqueConflictMessage;
        }
        String constraint = extractConstraintName(e);
        String suffix = constraint != null ? "（约束: " + constraint + "）" : "";
        if (isForeignKeyViolation(e)) {
            return "数据引用校验失败：引用的字典或关联数据不存在（如场景/风格值不在字典中）" + suffix;
        }
        return "数据完整性校验失败" + suffix + "，请检查数据后重试";
    }

    /** 沿 cause 链提取 SQLState（Spring 包装后的 Postgres 异常通常可在 cause 链上找到）。 */
    private static String sqlStateOf(DataIntegrityViolationException e) {
        Throwable t = e.getCause();
        while (t != null) {
            if (t instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            t = t.getCause();
        }
        return null;
    }
}
