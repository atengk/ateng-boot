package io.github.atengk.boot.encrypt.guard;

import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.springframework.boot.actuate.endpoint.SanitizableData;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.core.env.PropertySource;

import java.util.regex.Pattern;

/**
 * 运行时 Actuator 端点物理掩码脱敏守卫 (Sanitizing Guard)
 * 基于 Spring Boot 3 SanitizingFunction 规范，对所有曾带有 ENC(...) 标记的属性源字段
 * 以及敏感键名进行拦截，在 /actuator/env 端点强制输出为物理掩码 ******，杜绝运行时内存回显泄露
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class SanitizingGuard implements SanitizingFunction {

    private final EncryptProperties properties;
    private final CiphertextParser parser;
    private final Pattern sensitivePattern;

    public SanitizingGuard(EncryptProperties properties, CiphertextParser parser) {
        this.properties = properties != null ? properties : new EncryptProperties();
        this.parser = parser != null ? parser : new CiphertextParser();
        String patternStr = this.properties.getSensitiveKeyPattern();
        if (patternStr == null || patternStr.trim().isEmpty()) {
            patternStr = EnforceEncryptionPolicy.DEFAULT_SENSITIVE_KEY_PATTERN;
        }
        this.sensitivePattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
    }

    public SanitizingGuard() {
        this(new EncryptProperties(), new CiphertextParser());
    }

    @Override
    public SanitizableData apply(SanitizableData data) {
        if (data == null) {
            return null;
        }
        String key = data.getKey();
        if (key == null) {
            return data;
        }

        // 1. 若属性源为 EncryptPropertySourceWrapper 且曾包含密文标记
        PropertySource<?> source = data.getPropertySource();
        if (source instanceof EncryptPropertySourceWrapper wrapper) {
            if (wrapper.isEncryptedProperty(key)) {
                return data.withSanitizedValue();
            }
        }

        // 2. 若值本身包含密文标记
        Object value = data.getValue();
        if (value instanceof String strVal && parser.hasEncrypted(strVal)) {
            return data.withSanitizedValue();
        }

        // 3. 若键名匹配敏感属性正则模式
        if (sensitivePattern.matcher(key).matches()) {
            return data.withSanitizedValue();
        }

        return data;
    }
}
