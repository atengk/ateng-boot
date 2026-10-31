package io.github.atengk.boot.job.xxl.autoconfigure;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import io.github.atengk.boot.job.xxl.aspect.XxlJobContextAspect;
import io.github.atengk.boot.job.xxl.executor.AtengXxlJobSpringExecutor;
import io.github.atengk.boot.job.xxl.logback.XxlJobLogbackAppenderRegistrar;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.github.atengk.boot.job.xxl.watchdog.LocalWatchdog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
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
@AutoConfiguration
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

        log.info("开始装配 XXL-Job 执行器 [AppName: {}, AdminAddresses: {}, Port: {}]",
                properties.getAppname(), properties.getAdminAddresses(), properties.getPort());

        AtengXxlJobSpringExecutor executor = new AtengXxlJobSpringExecutor();
        executor.setAdminAddresses(properties.getAdminAddresses());
        executor.setAppname(properties.getAppname());

        if (StringUtils.hasText(properties.getAccessToken())) {
            executor.setAccessToken(properties.getAccessToken());
        } else {
            executor.setAccessToken("default_token");
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
     * 注册任务执行四维上下文、链路追踪与受管超时看门狗切面
     *
     * <p>自动为由 @XxlJob 或 @AtengXxlJob 标注的方法织入 32 位无锁 MDC traceId、操作人、多租户隔离与看门狗超时中断守护。</p>
     *
     * @param watchdogProvider     看门狗 Provider
     * @param properties           配置属性
     * @param taskExecutorProvider Core 受管异步线程池 Provider
     * @return 上下文切面实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.job.xxl", name = "context-aspect-enabled", havingValue = "true", matchIfMissing = true)
    public XxlJobContextAspect xxlJobContextAspect(
            ObjectProvider<LocalWatchdog> watchdogProvider,
            XxlJobProperties properties,
            @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider) {
        return new XxlJobContextAspect(
                watchdogProvider.getIfAvailable(),
                properties,
                taskExecutorProvider.getIfAvailable()
        );
    }

}
