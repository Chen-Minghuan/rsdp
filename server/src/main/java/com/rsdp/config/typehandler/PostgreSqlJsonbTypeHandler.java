package com.rsdp.config.typehandler;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * PostgreSQL jsonb 字段类型处理器。
 *
 * <p>继承 MyBatis-Plus 的 JacksonTypeHandler 完成 Java 对象与 JSON 字符串互转，
 * 写入时使用 {@link PGobject} 指定类型为 {@code jsonb}，避免 PostgreSQL 隐式类型转换错误。</p>
 */
@MappedTypes({Object.class})
@MappedJdbcTypes(JdbcType.OTHER)
public class PostgreSqlJsonbTypeHandler extends JacksonTypeHandler {

    public PostgreSqlJsonbTypeHandler(Class<?> type) {
        super(type);
    }

    public PostgreSqlJsonbTypeHandler(Class<?> type, Field field) {
        super(type, field);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType) throws SQLException {
        PGobject pgObject = new PGobject();
        pgObject.setType("jsonb");
        pgObject.setValue(toJson(parameter));
        ps.setObject(i, pgObject);
    }
}
