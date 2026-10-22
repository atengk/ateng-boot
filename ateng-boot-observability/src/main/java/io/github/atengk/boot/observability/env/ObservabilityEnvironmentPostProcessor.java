package io.github.atengk.boot.observability.env;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 云原生可观测性环境后处理器
 * <p>
 * 在 Spring Boot 容器启动早期向环境注入生产级基线默认配置：
 * 1. 默认安全收敛 Actuator 暴露端点：仅暴露 {@code health,info,prometheus}；
 * 2. 自动开启 Kubernetes 存活与就绪探针：{@code /actuator/health/liveness} 与 {@code /actuator/health/readiness}；
 * 3. 采用最低优先级属性源（{@code addLast}）注入，用户在 {@code application.yml} 或配置中心声明的任何配置均可无缝覆写。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class ObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * 注入属性源名称
     */
    public static final String PROPERTY_SOURCE_NAME = "atengObservabilityDefaults";

    /**
     * 执行优先级：低于大部分配置加载器，以便用户配置优先
     */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 10;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment == null) {
            return;
        }

        // 全局开关关闭时，跳过默认环境基线配置注入
        Boolean enabled = environment.getProperty("ateng.boot.observability.enabled", Boolean.class, true);
        if (Boolean.FALSE.equals(enabled)) {
            return;
        }

        // 已存在该属性源则不再重复注入
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }

        Map<String, Object> defaults = new LinkedHashMap<>();

        // 1. Actuator 暴露端点安全收敛（仅暴露黄金探针与度量，敏感高危端点默认关闭）
        defaults.put("management.endpoints.web.exposure.include", "health,info,prometheus");
        // 开启 Prometheus 度量导出器（Spring Boot 3.3+ 生产基线）
        defaults.put("management.prometheus.metrics.export.enabled", "true");

        // 2. 自动开启 Kubernetes 存活（Liveness）与就绪（Readiness）探针
        defaults.put("management.endpoint.health.probes.enabled", "true");
        defaults.put("management.health.livenessstate.enabled", "true");
        defaults.put("management.health.readinessstate.enabled", "true");

        // 3. 健康检查详情始终展示（由上层 ActuatorEndpointSecurityFilter 统一拦截敏感信息）
        defaults.put("management.endpoint.health.show-details", "always");

        // 4. 应用信息环境探测开启
        defaults.put("management.info.env.enabled", "true");

        // 最低优先级追加，保障下游配置可直接覆写
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }
}
