package io.github.atengk.boot.encrypt.env;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.util.Locale;

/**
 * 早阶配置属性解析共享辅助工具
 * 统一收口启用开关、默认算法、失败阻断策略与密文解析器的环境变量/系统属性/配置源多级解析逻辑
 *
 * @author Ateng
 * @since 2026-11-19
 */
public final class EncryptEnvironmentSupport {

    public static final String PROPERTY_ENABLED = "ateng.boot.encrypt.enabled";
    public static final String ENV_ENABLED = "ATENG_BOOT_ENCRYPT_ENABLED";
    public static final String PROPERTY_ALGORITHM = "ateng.boot.encrypt.algorithm";
    public static final String ENV_ALGORITHM = "ATENG_BOOT_ENCRYPT_ALGORITHM";
    public static final String PROPERTY_FAIL_ON_ERROR = "ateng.boot.encrypt.fail-on-error";
    public static final String ENV_FAIL_ON_ERROR = "ATENG_BOOT_ENCRYPT_FAIL_ON_ERROR";
    public static final String PROPERTY_PREFIX = "ateng.boot.encrypt.prefix";
    public static final String ENV_PREFIX = "ATENG_BOOT_ENCRYPT_PREFIX";
    public static final String PROPERTY_SUFFIX = "ateng.boot.encrypt.suffix";
    public static final String ENV_SUFFIX = "ATENG_BOOT_ENCRYPT_SUFFIX";
    public static final String PROPERTY_COMPATIBLE_CIPHER = "ateng.boot.encrypt.compatible-cipher-prefix";
    public static final String ENV_COMPATIBLE_CIPHER = "ATENG_BOOT_ENCRYPT_COMPATIBLE_CIPHER_PREFIX";

    private EncryptEnvironmentSupport() {
    }

    /**
     * 解析字符串配置值（按 Environment -> 系统属性 -> 环境变量顺序回退）
     */
    public static String resolveStringProperty(ConfigurableEnvironment environment,
                                               String propertyName,
                                               String envName) {
        if (environment != null && environment.containsProperty(propertyName)) {
            return environment.getProperty(propertyName);
        }
        String sysProp = System.getProperty(propertyName);
        if (sysProp != null && !sysProp.trim().isEmpty()) {
            return sysProp;
        }
        if (envName != null) {
            String envVal = System.getenv(envName);
            if (envVal != null && !envVal.trim().isEmpty()) {
                return envVal;
            }
        }
        return null;
    }

    /**
     * 解析字符串配置值（优先系统属性/环境变量，其次指定 PropertySource，最后 Environment）
     */
    public static String resolveStringProperty(ConfigurableEnvironment environment,
                                               PropertySource<?> source,
                                               String propertyName,
                                               String envName) {
        String sysProp = System.getProperty(propertyName);
        if (sysProp != null && !sysProp.trim().isEmpty()) {
            return sysProp;
        }
        if (envName != null) {
            String envVal = System.getenv(envName);
            if (envVal != null && !envVal.trim().isEmpty()) {
                return envVal;
            }
        }
        if (source != null && source.containsProperty(propertyName)) {
            Object val = source.getProperty(propertyName);
            return val != null ? val.toString() : null;
        }
        if (environment != null && environment.containsProperty(propertyName)) {
            return environment.getProperty(propertyName);
        }
        return null;
    }

    /**
     * 解析布尔配置值（按 Environment -> 系统属性 -> 环境变量顺序回退）
     */
    public static boolean resolveBooleanProperty(ConfigurableEnvironment environment,
                                                 String propertyName,
                                                 String envName,
                                                 boolean defaultValue) {
        String raw = resolveStringProperty(environment, propertyName, envName);
        return parseBooleanValue(raw, defaultValue);
    }

    /**
     * 解析布尔配置值（优先系统属性/环境变量，其次指定 PropertySource，最后 Environment）
     */
    public static boolean resolveBooleanProperty(ConfigurableEnvironment environment,
                                                 PropertySource<?> source,
                                                 String propertyName,
                                                 String envName,
                                                 boolean defaultValue) {
        String raw = resolveStringProperty(environment, source, propertyName, envName);
        return parseBooleanValue(raw, defaultValue);
    }

    /**
     * 宽容解析布尔原始值（支持 Boolean 实例及 true/false/yes/no/on/off/1/0 文本）
     */
    public static boolean parseBooleanValue(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String str = raw.toString().trim();
        if (str.isEmpty()) {
            return defaultValue;
        }
        String normalized = str.toLowerCase(Locale.ROOT);
        if ("false".equals(normalized) || "no".equals(normalized) || "off".equals(normalized) || "0".equals(normalized)) {
            return false;
        }
        if ("true".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized) || "1".equals(normalized)) {
            return true;
        }
        return defaultValue;
    }

    /**
     * 解析默认加密算法
     */
    public static EncryptAlgorithm resolveDefaultAlgorithm(ConfigurableEnvironment environment,
                                                           PropertySource<?> source,
                                                           EncryptAlgorithm fallback) {
        String algoCode = source != null
                ? resolveStringProperty(environment, source, PROPERTY_ALGORITHM, ENV_ALGORITHM)
                : resolveStringProperty(environment, PROPERTY_ALGORITHM, ENV_ALGORITHM);
        EncryptAlgorithm configured = EncryptAlgorithm.fromCode(algoCode);
        return configured != null ? configured : (fallback != null ? fallback : EncryptAlgorithm.AES_GCM);
    }

    /**
     * 解析密文语法解析器配置
     */
    public static CiphertextParser resolveCiphertextParser(ConfigurableEnvironment environment,
                                                           PropertySource<?> source) {
        String prefix = source != null
                ? resolveStringProperty(environment, source, PROPERTY_PREFIX, ENV_PREFIX)
                : resolveStringProperty(environment, PROPERTY_PREFIX, ENV_PREFIX);
        String suffix = source != null
                ? resolveStringProperty(environment, source, PROPERTY_SUFFIX, ENV_SUFFIX)
                : resolveStringProperty(environment, PROPERTY_SUFFIX, ENV_SUFFIX);
        boolean compatibleCipher = source != null
                ? resolveBooleanProperty(environment, source, PROPERTY_COMPATIBLE_CIPHER, ENV_COMPATIBLE_CIPHER, false)
                : resolveBooleanProperty(environment, PROPERTY_COMPATIBLE_CIPHER, ENV_COMPATIBLE_CIPHER, false);
        return new CiphertextParser(prefix, suffix, compatibleCipher);
    }
}
