package io.github.atengk.boot.liteflow.autoconfigure;

import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.lifecycle.LifeCycleHolder;
import com.yomahub.liteflow.property.LiteflowConfig;
import com.yomahub.liteflow.springboot.config.LiteflowMainAutoConfiguration;
import com.yomahub.liteflow.springboot.config.LiteflowPropertyAutoConfiguration;
import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.properties.AtengLiteflowProperties;
import io.github.atengk.boot.liteflow.rule.JdbcLiteflowRuleSourceProvider;
import io.github.atengk.boot.liteflow.rule.LiteflowRuleSourceManager;
import io.github.atengk.boot.liteflow.rule.LiteflowRuleSourceProvider;
import io.github.atengk.boot.liteflow.rule.event.LiteflowRuleRefreshListener;
import io.github.atengk.boot.liteflow.thread.FlowTimeoutInterruptionLifeCycle;
import io.github.atengk.boot.liteflow.thread.LiteflowGlobalExecutorBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * ateng-boot LiteFlow 规则编排底座自动装配
 * 负责统一强类型操作门面 LiteflowTemplate 装配、受管线程池全局接管、四维跨线程上下文透传与生产级属性绑定
 *
 * @author Ateng
 * @since 2026-11-03
 */
@AutoConfiguration
@AutoConfigureAfter({LiteflowPropertyAutoConfiguration.class, LiteflowMainAutoConfiguration.class})
@ConditionalOnClass(FlowExecutor.class)
@EnableConfigurationProperties(AtengLiteflowProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.liteflow", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengLiteflowAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengLiteflowAutoConfiguration.class);

    /**
     * 注册受管全局异步执行器构造器
     */
    @Bean
    @ConditionalOnMissingBean
    public LiteflowGlobalExecutorBuilder liteflowGlobalExecutorBuilder() {
        return new LiteflowGlobalExecutorBuilder();
    }

    /**
     * 注册 LiteFlow 流程执行超时协同中断生命周期拦截器
     */
    @Bean
    @ConditionalOnMissingBean
    public FlowTimeoutInterruptionLifeCycle flowTimeoutInterruptionLifeCycle() {
        FlowTimeoutInterruptionLifeCycle lifeCycle = new FlowTimeoutInterruptionLifeCycle();
        boolean alreadyAdded = LifeCycleHolder.getPostProcessFlowExecuteLifeCycleList().stream()
                .anyMatch(lc -> lc instanceof FlowTimeoutInterruptionLifeCycle);
        if (!alreadyAdded) {
            LifeCycleHolder.addLifeCycle(lifeCycle);
        }
        return lifeCycle;
    }

    /**
     * 单例初始化后置定制器，确保在 Spring 容器生命周期内可靠接管 LiteflowConfig
     */
    @Bean
    @ConditionalOnMissingBean
    public LiteflowConfigCustomizer liteflowConfigCustomizer(ObjectProvider<LiteflowConfig> liteflowConfigProvider,
                                                             AtengLiteflowProperties properties) {
        return new LiteflowConfigCustomizer(liteflowConfigProvider, properties);
    }

    /**
     * 注册 LiteFlow 统一强类型操作门面
     * 保留底层 FlowExecutor 官方逃生舱的同时，提供自动校验解包与因果异常转译
     */
    @Bean
    @ConditionalOnBean(FlowExecutor.class)
    @ConditionalOnMissingBean(LiteflowTemplate.class)
    public LiteflowTemplate liteflowTemplate(FlowExecutor flowExecutor,
                                             ObjectProvider<Executor> executorProvider,
                                             AtengLiteflowProperties properties) {
        return new LiteflowTemplate(flowExecutor, executorProvider.getIfAvailable(), properties);
    }

    /**
     * 注册基于 JDBC 的数据库动态规则提供者（当存在 DataSource 且配置启用时激活）
     */
    @Bean
    @ConditionalOnClass(DataSource.class)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "ateng.boot.liteflow.dynamic-rule.database", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(JdbcLiteflowRuleSourceProvider.class)
    public JdbcLiteflowRuleSourceProvider jdbcLiteflowRuleSourceProvider(DataSource dataSource, AtengLiteflowProperties properties) {
        return new JdbcLiteflowRuleSourceProvider(dataSource, properties.getDynamicRule().getDatabase());
    }

    /**
     * 注册多源动态规则管理器
     */
    @Bean
    @ConditionalOnBean(LiteflowTemplate.class)
    @ConditionalOnProperty(prefix = "ateng.boot.liteflow.dynamic-rule", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(LiteflowRuleSourceManager.class)
    public LiteflowRuleSourceManager liteflowRuleSourceManager(ObjectProvider<List<LiteflowRuleSourceProvider>> providersProvider,
                                                               LiteflowTemplate liteflowTemplate,
                                                               AtengLiteflowProperties properties) {
        List<LiteflowRuleSourceProvider> providers = providersProvider.getIfAvailable(Collections::emptyList);
        return new LiteflowRuleSourceManager(providers, liteflowTemplate, properties);
    }

    /**
     * 注册 Spring 领域事件热重载监听器
     */
    @Bean
    @ConditionalOnBean(LiteflowTemplate.class)
    @ConditionalOnProperty(prefix = "ateng.boot.liteflow.dynamic-rule", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(LiteflowRuleRefreshListener.class)
    public LiteflowRuleRefreshListener liteflowRuleRefreshListener(ObjectProvider<LiteflowRuleSourceManager> ruleSourceManagerProvider,
                                                                   LiteflowTemplate liteflowTemplate) {
        return new LiteflowRuleRefreshListener(ruleSourceManagerProvider.getIfAvailable(), liteflowTemplate);
    }

    /**
     * LiteflowConfig 运行时属性定制器
     */
    public static class LiteflowConfigCustomizer implements SmartInitializingSingleton {
        private final ObjectProvider<LiteflowConfig> liteflowConfigProvider;
        private final AtengLiteflowProperties properties;

        public LiteflowConfigCustomizer(ObjectProvider<LiteflowConfig> liteflowConfigProvider,
                                        AtengLiteflowProperties properties) {
            this.liteflowConfigProvider = liteflowConfigProvider;
            this.properties = properties;
        }

        @Override
        public void afterSingletonsInstantiated() {
            LiteflowConfig config = liteflowConfigProvider.getIfAvailable();
            if (config == null) {
                return;
            }

            // 1. 接管全局异步线程池构建器
            String currentExecutor = config.getGlobalThreadPoolExecutorClass();
            if (currentExecutor == null || currentExecutor.isBlank()
                    || "com.yomahub.liteflow.thread.LiteFlowDefaultGlobalExecutorBuilder".equals(currentExecutor)) {
                String configuredClass = properties.getThreadPool().getExecutorClass();
                String targetClass = (configuredClass != null && !configuredClass.isBlank())
                        ? configuredClass
                        : LiteflowGlobalExecutorBuilder.class.getName();
                config.setGlobalThreadPoolExecutorClass(targetClass);
                log.info("ateng-boot-liteflow: 全局异步线程池接管配置生效 -> [{}]", targetClass);
            }

            // 2. 默认关闭原生虚拟线程直调，交由受管线程池全局代理四维上下文
            if (config.getEnableVirtualThread() != null && config.getEnableVirtualThread()) {
                config.setEnableVirtualThread(false);
                log.info("ateng-boot-liteflow: 已将 liteflow.enable-virtual-thread 校准为 false，由受管线程池统一接管四维上下文透传");
            }

            // 3. 桥接 ruleSource 默认值
            if ((config.getRuleSource() == null || config.getRuleSource().isBlank())
                    && properties.getRuleSource() != null && !properties.getRuleSource().isBlank()) {
                config.setRuleSource(properties.getRuleSource());
            }

            // 4. 桥接 printExecutionLog
            if (config.getPrintExecutionLog() == null) {
                config.setPrintExecutionLog(properties.isPrintExecutionLog());
            }

            // 5. 桥接 whenMaxWaitTimeout 并行超时阈值
            if (properties.getWhenMaxWaitTimeout() != null && !properties.getWhenMaxWaitTimeout().isZero()
                    && !properties.getWhenMaxWaitTimeout().isNegative()) {
                long millis = properties.getWhenMaxWaitTimeout().toMillis();
                config.setWhenMaxWaitTime((int) millis);
                config.setWhenMaxWaitTimeUnit(TimeUnit.MILLISECONDS);
                log.info("ateng-boot-liteflow: 并行编排 WHEN 全局超时阈值已配置 -> [{} ms]", millis);
            }
        }
    }

    /**
     * 配置后置处理器，自动将 LiteFlow 的 globalThreadPoolExecutorClass 引导至受管构建器
     */
    @Bean
    public static BeanPostProcessor liteflowConfigPostProcessor(AtengLiteflowProperties properties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof LiteflowConfig config) {
                    // 1. 若当前配置为默认构建器或为空，自动接管为受管全局构建器
                    String currentExecutor = config.getGlobalThreadPoolExecutorClass();
                    if (currentExecutor == null || currentExecutor.isBlank()
                            || "com.yomahub.liteflow.thread.LiteFlowDefaultGlobalExecutorBuilder".equals(currentExecutor)) {
                        String configuredClass = properties.getThreadPool().getExecutorClass();
                        String targetClass = (configuredClass != null && !configuredClass.isBlank())
                                ? configuredClass
                                : LiteflowGlobalExecutorBuilder.class.getName();
                        config.setGlobalThreadPoolExecutorClass(targetClass);
                        log.info("ateng-boot-liteflow: 全局异步线程池接管配置生效 -> [{}]", targetClass);
                    }

                    // 2. 关闭原生虚拟线程直调，交由受管线程池全局代理四维上下文
                    if (config.getEnableVirtualThread() != null && config.getEnableVirtualThread()) {
                        config.setEnableVirtualThread(false);
                        log.info("ateng-boot-liteflow: 已将 liteflow.enable-virtual-thread 校准为 false，由受管线程池统一接管四维上下文透传");
                    }

                    // 3. 桥接 ruleSource 默认值
                    if ((config.getRuleSource() == null || config.getRuleSource().isBlank())
                            && properties.getRuleSource() != null && !properties.getRuleSource().isBlank()) {
                        config.setRuleSource(properties.getRuleSource());
                    }

                    // 4. 桥接 printExecutionLog
                    if (config.getPrintExecutionLog() == null) {
                        config.setPrintExecutionLog(properties.isPrintExecutionLog());
                    }
                }
                return bean;
            }
        };
    }
}
