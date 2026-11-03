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
}
