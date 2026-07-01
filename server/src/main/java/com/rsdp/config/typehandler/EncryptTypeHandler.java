package com.rsdp.config.typehandler;

import com.rsdp.util.AesEncryptionUtil;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 敏感价格字段加密 TypeHandler。
 *
 * <p>写入数据库时自动将 {@link BigDecimal} 加密为 Base64 密文（TEXT），
 * 读取时自动解密为 {@link BigDecimal}。业务代码无需感知加解密逻辑。</p>
 */
@MappedTypes(BigDecimal.class)
@MappedJdbcTypes({JdbcType.VARCHAR, JdbcType.CHAR, JdbcType.LONGVARCHAR, JdbcType.OTHER})
public class EncryptTypeHandler extends BaseTypeHandler<BigDecimal> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, BigDecimal parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, AesEncryptionUtil.encrypt(parameter));
    }

    @Override
    public BigDecimal getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return AesEncryptionUtil.decrypt(rs.getString(columnName));
    }

    @Override
    public BigDecimal getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return AesEncryptionUtil.decrypt(rs.getString(columnIndex));
    }

    @Override
    public BigDecimal getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return AesEncryptionUtil.decrypt(cs.getString(columnIndex));
    }
}
