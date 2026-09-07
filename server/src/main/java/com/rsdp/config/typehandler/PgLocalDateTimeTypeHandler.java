package com.rsdp.config.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * PostgreSQL TIMESTAMPTZ 兼容的 LocalDateTime TypeHandler。
 *
 * <p>数据库时间列已统一为 {@code TIMESTAMPTZ}（存 UTC、按会话时区渲染）。pgjdbc 拒绝将
 * timestamptz 列按 {@code getObject(col, LocalDateTime.class)} 转换（要求 OffsetDateTime），
 * 而 MyBatis 内置 {@code LocalDateTimeTypeHandler} 恰用该方式读取，会导致
 * {@code PSQLException: Cannot convert the column of type TIMESTAMPTZ}。</p>
 *
 * <p>本处理器读取走 {@code getTimestamp()}（JVM 默认时区渲染，与原无时区列行为一致），
 * 写入走 {@code setTimestamp()}（pgjdbc 自动按 JVM 时区转 UTC），实体侧无需任何改动。
 * 应用必须固定 JVM 时区（容器/启动脚本已设 Asia/Shanghai），否则读写的时区基准会漂移。</p>
 */
@MappedTypes(LocalDateTime.class)
@MappedJdbcTypes({JdbcType.TIMESTAMP, JdbcType.TIMESTAMP_WITH_TIMEZONE})
public class PgLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setTimestamp(i, Timestamp.valueOf(parameter));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Timestamp ts = rs.getTimestamp(columnName);
        return ts == null ? null : ts.toLocalDateTime();
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Timestamp ts = rs.getTimestamp(columnIndex);
        return ts == null ? null : ts.toLocalDateTime();
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Timestamp ts = cs.getTimestamp(columnIndex);
        return ts == null ? null : ts.toLocalDateTime();
    }
}
