package io.github.atengk.boot.admin.admission;

import com.fasterxml.jackson.databind.JsonNode;
import de.codecentric.boot.admin.server.domain.values.Registration;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端实例注册描述领域模型
 * 封装客户端实例注册上报的核心元数据（服务名、通信 URL 与扩展标签）
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
@Builder(toBuilder = true)
@ToString
public class InstanceRegistration implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例微服务名
     */
    private final String name;

    /**
     * 微服务业务根路径 URL
     */
    private final String serviceUrl;

    /**
     * 微服务 Actuator 管理端点根路径 URL
     */
    private final String managementUrl;

    /**
     * 微服务健康检查端点 URL
     */
    private final String healthUrl;

    /**
     * 注册来源标识（如 http-api、discovery 等）
     */
    private final String source;

    /**
     * 实例元数据键值对
     */
    private final Map<String, String> metadata;

    public InstanceRegistration(String name,
                                String serviceUrl,
                                String managementUrl,
                                String healthUrl,
                                String source,
                                Map<String, String> metadata) {
        this.name = name;
        this.serviceUrl = serviceUrl;
        this.managementUrl = managementUrl;
        this.healthUrl = healthUrl;
        this.source = source;
        this.metadata = metadata != null ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata)) : Collections.emptyMap();
    }

    /**
     * 便捷构建工厂
     */
    public static InstanceRegistration of(String name, String serviceUrl, String managementUrl, String healthUrl) {
        return builder()
                .name(name)
                .serviceUrl(serviceUrl)
                .managementUrl(managementUrl)
                .healthUrl(healthUrl)
                .metadata(Collections.emptyMap())
                .build();
    }

    /**
     * 从 Spring Boot Admin 原生 Registration 适配转换
     */
    public static InstanceRegistration from(Registration registration) {
        if (registration == null) {
            return null;
        }
        return builder()
                .name(registration.getName())
                .serviceUrl(registration.getServiceUrl())
                .managementUrl(registration.getManagementUrl())
                .healthUrl(registration.getHealthUrl())
                .source(registration.getSource())
                .metadata(registration.getMetadata())
                .build();
    }

    /**
     * 从 Jackson JsonNode 树解析构造实例注册模型
     */
    public static InstanceRegistration parse(JsonNode root) {
        if (root == null || root.isNull() || !root.isObject()) {
            return null;
        }
        String name = root.path("name").asText(null);
        String serviceUrl = root.path("serviceUrl").asText(null);
        String managementUrl = root.path("managementUrl").asText(null);
        String healthUrl = root.path("healthUrl").asText(null);
        String source = root.path("source").asText(null);

        Map<String, String> metadata = new LinkedHashMap<>();
        JsonNode metaNode = root.path("metadata");
        if (metaNode.isObject()) {
            metaNode.fields().forEachRemaining(entry -> {
                if (entry.getValue() != null && !entry.getValue().isNull()) {
                    metadata.put(entry.getKey(), entry.getValue().asText());
                }
            });
        }

        return builder()
                .name(name)
                .serviceUrl(serviceUrl)
                .managementUrl(managementUrl)
                .healthUrl(healthUrl)
                .source(source)
                .metadata(metadata)
                .build();
    }
}
