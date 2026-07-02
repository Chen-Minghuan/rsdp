package com.rsdp.config.typehandler;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * PostgreSQL jsonb 字段通用类型处理器。
 *
 * <p>同时支持 {@link String} 与 {@link Object} 类型的字段：</p>
 * <ul>
 *   <li>字段类型为 {@link String} 时，直接把数据库 jsonb 当字符串读写，不再做 Jackson 二次序列化；</li>
 *   <li>字段类型为 {@link Object} 或其他类型时，复用 {@link JacksonTypeHandler} 完成对象与 JSON 的互转。</li>
 * </ul>
 */
@MappedTypes({String.class, Object.class})
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbTypeHandler extends JacksonTypeHandler {

    public JsonbTypeHandler(Class<?> type) {
        super(type);
    }

    public JsonbTypeHandler(Class<?> type, Field field) {
        super(type, field);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType) throws SQLException {
        PGobject pgObject = new PGobject();
        pgObject.setType("jsonb");
        if (parameter instanceof String s) {
            pgObject.setValue(s);
        } else {
            pgObject.setValue(toJson(parameter));
        }
        ps.setObject(i, pgObject);
    }

    @Override
    public Object parse(String json) {
        if (isStringType()) {
            return json;
        }
        return super.parse(json);
    }

    private boolean isStringType() {
        Type fieldType = getFieldType();
        return fieldType == String.class;
    }
}
