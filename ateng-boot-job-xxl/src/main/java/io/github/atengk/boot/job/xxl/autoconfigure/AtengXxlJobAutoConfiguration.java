package io.github.atengk.boot.job.xxl.autoconfigure;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import io.github.atengk.boot.core.autoconfigure.AtengAsyncAutoConfiguration;
import io.github.atengk.boot.job.xxl.actuator.XxlJobEndpoint;
import io.github.atengk.boot.job.xxl.actuator.XxlJobHealthIndicator;
import io.github.atengk.boot.job.xxl.alert.XxlJobAlertAggregator;
import io.github.atengk.boot.job.xxl.aspect.XxlJobContextAspect;
import io.github.atengk.boot.job.xxl.executor.AtengXxlJobSpringExecutor;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper;
import io.github.atengk.boot.job.xxl.logback.XxlJobLogbackAppenderRegistrar;
import io.github.atengk.boot.job.xxl.metrics.XxlJobMetricsBinder;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.github.atengk.boot.job.xxl.template.TenantJobTemplate;
import io.github.atengk.boot.job.xxl.template.XxlJobAdminTemplate;
import io.github.atengk.boot.job.xxl.template.XxlMyBatisStreamingTemplate;
import io.github.atengk.boot.job.xxl.template.XxlShardingBatchTemplate;
import io.github.atengk.boot.job.xxl.tenant.TenantListProvider;
import io.github.atengk.boot.job.xxl.watchdog.LocalWatchdog;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.concurrent.Executor;

/**
 * XXL-Job 分布式任务调度执行器自动装配类
 *
 * <p>遵循 Spring Boot 3 规范与防御性装配原则，在配置了核心调度中心地址时自动构建生产增强版 {@link AtengXxlJobSpringExecutor}，
 * 在未配置或空地址时平滑降级旁路，杜绝应用启动崩溃。同时装载受管看门狗、SLF4J 动态日志镜像与四维上下文切面。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@AutoConfiguration(after = AtengAsyncAutoConfiguration.class)
@ConditionalOnClass(XxlJobSpringExecutor.class)
@ConditionalOnProperty(prefix = "ateng.boot.job.xxl", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(XxlJobProperties.class)
public class AtengXxlJobAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengXxlJobAutoConfiguration.class);

    /**
     * 注册并初始化 XXL-Job Spring 执行器（生产受管强化版）
     *
     * @param properties 执行器配置属性
     * @return XXL-Job 执行器实例，在调度地址缺省时返回 null 处于静默旁路模式
     */
    @Bean
    @ConditionalOnMissingBean(XxlJobSpringExecutor.class)
    @ConditionalOnProperty(prefix = "ateng.boot.job.xxl", name = "admin-addresses")
    public XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties properties) {
        if (!StringUtils.hasText(properties.getAdminAddresses())) {
            log.warn("XXL-Job 执行器已激活，但未检测到有效调度中心地址 ('ateng.boot.job.xxl.admin-addresses' 为空)，已平滑旁路跳过执行器初始化。");
            return null;
        }

        if (properties.isRequireAccessToken() && !StringUtils.hasText(properties.getAccessToken())) {
            throw new IllegalArgumentException("XXL-Job 启用了强制安全令牌校验 (requireAccessToken=true)，但未配置有效 AccessToken ('ateng.boot.job.xxl.access-token')！");
        }
        if (!StringUtils.hasText(properties.getAccessToken())) {
            log.warn("安全告警：XXL-Job 执行器未配置 AccessToken ('ateng.boot.job.xxl.access-token')，Netty 入站通信处于未鉴权状态，生产环境强烈建议配置！");
        }

        log.info("开始装配 XXL-Job 执行器 [AppName: {}, AdminAddresses: {}, Port: {}]",
                properties.getAppname(), properties.getAdminAddresses(), properties.getPort());

        AtengXxlJobSpringExecutor executor = new AtengXxlJobSpringExecutor(properties);
        executor.setAdminAddresses(properties.getAdminAddresses());
        executor.setAppname(properties.getAppname());

        if (StringUtils.hasText(properties.getAccessToken())) {
            executor.setAccessToken(properties.getAccessToken());
        }

        if (StringUtils.hasText(properties.getAddress())) {
            executor.setAddress(properties.getAddress());
        }
        if (StringUtils.hasText(properties.getIp())) {
            executor.setIp(properties.getIp());
        }

        executor.setPort(properties.getPort());
        executor.setLogPath(properties.getLogPath());
        executor.setLogRetentionDays(properties.getLogRetentionDays());

        if (properties.getTimeout() != null) {
            executor.setTimeout((int) properties.getTimeout().toSeconds());
        }

        return executor;
    }

    /**
     * 注册本地硬看门狗引擎
     *
     * <p>提供基于独立时间轮的硬超时强中断守护与 JobTimeoutEvent 事件广播。</p>
     *
     * @param eventPublisherProvider 事件发布器 Provider
     * @return 本地看门狗实例
     */
    @Bean
    @ConditionalOnMissingBean
    public LocalWatchdog localWatchdog(ObjectProvider<ApplicationEventPublisher> eventPublisherProvider) {
        return new LocalWatchdog(eventPublisherProvider.getIfAvailable());
    }

    /**
     * 注册 Logback 日志动态镜像器
     *
     * <p>仅当引入 Logback 并启用日志镜像时生效，将业务 SLF4J 标准日志动态镜像至 XXL-Job 调度分片日志文件。</p>
     *
     * @return Logback 追加器注册器
     */
    @Bean
    @ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.job.xxl", name = "log-mirror-enabled", havingValue = "true", matchIfMissing = true)
    public XxlJobLogbackAppenderRegistrar xxlJobLogbackAppenderRegistrar() {
        return new XxlJobLogbackAppenderRegistrar();
    }

    /**
     * 注册 XXL-Job 三阶告警防风暴聚合与自愈引擎
     *
     * @param properties 配置属性
     * @param eventPublisherProvider 事件发布器 Provider
     * @param notificationTemplateProvider 可选协同通知门面 Provider
     * @return 告警防风暴聚合自愈引擎
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.job.xxl", name = "alert-enabled", havingValue = "true", matchIfMissing = true)
    public XxlJobAlertAggregator xxlJobAlertAggregator(
            XxlJobProperties properties,
            ObjectProvider<ApplicationEventPublisher> eventPublisherProvider,
            ObjectProvider<NotificationTemplate> notificationTemplateProvider) {
        return new XxlJobAlertAggregator(
                properties,
                eventPublisherProvider.getIfAvailable(),
                notificationTemplateProvider.getIfAvailable()
        );
    }

    /**
     * 注册任务执行四维上下文、链路追踪与受管超时看门狗切面
     *
     * <p>自动为由 @XxlJob 或 @AtengXxlJob 标注的方法织入 32 位无锁 MDC traceId、操作人、多租户隔离与看门狗超时中断守护。</p>
     *
     * @param watchdogProvider        看门狗 Provider
     * @param properties              配置属性
     * @param taskExecutorProvider    Core 受管异步线程池 Provider
     * @param alertAggregatorProvider 告警聚合引擎 Provider
     * @param metricsBinderProvider   Micrometer 监控度量绑定器 Provider
     * @return 上下文切面实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.job.xxl", name = "context-aspect-enabled", havingValue = "true", matchIfMissing = true)
    public XxlJobContextAspect xxlJobContextAspect(
            ObjectProvider<LocalWatchdog> watchdogProvider,
            XxlJobProperties properties,
            @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider,
            ObjectProvider<XxlJobAlertAggregator> alertAggregatorProvider,
            ObjectProvider<XxlJobMetricsBinder> metricsBinderProvider) {
        return new XxlJobContextAspect(
                watchdogProvider.getIfAvailable(),
                properties,
                taskExecutorProvider.getIfAvailable(),
                alertAggregatorProvider.getIfAvailable(),
                metricsBinderProvider.getIfAvailable()
        );
    }

    /**
     * 注册 Micrometer 核心监控度量指标收集器
     *
     * <p>仅在引入 Micrometer 时生效，收集调度任务执行吞吐量、失败量与百分位耗时分布。</p>
     *
     * @return Micrometer 监控绑定器
     */
    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnMissingBean
    public XxlJobMetricsBinder xxlJobMetricsBinder() {
        return new XxlJobMetricsBinder();
    }

    /**
     * 注册脱网本地调试与端到端测试辅助门面
     *
     * @return 脱网调试辅助实例
     */
    @Bean
    @ConditionalOnMissingBean
    public XxlJobTestHelper xxlJobTestHelper() {
        return new XxlJobTestHelper();
    }

    /**
     * 注册 Actuator 健康探针
     *
     * @param properties       配置属性
     * @param executorProvider 执行器 Provider
     * @return 健康探针实例
     */
    @Bean
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnMissingBean(name = "xxlJobHealthIndicator")
    public XxlJobHealthIndicator xxlJobHealthIndicator(
            XxlJobProperties properties,
            ObjectProvider<XxlJobSpringExecutor> executorProvider) {
        return new XxlJobHealthIndicator(properties, executorProvider);
    }

    /**
     * 注册 Actuator 运维与手动触发端点 (/actuator/xxljobs)
     *
     * @param properties 配置属性
     * @return Actuator 端点实例
     */
    @Bean
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnMissingBean
    public XxlJobEndpoint xxlJobEndpoint(XxlJobProperties properties) {
        return new XxlJobEndpoint(properties);
    }

    /**
     * 注册 XXL-Job 3.x OpenAPI 编程式管理门面
     *
     * <p>提供基于官方 AdminJobBiz OpenAPI 的强类型增删改查、启动停止与即时触发功能，并内置集群多调度中心容灾故障转移机制。</p>
     *
     * @param properties 配置属性
     * @return OpenAPI 管理门面
     */
    @Bean
    @ConditionalOnMissingBean
    public XxlJobAdminTemplate xxlJobAdminTemplate(XxlJobProperties properties) {
        return new XxlJobAdminTemplate(properties);
    }

    /**
     * 联动 MyBatis-Plus 流式游标批处理配置
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate")
    public static class XxlJobMyBatisConfiguration {

        @Bean
        @ConditionalOnMissingBean(XxlShardingBatchTemplate.class)
        public XxlMyBatisStreamingTemplate xxlMyBatisStreamingTemplate(
                @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider) {
            return new XxlMyBatisStreamingTemplate(taskExecutorProvider.getIfAvailable());
        }
    }

    /**
     * 注册分片流式批处理模板
     *
     * <p>提供大数据分片流式步长游标拉取、防 OOM 并发切片消费，并自动向子线程透传四维上下文与 XXL-Job 日志镜像句柄。</p>
     *
     * @param taskExecutorProvider Core 受管异步线程池 Provider
     * @return 分片流式批处理模板实例
     */
    @Bean
    @ConditionalOnMissingBean(XxlShardingBatchTemplate.class)
    public XxlShardingBatchTemplate xxlShardingBatchTemplate(
            @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider) {
        return new XxlShardingBatchTemplate(taskExecutorProvider.getIfAvailable());
    }

    /**
     * 注册多租户隔离遍历执行模板
     *
     * <p>为分布式调度任务提供安全、沙箱化的租户级批量执行能力（对应 JobTenantMode.ITERATE 模式）。</p>
     *
     * @param tenantListProviderProvider 租户列表提供者 Provider
     * @return 多租户遍历执行模板
     */
    @Bean
    @ConditionalOnMissingBean
    public TenantJobTemplate tenantJobTemplate(ObjectProvider<TenantListProvider> tenantListProviderProvider) {
        return new TenantJobTemplate(tenantListProviderProvider.getIfAvailable());
    }

}
