package io.github.atengk.boot.mybatisplus.handler;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 持久层加密类型处理器单测
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("持久层加密类型处理器单测")
class EncryptTypeHandlerTest {

    private EncryptTypeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new EncryptTypeHandler();
        EncryptTypeHandler.setGlobalSecretKey(null); // 重置为默认内置密钥
    }

    @AfterEach
    void tearDown() {
        EncryptTypeHandler.setGlobalSecretKey(null);
    }

    @Test
    @DisplayName("写入数据时：自动将明文加密为 Base64 密文写入 PreparedStatement")
    void shouldEncryptPlaintextIntoPreparedStatement() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        String plaintext = "13800138000";

        handler.setNonNullParameter(ps, 1, plaintext, JdbcType.VARCHAR);

        // 验证写入的参数不是明文，并且解密后能够还原原始明文
        verify(ps).setString(org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.argThat(cipher -> {
            assertThat(cipher).isNotEqualTo(plaintext);
            assertThat(EncryptTypeHandler.decrypt(cipher)).isEqualTo(plaintext);
            return true;
        }));
    }

    @Test
    @DisplayName("读取数据时（按列名）：自动从 ResultSet 读取密文并解密为明文")
    void shouldDecryptCiphertextFromResultSetByColumnName() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        String plaintext = "张三丰";
        String ciphertext = EncryptTypeHandler.encrypt(plaintext);

        when(rs.getString("name")).thenReturn(ciphertext);

        String result = handler.getNullableResult(rs, "name");
        assertThat(result).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("读取数据时（按列索引）：自动从 ResultSet 读取密文并解密为明文")
    void shouldDecryptCiphertextFromResultSetByColumnIndex() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        String plaintext = "id_card_110101";
        String ciphertext = EncryptTypeHandler.encrypt(plaintext);

        when(rs.getString(2)).thenReturn(ciphertext);

        String result = handler.getNullableResult(rs, 2);
        assertThat(result).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("存储过程读取时：自动从 CallableStatement 读取密文并解密为明文")
    void shouldDecryptCiphertextFromCallableStatement() throws SQLException {
        CallableStatement cs = mock(CallableStatement.class);
        String plaintext = "bank_card_622202";
        String ciphertext = EncryptTypeHandler.encrypt(plaintext);

        when(cs.getString(3)).thenReturn(ciphertext);

        String result = handler.getNullableResult(cs, 3);
        assertThat(result).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("空值与空串处理：null 返回 null，空串原样返回")
    void shouldHandleNullAndEmptyGracefully() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("empty_col")).thenReturn("");
        when(rs.getString("null_col")).thenReturn(null);

        assertThat(handler.getNullableResult(rs, "empty_col")).isEqualTo("");
        assertThat(handler.getNullableResult(rs, "null_col")).isNull();
    }

    @Test
    @DisplayName("历史明文兜底：数据库中已存在的非加密明文在解密失败时原样返回，不崩溃")
    void shouldFallbackToRawStringWhenDecryptionFailsOnPlaintext() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        String legacyPlaintext = "plain-unencrypted-legacy-data";
        when(rs.getString("legacy")).thenReturn(legacyPlaintext);

        String result = handler.getNullableResult(rs, "legacy");
        assertThat(result).isEqualTo(legacyPlaintext);
    }

    @Test
    @DisplayName("支持全局自定义密钥动态注入与加解密")
    void shouldSupportCustomGlobalSecretKey() {
        String customKey = "CustomSecretKey1234567890abcdef";
        EncryptTypeHandler.setGlobalSecretKey(customKey);

        String plaintext = "sensitivePassword999";
        String ciphertext = EncryptTypeHandler.encrypt(plaintext);

        assertThat(ciphertext).isNotEqualTo(plaintext);
        assertThat(ciphertext).startsWith(EncryptTypeHandler.CIPHER_PREFIX_V1);
        assertThat(EncryptTypeHandler.decrypt(ciphertext)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("密文带 v1: 前缀生成与无前缀历史密文平滑兼容解密")
    void shouldSupportV1PrefixAndLegacyNoPrefixCiphertext() {
        String plaintext = "my-secret-token-2026";
        String v1Cipher = EncryptTypeHandler.encrypt(plaintext);
        assertThat(v1Cipher).startsWith("v1:");
        assertThat(EncryptTypeHandler.decrypt(v1Cipher)).isEqualTo(plaintext);

        // 模拟剥离前缀的旧版历史密文，验证无缝平滑解密
        String legacyNoPrefixCipher = v1Cipher.substring(3);
        assertThat(EncryptTypeHandler.decrypt(legacyNoPrefixCipher)).isEqualTo(plaintext);
    }
}
