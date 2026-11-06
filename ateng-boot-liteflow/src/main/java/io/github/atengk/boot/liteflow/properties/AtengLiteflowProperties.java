package io.github.atengk.boot.liteflow.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * ateng-boot LiteFlow 规则编排底座配置属性
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.liteflow")
public class AtengLiteflowProperties {

    /**
     * 是否启用 LiteFlow 规则编排底座自动装配
     */
    private boolean enabled = true;

    /**
     * 规则文件扫描路径（支持逗号分隔多个路径，支持 classpath 表达式）
     */
    private String ruleSource = "classpath*:liteflow/*.el.xml,classpath*:liteflow/*.el.yml";

    /**
     * 是否在规则执行完毕后打印格式化链路日志
     */
    private boolean printExecutionLog = true;

    /**
     * 并行编排 WHEN 默认全局最大等待超时时间（支持标准 Duration 格式，如 3s、500ms，留空则不设全局超时）
     */
    private Duration whenMaxWaitTimeout;

    /**
     * 异步并行编排线程池配置
     */
    @NestedConfigurationProperty
    private ThreadPoolProperties threadPool = new ThreadPoolProperties();

    /**
     * 多源动态规则加载与热重载扩展配置
     */
    @NestedConfigurationProperty
    private DynamicRuleProperties dynamicRule = new DynamicRuleProperties();

    /**
     * 结构化链路轨迹配置
     */
    @NestedConfigurationProperty
    private TraceProperties trace = new TraceProperties();

    /**
     * 云原生 Micrometer 性能度量监控配置
     */
    @NestedConfigurationProperty
    private MetricsProperties metrics = new MetricsProperties();

    /**
     * 异步并行编排线程池属性
     */
    @Data
    public static class ThreadPoolProperties {

        /**
         * 是否自动将四维上下文（MDC TraceId、UserId、TenantId、IgnoreTenant）透传至子线程
         */
        private boolean propagateContext = true;

        /**
         * 是否使用全局受管线程池（true 则接管 Core 模块 ThreadPoolTaskExecutor；false 则使用隔离独立线程池）
         */
        private boolean useManagedPool = true;

        /**
         * 自定义全局线程池执行器类名（留空则默认装配 LiteflowGlobalExecutorBuilder）
         */
        private String executorClass;

        /**
         * 独立线程池核心线程数（当 useManagedPool=false 或回退模式时生效）
         */
        private int corePoolSize = 16;

        /**
         * 独立线程池最大线程数（当 useManagedPool=false 或回退模式时生效）
         */
        private int maxPoolSize = 64;

        /**
         * 独立线程池阻塞队列容量（当 useManagedPool=false 或回退模式时生效）
         */
        private int queueCapacity = 1000;

        /**
         * 独立线程池空闲线程保活时长
         */
        private Duration keepAlive = Duration.ofSeconds(60);

        /**
         * 独立线程池线程名称前缀
         */
        private String threadNamePrefix = "ateng-liteflow-";
    }

    /**
     * 多源动态规则扩展属性
     */
    @Data
    public static class DynamicRuleProperties {

        /**
         * 是否启用多源动态规则扩展管理器
         */
        private boolean enabled = true;

        /**
         * 容器就绪后是否自动从已注册的 SPI 规则源拉取并注册规则
         */
        private boolean autoLoadOnStartup = true;

        /**
         * 动态规则历史版本快照最大保留数量（防御内存无界膨胀，默认 5）
         */
        private int maxHistorySnapshots = 5;

        /**
         * 数据库动态规则加载器配置
         */
        @NestedConfigurationProperty
        private DatabaseRuleProperties database = new DatabaseRuleProperties();
    }

    /**
     * 数据库动态规则加载配置
     */
    @Data
    public static class DatabaseRuleProperties {

        /**
         * 是否启用数据库动态规则加载器（默认 false，需开发者显式启用）
         */
        private boolean enabled = false;

        /**
         * 规则元数据表名
         */
        private String tableName = "sys_liteflow_rule";

        /**
         * 流程链 ID 列名 (chainId)
         */
        private String ruleIdColumn = "rule_id";

        /**
         * 规则中文名称列名
         */
        private String ruleNameColumn = "rule_name";

        /**
         * EL 表达式列名
         */
        private String elColumn = "el_expression";

        /**
         * 状态标识列名 (1-启用, 0-禁用)
         */
        private String statusColumn = "status";

        /**
         * 路由/分组列名
         */
        private String routeGroupColumn = "route_group";

        /**
         * 过滤的路由分组（留空则不按分组过滤）
         */
        private String routeGroup = "DEFAULT";

        /**
         * 规则描述备注列名
         */
        private String descriptionColumn = "description";
    }

    /**
     * 结构化链路轨迹配置
     */
    @Data
    public static class TraceProperties {

        /**
         * 是否在规则执行完毕后自动将链路轨迹写入 MDC 审计日志
         */
        private boolean enableMdc = false;

        /**
         * MDC 注入时使用的 Key 名称
         */
        private String mdcKey = "liteflow_trace";
    }

    /**
     * 云原生 Micrometer 性能度量监控配置
     */
    @Data
    public static class MetricsProperties {

        /**
         * 是否启用 LiteFlow 云原生 Micrometer 性能指标度量
         */
        private boolean enabled = true;
    }
}
