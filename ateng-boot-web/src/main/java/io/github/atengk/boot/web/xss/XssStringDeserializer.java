package io.github.atengk.boot.web.xss;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.github.atengk.boot.web.util.ServletUtil;
import io.github.atengk.boot.web.xss.annotation.XssIgnore;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * Jackson JSON 字符串反序列化 XSS 安全清洗器
 * 对入站 JSON 字符串属性执行安全清洗，并支持基于 {@link XssIgnore} 注解在字段或类级别豁免放行
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class XssStringDeserializer extends StdDeserializer<String> implements ContextualDeserializer {

    private final XssCleaner cleaner;
    private final boolean ignore;

    public XssStringDeserializer(XssCleaner cleaner) {
        this(cleaner, false);
    }

    public XssStringDeserializer(XssCleaner cleaner, boolean ignore) {
        super(String.class);
        this.cleaner = cleaner != null ? cleaner : new XssCleaner();
        this.ignore = ignore;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
        boolean shouldIgnore = false;
        if (property != null) {
            // 1. 字段或 getter/setter 上的 @XssIgnore
            if (property.getAnnotation(XssIgnore.class) != null) {
                shouldIgnore = true;
            } else if (property.getContextAnnotation(XssIgnore.class) != null) {
                // 2. 所属类上下文上的 @XssIgnore
                shouldIgnore = true;
            } else if (property.getMember() != null
                    && property.getMember().getDeclaringClass() != null
                    && property.getMember().getDeclaringClass().isAnnotationPresent(XssIgnore.class)) {
                // 3. 声明类上的 @XssIgnore
                shouldIgnore = true;
            }
        }
        if (shouldIgnore != this.ignore) {
            return new XssStringDeserializer(this.cleaner, shouldIgnore);
        }
        return this;
    }

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.hasToken(JsonToken.VALUE_NULL)) {
            return null;
        }
        String value = p.getValueAsString();
        if (value == null) {
            return null;
        }
        // 若当前字段/类标注了 @XssIgnore，或者当前 HTTP 请求命中了 excludeUrls 白名单，跳过清洗
        if (ignore || isRequestExcluded()) {
            return value;
        }
        return cleaner != null ? cleaner.clean(value) : value;
    }

    private boolean isRequestExcluded() {
        try {
            HttpServletRequest request = ServletUtil.getRequest();
            if (request != null) {
                Object skipAttr = request.getAttribute(XssFilter.XSS_SKIP_ATTRIBUTE);
                return Boolean.TRUE.equals(skipAttr);
            }
        } catch (Throwable ignored) {
            // 非 Web 上下文或异常时忽略
        }
        return false;
    }

    public XssCleaner getCleaner() {
        return cleaner;
    }

    public boolean isIgnore() {
        return ignore;
    }
}
