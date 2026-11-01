package io.github.atengk.boot.encrypt.guard;

import io.github.atengk.boot.encrypt.exception.ConfigSecurityException;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
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

    private static final Logger log = LoggerFactory.getLogger(EnforceEncryptionPolicy.class);

    public static final String DEFAULT_SENSITIVE_KEY_PATTERN = ".*password.*|.*secret.*|.*token.*|.*private-key.*";

    private static final String[] EXEMPTED_URI_SCHEMES = {
            "http://", "https://", "classpath:", "file:", "ws://", "wss://"
    };

    private static final Set<String> EXEMPTED_LOG_LEVELS = Set.of(
            "DEBUG", "INFO", "WARN", "ERROR", "TRACE"
    );

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
        enforce(environment, sensitivePattern, null, parser);
    }

    /**
     * 执行环境敏感属性明文检测与安全阻断（支持显式属性白名单）
     *
     * @param environment      Spring 可配置环境
     * @param sensitivePattern 敏感属性键名正则表达式
     * @param ignoredKeys      白名单键名集合（可为 null）
     * @param parser           密文语法解析器
     */
    public static void enforce(ConfigurableEnvironment environment,
                               String sensitivePattern,
                               Set<String> ignoredKeys,
                               CiphertextParser parser) {
        if (environment == null) {
            return;
        }
        String patternStr = (sensitivePattern != null && !sensitivePattern.trim().isEmpty())
                ? sensitivePattern.trim()
                : DEFAULT_SENSITIVE_KEY_PATTERN;
        Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
        CiphertextParser tokenParser = parser != null ? parser : new CiphertextParser();

        Set<String> effectiveIgnoredKeys = resolveEffectiveIgnoredKeys(environment, ignoredKeys);

        for (PropertySource<?> source : environment.getPropertySources()) {
            if (shouldSkipPropertySource(source)) {
                continue;
            }
            scanAndValidate(source, pattern, effectiveIgnoredKeys, tokenParser);
        }
    }

    private static Set<String> resolveEffectiveIgnoredKeys(ConfigurableEnvironment environment, Set<String> ignoredKeys) {
        Set<String> result = new HashSet<>();
        if (ignoredKeys != null) {
            result.addAll(ignoredKeys);
        }
        if (environment != null) {
            try {
                Set<String> bound = Binder.get(environment)
                        .bind("ateng.boot.encrypt.ignored-keys", Bindable.setOf(String.class))
                        .orElse(Collections.emptySet());
                result.addAll(bound);
            } catch (Exception e) {
                log.debug("[ateng-boot-encrypt] 读取自定义豁免键列表失败或环境属性源尚未就绪: {}", e.getMessage());
            }
        }
        return result;
    }

    private static void scanAndValidate(PropertySource<?> source,
                                        Pattern pattern,
                                        Set<String> ignoredKeys,
                                        CiphertextParser parser) {
        if (source instanceof EnumerablePropertySource<?> enumerableSource) {
            for (String key : enumerableSource.getPropertyNames()) {
                if (isIgnoredKey(key, ignoredKeys)) {
                    continue;
                }
                if (pattern.matcher(key).matches()) {
                    Object rawValue = getRawProperty(source, key);
                    String strVal = extractStringValue(rawValue);
                    if (strVal != null && !strVal.trim().isEmpty()) {
                        // 协议与日志级别等特征防误伤豁免
                        if (isExemptedValue(strVal)) {
                            continue;
                        }
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

    /**
     * 判断属性值是否属于协议路径或标准日志级别等安全防误伤豁免值
     *
     * @param value 属性值字符串
     * @return 若符合豁免特征返回 true
     */
    public static boolean isExemptedValue(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        // 1. URI 协议特征豁免（http://, https://, classpath:, file:, ws://, wss://）
        for (String prefix : EXEMPTED_URI_SCHEMES) {
            if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        // 2. 真实多级绝对文件路径特征豁免
        if (isPathLike(trimmed)) {
            return true;
        }
        // 3. 标准日志级别特征豁免（DEBUG, INFO, WARN, ERROR, TRACE）
        return EXEMPTED_LOG_LEVELS.contains(trimmed.toUpperCase(Locale.ROOT));
    }

    private static boolean isPathLike(String val) {
        // Unix 绝对路径：以 / 开头且至少存在二级目录，如 /etc/ssl/cert.pem，防止 /Secret2026 单层明文逃逸
        if (val.startsWith("/") && val.indexOf('/', 1) > 0) {
            return true;
        }
        // Windows 绝对路径：以盘符开头，如 C:/certs/app.p12 或 C:\certs\app.p12
        if (val.length() >= 3 && Character.isLetter(val.charAt(0)) && val.charAt(1) == ':'
                && (val.charAt(2) == '/' || val.charAt(2) == '\\')) {
            return true;
        }
        return false;
    }

    public static Object getRawProperty(PropertySource<?> source, String key) {
        if (source instanceof EncryptPropertySourceWrapper wrapper) {
            return wrapper.getDelegate().getProperty(key);
        }
        return source.getProperty(key);
    }

    /**
     * 从任意原始属性值中提取规范化字符串（兼容 CharSequence 与未加引号的 Number）
     *
     * @param rawValue 原始属性对象
     * @return 转换后的字符串，若非文本或数值类型则返回 null
     */
    public static String extractStringValue(Object rawValue) {
        if (rawValue instanceof CharSequence cs) {
            return cs.toString();
        } else if (rawValue instanceof Number num) {
            return num.toString();
        }
        return null;
    }

    static boolean shouldSkipPropertySource(PropertySource<?> source) {
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

    public static boolean isIgnoredKey(String key, Set<String> ignoredKeys) {
        if (key == null) {
            return true;
        }
        // 1. 忽略框架自身解密配置项（主密钥与多 Key 映射表等）
        if (key.startsWith("ateng.boot.encrypt.") || "ateng.boot.encrypt".equals(key)) {
            return true;
        }
        // 2. 忽略 Spring Boot 与 JVM 内部变量
        if (key.startsWith("java.") || key.startsWith("sun.") || key.startsWith("user.")) {
            return true;
        }
        // 3. 显式白名单机制
        if (ignoredKeys != null && ignoredKeys.contains(key)) {
            return true;
        }
        return false;
    }

    public static boolean isIgnoredKey(String key) {
        return isIgnoredKey(key, null);
    }
}
