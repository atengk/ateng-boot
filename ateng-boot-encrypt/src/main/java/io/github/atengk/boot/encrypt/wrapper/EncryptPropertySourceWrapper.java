package io.github.atengk.boot.encrypt.wrapper;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.decryptor.AesCbcConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm4ConfigDecryptor;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.exception.ConfigDecryptionException;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 加密属性源动态装饰器
 * 对 Spring 属性源进行透明代理，拦截 getProperty 调用并执行懒解密与并发安全缓存
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class EncryptPropertySourceWrapper extends EnumerablePropertySource<PropertySource<?>> {

    private final PropertySource<?> delegate;
    private final CiphertextParser parser;
    private final KeyDetector keyDetector;
    private final List<ConfigDecryptor> decryptors;
    private final EncryptAlgorithm defaultAlgorithm;
    private final boolean failOnError;

    private final Map<String, Object> decryptedCache = new ConcurrentHashMap<>();

    public EncryptPropertySourceWrapper(PropertySource<?> delegate,
                                        CiphertextParser parser,
                                        KeyDetector keyDetector,
                                        List<ConfigDecryptor> decryptors,
                                        EncryptAlgorithm defaultAlgorithm,
                                        boolean failOnError) {
        super(delegate.getName(), delegate);
        this.delegate = delegate;
        this.parser = parser != null ? parser : new CiphertextParser();
        this.keyDetector = keyDetector != null ? keyDetector : new KeyDetector();
        this.decryptors = initDecryptors(decryptors);
        this.defaultAlgorithm = defaultAlgorithm != null ? defaultAlgorithm : EncryptAlgorithm.AES_GCM;
        this.failOnError = failOnError;
    }

    public EncryptPropertySourceWrapper(PropertySource<?> delegate, KeyDetector keyDetector) {
        this(delegate, new CiphertextParser(), keyDetector, null, EncryptAlgorithm.AES_GCM, true);
    }

    public EncryptPropertySourceWrapper(PropertySource<?> delegate, KeyDetector keyDetector, EncryptAlgorithm defaultAlgorithm) {
        this(delegate, new CiphertextParser(), keyDetector, null, defaultAlgorithm, true);
    }

    private static List<ConfigDecryptor> initDecryptors(List<ConfigDecryptor> customDecryptors) {
        List<ConfigDecryptor> list = new ArrayList<>();
        if (customDecryptors != null) {
            list.addAll(customDecryptors);
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
        return list;
    }

    @Override
    public Object getProperty(String name) {
        Object rawValue = delegate.getProperty(name);
        if (rawValue == null || !(rawValue instanceof String strVal)) {
            return rawValue;
        }

        // 若不包含密文标记，直接保真返回原值
        if (!parser.hasEncrypted(strVal)) {
            return rawValue;
        }

        // 优先从内存缓存中获取已解密值
        Object cached = decryptedCache.get(name);
        if (cached != null) {
            return cached;
        }

        // 按需懒解密并存入缓存
        try {
            String decrypted = parser.replaceTokens(strVal, token -> {
                String keyId = token.getKeyId();
                String password = keyDetector.getPassword(keyId);
                if (password == null || password.trim().isEmpty()) {
                    throw new IllegalStateException(String.format("缺失解密主密钥 [KeyId: '%s']",
                            keyId != null ? keyId : "default"));
                }
                EncryptAlgorithm algorithm = token.getAlgorithm() != null ? token.getAlgorithm() : defaultAlgorithm;
                ConfigDecryptor decryptor = findDecryptor(algorithm);
                if (decryptor == null) {
                    throw new UnsupportedOperationException("未找到支持算法的解密器: " + algorithm);
                }
                try {
                    return decryptor.decrypt(token.getCiphertext(), password);
                } catch (Exception e) {
                    throw new ConfigDecryptionException(name, keyId, algorithm.getCode(), e);
                }
            });
            decryptedCache.put(name, decrypted);
            return decrypted;
        } catch (ConfigDecryptionException e) {
            if (failOnError) {
                throw e;
            }
            return rawValue;
        } catch (Exception e) {
            ConfigDecryptionException ex = new ConfigDecryptionException(name, "未知解密异常", e);
            if (failOnError) {
                throw ex;
            }
            return rawValue;
        }
    }

    private ConfigDecryptor findDecryptor(EncryptAlgorithm algorithm) {
        for (ConfigDecryptor decryptor : decryptors) {
            if (decryptor.supports(algorithm)) {
                return decryptor;
            }
        }
        return null;
    }

    @Override
    public boolean containsProperty(String name) {
        return delegate.containsProperty(name);
    }

    @Override
    public String[] getPropertyNames() {
        if (delegate instanceof EnumerablePropertySource<?> enumerableSource) {
            return enumerableSource.getPropertyNames();
        }
        return new String[0];
    }

    /**
     * 清空解密缓存（用于配置热刷新时原子置换）
     */
    public void clearCache() {
        decryptedCache.clear();
    }

    public PropertySource<?> getDelegate() {
        return delegate;
    }
}
