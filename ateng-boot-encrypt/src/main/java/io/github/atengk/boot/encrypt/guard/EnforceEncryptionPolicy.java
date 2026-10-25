package io.github.atengk.boot.encrypt.guard;

import io.github.atengk.boot.encrypt.exception.ConfigSecurityException;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.regex.Pattern;

/**
 * 生产强制加密门禁策略 (Enforce Encryption Policy)
 * 在开启 enforce-encrypted=true 时，前置扫描所有配置属性；
 * 若检测到敏感属性未以 ENC(...) 包裹（即裸明文），立即触发 Fail-Fast 阻断启动，彻底防止生产明文误提交
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class EnforceEncryptionPolicy {

    public static final String DEFAULT_SENSITIVE_KEY_PATTERN = ".*password.*|.*secret.*|.*token.*|.*private-key.*";

    private EnforceEncryptionPolicy() {
    }

    /**
     * 执行环境敏感属性明文检测与安全阻断
     *
     * @param environment      Spring 可配置环境
     * @param sensitivePattern 敏感属性键名正则表达式
     * @param parser           密文语法解析器
     */
    public static void enforce(ConfigurableEnvironment environment, String sensitivePattern, CiphertextParser parser) {
        if (environment == null) {
            return;
        }
        String patternStr = (sensitivePattern != null && !sensitivePattern.trim().isEmpty())
                ? sensitivePattern.trim()
                : DEFAULT_SENSITIVE_KEY_PATTERN;
        Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
        CiphertextParser tokenParser = parser != null ? parser : new CiphertextParser();

        for (PropertySource<?> source : environment.getPropertySources()) {
            if (shouldSkipPropertySource(source)) {
                continue;
            }
            scanAndValidate(source, pattern, tokenParser);
        }
    }

    private static void scanAndValidate(PropertySource<?> source, Pattern pattern, CiphertextParser parser) {
        if (source instanceof EnumerablePropertySource<?> enumerableSource) {
            for (String key : enumerableSource.getPropertyNames()) {
                if (isIgnoredKey(key)) {
                    continue;
                }
                if (pattern.matcher(key).matches()) {
                    Object rawValue = getRawProperty(source, key);
                    if (rawValue instanceof String strVal && !strVal.trim().isEmpty()) {
                        if (!parser.hasEncrypted(strVal)) {
                            throw new ConfigSecurityException(String.format(
                                    "生产环境强制要求敏感配置必须加密 (enforce-encrypted=true)！检测到明文敏感配置属性: '%s'，已被安全策略刚性拦截",
                                    key
                            ));
                        }
                    }
                }
            }
        }
    }

    private static Object getRawProperty(PropertySource<?> source, String key) {
        if (source instanceof EncryptPropertySourceWrapper wrapper) {
            return wrapper.getDelegate().getProperty(key);
        }
        return source.getProperty(key);
    }

    private static boolean shouldSkipPropertySource(PropertySource<?> source) {
        if (source == null) {
            return true;
        }
        String name = source.getName();
        if (name == null) {
            return true;
        }
        // 排除 Spring 虚拟复合源、宿主操作系统环境与系统属性
        return "configurationProperties".equals(name)
                || StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(name)
                || StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME.equals(name)
                || name.startsWith("stub");
    }

    private static boolean isIgnoredKey(String key) {
        if (key == null) {
            return true;
        }
        // 忽略框架自身解密配置项（主密钥与多 Key 映射表等）
        if (key.startsWith("ateng.boot.encrypt.")) {
            return true;
        }
        // 忽略 Spring Boot 与 JVM 内部变量
        return key.startsWith("java.") || key.startsWith("sun.") || key.startsWith("user.");
    }
}
