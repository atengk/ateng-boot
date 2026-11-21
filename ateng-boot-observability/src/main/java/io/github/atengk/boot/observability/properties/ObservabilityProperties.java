package io.github.atengk.boot.observability.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * Spring Boot Admin 客户端注册与握手凭据配置
     */
    @NestedConfigurationProperty
    private AdminClientProperties adminClient = new AdminClientProperties();

    /**
     * 指标导出相关配置
     */
    @Getter
    @Setter
    public static class MetricsProperties {

        /**
         * 是否自动注入 spring.application.name 作为全局 application 公共标签（默认开启）
         */
        private boolean includeApplicationTag = true;

        /**
         * 全局自定义公共标签键值对（自动附加到所有注册指标，例如 env=prod、region=cn-east）
         */
        private Map<String, String> commonTags = new LinkedHashMap<>();

        /**
         * 单个标签值允许的最大字符长度（默认 128，超过则截断防御索引膨胀；小于等于 0 表示不截断）
         */
        private int maxTagValueLength = 128;

        /**
         * 单个指标名称允许的最大标签组合基数上限（默认 1000，超出上限的新序列将被拒绝注册以防维度爆炸；小于等于 0 表示不限制）
         */
        private int maxMeterCardinality = 1000;

        /**
         * 按指标名称前缀黑名单丢弃采集（命中前缀的指标不被注册，如 jvm.gc.pause）
         */
        private List<String> denyMetricPrefixes = new ArrayList<>();

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

        /**
         * 声明式方法度量切面配置
         */
        @NestedConfigurationProperty
        private ObservedProperties observed = new ObservedProperties();
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
     * 声明式方法度量切面配置
     */
    @Getter
    @Setter
    public static class ObservedProperties {

        /**
         * 是否启用 @ObservedMetric 切面度量采集（默认开启）
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
         * 探针健康结果短效缓存有效期（强类型 Duration，默认 0s 表示关闭缓存每次实时探测；大于 0 时防高频抓取击穿下游中间件）
         */
        private Duration cacheTtl = Duration.ZERO;

        /**
         * 同一探针连续故障期间重复广播降级事件的最小冷却间隔（强类型 Duration，默认 0s 表示不抑制；大于 0 时抑制告警风暴）
         */
        private Duration degradedEventCooldown = Duration.ZERO;

        /**
         * Kubernetes 黄金探针智能硬分级隔离开关（默认开启）。
         * 开启时强制将外部物理依赖从 liveness 存活探针中剔除（仅包含 livenessState 与 ping），
         * 外部依赖仅在 readiness 中聚合，从根本上杜绝外部网络抖动引发 Pod 死亡螺旋（Death-Spiral）与误杀重启。
         */
        private boolean k8sIsolationEnabled = true;

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

        /**
         * Redis / Redisson（ateng-boot-redis）健康探针配置
         */
        @NestedConfigurationProperty
        private RedisHealthProperties redis = new RedisHealthProperties();

        /**
         * Kafka（ateng-boot-kafka）健康探针配置
         */
        @NestedConfigurationProperty
        private KafkaHealthProperties kafka = new KafkaHealthProperties();

        /**
         * RabbitMQ（ateng-boot-rabbitmq）健康探针配置
         */
        @NestedConfigurationProperty
        private RabbitmqHealthProperties rabbitmq = new RabbitmqHealthProperties();

        /**
         * 搜索引擎 Elasticsearch（ateng-boot-search）健康探针配置
         */
        @NestedConfigurationProperty
        private SearchHealthProperties search = new SearchHealthProperties();

        /**
         * 邮件通信基础设施（ateng-boot-mail）健康探针配置
         */
        @NestedConfigurationProperty
        private MailHealthProperties mail = new MailHealthProperties();

        /**
         * 企业级短信基础设施（ateng-boot-sms）健康探针配置
         */
        @NestedConfigurationProperty
        private SmsHealthProperties sms = new SmsHealthProperties();
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
     * Redis / Redisson 探针配置
     */
    @Getter
    @Setter
    public static class RedisHealthProperties {

        /**
         * 是否启用 Redis / Redisson 安全健康探针（默认开启）
         */
        private boolean enabled = true;
    }

    /**
     * Kafka 探针配置
     */
    @Getter
    @Setter
    public static class KafkaHealthProperties {

        /**
         * 是否启用 Kafka 安全健康探针（默认开启）
         */
        private boolean enabled = true;
    }

    /**
     * RabbitMQ 探针配置
     */
    @Getter
    @Setter
    public static class RabbitmqHealthProperties {

        /**
         * 是否启用 RabbitMQ 安全健康探针（默认开启）
         */
        private boolean enabled = true;
    }

    /**
     * 搜索引擎 Elasticsearch 探针配置
     */
    @Getter
    @Setter
    public static class SearchHealthProperties {

        /**
         * 是否启用 Search 安全健康探针（默认开启）
         */
        private boolean enabled = true;
    }

    /**
     * 邮件通信基础设施（ateng-boot-mail）探针配置
     */
    @Getter
    @Setter
    public static class MailHealthProperties {

        /**
         * 是否启用邮件静态账号健康探针（默认开启）
         */
        private boolean enabled = true;

        /**
         * 邮件健康探针防刷缓存有效期（强类型 Duration，默认 60s 以防高频探活触发公网邮件服务商认证限流；设为 0s 可关闭缓存）
         */
        private Duration cacheTtl = Duration.ofSeconds(60);
    }

    /**
     * 企业级短信基础设施（ateng-boot-sms）探针配置
     */
    @Getter
    @Setter
    public static class SmsHealthProperties {

        /**
         * 是否启用短信静态渠道安全健康探针（默认开启）
         */
        private boolean enabled = true;

        /**
         * 短信健康探针防刷缓存有效期（强类型 Duration，默认 60s 以防高频探活压垮外部短信网关；设为 0s 可关闭缓存）
         */
        private Duration cacheTtl = Duration.ofSeconds(60);

        /**
         * 是否对支持只读余额查询接口的静态短信渠道顺带探查账户余额（默认关闭，开启后也绝不发送计费短信）
         */
        private boolean probeBalance = false;
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

        /**
         * 免密访问高危端点的内网 IP 或 CIDR 网段白名单（例如 10.0.0.0/8, 192.168.1.0/24, 127.0.0.1）
         */
        private List<String> allowedIpRanges = new ArrayList<>();

        /**
         * 是否无条件信任反向代理转发的客户端 IP 请求头（如 X-Forwarded-For、X-Real-IP，默认关闭以防外网伪造绕过白名单）。
         * 关闭时仅采信直连网络层 RemoteAddr；若需仅信任网关代理头，推荐保持 false 并配置 {@link #trustedProxies}。
         */
        private boolean trustProxyHeaders = false;

        /**
         * 受信任的反向代理服务器 IP 或 CIDR 网段列表（例如 10.0.0.0/8、172.16.0.0/12、127.0.0.1）。
         * 仅当直连 RemoteAddr 命中此列表时，才允许解析反向代理请求头提取首跳客户端 IP。
         */
        private List<String> trustedProxies = new ArrayList<>();

        /**
         * 404 隐身防扫描阻断模式开关（默认关闭）。
         * 开启后未授权访问敏感端点对外返回 HTTP 404 JSON 报文，关闭时保持 HTTP 403；
         * 无论处于何种模式，拦截前均如实记录安全告警并发布 ActuatorAccessDeniedEvent。
         */
        private boolean stealthMode = false;

        /**
         * 免密放行的 Actuator 相对子路径白名单（兼容配置项带或不带前导 /）。
         * 默认放行 health、info、prometheus、pre-stop；/actuator 根索引端点始终受安全拦截保护。
         */
        private List<String> whitelistedEndpoints = new ArrayList<>(List.of("health", "info", "prometheus", "pre-stop"));

        /**
         * Actuator 端点 HTTP Basic Auth 认证配置
         */
        @NestedConfigurationProperty
        private BasicAuthProperties basicAuth = new BasicAuthProperties();
    }

    /**
     * Actuator 端点 HTTP Basic Auth 认证配置
     */
    @Getter
    @Setter
    public static class BasicAuthProperties {

        /**
         * 是否启用 Actuator 端点 HTTP Basic Auth 认证（默认关闭）
         */
        private boolean enabled = false;

        /**
         * HTTP Basic Auth 认证用户名
         */
        private String username = "";

        /**
         * HTTP Basic Auth 认证密码
         */
        private String password = "";
    }

    /**
     * Spring Boot Admin 客户端透明增强与安全握手配置
     */
    @Getter
    @Setter
    public static class AdminClientProperties {

        /**
         * 是否自动向 SBA Server 上报端点凭据与密钥（默认开启）
         */
        private boolean enabled = true;

        /**
         * 向 SBA Server 发起注册时的握手通信密钥（对应服务端的 client-secret）
         */
        private String clientSecret;

        /**
         * 显式指定上报给 SBA 的业务地址（支持 K8s 域名与公网网关）
         */
        private String serviceUrl;

        /**
         * 显式指定独立管理端口访问地址（针对双端口物理隔离架构）
         */
        private String managementUrl;
    }
}
