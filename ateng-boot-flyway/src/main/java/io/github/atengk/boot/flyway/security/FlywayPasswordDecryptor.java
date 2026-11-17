package io.github.atengk.boot.flyway.security;

import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.HexUtil;
import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 基于 Core 模块 {@link CryptoUtil} 的 Flyway DDL 凭据瞬态解密器
 * <p>
 * 仅在内存中对 {@code enc:sm4:} 与 {@code enc:aes:} 前缀密文口令执行瞬态解密，
 * 绝不向持久化配置、日志或异常堆栈输出明文口令或原始密文载荷。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class FlywayPasswordDecryptor {

    private static final Logger log = LoggerFactory.getLogger(FlywayPasswordDecryptor.class);

    public static final String PREFIX_AES = "enc:aes:";
    public static final String PREFIX_SM4 = "enc:sm4:";

    private final AtengFlywayProperties.CredentialProperties credentialProperties;

    /**
     * 使用隔离凭据配置构造解密器
     *
     * @param credentialProperties 隔离 DDL 凭据配置属性
     */
    public FlywayPasswordDecryptor(AtengFlywayProperties.CredentialProperties credentialProperties) {
        this.credentialProperties = Objects.requireNonNull(credentialProperties, "credentialProperties 不能为空");
    }

    /**
     * 判断口令文本是否携带受支持的加密前缀
     *
     * @param rawPassword 原始配置口令
     * @return true 表示需要解密
     */
    public boolean supports(String rawPassword) {
        if (!StringUtils.hasText(rawPassword)) {
            return false;
        }
        return rawPassword.startsWith(PREFIX_AES) || rawPassword.startsWith(PREFIX_SM4);
    }

    /**
     * 按需瞬态解密凭据口令（明文直通，密文按前缀分发解密并保留因果链）
     *
     * @param rawPassword 原始配置口令或密文
     * @return 内存明文口令
     */
    public String decrypt(String rawPassword) {
        if (!supports(rawPassword)) {
            return rawPassword;
        }
        try {
            String plaintext;
            if (rawPassword.startsWith(PREFIX_AES)) {
                plaintext = decryptAes(rawPassword.substring(PREFIX_AES.length()));
            } else if (rawPassword.startsWith(PREFIX_SM4)) {
                plaintext = decryptSm4(rawPassword.substring(PREFIX_SM4.length()));
            } else {
                return rawPassword;
            }
            if (log.isDebugEnabled()) {
                log.debug("ateng-boot-flyway DDL 凭据口令瞬态解密成功 [masked={}]", DesensitizeUtil.password(rawPassword));
            }
            return plaintext;
        } catch (FlywayException e) {
            throw e;
        } catch (Exception e) {
            String masked = DesensitizeUtil.password(rawPassword);
            throw new FlywayException(
                    FlywayResultCode.CREDENTIAL_DECRYPT_FAILED,
                    "DDL 迁移凭据口令解密失败 [masked=" + masked + ", reason="
                            + FlywayConfigurationSupport.sanitizeSensitiveText(e.getClass().getSimpleName()) + "]",
                    e
            );
        }
    }

    private String decryptAes(String payload) {
        String[] parts = payload.split(":", -1);
        if (parts.length == 3) {
            byte[] key = parseMaterial(parts[0], false);
            byte[] iv = parseMaterial(parts[1], true);
            return CryptoUtil.decryptCbc(parts[2], key, iv);
        }
        if (parts.length == 1) {
            byte[] key = resolveGlobalKey();
            byte[] iv = resolveGlobalIv();
            return CryptoUtil.decryptCbc(parts[0], key, iv);
        }
        throw new IllegalArgumentException("非法 AES 密文格式");
    }

    private String decryptSm4(String payload) {
        String[] parts = payload.split(":", -1);
        if (parts.length == 3) {
            byte[] key = parseMaterial(parts[0], false);
            byte[] iv = parseMaterial(parts[1], true);
            return CryptoUtil.decryptSm4Cbc(parts[2], key, iv);
        }
        if (parts.length == 2) {
            byte[] key = parseMaterial(parts[0], false);
            return CryptoUtil.decryptSm4Ecb(parts[1], key);
        }
        if (parts.length == 1) {
            byte[] key = resolveGlobalKey();
            if (StringUtils.hasText(credentialProperties.getIv())) {
                byte[] iv = resolveGlobalIv();
                return CryptoUtil.decryptSm4Cbc(parts[0], key, iv);
            }
            return CryptoUtil.decryptSm4Ecb(parts[0], key);
        }
        throw new IllegalArgumentException("非法 SM4 密文格式");
    }

    private byte[] resolveGlobalKey() {
        String globalKey = credentialProperties.getSecretKey();
        if (!StringUtils.hasText(globalKey)) {
            throw new IllegalStateException("未配置全局解密密钥 ateng.boot.flyway.credential.secret-key");
        }
        return parseMaterial(globalKey, false);
    }

    private byte[] resolveGlobalIv() {
        String globalIv = credentialProperties.getIv();
        if (!StringUtils.hasText(globalIv)) {
            return new byte[CryptoUtil.DEFAULT_CBC_IV_SIZE];
        }
        return parseMaterial(globalIv, true);
    }

    private byte[] parseMaterial(String raw, boolean enforceIvLength) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("密钥或 IV 材料不能为空");
        }
        String trimmed = raw.trim();
        if (isEvenHex(trimmed)) {
            byte[] hexBytes = HexUtil.decodeHex(trimmed);
            if (isValidLength(hexBytes.length, enforceIvLength)) {
                return hexBytes;
            }
        }
        try {
            byte[] base64Bytes = CryptoUtil.decodeBase64(trimmed);
            if (base64Bytes != null && isValidLength(base64Bytes.length, enforceIvLength)) {
                return base64Bytes;
            }
        } catch (Exception ignored) {
            // 非 Base64 编码时回退至 UTF-8 原始字节检验
        }
        byte[] utf8 = trimmed.getBytes(StandardCharsets.UTF_8);
        if (isValidLength(utf8.length, enforceIvLength)) {
            return utf8;
        }
        throw new IllegalArgumentException("密钥或 IV 长度不合法: " + utf8.length);
    }

    private boolean isEvenHex(String text) {
        int len = text.length();
        if ((len & 1) != 0 || (len != 32 && len != 48 && len != 64)) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidLength(int length, boolean enforceIvLength) {
        if (enforceIvLength) {
            return length == CryptoUtil.DEFAULT_CBC_IV_SIZE;
        }
        return length == 16 || length == 24 || length == 32;
    }
}
