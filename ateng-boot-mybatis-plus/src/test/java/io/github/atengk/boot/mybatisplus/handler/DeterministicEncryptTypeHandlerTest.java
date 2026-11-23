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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 确定性持久层加密类型处理器单测
 * 验证：
 * 1. 相同明文始终生成相同的 v1: 确定性密文，不同明文密文互异；
 * 2. DeterministicEncryptTypeHandler 与 EncryptTypeHandler 100% 双向解密互通；
 * 3. 历史未加密明文在解密时安全兜底原样返回；
 * 4. PreparedStatement 写入与 ResultSet/CallableStatement 读取生命周期。
 *
 * @author Ateng
 * @since 2026-11-09
 */
@DisplayName("确定性持久层加密类型处理器单测 (DeterministicEncryptTypeHandlerTest)")
class DeterministicEncryptTypeHandlerTest {

    private DeterministicEncryptTypeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DeterministicEncryptTypeHandler();
        EncryptTypeHandler.setGlobalSecretKey(null);
    }

    @AfterEach
    void tearDown() {
        EncryptTypeHandler.setGlobalSecretKey(null);
    }

    @Test
    @DisplayName("1. 验证相同明文生成一致密文，不同明文密文互异，空值原样返回")
    void shouldProduceConsistentCiphertextForSamePlaintextAndDifferentiateDistinctInputs() {
        String plaintextA = "user@example.com";
        String plaintextB = "admin@example.com";

        // 多次调用生成一致密文
        String cipherA1 = EncryptTypeHandler.encryptDeterministic(plaintextA);
        String cipherA2 = EncryptTypeHandler.encryptDeterministic(plaintextA);
        String cipherB = EncryptTypeHandler.encryptDeterministic(plaintextB);

        assertThat(cipherA1).startsWith("v1:");
        assertThat(cipherA1).isEqualTo(cipherA2);
        assertThat(cipherA1).isNotEqualTo(cipherB);
        assertThat(cipherA1).isNotEqualTo(plaintextA);

        // 空值与空串处理
        assertThat(EncryptTypeHandler.encryptDeterministic(null)).isNull();
        assertThat(EncryptTypeHandler.encryptDeterministic("")).isEmpty();
    }

    @Test
    @DisplayName("2. 验证与随机 IV EncryptTypeHandler 100% 双向解密兼容")
    void shouldBeBidirectionallyCompatibleWithDefaultEncryptTypeHandler() throws SQLException {
        String plaintext = "13912345678";

        // 确定性加密密文可由 EncryptTypeHandler 解密
        String deterministicCipher = EncryptTypeHandler.encryptDeterministic(plaintext);
        assertThat(EncryptTypeHandler.decrypt(deterministicCipher)).isEqualTo(plaintext);

        // 随机 IV 加密密文可由 DeterministicEncryptTypeHandler 解密（复用父类 decrypt）
        String randomIvCipher = EncryptTypeHandler.encrypt(plaintext);
        assertThat(randomIvCipher).isNotEqualTo(deterministicCipher);
        assertThat(handler.getNullableResult(mockResultSet("col", randomIvCipher), "col")).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("3. 验证历史非加密明文安全兜底原样返回")
    void shouldGracefullyFallbackOnLegacyPlaintext() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("legacy_phone")).thenReturn("13800000000");

        String result = handler.getNullableResult(rs, "legacy_phone");
        assertThat(result).isEqualTo("13800000000");
    }

    @Test
    @DisplayName("4. 验证 PreparedStatement 写入与 ResultSet / CallableStatement 读取")
    void shouldHandlePreparedStatementAndResultSetLifecycle() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        String plaintext = "vip-secret-code-2026";
        String expectedDeterministicCipher = EncryptTypeHandler.encryptDeterministic(plaintext);

        // 写入 PreparedStatement
        handler.setNonNullParameter(ps, 1, plaintext, JdbcType.VARCHAR);
        verify(ps).setString(1, expectedDeterministicCipher);

        // 按列名读取
        ResultSet rsCol = mock(ResultSet.class);
        when(rsCol.getString("secret")).thenReturn(expectedDeterministicCipher);
        assertThat(handler.getNullableResult(rsCol, "secret")).isEqualTo(plaintext);

        // 按索引读取
        ResultSet rsIdx = mock(ResultSet.class);
        when(rsIdx.getString(2)).thenReturn(expectedDeterministicCipher);
        assertThat(handler.getNullableResult(rsIdx, 2)).isEqualTo(plaintext);

        // 存储过程读取
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(3)).thenReturn(expectedDeterministicCipher);
        assertThat(handler.getNullableResult(cs, 3)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("5. 验证自定义全局密钥下的确定性加解密一致性")
    void shouldWorkWithCustomGlobalSecretKey() {
        EncryptTypeHandler.setGlobalSecretKey("CustomSecretKeyForDeterministic2026");
        String plaintext = "query-target-user@ateng.com";

        String cipher1 = EncryptTypeHandler.encryptDeterministic(plaintext);
        String cipher2 = EncryptTypeHandler.encryptDeterministic(plaintext);

        assertThat(cipher1).isEqualTo(cipher2);
        assertThat(EncryptTypeHandler.decrypt(cipher1)).isEqualTo(plaintext);
    }

    private ResultSet mockResultSet(String columnName, String value) {
        try {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(columnName)).thenReturn(value);
            return rs;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
