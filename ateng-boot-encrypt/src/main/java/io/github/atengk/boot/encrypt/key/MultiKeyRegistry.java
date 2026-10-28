package io.github.atengk.boot.encrypt.key;

import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多密钥命名注册表与动态轮换管理器
 * 支持管理全局默认密钥与多个业务命名密钥（KeyId），用于实现分域解密与平滑零停机密钥轮换
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class MultiKeyRegistry {

    public static final String DEFAULT_KEY_ALIAS = "default";

    private final Map<String, String> keyMap = new ConcurrentHashMap<>();
    private volatile String defaultKey;

    public MultiKeyRegistry() {
    }

    public MultiKeyRegistry(String defaultKey) {
        this.defaultKey = (defaultKey != null && !defaultKey.trim().isEmpty()) ? defaultKey.trim() : null;
    }

    public MultiKeyRegistry(String defaultKey, Map<String, String> initialKeys) {
        this(defaultKey);
        if (initialKeys != null) {
            initialKeys.forEach(this::registerKey);
        }
    }

    /**
     * 注册或更新指定别名的命名密钥
     *
     * @param keyId    密钥唯一别名（不区分大小写，自动修剪空白）
     * @param password 密钥内容口令
     */
    public void registerKey(String keyId, String password) {
        if (keyId == null || keyId.trim().isEmpty()) {
            throw new IllegalArgumentException("keyId 不能为空");
        }
        String normalizedKeyId = keyId.trim();
        if (DEFAULT_KEY_ALIAS.equalsIgnoreCase(normalizedKeyId)) {
            this.defaultKey = (password != null && !password.trim().isEmpty()) ? password.trim() : null;
            return;
        }
        if (password == null || password.trim().isEmpty()) {
            keyMap.remove(normalizedKeyId);
        } else {
            keyMap.put(normalizedKeyId, password.trim());
        }
    }

    /**
     * 注销指定别名的命名密钥
     *
     * @param keyId 密钥别名
     * @return 被移除的旧密钥，若不存在返回 null
     */
    public String unregisterKey(String keyId) {
        if (keyId == null || keyId.trim().isEmpty()) {
            return null;
        }
        String normalizedKeyId = keyId.trim();
        if (DEFAULT_KEY_ALIAS.equalsIgnoreCase(normalizedKeyId)) {
            String old = this.defaultKey;
            this.defaultKey = null;
            return old;
        }
        return keyMap.remove(normalizedKeyId);
    }

    /**
     * 获取指定 keyId 对应的解密密钥
     * 若 keyId 为 null、空或 "default"，返回全局默认密钥；否则返回对应的命名密钥
     *
     * @param keyId 密钥别名
     * @return 密钥字符串，若不存在则返回 null
     */
    public String getKey(String keyId) {
        if (keyId == null || keyId.trim().isEmpty() || DEFAULT_KEY_ALIAS.equalsIgnoreCase(keyId.trim())) {
            return defaultKey;
        }
        return keyMap.get(keyId.trim());
    }

    /**
     * 获取指定 keyId 对应的解密密钥，若未找到则安全回退到默认全局密钥
     *
     * @param keyId 密钥别名
     * @return 匹配到的密钥或全局默认密钥
     */
    public String getKeyOrDefault(String keyId) {
        String key = getKey(keyId);
        return key != null ? key : defaultKey;
    }

    /**
     * 判断是否已注册指定别名的密钥
     *
     * @param keyId 密钥别名
     * @return 若存在有效密钥返回 true
     */
    public boolean hasKey(String keyId) {
        if (keyId == null || keyId.trim().isEmpty() || DEFAULT_KEY_ALIAS.equalsIgnoreCase(keyId.trim())) {
            return defaultKey != null;
        }
        return keyMap.containsKey(keyId.trim());
    }

    /**
     * 平滑轮换默认全局主密钥
     *
     * @param newDefaultKey 新的全局默认主密钥
     */
    public void rotateDefaultKey(String newDefaultKey) {
        this.defaultKey = (newDefaultKey != null && !newDefaultKey.trim().isEmpty()) ? newDefaultKey.trim() : null;
    }

    /**
     * 获取当前默认全局主密钥
     *
     * @return 全局主密钥
     */
    public String getDefaultKey() {
        return defaultKey;
    }

    /**
     * 设置当前默认全局主密钥
     *
     * @param defaultKey 全局主密钥
     */
    public void setDefaultKey(String defaultKey) {
        this.defaultKey = (defaultKey != null && !defaultKey.trim().isEmpty()) ? defaultKey.trim() : null;
    }

    /**
     * 获取所有已注册的命名密钥別名集合
     *
     * @return 只读别名集合
     */
    public Set<String> getKeyIds() {
        return Collections.unmodifiableSet(keyMap.keySet());
    }

    /**
     * 获取所有命名密钥的只读映射副本
     *
     * @return 密钥映射副本
     */
    public Map<String, String> getAllKeys() {
        return Collections.unmodifiableMap(keyMap);
    }

    /**
     * 清空所有命名密钥与默认密钥
     */
    public void clear() {
        keyMap.clear();
        defaultKey = null;
    }

    /**
     * 当前已注册的命名密钥总数（不含默认密钥）
     */
    public int size() {
        return keyMap.size();
    }

    /**
     * 从配置属性类快速构建多密钥注册表
     *
     * @param properties 配置属性
     * @return 初始化的 MultiKeyRegistry 实例
     */
    public static MultiKeyRegistry fromProperties(EncryptProperties properties) {
        if (properties == null) {
            return new MultiKeyRegistry();
        }
        MultiKeyRegistry registry = new MultiKeyRegistry(properties.getPassword());
        if (properties.getKeys() != null) {
            properties.getKeys().forEach(registry::registerKey);
        }
        return registry;
    }

    /**
     * 从 Spring Environment 中抽取主密钥与命名密钥构建多密钥注册表
     *
     * @param environment Spring 运行环境
     * @return 初始化的 MultiKeyRegistry 实例
     */
    public static MultiKeyRegistry fromEnvironment(ConfigurableEnvironment environment) {
        MultiKeyRegistry registry = new MultiKeyRegistry();
        if (environment == null) {
            registry.setDefaultKey(KeyDetector.resolveDefaultPassword(null));
            return registry;
        }

        // 1. 获取默认主密钥
        String defaultPassword = KeyDetector.resolveDefaultPassword(environment);
        registry.setDefaultKey(defaultPassword);

        // 2. 遍历扫描命名密钥
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerableSource) {
                for (String propName : enumerableSource.getPropertyNames()) {
                    if (propName.startsWith(KeyDetector.KEYS_PREFIX)) {
                        String keyId = propName.substring(KeyDetector.KEYS_PREFIX.length()).trim();
                        Object val = source.getProperty(propName);
                        if (val != null && !keyId.isEmpty()) {
                            registry.registerKey(keyId, val.toString().trim());
                        }
                    }
                }
            }
        }
        return registry;
    }
}
