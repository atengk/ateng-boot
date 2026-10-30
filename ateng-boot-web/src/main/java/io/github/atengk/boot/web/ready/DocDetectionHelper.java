package io.github.atengk.boot.web.ready;

import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * 在线文档组件（Knife4j / SpringDoc OpenAPI）弱引用嗅探辅助工具
 *
 * @author Ateng
 * @since 2026-10-30
 */
final class DocDetectionHelper {

    private DocDetectionHelper() {
    }

    static boolean isKnife4jPresent(ClassLoader classLoader, Environment environment) {
        return ClassUtils.isPresent("com.github.xiaoymin.knife4j.spring.configuration.Knife4jAutoConfiguration", classLoader)
                || ClassUtils.isPresent("com.github.xiaoymin.knife4j.spring.extension.Knife4jOpenApiCustomizer", classLoader)
                || (environment != null && Boolean.parseBoolean(environment.getProperty("knife4j.enable", "false")));
    }

    static boolean isKnife4jEnabled(ClassLoader classLoader, Environment environment) {
        if (!isKnife4jPresent(classLoader, environment)) {
            return false;
        }
        return environment != null ? environment.getProperty("knife4j.enable", Boolean.class, true) : true;
    }

    static boolean isSpringDocPresent(ClassLoader classLoader, Environment environment) {
        if (environment != null && (environment.containsProperty("springdoc.api-docs.enabled")
                || environment.containsProperty("springdoc.swagger-ui.enabled")
                || environment.containsProperty("springdoc.swagger-ui.path"))) {
            return true;
        }
        return ClassUtils.isPresent("org.springdoc.core.properties.SpringDocConfigProperties", classLoader)
                || ClassUtils.isPresent("org.springdoc.webmvc.ui.SwaggerIndexTransformer", classLoader);
    }

    static boolean isSpringDocEnabled(ClassLoader classLoader, Environment environment) {
        if (!isSpringDocPresent(classLoader, environment)) {
            return false;
        }
        if (environment == null) {
            return true;
        }
        boolean apiDocsEnabled = environment.getProperty("springdoc.api-docs.enabled", Boolean.class, true);
        boolean swaggerUiEnabled = environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, true);
        return apiDocsEnabled || swaggerUiEnabled;
    }

    static String resolveDocPath(Environment environment, ClassLoader classLoader) {
        if (isKnife4jEnabled(classLoader, environment)) {
            return "/doc.html";
        }
        if (isSpringDocPresent(classLoader, environment)) {
            boolean apiDocsEnabled = environment != null ? environment.getProperty("springdoc.api-docs.enabled", Boolean.class, true) : true;
            boolean swaggerUiEnabled = environment != null ? environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, true) : true;
            if (apiDocsEnabled && swaggerUiEnabled) {
                String customPath = environment != null ? environment.getProperty("springdoc.swagger-ui.path") : null;
                if (customPath != null && !customPath.isBlank()) {
                    return customPath.trim();
                }
                return "/swagger-ui/index.html";
            }
        }
        return null;
    }
}
