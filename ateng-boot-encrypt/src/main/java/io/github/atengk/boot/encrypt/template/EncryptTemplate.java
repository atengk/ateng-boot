package io.github.atengk.boot.encrypt.template;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.decryptor.ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigDecryptorFactory;
import io.github.atengk.boot.encrypt.decryptor.ConfigEncryptor;
import io.github.atengk.boot.encrypt.exception.ConfigDecryptionException;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * 统一编程式加解密受管门面
 * Spring 受管单例 Bean，自动整合默认算法、前后缀解析器与多密钥注册表，为业务层提供简单对称的编程式加解密、密文识别与 Token 组装能力
 *
 * @author Ateng
 * @since 2026-11-01
 */
public class EncryptTemplate {

    private static final Logger log = LoggerFactory.getLogger(EncryptTemplate.class);

    @Getter
    private final EncryptProperties properties;

    @Getter
    private final EncryptAlgorithm defaultAlgorithm;

    @Getter
    private final MultiKeyRegistry keyRegistry;

    @Getter
    private final CiphertextParser parser;

    @Getter
    private final List<ConfigDecryptor> decryptors;

    public EncryptTemplate(EncryptProperties properties,
                           EncryptAlgorithm defaultAlgorithm,
                           MultiKeyRegistry keyRegistry,
                           CiphertextParser parser,
                           List<ConfigDecryptor> customDecryptors) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.defaultAlgorithm = defaultAlgorithm != null ? defaultAlgorithm :
                (properties.getAlgorithm() != null ? properties.getAlgorithm() : EncryptAlgorithm.AES_GCM);
        this.keyRegistry = Objects.requireNonNull(keyRegistry, "keyRegistry 不能为空");
        this.parser = Objects.requireNonNull(parser, "parser 不能为空");
        this.decryptors = ConfigDecryptorFactory.createDefaultDecryptors(
                customDecryptors, properties.getRetry());
    }

    public EncryptTemplate(EncryptProperties properties,
                           MultiKeyRegistry keyRegistry,
                           CiphertextParser parser,
                           List<ConfigDecryptor> customDecryptors) {
        this(properties, null, keyRegistry, parser, customDecryptors);
    }

    public EncryptTemplate(EncryptProperties properties,
                           MultiKeyRegistry keyRegistry,
                           CiphertextParser parser) {
        this(properties, null, keyRegistry, parser, null);
    }

    /**
     * 使用全局默认密钥与默认算法对明文进行加密，并包装为当前解析器配置的密文标记
     *
     * @param plaintext 明文字符串
     * @return 格式化后的密文标记，如 ENC(...)；入参为 null 时返回 null
     */
    public String encrypt(String plaintext) {
        return encrypt(plaintext, null, null);
    }

    /**
     * 使用指定命名密钥与环境默认算法对明文进行加密，并包装为当前解析器配置的密文标记
     *
     * @param plaintext 明文字符串
     * @param keyId     密钥别名（null 或 "default" 对应默认全局密钥）
     * @return 格式化后的密文标记，如 ENC[keyId](...)；入参为 null 时返回 null
     */
    public String encrypt(String plaintext, String keyId) {
        return encrypt(plaintext, keyId, null);
    }

    /**
     * 使用全局默认密钥与默认算法加密，返回裸 Base64 密文字符串（不带 ENC 前后缀）
     *
     * @param plaintext 明文字符串
     * @return 包含 IV 的 Base64 密文；入参为 null 时返回 null
     */
    public String encryptRaw(String plaintext) {
        return encryptRaw(plaintext, null, null);
    }

    /**
     * 使用指定命名密钥与环境默认算法加密，返回裸 Base64 密文字符串（不带 ENC 前后缀）
     *
     * @param plaintext 明文字符串
     * @param keyId     密钥别名（null 或 "default" 对应默认全局密钥）
     * @return 包含 IV 的 Base64 密文；入参为 null 时返回 null
     */
    public String encryptRaw(String plaintext, String keyId) {
        return encryptRaw(plaintext, keyId, null);
    }

    /**
     * 指定密钥别名与加密算法对明文进行加密，并包装为当前解析器配置的密文标记
     *
     * @param plaintext 明文字符串
     * @param keyId     密钥别名（null 或 "default" 对应默认全局密钥）
     * @param algorithm 加密算法（为 null 时使用环境默认算法）
     * @return 格式化后的密文标记；入参为 null 时返回 null
     */
    public String encrypt(String plaintext, String keyId, EncryptAlgorithm algorithm) {
        if (plaintext == null) {
            return null;
        }
        EncryptAlgorithm effectiveAlgo = algorithm != null ? algorithm : resolveDefaultAlgorithm();
        String rawCipher = encryptRaw(plaintext, keyId, effectiveAlgo);
        return formatToken(keyId, effectiveAlgo, rawCipher);
    }

    /**
     * 指定密钥别名与加密算法加密，返回裸 Base64 密文字符串
     *
     * @param plaintext 明文字符串
     * @param keyId     密钥别名
     * @param algorithm 加密算法
     * @return 包含 IV 的 Base64 密文；入参为 null 时返回 null
     */
    public String encryptRaw(String plaintext, String keyId, EncryptAlgorithm algorithm) {
        if (plaintext == null) {
            return null;
        }
        String password = resolvePassword(keyId);
        EncryptAlgorithm effectiveAlgo = algorithm != null ? algorithm : resolveDefaultAlgorithm();
        return ConfigEncryptor.encrypt(plaintext, password, effectiveAlgo);
    }

    /**
     * 自动解密密文字符串（支持标准 ENC(...) 标记串、自定义前后缀标记串、内嵌密文复合串及纯 Base64 裸密文）
     * 遵循 Fail-Fast 铁律：解密失败时抛出受管业务异常，绝不吞咽异常掩盖凭据错误
     *
     * @param ciphertext 密文字符串
     * @return 解密还原后的真实明文字符串；入参为 null 时返回 null
     * @throws ConfigDecryptionException 解密失败时抛出语义化异常并保留原始异常因果链
     */
    public String decrypt(String ciphertext) {
        return decrypt(ciphertext, null, null);
    }

    /**
     * 使用指定命名密钥与环境默认算法解密密文字符串（支持密文标记串与纯 Base64 裸密文）
     * 遵循 Fail-Fast 铁律：解密失败或缺失密钥时抛出保留因果链的 ConfigDecryptionException
     *
     * @param ciphertext 密文字符串
     * @param keyId      密钥别名（当密文标记内未携带 [keyId] 或入参为裸密文时生效）
     * @return 解密还原后的真实明文字符串；入参为 null 时返回 null
     * @throws ConfigDecryptionException 解密失败时抛出语义化异常并保留原始异常因果链
     */
    public String decrypt(String ciphertext, String keyId) {
        return decrypt(ciphertext, keyId, null);
    }

    /**
     * 宽容解密：解密失败或非有效密文时安全保底返回原始输入字符串（容错逃生舱）
     *
     * @param ciphertext 待解密密文或可能为明文的普通文本
     * @return 解密成功返回明文，解密异常或入参非密文时返回 original 入参
     */
    public String decryptOrOriginal(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        return decryptOrDefault(ciphertext, ciphertext);
    }

    /**
     * 宽容解密：解密失败时安全返回指定的默认值
     *
     * @param ciphertext   待解密密文字符串
     * @param defaultValue 解密失败时的回退默认值
     * @return 解密成功返回真实明文，解密失败返回 defaultValue
     */
    public String decryptOrDefault(String ciphertext, String defaultValue) {
        if (ciphertext == null) {
            return defaultValue;
        }
        try {
            return decrypt(ciphertext);
        } catch (Exception e) {
            log.trace("[ateng-boot-encrypt] 文本解密失败，回退到默认值: {}", e.getMessage());
            return defaultValue;
        }
    }

    /**
     * 显式指定密钥别名与算法解密密文字符串（支持密文标记串与纯 Base64 裸密文）
     *
     * @param ciphertext 密文字符串
     * @param keyId      密钥别名
     * @param algorithm  解密算法
     * @return 还原后的真实明文字符串
     * @throws ConfigDecryptionException 解密失败时抛出异常
     */
    public String decrypt(String ciphertext, String keyId, EncryptAlgorithm algorithm) {
        if (ciphertext == null) {
            return null;
        }
        if (ciphertext.trim().isEmpty()) {
            return ciphertext;
        }
        if (parser.hasEncrypted(ciphertext)) {
            return decryptTokens(ciphertext, keyId, algorithm);
        }
        return decryptPayload("raw", ciphertext.trim(), keyId, algorithm);
    }

    /**
     * 判断指定文本中是否包含需要解密的密文标记
     *
     * @param text 待检测文本
     * @return 若包含密文标记返回 true
     */
    public boolean isEncrypted(String text) {
        return parser.hasEncrypted(text);
    }

    /**
     * 将密文内容格式化为符合当前解析器前后缀与默认算法规范的标准密文标记
     *
     * @param keyId      密钥别名
     * @param algorithm  实际加密算法
     * @param ciphertext 密文内容
     * @return 格式化后的 Token，如 ENC(CZA...)、ENC[db](SM4-CBC@...) 或自定义前后缀标记
     */
    public String formatToken(String keyId, EncryptAlgorithm algorithm, String ciphertext) {
        EncryptAlgorithm baseAlgo = resolveDefaultAlgorithm();
        return parser.formatToken(baseAlgo, keyId, algorithm, ciphertext);
    }

    /**
     * 根据算法查找匹配的解密器
     */
    public ConfigDecryptor findDecryptor(EncryptAlgorithm algorithm) {
        if (algorithm == null) {
            return null;
        }
        for (ConfigDecryptor decryptor : decryptors) {
            if (decryptor.supports(algorithm)) {
                return decryptor;
            }
        }
        return null;
    }

    private String decryptTokens(String ciphertext, String fallbackKeyId, EncryptAlgorithm fallbackAlgorithm) {
        return parser.replaceTokens(ciphertext, token -> {
            String effectiveKeyId = (token.getKeyId() != null && !token.getKeyId().trim().isEmpty())
                    ? token.getKeyId()
                    : fallbackKeyId;
            EncryptAlgorithm effectiveAlgo = token.getAlgorithm() != null
                    ? token.getAlgorithm()
                    : fallbackAlgorithm;
            return decryptPayload("token", token.getCiphertext(), effectiveKeyId, effectiveAlgo);
        });
    }

    private String decryptPayload(String mode, String payload, String keyId, EncryptAlgorithm algorithm) {
        String password = resolvePassword(keyId);
        EncryptAlgorithm effectiveAlgo = algorithm != null ? algorithm : resolveDefaultAlgorithm();
        ConfigDecryptor decryptor = findDecryptor(effectiveAlgo);
        if (decryptor == null) {
            throw new UnsupportedOperationException("未找到支持算法的解密器: " + effectiveAlgo);
        }
        try {
            return decryptor.decrypt(payload, password);
        } catch (ConfigDecryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigDecryptionException(mode, keyId, effectiveAlgo.getCode(), e);
        }
    }

    private EncryptAlgorithm resolveDefaultAlgorithm() {
        if (defaultAlgorithm != null) {
            return defaultAlgorithm;
        }
        return properties.getAlgorithm() != null ? properties.getAlgorithm() : EncryptAlgorithm.AES_GCM;
    }

    private String resolvePassword(String keyId) {
        String password = keyRegistry.getKeyOrDefault(keyId);
        if (password == null || password.trim().isEmpty()) {
            throw new ConfigDecryptionException("raw", keyId,
                    resolveDefaultAlgorithm().getCode(),
                    new IllegalStateException(String.format("缺失解密主密钥 [KeyId: '%s']，请通过环境变量或启动参数注入",
                            keyId != null ? keyId : "default")));
        }
        return password.trim();
    }
}
