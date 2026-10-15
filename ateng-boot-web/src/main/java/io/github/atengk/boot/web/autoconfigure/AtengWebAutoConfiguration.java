package io.github.atengk.boot.web.autoconfigure;

import io.github.atengk.boot.web.handler.GlobalExceptionHandler;
import io.github.atengk.boot.web.jackson.JacksonCustomizer;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.trace.TraceFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Web 运行时增强自动配置类
 * 负责装配全链路追踪 TraceFilter、全局异常防御处理器、Jackson 日期与长整型定制，以及可选的全局 CORS 跨域过滤器
 *
 * @author Ateng
 * @since 2026-10-15
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(WebProperties.class)
public class AtengWebAutoConfiguration {

    /**
     * 注册全链路追踪过滤器，显式指定最高优先级确保最先执行与最后清理
     *
     * @param webProperties 配置属性
     * @return FilterRegistrationBean 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.web.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "traceFilterRegistration")
    public org.springframework.boot.web.servlet.FilterRegistrationBean<TraceFilter> traceFilterRegistration(WebProperties webProperties) {
        TraceFilter filter = new TraceFilter(webProperties);
        org.springframework.boot.web.servlet.FilterRegistrationBean<TraceFilter> registration =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setOrder(TraceFilter.ORDER);
        return registration;
    }

    /**
     * 注册全局统一异常安全拦截处理器
     *
     * @return GlobalExceptionHandler 实例
     */
    @Bean
    @ConditionalOnMissingBean(GlobalExceptionHandler.class)
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /**
     * 注册 Jackson 格式化定制器
     *
     * @param webProperties 配置属性
     * @return JacksonCustomizer 实例
     */
    @Bean
    @ConditionalOnClass(Jackson2ObjectMapperBuilderCustomizer.class)
    @ConditionalOnMissingBean(JacksonCustomizer.class)
    public JacksonCustomizer jacksonCustomizer(WebProperties webProperties) {
        return new JacksonCustomizer(webProperties);
    }

    /**
     * 条件装配全局 CORS 跨域过滤器
     *
     * @param webProperties 配置属性
     * @return CorsFilter 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.web.cors", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(CorsFilter.class)
    public CorsFilter corsFilter(WebProperties webProperties) {
        WebProperties.CorsProperties cors = webProperties.getCors();
        CorsConfiguration config = new CorsConfiguration();

        if (cors.getAllowedOrigins() != null && !cors.getAllowedOrigins().isEmpty()) {
            config.setAllowedOriginPatterns(cors.getAllowedOrigins());
        }
        if (cors.getAllowedMethods() != null && !cors.getAllowedMethods().isEmpty()) {
            config.setAllowedMethods(cors.getAllowedMethods());
        }
        if (cors.getAllowedHeaders() != null && !cors.getAllowedHeaders().isEmpty()) {
            config.setAllowedHeaders(cors.getAllowedHeaders());
        }
        if (cors.getAllowCredentials() != null) {
            config.setAllowCredentials(cors.getAllowCredentials());
        }
        if (cors.getMaxAge() != null) {
            config.setMaxAge(cors.getMaxAge());
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
