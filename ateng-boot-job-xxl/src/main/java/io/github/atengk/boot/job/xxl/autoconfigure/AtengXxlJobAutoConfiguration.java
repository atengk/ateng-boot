package io.github.atengk.boot.job.xxl.autoconfigure;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * XXL-Job 分布式任务调度执行器自动装配类
 *
 * <p>遵循 Spring Boot 3 规范与防御性装配原则，在配置了核心调度中心地址时自动构建 {@link XxlJobSpringExecutor}，
 * 在未配置或空地址时平滑降级旁路，杜绝应用启动崩溃。</p>
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
     * 注册并初始化 XXL-Job Spring 执行器
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

        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
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

}
