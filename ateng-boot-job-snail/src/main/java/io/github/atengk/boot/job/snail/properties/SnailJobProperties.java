package io.github.atengk.boot.job.snail.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * SnailJob 分布式任务调度与分布式重试双擎配置属性
 *
 * <p>统一遵循框架强类型时间规范（{@link Duration}），杜绝无量纲配置歧义。
 * 支持细粒度特性开关（{@code job.enabled}、{@code retry.enabled}、{@code openapi.enabled}），
 * 兼顾单机极速起步与分布式集群生产级安全治理。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.job.snail")
public class SnailJobProperties {

    /**
     * 是否启用 SnailJob 自动装配引擎
     */
    private boolean enabled = true;

    /**
     * SnailJob 服务端通信地址（支持 host:port 格式，集群节点支持逗号分隔，如 "127.0.0.1:17888"）
     */
    private String server = "127.0.0.1:17888";

    /**
     * 客户端命名组标识（留空时默认取 spring.application.name）
     */
    private String group = "";

    /**
     * SnailJob 命名空间唯一标识
     */
    private String namespace = "default";

    /**
     * SnailJob 客户端通信访问令牌
     */
    private String token = "default_token";

    /**
     * 客户端本地 RPC 通信端口
     */
    private Integer port = 17889;

    /**
     * RPC 通信超时阈值
     */
    private Duration timeout = Duration.ofSeconds(10);

    /**
     * K8s 平滑停机最大排空等待时长（超限则强制销毁 Netty 物理通道）
     */
    private Duration drainTimeout = Duration.ofSeconds(30);

    /**
     * 本地硬看门狗默认超时阈值（超限强行 interrupt 工作线程，防无界挂起）
     */
    private Duration defaultJobTimeout = Duration.ofHours(1);

    /**
     * 是否开启 SLF4J 动态日志双写镜像（自动将任务中的 log.info 镜像推送到 SnailJob 服务端控制台）
     */
    private boolean logMirrorEnabled = true;

    /**
     * 是否将调度与重试方法提交至 Core 受管异步线程池设施执行
     */
    private boolean managedPoolEnabled = true;

    /**
     * 分布式任务调度专属配置
     */
    @NestedConfigurationProperty
    private JobProperties job = new JobProperties();

    /**
     * 分布式重试专属配置
     */
    @NestedConfigurationProperty
    private RetryProperties retry = new RetryProperties();

    /**
     * SnailJob OpenAPI 编程式管理门面配置
     */
    @NestedConfigurationProperty
    private OpenApiProperties openapi = new OpenApiProperties();

    /**
     * 告警防风暴聚合配置
     */
    @NestedConfigurationProperty
    private AlertProperties alert = new AlertProperties();

    /**
     * 任务调度专属配置项
     */
    @Data
    public static class JobProperties {

        /**
         * 是否启用分布式任务调度功能
         */
        private boolean enabled = true;

        /**
         * 是否启用调度切面拦截（四维上下文 MDC traceId 注入、JSR-303 参数反序列化防卫与看门狗）
         */
        private boolean contextAspectEnabled = true;

        /**
         * 是否启用 SnailShardingBatchTemplate 大数据游标批处理门面
         */
        private boolean batchTemplateEnabled = true;

        /**
         * 是否启用 TenantJobTemplate 多租户隔离遍历执行门面
         */
        private boolean tenantTemplateEnabled = true;
    }

    /**
     * 分布式重试专属配置项
     */
    @Data
    public static class RetryProperties {

        /**
         * 是否启用分布式异常重试功能
         */
        private boolean enabled = true;

        /**
         * 是否启用重试切面拦截（四维现场快照封包、跨时间远程重放上下文还原与死信通道）
         */
        private boolean contextAspectEnabled = true;
    }

    /**
     * SnailJob OpenAPI 编程式管理配置项
     */
    @Data
    public static class OpenApiProperties {

        /**
         * 是否启用 OpenAPI 编程式管理门面
         */
        private boolean enabled = true;

        /**
         * SnailJob 服务端 Web 端口号（官方默认 8080）
         */
        private int port = 8080;

        /**
         * 服务端 Web API 路由前缀（官方默认 snail-job）
         */
        private String prefix = "snail-job";

        /**
         * 是否通过 HTTPS 协议与 SnailJob OpenAPI 交互
         */
        private boolean https = false;
    }

    /**
     * 告警防风暴聚合配置项
     */
    @Data
    public static class AlertProperties {

        /**
         * 是否启用三阶告警防风暴聚合（首发告警 -> 窗口聚合摘要 -> 成功自愈）
         */
        private boolean enabled = true;

        /**
         * 告警静默抑制窗口周期
         */
        private Duration suppressDuration = Duration.ofMinutes(5);

        /**
         * 触发告警摘要的连续失败次数阈值
         */
        private int suppressThreshold = 3;
    }
}
