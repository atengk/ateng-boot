package io.github.atengk.boot.mybatisplus.handler;

import org.apache.ibatis.type.JdbcType;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 确定性数据持久层透明加解密 MyBatis 类型处理器
 * 写入数据库时基于 HMAC-SHA256 合成初始化向量（SIV）执行确定性 AES-CBC 加密，
 * 对相同明文始终生成一致密文，支持精确等值检索（EQ/NE/IN/NOT_IN）；读取时复用父类透明解密与历史明文安全兜底。
 * 配合 @FieldEncrypt(deterministic = true) 与 @TableField(typeHandler = DeterministicEncryptTypeHandler.class) 使用。
 *
 * @author Ateng
 * @since 2026-11-09
 */
public class DeterministicEncryptTypeHandler extends EncryptTypeHandler {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, encryptDeterministic(parameter));
    }
}
