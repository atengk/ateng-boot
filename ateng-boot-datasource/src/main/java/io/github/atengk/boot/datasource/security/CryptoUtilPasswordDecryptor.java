package io.github.atengk.boot.datasource.security;

import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.datasource.enums.DatasourceResultCode;
import io.github.atengk.boot.datasource.exception.DataSourceSwitchException;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 基于 Core 模块 {@link CryptoUtil} 的纯原生密码解密器
 * 100% 依托原生能力支持国密 SM4 (CBC/ECB) 与国际 AES (CBC) 密文密码的纯内存瞬态解密，
 * 绝不向持久化属性、配置文件或日志中泄露任何明文密码
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class CryptoUtilPasswordDecryptor implements DataSourcePasswordDecryptor {

    private static final Logger log = LoggerFactory.getLogger(CryptoUtilPasswordDecryptor.class);

    public static final String PREFIX_AES = "enc:aes:";
    public static final String PREFIX_SM4 = "enc:sm4:";

    private final DatasourceProperties datasourceProperties;

    public CryptoUtilPasswordDecryptor(DatasourceProperties datasourceProperties) {
        this.datasourceProperties = datasourceProperties;
    }

    @Override
    public boolean supports(String encryptedPassword) {
        if (encryptedPassword == null || encryptedPassword.isBlank()) {
            return false;
        }
        return encryptedPassword.startsWith(PREFIX_AES) || encryptedPassword.startsWith(PREFIX_SM4);
    }

    @Override
    public String decrypt(String encryptedPassword) {
        if (!supports(encryptedPassword)) {
            return encryptedPassword;
        }
        try {
            String decrypted;
            if (encryptedPassword.startsWith(PREFIX_AES)) {
                decrypted = decryptAes(encryptedPassword.substring(PREFIX_AES.length()));
            } else if (encryptedPassword.startsWith(PREFIX_SM4)) {
                decrypted = decryptSm4(encryptedPassword.substring(PREFIX_SM4.length()));
            } else {
                return encryptedPassword;
            }
            if (log.isDebugEnabled()) {
                log.debug("ateng-boot-datasource: 密码凭据解密成功 [masked={}]", DesensitizeUtil.password(encryptedPassword));
            }
            return decrypted;
        } catch (Exception e) {
            String masked = DesensitizeUtil.password(encryptedPassword);
            log.error("ateng-boot-datasource: 密码凭据解密失败 [masked={}]", masked, e);
            throw new DataSourceSwitchException("数据源密码凭据解密失败 [masked=" + masked + "]: " + e.getMessage(), e,
                    DatasourceResultCode.DATASOURCE_INIT_ERROR);
        }
    }

    private String decryptAes(String payload) {
        String[] parts = payload.split(":");
        if (parts.length == 3) {
            // 格式: enc:aes:<base64Key>:<base64Iv>:<base64Ciphertext>
            byte[] key = parseKey(parts[0]);
            byte[] iv = parseIv(parts[1], 16);
            return CryptoUtil.decryptCbc(parts[2], key, iv);
        } else if (parts.length == 1) {
            // 格式: enc:aes:<base64Ciphertext>，使用全局配置密钥
            byte[] key = getGlobalSecretKey();
            byte[] iv = getGlobalIv(16);
            return CryptoUtil.decryptCbc(parts[0], key, iv);
        } else {
            throw new IllegalArgumentException("非法 AES 密文格式，必须为 enc:aes:<ciphertext> 或 enc:aes:<key>:<iv>:<ciphertext>");
        }
    }

    private String decryptSm4(String payload) {
        String[] parts = payload.split(":");
        if (parts.length == 3) {
            // 格式: enc:sm4:<base64Key>:<base64Iv>:<base64Ciphertext> (CBC 模式)
            byte[] key = parseKey(parts[0]);
            byte[] iv = parseIv(parts[1], 16);
            return CryptoUtil.decryptSm4Cbc(parts[2], key, iv);
        } else if (parts.length == 2) {
            // 格式: enc:sm4:<base64Key>:<base64Ciphertext> (ECB 模式)
            byte[] key = parseKey(parts[0]);
            return CryptoUtil.decryptSm4Ecb(parts[1], key);
        } else if (parts.length == 1) {
            // 格式: enc:sm4:<base64Ciphertext>，使用全局配置密钥
            byte[] key = getGlobalSecretKey();
            if (datasourceProperties != null && datasourceProperties.getSecurity() != null &&
                    datasourceProperties.getSecurity().getIv() != null && !datasourceProperties.getSecurity().getIv().isBlank()) {
                byte[] iv = getGlobalIv(16);
                return CryptoUtil.decryptSm4Cbc(parts[0], key, iv);
            } else {
                return CryptoUtil.decryptSm4Ecb(parts[0], key);
            }
        } else {
            throw new IllegalArgumentException("非法 SM4 密文格式，必须为 enc:sm4:<ciphertext>、enc:sm4:<key>:<ciphertext> 或 enc:sm4:<key>:<iv>:<ciphertext>");
        }
    }

    private byte[] parseKey(String keyStr) {
        try {
            return CryptoUtil.decodeBase64(keyStr);
        } catch (Exception e) {
            return keyStr.getBytes(StandardCharsets.UTF_8);
        }
    }

    private byte[] parseIv(String ivStr, int expectedLength) {
        try {
            byte[] decoded = CryptoUtil.decodeBase64(ivStr);
            if (decoded != null && decoded.length == expectedLength) {
                return decoded;
            }
        } catch (Exception ignored) {
        }
        byte[] raw = ivStr.getBytes(StandardCharsets.UTF_8);
        if (raw.length != expectedLength) {
            throw new IllegalArgumentException("IV 字节长度非法，预期为 " + expectedLength + "，实际为: " + raw.length);
        }
        return raw;
    }

    private byte[] getGlobalSecretKey() {
        if (datasourceProperties == null || datasourceProperties.getSecurity() == null ||
                datasourceProperties.getSecurity().getSecretKey() == null || datasourceProperties.getSecurity().getSecretKey().isBlank()) {
            throw new IllegalStateException("未配置全局解密密钥 ateng.boot.datasource.security.secret-key，无法解密单段密文密码");
        }
        return parseKey(datasourceProperties.getSecurity().getSecretKey());
    }

    private byte[] getGlobalIv(int expectedLength) {
        if (datasourceProperties != null && datasourceProperties.getSecurity() != null &&
                datasourceProperties.getSecurity().getIv() != null && !datasourceProperties.getSecurity().getIv().isBlank()) {
            return parseIv(datasourceProperties.getSecurity().getIv(), expectedLength);
        }
        // 缺省 16 字节 IV
        return new byte[expectedLength];
    }
}
