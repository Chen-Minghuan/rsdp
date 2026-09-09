package com.rsdp.config.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL pgvector {@code vector} 列与 {@code float[]} 的互转 TypeHandler。
 *
 * <p>写入侧以 pgvector 文本格式（{@code [1.0,2.0,...]}）封装为 {@link PGobject} 提交，
 * 不引入额外 JDBC 依赖；读取侧解析同格式文本。与 {@link JsonbTypeHandler} 同一模式。</p>
 */
@MappedTypes(float[].class)
public class VectorTypeHandler extends BaseTypeHandler<float[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, float[] parameter, JdbcType jdbcType)
        throws SQLException {
        PGobject pg = new PGobject();
        pg.setType("vector");
        pg.setValue(toVectorLiteral(parameter));
        ps.setObject(i, pg);
    }

    @Override
    public float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    /**
     * 将向量序列化为 pgvector 文本格式。
     *
     * @param vector 浮点向量
     * @return {@code [v1,v2,...]} 字面量
     */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * 解析 pgvector 文本格式为浮点数组。
     *
     * @param literal {@code [v1,v2,...]} 字面量，null 或空串返回 null
     * @return 浮点向量
     */
    public static float[] parse(String literal) {
        if (literal == null || literal.isBlank()) {
            return null;
        }
        String body = literal.trim();
        if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1);
        }
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
