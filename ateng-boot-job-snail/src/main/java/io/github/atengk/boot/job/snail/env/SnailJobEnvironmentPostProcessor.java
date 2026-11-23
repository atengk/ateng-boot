package io.github.atengk.boot.job.snail.env;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SnailJob 运行环境后置处理器
 *
 * <p>在 Spring Boot 环境准备阶段（{@code ConfigDataEnvironmentPostProcessor} 之后）
 * 自动将 {@code ateng.boot.job.snail.*} 统一配置桥接为官方 {@code snail-job.*} 原生配置，
 * 使业务工程无需手动重复编写 {@code snail-job.enabled=true} 或注解 {@code @EnableSnailJob}
 * 即可无缝激活官方调度/重试客户端与 OpenAPI 属性对象。</p>
 *
 * <p>采用最低优先级属性源（{@code addLast}）注入：用户显式声明的任何 {@code snail-job.*}
 * 属性均保持绝对优先生效；当总开关关闭或未配置服务端地址时静默退让。</p>
 *
 * @author Ateng
 * @since 2026-11-20
 */
public class SnailJobEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * 注入属性源名称
     */
    public static final String PROPERTY_SOURCE_NAME = "atengSnailJobDefaults";

    /**
     * 执行优先级：低于配置加载器（ConfigDataEnvironmentPostProcessor），确保读取到最终生效配置且不覆盖显式配置
     */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 10;

    private static final String PREFIX = "ateng.boot.job.snail.";

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment == null) {
            return;
        }

        Boolean enabled = environment.getProperty(PREFIX + "enabled", Boolean.class, true);
        if (Boolean.FALSE.equals(enabled)) {
            return;
        }

        String server = environment.getProperty(PREFIX + "server");
        if (!StringUtils.hasText(server)) {
            return;
        }

        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }

        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("snail-job.enabled", Boolean.TRUE);

        // 1. 解析首节点服务端地址与默认端口
        String primaryNode = server.contains(",") ? server.split(",")[0].trim() : server.trim();
        String serverHost = primaryNode;
        int serverPort = 17888;
        int colonIdx = primaryNode.lastIndexOf(':');
        if (colonIdx > 0 && colonIdx < primaryNode.length() - 1) {
            serverHost = primaryNode.substring(0, colonIdx).trim();
            try {
                serverPort = Integer.parseInt(primaryNode.substring(colonIdx + 1).trim());
            } catch (NumberFormatException ignored) {
                serverPort = 17888;
            }
        }
        if (StringUtils.hasText(serverHost)) {
            defaults.put("snail-job.server.host", serverHost);
        }
        defaults.put("snail-job.server.port", serverPort);

        // 2. 同步组名、命名空间、令牌与本地客户端端口
        String group = environment.getProperty(PREFIX + "group");
        if (!StringUtils.hasText(group)) {
            group = environment.getProperty("spring.application.name");
        }
        if (StringUtils.hasText(group)) {
            defaults.put("snail-job.group", group.trim());
        }

        String namespace = environment.getProperty(PREFIX + "namespace");
        if (StringUtils.hasText(namespace)) {
            defaults.put("snail-job.namespace", namespace.trim());
        }

        String token = environment.getProperty(PREFIX + "token");
        if (StringUtils.hasText(token)) {
            defaults.put("snail-job.token", token.trim());
        }

        Integer port = environment.getProperty(PREFIX + "port", Integer.class);
        if (port != null && port > 0) {
            defaults.put("snail-job.port", port);
        }

        // 3. 同步 OpenAPI 连接配置
        String openApiHost = environment.getProperty(PREFIX + "openapi.host");
        String resolvedOpenApiHost = StringUtils.hasText(openApiHost) ? openApiHost.trim() : serverHost;
        if (StringUtils.hasText(resolvedOpenApiHost)) {
            defaults.put("snail-job.openapi.host", resolvedOpenApiHost);
        }
        Integer openApiPort = environment.getProperty(PREFIX + "openapi.port", Integer.class);
        if (openApiPort != null && openApiPort > 0) {
            defaults.put("snail-job.openapi.port", openApiPort);
        }
        String openApiPrefix = environment.getProperty(PREFIX + "openapi.prefix");
        if (StringUtils.hasText(openApiPrefix)) {
            defaults.put("snail-job.openapi.prefix", openApiPrefix.trim());
        }
        Boolean openApiHttps = environment.getProperty(PREFIX + "openapi.https", Boolean.class);
        if (openApiHttps != null) {
            defaults.put("snail-job.openapi.https", openApiHttps);
        }

        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }
}
