package io.github.atengk.boot.encrypt.template;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.decryptor.AesCbcConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigEncryptor;
import io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm2ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm4ConfigDecryptor;
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
    private final MultiKeyRegistry keyRegistry;

    @Getter
    private final CiphertextParser parser;

    @Getter
    private final List<ConfigDecryptor> decryptors;

    public EncryptTemplate(EncryptProperties properties,
                           MultiKeyRegistry keyRegistry,
                           CiphertextParser parser,
                           List<ConfigDecryptor> customDecryptors) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.keyRegistry = Objects.requireNonNull(keyRegistry, "keyRegistry 不能为空");
        this.parser = Objects.requireNonNull(parser, "parser 不能为空");
        this.decryptors = initDecryptors(customDecryptors);
    }

    public EncryptTemplate(EncryptProperties properties,
                           MultiKeyRegistry keyRegistry,
                           CiphertextParser parser) {
        this(properties, keyRegistry, parser, null);
    }

    private static List<ConfigDecryptor> initDecryptors(List<ConfigDecryptor> customDecryptors) {
        List<ConfigDecryptor> list = new ArrayList<>();
        if (customDecryptors != null) {
            list.addAll(customDecryptors);
        }
        // 通过 Java ServiceLoader 发现外部 SPI 扩展解密器
        ServiceLoader<ConfigDecryptor> serviceLoader = ServiceLoader.load(ConfigDecryptor.class);
        for (ConfigDecryptor spiDecryptor : serviceLoader) {
            if (list.stream().noneMatch(d -> d.getClass().equals(spiDecryptor.getClass()))) {
                list.add(spiDecryptor);
            }
        }
        // 注册默认纯 JDK 原生解密器
        if (list.stream().noneMatch(d -> d.supports(EncryptAlgorithm.AES_GCM))) {
            list.add(new AesGcmConfigDecryptor());
        }
        if (list.stream().noneMatch(d -> d.supports(EncryptAlgorithm.AES_CBC))) {
            list.add(new AesCbcConfigDecryptor());
        }
        if (list.stream().noneMatch(d -> d.supports(EncryptAlgorithm.SM4_CBC))) {
            list.add(new Sm4ConfigDecryptor());
        }
        if (list.stream().noneMatch(d -> d.supports(EncryptAlgorithm.RSA))) {
            list.add(new RsaConfigDecryptor());
        }
        if (list.stream().noneMatch(d -> d.supports(EncryptAlgorithm.SM2))) {
            list.add(new Sm2ConfigDecryptor());
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 使用全局默认密钥与默认算法对明文进行加密，并包装为标准 ENC(...) 密文标记
     *
     * @param plaintext 明文字符串
     * @return 格式化后的密文标记，如 ENC(...)；入参为 null 时返回 null
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        String rawCipher = encryptRaw(plaintext);
        EncryptAlgorithm algo = properties.getAlgorithm() != null ? properties.getAlgorithm() : EncryptAlgorithm.AES_GCM;
        return formatToken(null, algo, rawCipher);
    }

    /**
     * 使用全局默认密钥与默认算法加密，返回裸 Base64 密文字符串（不带 ENC 前后缀）
     *
     * @param plaintext 明文字符串
     * @return 包含 IV 的 Base64 密文；入参为 null 时返回 null
     */
    public String encryptRaw(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        String password = resolvePassword(null);
        EncryptAlgorithm algo = properties.getAlgorithm() != null ? properties.getAlgorithm() : EncryptAlgorithm.AES_GCM;
        return ConfigEncryptor.encrypt(plaintext, password, algo);
    }

    /**
     * 指定密钥别名与加密算法对明文进行加密，并包装为标准 ENC[keyId](algo@...) 密文标记
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
        String rawCipher = encryptRaw(plaintext, keyId, algorithm);
        EncryptAlgorithm effectiveAlgo = algorithm != null ? algorithm : properties.getAlgorithm();
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
        EncryptAlgorithm effectiveAlgo = algorithm != null ? algorithm : properties.getAlgorithm();
        return ConfigEncryptor.encrypt(plaintext, password, effectiveAlgo);
    }

    /**
     * 自动解密密文字符串（支持标准 ENC(...) 标记串、内嵌密文复合串及纯 Base64 裸密文）
     * 遵循 Fail-Fast 铁律：解密失败时抛出受管业务异常，绝不吞咽异常掩盖凭据错误
     *
     * @param ciphertext 密文字符串
     * @return 解密还原后的真实明文字符串；入参为 null 时返回 null
     * @throws ConfigDecryptionException 解密失败时抛出语义化异常并保留原始异常因果链
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        if (ciphertext.trim().isEmpty()) {
            return ciphertext;
        }

        // 1. 若文本中包含 ENC(...) 密文标记，使用 parser 进行令牌提取并流式替换
        if (parser.hasEncrypted(ciphertext)) {
            return parser.replaceTokens(ciphertext, token -> {
                String keyId = token.getKeyId();
                String password = resolvePassword(keyId);
                EncryptAlgorithm algo = token.getAlgorithm() != null ? token.getAlgorithm() : properties.getAlgorithm();
                if (algo == null) {
                    algo = EncryptAlgorithm.AES_GCM;
                }
                ConfigDecryptor decryptor = findDecryptor(algo);
                if (decryptor == null) {
                    throw new UnsupportedOperationException("未找到支持算法的解密器: " + algo);
                }
                try {
                    return decryptor.decrypt(token.getCiphertext(), password);
                } catch (Exception e) {
                    throw new ConfigDecryptionException("token", keyId, algo.getCode(), e);
                }
            });
        }

        // 2. 若文本不带 ENC 标记，作为纯 Base64 裸密文按环境默认算法与主密钥执行解密
        EncryptAlgorithm defaultAlgo = properties.getAlgorithm() != null ? properties.getAlgorithm() : EncryptAlgorithm.AES_GCM;
        String password = resolvePassword(null);
        ConfigDecryptor decryptor = findDecryptor(defaultAlgo);
        if (decryptor == null) {
            throw new UnsupportedOperationException("未找到支持默认算法的解密器: " + defaultAlgo);
        }
        try {
            return decryptor.decrypt(ciphertext.trim(), password);
        } catch (ConfigDecryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigDecryptionException("raw", null, defaultAlgo.getCode(), e);
        }
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
        try {
            return decrypt(ciphertext);
        } catch (Exception e) {
            log.trace("[ateng-boot-encrypt] 文本解密失败，容错返回原始文本: {}", e.getMessage());
            return ciphertext;
        }
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
     * 显式指定密钥别名与算法解密裸密文字符串
     *
     * @param ciphertext 密文字符串
     * @param keyId     密钥别名
     * @param algorithm 解密算法
     * @return 还原后的真实明文字符串
     * @throws ConfigDecryptionException 解密失败时抛出异常
     */
    public String decrypt(String ciphertext, String keyId, EncryptAlgorithm algorithm) {
        if (ciphertext == null) {
            return null;
        }
        if (parser.hasEncrypted(ciphertext)) {
            return decrypt(ciphertext);
        }
        EncryptAlgorithm effectiveAlgo = algorithm != null ? algorithm : properties.getAlgorithm();
        if (effectiveAlgo == null) {
            effectiveAlgo = EncryptAlgorithm.AES_GCM;
        }
        String password = resolvePassword(keyId);
        ConfigDecryptor decryptor = findDecryptor(effectiveAlgo);
        if (decryptor == null) {
            throw new UnsupportedOperationException("未找到支持算法的解密器: " + effectiveAlgo);
        }
        try {
            return decryptor.decrypt(ciphertext.trim(), password);
        } catch (ConfigDecryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigDecryptionException("raw", keyId, effectiveAlgo.getCode(), e);
        }
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
     * 将密文内容格式化为符合当前环境规范的标准 ENC[...] 标记
     *
     * @param keyId      密钥别名
     * @param algorithm  实际加密算法
     * @param ciphertext 密文内容
     * @return 格式化后的 Token，如 ENC(CZA...) 或 ENC[db](SM4-CBC@...)
     */
    public String formatToken(String keyId, EncryptAlgorithm algorithm, String ciphertext) {
        EncryptAlgorithm baseAlgo = properties.getAlgorithm() != null ? properties.getAlgorithm() : EncryptAlgorithm.AES_GCM;
        return CiphertextParser.formatToken(keyId, algorithm, baseAlgo, ciphertext);
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

    private String resolvePassword(String keyId) {
        String password = keyRegistry.getKeyOrDefault(keyId);
        if (password == null || password.trim().isEmpty()) {
            throw new ConfigDecryptionException("raw", keyId,
                    properties.getAlgorithm() != null ? properties.getAlgorithm().getCode() : "unknown",
                    new IllegalStateException(String.format("缺失解密主密钥 [KeyId: '%s']，请通过环境变量或启动参数注入",
                            keyId != null ? keyId : "default")));
        }
        return password.trim();
    }
}
