package io.github.atengk.boot.web.shutdown.autoconfigure;

import io.github.atengk.boot.web.autoconfigure.AtengWebAutoConfiguration;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.shutdown.AtengGracefulShutdownHandler;
import io.github.atengk.boot.web.shutdown.PreStopController;
import io.github.atengk.boot.web.shutdown.PreStopEndpoint;
import io.github.atengk.boot.web.trace.TraceFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.web.PathMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Map;

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
@AutoConfigureAfter(
        value = AtengWebAutoConfiguration.class,
        name = "org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration"
)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ateng.boot.web.shutdown", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WebProperties.class)
public class AtengGracefulShutdownAutoConfiguration {

    /**
     * 注册优雅停机与流量排空核心处理器（含 SmartLifecycle 兜底及在途请求感知）
     *
     * @param webProperties                 配置属性
     * @param traceFilterRegistrationProvider TraceFilter 注册包装器提供者
     * @param traceFilterProvider           TraceFilter 直接 Bean 提供者（兜底）
     * @return AtengGracefulShutdownHandler 实例
     */
    @Bean
    @ConditionalOnMissingBean(AtengGracefulShutdownHandler.class)
    public AtengGracefulShutdownHandler gracefulShutdownHandler(
            WebProperties webProperties,
            @Qualifier("traceFilterRegistration") ObjectProvider<FilterRegistrationBean<TraceFilter>> traceFilterRegistrationProvider,
            ObjectProvider<TraceFilter> traceFilterProvider) {
        FilterRegistrationBean<TraceFilter> registration = traceFilterRegistrationProvider.getIfAvailable();
        TraceFilter traceFilter = registration != null ? registration.getFilter() : traceFilterProvider.getIfAvailable();
        return new AtengGracefulShutdownHandler(webProperties, traceFilter);
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

        /**
         * 将 {@code @Endpoint(id = "preStop")} 默认映射至 {@code /actuator/pre-stop}，同时在用户显式覆盖映射时自动让位
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass({PathMapper.class, EndpointId.class})
        static class PreStopPathMapperConfiguration {

            private static final EndpointId PRE_STOP_ENDPOINT_ID = EndpointId.of("preStop");

            /**
             * 注册 {@code preStop} 端点的默认短横线路径映射，使未显式配置映射时固定暴露在 {@code /actuator/pre-stop}。
             *
             * @param environment Spring 运行环境，用于探测显式 {@code management.endpoints.web.path-mapping} 覆盖配置
             * @return 兼容用户覆写的 {@link PathMapper}
             */
            @Bean(name = "atengPreStopEndpointPathMapper")
            @ConditionalOnMissingBean(name = "atengPreStopEndpointPathMapper")
            public PathMapper atengPreStopEndpointPathMapper(Environment environment) {
                return endpointId -> {
                    if (!PRE_STOP_ENDPOINT_ID.equals(endpointId)) {
                        return null;
                    }
                    // 若用户显式配置了 management.endpoints.web.path-mapping 覆盖 preStop/pre-stop，让位给 MappingWebEndpointPathMapper
                    if (hasExplicitPreStopPathMapping(environment)) {
                        return null;
                    }
                    return "pre-stop";
                };
            }

            private static boolean hasExplicitPreStopPathMapping(Environment environment) {
                if (environment == null) {
                    return false;
                }
                Map<String, String> configuredMappings = Binder.get(environment)
                        .bind("management.endpoints.web.path-mapping", Bindable.mapOf(String.class, String.class))
                        .orElse(Collections.emptyMap());
                for (Map.Entry<String, String> entry : configuredMappings.entrySet()) {
                    String rawKey = entry.getKey();
                    String mappedPath = entry.getValue();
                    if (!StringUtils.hasText(rawKey) || !StringUtils.hasText(mappedPath)) {
                        continue;
                    }
                    try {
                        if (PRE_STOP_ENDPOINT_ID.equals(EndpointId.fromPropertyValue(rawKey))) {
                            return true;
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 忽略非标准端点键，避免干扰正常路径映射
                    }
                }
                return false;
            }
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
