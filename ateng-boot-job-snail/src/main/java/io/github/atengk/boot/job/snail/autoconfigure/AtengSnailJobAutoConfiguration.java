package io.github.atengk.boot.job.snail.autoconfigure;

import com.aizuda.snailjob.client.common.config.SnailJobProperties.ServerConfig;
import io.github.atengk.boot.core.autoconfigure.AtengAsyncAutoConfiguration;
import io.github.atengk.boot.job.snail.aspect.SnailJobContextAspect;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.github.atengk.boot.job.snail.retry.aspect.SnailRetryContextAspect;
import io.github.atengk.boot.job.snail.retry.generator.ArgsHashIdempotentIdGenerator;
import io.github.atengk.boot.job.snail.retry.generator.SpelIdempotentIdGenerator;
import io.github.atengk.boot.job.snail.retry.template.SnailRetryTemplate;
import io.github.atengk.boot.job.snail.watchdog.SnailLocalWatchdog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * SnailJob 分布式任务调度与分布式重试双擎自动装配入口
 *
 * <p>遵循 Spring Boot 3 规范与防御性装配四道铁律：
 * 1. 标准规范注册于 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}；
 * 2. 缺配置平滑探测：当未配置关键外部服务端连接信息时，自动告警并平滑旁路，严禁应用启动崩溃；
 * 3. 每一个公开 {@code @Bean} 严格标配 {@link ConditionalOnMissingBean}，支持业务方平滑接管；
 * 4. 显式声明在 {@link AtengAsyncAutoConfiguration} 之后装配，保障受管线程池设施优先就绪。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@AutoConfiguration(after = AtengAsyncAutoConfiguration.class)
@ConditionalOnClass(com.aizuda.snailjob.client.common.config.SnailJobProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.job.snail", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SnailJobProperties.class)
public class AtengSnailJobAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengSnailJobAutoConfiguration.class);

    /**
     * SnailJob 配置属性桥接适配器
     *
     * <p>将 {@code ateng.boot.job.snail.*} 强类型配置平滑同步映射至 SnailJob 原生客户端属性模型，
     * 支持服务端地址缺省探测与静默旁路保护。</p>
     *
     * @param properties ateng SnailJob 统一配置属性
     * @param nativePropertiesProvider SnailJob 官方原生配置属性提供者
     * @param environment Spring 运行期环境变量
     * @return 属性桥接适配器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SnailJobPropertiesBridge snailJobPropertiesBridge(
            SnailJobProperties properties,
            ObjectProvider<com.aizuda.snailjob.client.common.config.SnailJobProperties> nativePropertiesProvider,
            Environment environment) {

        if (!StringUtils.hasText(properties.getServer())) {
            log.warn("SnailJob 自动装配已激活，但未检测到有效服务端地址 ('ateng.boot.job.snail.server' 为空)，已平滑旁路跳过。");
            return new SnailJobPropertiesBridge(properties, false);
        }

        com.aizuda.snailjob.client.common.config.SnailJobProperties nativeProps = nativePropertiesProvider.getIfAvailable();
        if (nativeProps != null) {
            syncNativeProperties(properties, nativeProps, environment);
        }

        log.info("SnailJob 双擎加固底座初始化就绪 [Server: {}, Namespace: {}, Group: {}, Port: {}]",
                properties.getServer(),
                properties.getNamespace(),
                StringUtils.hasText(properties.getGroup()) ? properties.getGroup() : "<默认应用名>",
                properties.getPort());

        return new SnailJobPropertiesBridge(properties, true);
    }

    /**
     * SnailJob 本地硬超时看门狗引擎
     *
     * @param eventPublisher Spring 领域事件发布器
     * @return SnailLocalWatchdog 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SnailLocalWatchdog snailLocalWatchdog(ApplicationEventPublisher eventPublisher) {
        return new SnailLocalWatchdog(eventPublisher);
    }

    /**
     * SnailJob 调度任务四维上下文与声明式增强切面
     *
     * @param watchdog 本地硬看门狗
     * @param properties SnailJob 配置属性
     * @return SnailJobContextAspect 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.job.snail.job", name = "context-aspect-enabled", havingValue = "true", matchIfMissing = true)
    public SnailJobContextAspect snailJobContextAspect(
            SnailLocalWatchdog watchdog,
            SnailJobProperties properties) {
        return new SnailJobContextAspect(watchdog, properties);
    }

    /**
     * SnailJob SpEL 业务幂等键生成器
     *
     * @return SpelIdempotentIdGenerator 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SpelIdempotentIdGenerator spelIdempotentIdGenerator() {
        return new SpelIdempotentIdGenerator();
    }

    /**
     * SnailJob 参数哈希摘要幂等键生成器
     *
     * @return ArgsHashIdempotentIdGenerator 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ArgsHashIdempotentIdGenerator argsHashIdempotentIdGenerator() {
        return new ArgsHashIdempotentIdGenerator();
    }

    /**
     * SnailJob 分布式重试四维现场快照与跨节点保真还原切面
     *
     * @return SnailRetryContextAspect 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.job.snail.retry", name = "context-aspect-enabled", havingValue = "true", matchIfMissing = true)
    public SnailRetryContextAspect snailRetryContextAspect() {
        return new SnailRetryContextAspect();
    }

    /**
     * SnailJob 编程式分布式重试执行与对称手动上报门面
     *
     * @param reportProvider SnailJob 上报门面对象提供者
     * @param properties SnailJob 配置属性
     * @return SnailRetryTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.job.snail.retry", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SnailRetryTemplate snailRetryTemplate(
            ObjectProvider<com.aizuda.snailjob.client.core.Report> reportProvider,
            SnailJobProperties properties) {
        return new SnailRetryTemplate(reportProvider, properties);
    }

    /**
     * 将 ateng 统一配置同步填充至 SnailJob 官方原生配置对象
     */
    private void syncNativeProperties(
            SnailJobProperties source,
            com.aizuda.snailjob.client.common.config.SnailJobProperties target,
            Environment environment) {

        // 1. 同步服务端地址解析
        String server = source.getServer();
        if (StringUtils.hasText(server)) {
            String[] parts = server.split(":");
            ServerConfig serverConfig = target.getServer();
            if (serverConfig == null) {
                serverConfig = new ServerConfig();
                target.setServer(serverConfig);
            }
            if (parts.length >= 2) {
                serverConfig.setHost(parts[0].trim());
                try {
                    serverConfig.setPort(Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                    // 保底默认端口
                    serverConfig.setPort(17888);
                }
            } else {
                serverConfig.setHost(server.trim());
            }
        }

        // 2. 同步命名空间与令牌
        if (StringUtils.hasText(source.getNamespace())) {
            target.setNamespace(source.getNamespace());
        }
        if (StringUtils.hasText(source.getToken())) {
            target.setToken(source.getToken());
        }

        // 3. 同步本地客户端端口
        if (source.getPort() != null) {
            target.setPort(source.getPort());
        }

        // 4. 同步应用组名称 (未显式指定 group 则从 spring.application.name 获取)
        String group = source.getGroup();
        if (!StringUtils.hasText(group)) {
            group = environment.getProperty("spring.application.name");
        }
        if (StringUtils.hasText(group)) {
            target.setGroup(group);
        }
    }

    /**
     * SnailJob 属性桥接指示器（封装配置激活与服务端有效性状态）
     */
    public static class SnailJobPropertiesBridge {

        private final SnailJobProperties properties;
        private final boolean serverConfigured;

        public SnailJobPropertiesBridge(SnailJobProperties properties, boolean serverConfigured) {
            this.properties = properties;
            this.serverConfigured = serverConfigured;
        }

        public SnailJobProperties getProperties() {
            return properties;
        }

        public boolean isServerConfigured() {
            return serverConfigured;
        }
    }
}
