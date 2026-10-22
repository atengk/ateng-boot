package io.github.atengk.boot.observability.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * 云原生可观测性与度量运维配置属性
 * 提供 Prometheus 指标导出、受管线程池动态度量、高可用健康探针与端点安全守卫强类型配置
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ateng.boot.observability")
public class ObservabilityProperties {

    /**
     * 是否启用可观测性模块自动装配（默认开启）
     */
    private boolean enabled = true;

    /**
     * 度量指标导出配置
     */
    @NestedConfigurationProperty
    private MetricsProperties metrics = new MetricsProperties();

    /**
     * 健康探针探测配置
     */
    @NestedConfigurationProperty
    private HealthProperties health = new HealthProperties();

    /**
     * Actuator 端点生产安全守卫配置
     */
    @NestedConfigurationProperty
    private SecurityProperties security = new SecurityProperties();

    /**
     * 指标导出相关配置
     */
    @Getter
    @Setter
    public static class MetricsProperties {

        /**
         * Prometheus 指标导出配置
         */
        @NestedConfigurationProperty
        private PrometheusProperties prometheus = new PrometheusProperties();

        /**
         * 受管线程池动态度量配置
         */
        @NestedConfigurationProperty
        private ThreadPoolProperties threadPool = new ThreadPoolProperties();
    }

    /**
     * Prometheus 指标导出配置
     */
    @Getter
    @Setter
    public static class PrometheusProperties {

        /**
         * 是否启用 Prometheus 指标自动注册与导出（默认开启）
         */
        private boolean enabled = true;
    }

    /**
     * 受管线程池动态度量配置
     */
    @Getter
    @Setter
    public static class ThreadPoolProperties {

        /**
         * 是否自动扫描并绑定 Spring 容器内所有受管线程池度量指标（默认开启）
         */
        private boolean enabled = true;
    }

    /**
     * 健康探针探测配置
     */
    @Getter
    @Setter
    public static class HealthProperties {

        /**
         * 探针安全探测默认超时时间（强类型 Duration，默认 2 秒，防网络假死拖垮线程池）
         */
        private Duration timeout = Duration.ofSeconds(2);

        /**
         * 对象存储（ateng-boot-storage）健康探针配置
         */
        @NestedConfigurationProperty
        private StorageHealthProperties storage = new StorageHealthProperties();

        /**
         * RocketMQ 5.x（ateng-boot-rocketmq）健康探针配置
         */
        @NestedConfigurationProperty
        private RocketmqHealthProperties rocketmq = new RocketmqHealthProperties();

        /**
         * 实时通信（ateng-boot-realtime）健康探针配置
         */
        @NestedConfigurationProperty
        private RealtimeHealthProperties realtime = new RealtimeHealthProperties();

        /**
         * 多数据源（ateng-boot-mybatis-plus）健康探针配置
         */
        @NestedConfigurationProperty
        private DynamicDatasourceHealthProperties dynamicDatasource = new DynamicDatasourceHealthProperties();
    }

    /**
     * 对象存储探针配置
     */
    @Getter
    @Setter
    public static class StorageHealthProperties {

        /**
         * 是否启用 Storage 健康探针（默认开启）
         */
        private boolean enabled = true;
    }

    /**
     * RocketMQ 5.x 探针配置
     */
    @Getter
    @Setter
    public static class RocketmqHealthProperties {

        /**
         * 是否启用 RocketMQ 5.x 连通性探针（默认开启）
         */
        private boolean enabled = true;
    }

    /**
     * 实时通信探针配置
     */
    @Getter
    @Setter
    public static class RealtimeHealthProperties {

        /**
         * 是否启用 Realtime 会话与广播探针（默认开启）
         */
        private boolean enabled = true;
    }

    /**
     * 多数据源探针配置
     */
    @Getter
    @Setter
    public static class DynamicDatasourceHealthProperties {

        /**
         * 是否启用动态多数据源连通性探针（默认开启）
         */
        private boolean enabled = true;
    }

    /**
     * Actuator 端点生产安全守卫配置
     */
    @Getter
    @Setter
    public static class SecurityProperties {

        /**
         * 是否启用高危敏感端点生产加固守卫（默认开启）
         */
        private boolean lockdownEnabled = true;

        /**
         * 访问高危敏感端点（如 /actuator/env、/actuator/beans）所需的授权 Token 密钥。
         * 若为空字符串，则高危敏感端点完全禁止匿名外部访问，直接返回 403/404。
         */
        private String token = "";
    }
}
