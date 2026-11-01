package io.github.atengk.boot.encrypt.wrapper;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.decryptor.ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigDecryptorFactory;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.exception.ConfigDecryptionException;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 加密属性源动态装饰器
 * 对 Spring 属性源进行透明代理，拦截 getProperty 调用并执行懒解密与并发安全缓存
 * 继承自 MapPropertySource，完美兼顾与 Spring Boot 内部处理器（如 SpringBootTestRandomPort 等）强转契约的兼容性
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class EncryptPropertySourceWrapper extends MapPropertySource {

    private static final Logger log = LoggerFactory.getLogger(EncryptPropertySourceWrapper.class);

    private final PropertySource<?> delegate;
    private final CiphertextParser parser;
    private volatile KeyDetector keyDetector;
    private final List<ConfigDecryptor> decryptors;
    private final EncryptAlgorithm defaultAlgorithm;
    private final boolean failOnError;

    private Consumer<String> registeredListener;
    private MultiKeyRegistry registeredRegistry;

    private final AtomicReference<ConcurrentHashMap<String, Object>> decryptedCacheRef =
            new AtomicReference<>(new ConcurrentHashMap<>());
    private final Set<String> encryptedPropertyNames = ConcurrentHashMap.newKeySet();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractSourceMap(PropertySource<?> delegate) {
        if (delegate instanceof MapPropertySource mapSource) {
            return mapSource.getSource();
        }
        return Collections.emptyMap();
    }

    public EncryptPropertySourceWrapper(PropertySource<?> delegate,
                                        CiphertextParser parser,
                                        KeyDetector keyDetector,
                                        List<ConfigDecryptor> decryptors,
                                        EncryptAlgorithm defaultAlgorithm,
                                        boolean failOnError) {
        super(delegate.getName(), extractSourceMap(delegate));
        this.delegate = delegate;
        this.parser = parser != null ? parser : new CiphertextParser();
        this.keyDetector = keyDetector != null ? keyDetector : new KeyDetector();
        this.decryptors = ConfigDecryptorFactory.createDefaultDecryptors(decryptors);
        this.defaultAlgorithm = defaultAlgorithm != null ? defaultAlgorithm : EncryptAlgorithm.AES_GCM;
        this.failOnError = failOnError;
        registerCacheClearingListener(this.keyDetector);
    }

    public EncryptPropertySourceWrapper(PropertySource<?> delegate, KeyDetector keyDetector) {
        this(delegate, new CiphertextParser(), keyDetector, null, EncryptAlgorithm.AES_GCM, true);
    }

    public EncryptPropertySourceWrapper(PropertySource<?> delegate, KeyDetector keyDetector, EncryptAlgorithm defaultAlgorithm) {
        this(delegate, new CiphertextParser(), keyDetector, null, defaultAlgorithm, true);
    }

    public EncryptPropertySourceWrapper(PropertySource<?> delegate,
                                        CiphertextParser parser,
                                        io.github.atengk.boot.encrypt.key.MultiKeyRegistry keyRegistry,
                                        List<ConfigDecryptor> decryptors,
                                        EncryptAlgorithm defaultAlgorithm,
                                        boolean failOnError) {
        this(delegate, parser, new KeyDetector(keyRegistry), decryptors, defaultAlgorithm, failOnError);
    }

    public EncryptPropertySourceWrapper(PropertySource<?> delegate, io.github.atengk.boot.encrypt.key.MultiKeyRegistry keyRegistry) {
        this(delegate, new CiphertextParser(), new KeyDetector(keyRegistry), null, EncryptAlgorithm.AES_GCM, true);
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

        encryptedPropertyNames.add(name);

        // 优先从内存缓存中获取已解密值
        ConcurrentHashMap<String, Object> cache = decryptedCacheRef.get();
        Object cached = cache.get(name);
        if (cached != null) {
            return cached;
        }

        // 按需懒解密并存入缓存
        try {
            String decrypted = parser.replaceTokens(strVal, token -> {
                String keyId = token.getKeyId();
                String password = keyDetector.getPassword(keyId);
                if (password == null || password.trim().isEmpty()) {
                    throw new ConfigDecryptionException(name, keyId, defaultAlgorithm.getCode(),
                            new IllegalStateException(String.format("缺失解密主密钥 [KeyId: '%s']",
                                    keyId != null ? keyId : "default")));
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
            cache.put(name, decrypted);
            return decrypted;
        } catch (ConfigDecryptionException e) {
            if (failOnError) {
                throw e;
            }
            return rawValue;
        } catch (Exception e) {
            ConfigDecryptionException ex = new ConfigDecryptionException(name,
                    e.getMessage() != null ? e.getMessage() : "未知解密异常", e);
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
        decryptedCacheRef.set(new ConcurrentHashMap<>());
        encryptedPropertyNames.clear();
    }

    public PropertySource<?> getDelegate() {
        return delegate;
    }

    public KeyDetector getKeyDetector() {
        return keyDetector;
    }

    /**
     * 更新密钥探测器并清空解密缓存（用于后置阶段或动态刷新时同步最新密钥）
     */
    public void updateKeyDetector(KeyDetector newKeyDetector) {
        if (newKeyDetector != null) {
            this.keyDetector = newKeyDetector;
            registerCacheClearingListener(newKeyDetector);
            clearCache();
        }
    }

    private synchronized void registerCacheClearingListener(KeyDetector detector) {
        if (detector == null || detector.getRegistry() == null) {
            return;
        }
        MultiKeyRegistry targetRegistry = detector.getRegistry();
        if (this.registeredRegistry == targetRegistry && this.registeredListener != null) {
            return;
        }
        if (this.registeredRegistry != null && this.registeredListener != null) {
            this.registeredRegistry.removeChangeListener(this.registeredListener);
        }
        this.registeredRegistry = targetRegistry;
        this.registeredListener = keyId -> {
            log.info("[ateng-boot-encrypt] 检测到密钥变更或轮换 [KeyId: {}]，立即原子清空属性源解密缓存: {}",
                    keyId, delegate.getName());
            clearCache();
        };
        this.registeredRegistry.addChangeListener(this.registeredListener);
    }

    /**
     * 判断指定属性是否属于被加密的密文属性
     *
     * @param name 属性名称
     * @return 若原始值带有密文标记则返回 true
     */
    public boolean isEncryptedProperty(String name) {
        if (encryptedPropertyNames.contains(name)) {
            return true;
        }
        Object raw = delegate.getProperty(name);
        if (raw instanceof String str && parser.hasEncrypted(str)) {
            encryptedPropertyNames.add(name);
            return true;
        }
        return false;
    }

    /**
     * 获取当前已被识别的所有加密属性名称集合
     *
     * @return 只读属性名集合
     */
    public Set<String> getEncryptedPropertyNames() {
        return Collections.unmodifiableSet(encryptedPropertyNames);
    }
}
