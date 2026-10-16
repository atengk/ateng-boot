package io.github.atengk.boot.mybatisplus.handler;

import io.github.atengk.boot.core.util.CryptoUtil;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;

/**
 * 数据持久层透明加解密 MyBatis 类型处理器
 * 写入数据库时自动加密为 AES 密文（16 字节随机 IV + 密文组合 Base64），读取时自动透明解密为真实明文
 * 配合 @TableField(typeHandler = EncryptTypeHandler.class) 使用
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class EncryptTypeHandler extends BaseTypeHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(EncryptTypeHandler.class);

    /**
     * 框架自适应派生的 32 字节（256 位）默认安全密钥字节
     */
    private static final byte[] DEFAULT_SECRET_KEY_BYTES;

    static {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            DEFAULT_SECRET_KEY_BYTES = md.digest("io.github.atengk.boot.mybatisplus.crypto.default".getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要算法在当前环境不可用", e);
        }
    }

    /**
     * 全局受管 AES 密钥字符串（支持通过 ateng.boot.mybatis-plus.crypto.secret-key 动态注入）
     */
    private static volatile String globalSecretKey;

    /**
     * 配置全局加解密密钥
     *
     * @param secretKey 密钥字符串；若为 null 或空串则回退至自适应派生默认密钥
     */
    public static void setGlobalSecretKey(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            globalSecretKey = null;
        } else {
            globalSecretKey = secretKey.trim();
        }
    }

    /**
     * 获取当前生效的全局密钥字符串
     *
     * @return 密钥字符串
     */
    public static String getGlobalSecretKey() {
        return globalSecretKey;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decrypt(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decrypt(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decrypt(cs.getString(columnIndex));
    }

    /**
     * 对明文字符串进行 AES 加密
     *
     * @param plaintext 明文字符串
     * @return Base64 组合密文字符串；若明文为 null 或空串则原样返回
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] keyBytes = resolveKeyBytes();
            byte[] iv = CryptoUtil.generateIv();
            byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] cipherBytes = CryptoUtil.encryptCbc(plainBytes, keyBytes, iv);

            // 前置拼接 16 字节 IV 与密文内容
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
            return CryptoUtil.encodeBase64(combined);
        } catch (Exception e) {
            log.error("数据持久化加密失败: {}", e.getMessage(), e);
            throw new IllegalStateException("数据持久化加密失败", e);
        }
    }

    /**
     * 对 Base64 组合密文进行 AES 解密
     *
     * @param ciphertext 密文字符串
     * @return 解密后的真实明文；若密文为 null 或空串则原样返回
     */
    public static String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        try {
            byte[] combined = CryptoUtil.decodeBase64(ciphertext);
            if (combined == null || combined.length <= CryptoUtil.DEFAULT_CBC_IV_SIZE) {
                // 长度不足 IV 长度，判定为历史未加密数据，安全原样返回
                return ciphertext;
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, CryptoUtil.DEFAULT_CBC_IV_SIZE);
            byte[] cipherBytes = Arrays.copyOfRange(combined, CryptoUtil.DEFAULT_CBC_IV_SIZE, combined.length);
            byte[] keyBytes = resolveKeyBytes();
            byte[] decrypted = CryptoUtil.decryptCbc(cipherBytes, keyBytes, iv);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 历史明文数据或非标准密文解密失败时做防御性降级，记录日志并返回原值，杜绝系统崩溃
            log.warn("密文解密失败或数据为明文，安全回退至原始值: {}", e.getMessage());
            return ciphertext;
        }
    }

    /**
     * 解析密钥为合法的 16/24/32 字节 AES 密钥数组
     *
     * @return 16/24/32 字节数组
     */
    public static byte[] resolveKeyBytes() {
        if (globalSecretKey == null || globalSecretKey.isBlank()) {
            return DEFAULT_SECRET_KEY_BYTES;
        }
        byte[] bytes = globalSecretKey.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 16 || bytes.length == 24 || bytes.length == 32) {
            return bytes;
        }

        // 尝试 Base64 解码校验
        try {
            byte[] decoded = Base64.getDecoder().decode(globalSecretKey);
            if (decoded.length == 16 || decoded.length == 24 || decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // 忽略非 Base64 格式
        }

        // 自适应哈希派生：使用 SHA-256 计算 32 字节哈希值
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要算法在当前环境不可用", e);
        }
    }
}
