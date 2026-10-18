package io.github.atengk.boot.web.shutdown.autoconfigure;

import io.github.atengk.boot.web.autoconfigure.AtengWebAutoConfiguration;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.shutdown.AtengGracefulShutdownHandler;
import io.github.atengk.boot.web.shutdown.PreStopController;
import io.github.atengk.boot.web.shutdown.PreStopEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;

/**
 * Kubernetes 云原生就绪探针排空与容器生命周期治理自动装配类
 * <p>
 * 1. 装配 {@link AtengGracefulShutdownHandler} 核心处理器与 SmartLifecycle 兜底逻辑；
 * 2. 当类路径存在 Actuator 时，注册 {@link PreStopEndpoint}（暴露 /actuator/pre-stop）；
 * 3. 当未引入 Actuator 或显式开启时，条件降级注册轻量级 MVC 控制器 {@link PreStopController}（暴露 /pre-stop）。
 *
 * @author Ateng
 * @since 2026-10-18
 */
@AutoConfiguration
@AutoConfigureAfter(AtengWebAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ateng.boot.web.shutdown", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WebProperties.class)
public class AtengGracefulShutdownAutoConfiguration {

    /**
     * 注册优雅停机与流量排空核心处理器（含 SmartLifecycle 兜底）
     *
     * @param webProperties 配置属性
     * @return AtengGracefulShutdownHandler 实例
     */
    @Bean
    @ConditionalOnMissingBean(AtengGracefulShutdownHandler.class)
    public AtengGracefulShutdownHandler gracefulShutdownHandler(WebProperties webProperties) {
        return new AtengGracefulShutdownHandler(webProperties);
    }

    /**
     * Actuator 端点自动装配（当类路径存在 Actuator 且未被显式禁用时激活）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnProperty(prefix = "ateng.boot.web.shutdown", name = "actuator-endpoint-enabled", havingValue = "true", matchIfMissing = true)
    public static class ActuatorShutdownConfiguration {

        @Bean
        @ConditionalOnMissingBean(PreStopEndpoint.class)
        public PreStopEndpoint preStopEndpoint(AtengGracefulShutdownHandler gracefulShutdownHandler) {
            return new PreStopEndpoint(gracefulShutdownHandler);
        }
    }

    /**
     * Spring MVC 降级端点自动装配（在无 Actuator 环境下自动激活，或在配置中显式启用）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Conditional(OnMvcShutdownCondition.class)
    public static class MvcShutdownConfiguration {

        @Bean
        @ConditionalOnMissingBean(PreStopController.class)
        public PreStopController preStopController(AtengGracefulShutdownHandler gracefulShutdownHandler) {
            return new PreStopController(gracefulShutdownHandler);
        }
    }

    /**
     * Spring MVC 降级条件评估器
     */
    static class OnMvcShutdownCondition extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            Boolean mvcExplicit = env.getProperty("ateng.boot.web.shutdown.mvc-endpoint-enabled", Boolean.class);
            if (Boolean.TRUE.equals(mvcExplicit)) {
                return ConditionOutcome.match("ateng.boot.web.shutdown.mvc-endpoint-enabled is explicitly set to true");
            }
            if (Boolean.FALSE.equals(mvcExplicit)) {
                return ConditionOutcome.noMatch("ateng.boot.web.shutdown.mvc-endpoint-enabled is explicitly set to false");
            }

            // 未显式配置时：若类路径缺失 Actuator Endpoint，则自动降级启用 MVC 控制器
            boolean hasActuator = ClassUtils.isPresent(
                    "org.springframework.boot.actuate.endpoint.annotation.Endpoint",
                    context.getClassLoader()
            );
            if (!hasActuator) {
                return ConditionOutcome.match("Actuator Endpoint class is not present on classpath; falling back to Spring MVC preStop controller");
            }
            return ConditionOutcome.noMatch("Actuator Endpoint class is present on classpath; default to Actuator preStop endpoint");
        }
    }
}
