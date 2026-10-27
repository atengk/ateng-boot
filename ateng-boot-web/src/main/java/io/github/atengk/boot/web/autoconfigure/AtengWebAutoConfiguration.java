package io.github.atengk.boot.web.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.web.aspect.OperateLogAspect;
import io.github.atengk.boot.web.aspect.WebLogAspect;
import io.github.atengk.boot.web.audit.DefaultOperateLogStorageProvider;
import io.github.atengk.boot.web.audit.OperateLogStorageProvider;
import io.github.atengk.boot.web.filter.ContentCachingFilter;
import io.github.atengk.boot.web.filter.PayloadLoggingFilter;
import io.github.atengk.boot.web.filter.SecurityHeadersFilter;
import io.github.atengk.boot.web.filter.SlowRequestFilter;
import io.github.atengk.boot.web.xss.XssCleaner;
import io.github.atengk.boot.web.xss.XssFilter;
import io.github.atengk.boot.web.xss.XssStringDeserializer;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import io.github.atengk.boot.web.handler.GlobalExceptionCustomizer;
import io.github.atengk.boot.web.handler.GlobalExceptionHandler;
import io.github.atengk.boot.web.jackson.JacksonCustomizer;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.trace.TraceFilter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.util.unit.DataSize;
import java.util.concurrent.Executor;
import io.github.atengk.boot.web.cors.CorsOriginProvider;
import io.github.atengk.boot.web.cors.DefaultCorsOriginProvider;
import io.github.atengk.boot.web.cors.DynamicCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Web 运行时增强自动配置类
 * 负责装配全链路追踪 TraceFilter、全局异常防御处理器、Jackson 日期与长整型定制，以及可选的全局 CORS 跨域过滤器与 Web 接口审计日志切面 WebLogAspect
 *
 * @author Ateng
 * @since 2026-10-15
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ateng.boot.web", name = "enabled", havingValue = "true", matchIfMissing = true)
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
     * 条件装配通用的请求体可重读与防爆过滤器
     * 仅在开启 API 加签验签或 Payload 采样日志时激活，关闭时零包装开销
     *
     * @param webProperties 配置属性
     * @return FilterRegistrationBean 实例
     */
    @Bean(name = {"contentCachingFilterRegistration", "requestBodyCachingFilterRegistration"})
    @Conditional(ContentCachingCondition.class)
    @ConditionalOnMissingBean(name = "contentCachingFilterRegistration")
    public FilterRegistrationBean<ContentCachingFilter> contentCachingFilterRegistration(WebProperties webProperties) {
        DataSize maxBodySize = webProperties.getBodyCache().getMaxBodySize();
        if (webProperties.getSign().isEnabled() && webProperties.getSign().getMaxBodySize() != null) {
            maxBodySize = webProperties.getSign().getMaxBodySize();
        }
        ContentCachingFilter filter = new ContentCachingFilter(maxBodySize);
        FilterRegistrationBean<ContentCachingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(ContentCachingFilter.ORDER);
        return registration;
    }

    /**
     * 条件注册 HTTP Payload 双向采样安全日志过滤器
     * 位于 ContentCachingFilter 之后，支持对请求体与响应体执行安全采样并输出聚合日志
     *
     * @param webProperties Web 配置属性
     * @param payloadLoggingFilterProvider 自定义 PayloadLoggingFilter 提供者
     * @return FilterRegistrationBean 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.web.payload-log", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "payloadLoggingFilterRegistration")
    public FilterRegistrationBean<PayloadLoggingFilter> payloadLoggingFilterRegistration(
            WebProperties webProperties,
            ObjectProvider<PayloadLoggingFilter> payloadLoggingFilterProvider) {
        PayloadLoggingFilter filter = payloadLoggingFilterProvider.getIfAvailable(() ->
                new PayloadLoggingFilter(webProperties.getPayloadLog()));
        FilterRegistrationBean<PayloadLoggingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(PayloadLoggingFilter.ORDER);
        return registration;
    }

    /**
     * 注册慢请求性能主动告警过滤器
     * 位于链路追踪与可重读过滤器之后，在请求出口处统计总耗时，超过阈值时输出告警日志
     *
     * @param webProperties Web 配置属性
     * @param slowRequestFilterProvider 自定义 SlowRequestFilter 提供者
     * @return FilterRegistrationBean 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.web.slow-request", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "slowRequestFilterRegistration")
    public FilterRegistrationBean<SlowRequestFilter> slowRequestFilterRegistration(
            WebProperties webProperties,
            ObjectProvider<SlowRequestFilter> slowRequestFilterProvider) {
        SlowRequestFilter filter = slowRequestFilterProvider.getIfAvailable(() ->
                new SlowRequestFilter(webProperties.getSlowRequest()));
        FilterRegistrationBean<SlowRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(SlowRequestFilter.ORDER);
        return registration;
    }

    /**
     * 注册 HTTP 安全响应头防御与容器指纹清洗过滤器
     * 位于慢请求监控过滤器之后，开箱即用注入 OWASP 与等保三级推荐标准基线安全响应头
     *
     * @param webProperties Web 配置属性
     * @param securityHeadersFilterProvider 自定义 SecurityHeadersFilter 提供者
     * @return FilterRegistrationBean 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.web.security-headers", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "securityHeadersFilterRegistration")
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilterRegistration(
            WebProperties webProperties,
            ObjectProvider<SecurityHeadersFilter> securityHeadersFilterProvider) {
        SecurityHeadersFilter filter = securityHeadersFilterProvider.getIfAvailable(() ->
                new SecurityHeadersFilter(webProperties.getSecurityHeaders()));
        FilterRegistrationBean<SecurityHeadersFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(SecurityHeadersFilter.ORDER);
        return registration;
    }

    /**
     * 条件装配 Spring 官方 ShallowEtagHeaderFilter 协商缓存过滤器
     * 对出站响应体计算 ETag 并支持 304 Not Modified 条件协商以降低重复网络开销
     *
     * @param webProperties Web 配置属性
     * @return FilterRegistrationBean 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.web.etag", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "shallowEtagHeaderFilterRegistration")
    public FilterRegistrationBean<ShallowEtagHeaderFilter> shallowEtagHeaderFilterRegistration(WebProperties webProperties) {
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter();
        filter.setWriteWeakETag(webProperties.getEtag().isWriteWeakETag());
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(SecurityHeadersFilter.ORDER + 1);
        return registration;
    }

    /**
     * 注册 XSS 安全清洗器
     *
     * @param webProperties Web 配置属性
     * @return XssCleaner 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.web.xss", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(XssCleaner.class)
    public XssCleaner xssCleaner(WebProperties webProperties) {
        return new XssCleaner(webProperties.getXss().getMode());
    }

    /**
     * 注册 XSS 跨站脚本安全清洗过滤器
     *
     * @param webProperties Web 配置属性
     * @param xssCleaner    XSS 清洗器
     * @param xssFilterProvider 自定义 XssFilter 提供者
     * @return FilterRegistrationBean 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.web.xss", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "xssFilterRegistration")
    public FilterRegistrationBean<XssFilter> xssFilterRegistration(
            WebProperties webProperties,
            XssCleaner xssCleaner,
            ObjectProvider<XssFilter> xssFilterProvider) {
        XssFilter filter = xssFilterProvider.getIfAvailable(() ->
                new XssFilter(webProperties.getXss(), xssCleaner));
        FilterRegistrationBean<XssFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(XssFilter.ORDER);
        return registration;
    }

    /**
     * 注册 Jackson JSON 字符串入参 XSS 安全清洗定制器
     *
     * @param xssCleaner XSS 清洗器
     * @return Jackson2ObjectMapperBuilderCustomizer 实例
     */
    @Bean
    @ConditionalOnClass(Jackson2ObjectMapperBuilderCustomizer.class)
    @ConditionalOnProperty(prefix = "ateng.boot.web.xss", name = "enabled", havingValue = "true")
    public Jackson2ObjectMapperBuilderCustomizer xssJacksonCustomizer(XssCleaner xssCleaner) {
        return builder -> builder.deserializerByType(String.class, new XssStringDeserializer(xssCleaner));
    }

    /**
     * 注册全局统一异常安全拦截处理器
     * 自动感知并链式装配容器中的 GlobalExceptionCustomizer 扩展点
     *
     * @param customizerProvider 全局异常定制器提供者
     * @return GlobalExceptionHandler 实例
     */
    @Bean
    @ConditionalOnMissingBean(GlobalExceptionHandler.class)
    public GlobalExceptionHandler globalExceptionHandler(ObjectProvider<GlobalExceptionCustomizer> customizerProvider) {
        return new GlobalExceptionHandler(customizerProvider.orderedStream().toList());
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
     * 注册默认动态 CORS 来源提供者
     *
     * @param webProperties 配置属性
     * @return CorsOriginProvider 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.web.cors", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(CorsOriginProvider.class)
    public CorsOriginProvider corsOriginProvider(WebProperties webProperties) {
        return new DefaultCorsOriginProvider(webProperties.getCors());
    }

    /**
     * 注册动态 CORS 配置源
     *
     * @param originProvider 跨域来源提供者 SPI
     * @param webProperties  配置属性
     * @return CorsConfigurationSource 实例
     */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "ateng.boot.web.cors", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "corsConfigurationSource")
    public CorsConfigurationSource corsConfigurationSource(CorsOriginProvider originProvider,
                                                           WebProperties webProperties) {
        return new DynamicCorsConfigurationSource(originProvider, webProperties.getCors());
    }

    /**
     * 条件装配全局 CORS 跨域过滤器
     *
     * @param corsConfigurationSource 跨域配置源
     * @return CorsFilter 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.web.cors", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(CorsFilter.class)
    public CorsFilter corsFilter(@Qualifier("corsConfigurationSource") CorsConfigurationSource corsConfigurationSource) {
        return new CorsFilter(corsConfigurationSource);
    }

    /**
     * 注册 CORS 跨域过滤器 FilterRegistrationBean，显式指定高优先级保证在安全与业务过滤器前执行
     *
     * @param corsFilter CorsFilter 实例
     * @return FilterRegistrationBean 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.web.cors", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "corsFilterRegistration")
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(CorsFilter corsFilter) {
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(corsFilter);
        registration.setOrder(TraceFilter.ORDER + 10);
        return registration;
    }

    /**
     * 注册 Web 接口审计日志切面
     *
     * @param objectMapperProvider Jackson ObjectMapper 提供者
     * @return WebLogAspect 切面实例
     */
    @Bean
    @ConditionalOnClass({ProceedingJoinPoint.class, Aspect.class})
    @ConditionalOnProperty(prefix = "ateng.boot.web.log", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(WebLogAspect.class)
    public WebLogAspect webLogAspect(ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new WebLogAspect(objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }

    /**
     * 注册默认操作审计存储提供者（基于 SLF4J 输出格式化 JSON）
     *
     * @param objectMapperProvider Jackson ObjectMapper 提供者
     * @return OperateLogStorageProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean(OperateLogStorageProvider.class)
    public OperateLogStorageProvider defaultOperateLogStorageProvider(ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new DefaultOperateLogStorageProvider(objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }

    /**
     * 注册结构化操作审计切面
     *
     * @param storageProvider 审计存储 SPI 提供者
     * @param taskExecutorProvider 异步任务执行器提供者
     * @param objectMapperProvider Jackson ObjectMapper 提供者
     * @param eventPublisherProvider 事件发布者提供者
     * @param webProperties Web 配置属性
     * @return OperateLogAspect 实例
     */
    @Bean
    @ConditionalOnClass({ProceedingJoinPoint.class, Aspect.class})
    @ConditionalOnProperty(prefix = "ateng.boot.web.operate-log", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(OperateLogAspect.class)
    public OperateLogAspect operateLogAspect(OperateLogStorageProvider storageProvider,
                                            @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider,
                                            ObjectProvider<ObjectMapper> objectMapperProvider,
                                            ObjectProvider<ApplicationEventPublisher> eventPublisherProvider,
                                            WebProperties webProperties) {
        return new OperateLogAspect(
                storageProvider,
                taskExecutorProvider,
                objectMapperProvider.getIfAvailable(ObjectMapper::new),
                eventPublisherProvider.getIfAvailable(),
                webProperties
        );
    }

    /**
     * 容器初始化完成后自动将受管的定制 ObjectMapper 绑定至 JsonUtil 静态门面
     * 兑现 ADR-0025 架构承诺，使静态门面与 Spring 容器序列化规则完全保持自洽
     *
     * @param objectMapperProvider ObjectMapper 提供者
     * @return SmartInitializingSingleton 实例
     */
    @Bean
    @ConditionalOnClass(io.github.atengk.boot.core.util.JsonUtil.class)
    public org.springframework.beans.factory.SmartInitializingSingleton jsonUtilInitializer(
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        return () -> {
            ObjectMapper objectMapper = objectMapperProvider.getIfAvailable();
            if (objectMapper != null) {
                io.github.atengk.boot.core.util.JsonUtil.init(objectMapper);
            }
        };
    }
}
