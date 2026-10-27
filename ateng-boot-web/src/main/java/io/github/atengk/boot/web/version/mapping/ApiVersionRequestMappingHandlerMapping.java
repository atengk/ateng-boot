package io.github.atengk.boot.web.version.mapping;

import io.github.atengk.boot.web.version.annotation.ApiVersion;
import io.github.atengk.boot.web.version.condition.ApiVersionCondition;
import io.github.atengk.boot.web.version.properties.ApiVersionProperties;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.condition.RequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * API 版本感知型请求映射处理器
 * 继承 Spring MVC 原生 {@link RequestMappingHandlerMapping}，自动探测并挂载 {@link ApiVersion} 条件，
 * 支持在保留原路径的同时动态追加 /v{version} 路径前缀模式，实现路径与请求头多维版本无缝分流
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class ApiVersionRequestMappingHandlerMapping extends RequestMappingHandlerMapping {

    private final ApiVersionProperties properties;

    public ApiVersionRequestMappingHandlerMapping(ApiVersionProperties properties) {
        this.properties = properties != null ? properties : new ApiVersionProperties();
    }

    @Override
    protected RequestCondition<?> getCustomTypeCondition(Class<?> handlerType) {
        if (!properties.isEnabled()) {
            return null;
        }
        ApiVersion apiVersion = AnnotatedElementUtils.findMergedAnnotation(handlerType, ApiVersion.class);
        return apiVersion != null ? new ApiVersionCondition(resolveVersion(apiVersion), properties) : null;
    }

    @Override
    protected RequestCondition<?> getCustomMethodCondition(Method method) {
        if (!properties.isEnabled()) {
            return null;
        }
        ApiVersion apiVersion = AnnotatedElementUtils.findMergedAnnotation(method, ApiVersion.class);
        return apiVersion != null ? new ApiVersionCondition(resolveVersion(apiVersion), properties) : null;
    }

    @Override
    protected RequestMappingInfo getMappingForMethod(Method method, Class<?> handlerType) {
        RequestMappingInfo info = super.getMappingForMethod(method, handlerType);
        if (info == null || !properties.isEnabled() || !properties.isPathPrefixEnabled()) {
            return info;
        }

        ApiVersion apiVersion = AnnotatedElementUtils.findMergedAnnotation(method, ApiVersion.class);
        if (apiVersion == null) {
            apiVersion = AnnotatedElementUtils.findMergedAnnotation(handlerType, ApiVersion.class);
        }
        if (apiVersion == null) {
            return info;
        }
        String versionStr = resolveVersion(apiVersion);
        if (!StringUtils.hasText(versionStr)) {
            return info;
        }

        String cleanVer = ApiVersionCondition.cleanVersion(versionStr);
        String prefix = "/v" + cleanVer;

        Set<String> patterns = info.getPatternValues();
        if (patterns.isEmpty()) {
            return info;
        }

        Set<String> newPatterns = new LinkedHashSet<>(patterns);
        for (String pattern : patterns) {
            if (!pattern.startsWith(prefix + "/") && !pattern.equals(prefix)) {
                String combined = prefix + (pattern.startsWith("/") ? pattern : "/" + pattern);
                newPatterns.add(combined);
            }
        }

        return info.mutate().paths(newPatterns.toArray(String[]::new)).build();
    }

    /**
     * 解析 ApiVersion 注解的版本字符串（智能兼容 value 字符串与 number 主版本数值）
     *
     * @param apiVersion 注解对象
     * @return 解析后的版本字符串
     */
    public static String resolveVersion(ApiVersion apiVersion) {
        if (apiVersion == null) {
            return "1";
        }
        if (apiVersion.number() > 0 && "1".equals(apiVersion.value())) {
            return String.valueOf(apiVersion.number());
        }
        if (StringUtils.hasText(apiVersion.value())) {
            return apiVersion.value().trim();
        }
        if (apiVersion.number() > 0) {
            return String.valueOf(apiVersion.number());
        }
        return "1";
    }
}
