package io.github.atengk.boot.satoken.autoconfigure;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.filter.SaTokenCorsFilterForJakartaServlet;
import cn.dev33.satoken.listener.SaTokenListener;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaFirewallStrategy;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.spi.CurrentTenantProvider;
import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.satoken.aspect.SaCheckDataOwnerAspect;
import io.github.atengk.boot.satoken.aspect.SaCheckSameTokenAspect;
import io.github.atengk.boot.satoken.cache.CachedStpInterface;
import io.github.atengk.boot.satoken.cache.DefaultUserRolePermissionLoader;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.satoken.cache.UserRolePermissionLoader;
import io.github.atengk.boot.satoken.config.SaTokenWebMvcConfigurer;
import io.github.atengk.boot.satoken.dao.SaTokenRedisDao;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.boot.satoken.event.SaTokenEventPublisher;
import io.github.atengk.boot.satoken.handler.SaTokenExceptionHandler;
import io.github.atengk.boot.satoken.interceptor.AtengSaTokenInterceptor;
import io.github.atengk.boot.satoken.openapi.SaTokenOpenApiCustomizer;
import io.github.atengk.boot.satoken.processor.AnonymousUrlProcessor;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.provider.SaTokenCurrentTenantProvider;
import io.github.atengk.boot.satoken.provider.SaTokenCurrentUserProvider;
import io.github.atengk.boot.satoken.spi.SaTokenWhitelistProvider;
import io.github.atengk.boot.satoken.stp.DefaultStpInterface;
import io.github.atengk.boot.satoken.util.LoginHelper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.atengk.boot.satoken.cluster.broadcaster.SaTokenClusterBroadcaster;
import io.github.atengk.boot.satoken.cluster.listener.SaTokenClusterSyncListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import io.github.atengk.boot.satoken.cluster.listener.SaTokenClusterRedisMessageSubscriber;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 核心基础设施与防御性配置自动装配类
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "io.github.atengk.boot.redis.autoconfigure.AtengRedisAutoConfiguration",
        "io.github.atengk.boot.web.autoconfigure.AtengWebAutoConfiguration"
})
@ConditionalOnClass(StpUtil.class)
@EnableConfigurationProperties(SaTokenProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.satoken", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AtengSaTokenAutoConfiguration {

    private final SaTokenProperties properties;

    /**
     * 初始化全局安全防火墙策略，统一错误输出为 Result 契约
     */
    @PostConstruct
    public void initFirewallStrategy() {
        // 自动挂载 Sa-Token 当前操作人与当前租户提取 SPI 至 Core 核心底座
        UserContextHolder.setUserProvider(new SaTokenCurrentUserProvider());
        TenantContextHolder.setTenantProvider(LoginHelper::getTenantId);

        SaFirewallStrategy.instance.checkFailHandle = (e, req, res, extArg) -> {
            log.warn("Sa-Token 安全防火墙拦截异常: message={}", e.getMessage());
            res.setHeader("Content-Type", "application/json;charset=UTF-8");
            res.setStatus(200);
            Result<Void> failResult = Result.fail(CommonResultCode.BAD_REQUEST.getCode(), "非法请求拦截: " + e.getMessage());
            if (res.getSource() instanceof jakarta.servlet.http.HttpServletResponse servletResponse) {
                try {
                    if (!servletResponse.isCommitted()) {
                        servletResponse.setContentType("application/json;charset=UTF-8");
                        servletResponse.getWriter().write(JsonUtil.toJsonString(failResult));
                        servletResponse.getWriter().flush();
                    }
                } catch (Exception ex) {
                    log.error("Sa-Token 防火墙输出异常: ", ex);
                }
            }
        };
    }


    /**
     * 注册 Sa-Token 全局统一异常处理器
     *
     * @return 异常处理器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SaTokenExceptionHandler saTokenExceptionHandler() {
        return new SaTokenExceptionHandler();
    }

    /**
     * 禁用 Sa-Token 官方默认的 CORS Filter，防止与 ateng-boot-web (ADR-0015) 动态跨域过滤器产生多重冲突
     *
     * @param filter 官方 CORS Filter 实例
     * @return 禁用的 FilterRegistrationBean
     */
    @Bean
    @ConditionalOnClass(FilterRegistrationBean.class)
    @ConditionalOnBean(SaTokenCorsFilterForJakartaServlet.class)
    public FilterRegistrationBean<SaTokenCorsFilterForJakartaServlet> disableSaTokenCorsFilter(SaTokenCorsFilterForJakartaServlet filter) {
        FilterRegistrationBean<SaTokenCorsFilterForJakartaServlet> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 注册两级权限与角色缓存管理门面服务
     *
     * @param redisTemplateProvider RedisTemplate 可选提供者
     * @param broadcasterProvider   集群同步广播器可选提供者
     * @return 缓存管理服务实例
     */
    @Bean
    @ConditionalOnMissingBean
    public PermissionCacheService permissionCacheService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectProvider<SaTokenClusterBroadcaster> broadcasterProvider) {
        return new PermissionCacheService(redisTemplateProvider, broadcasterProvider);
    }

    /**
     * 注册 Sa-Token 分布式集群广播器门面
     *
     * @param redisTemplateProvider RedisTemplate 可选提供者
     * @return SaTokenClusterBroadcaster 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.satoken.cluster-sync", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SaTokenClusterBroadcaster saTokenClusterBroadcaster(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        return new SaTokenClusterBroadcaster(redisTemplateProvider, properties);
    }

    /**
     * 注册 Sa-Token 分布式集群同步监听器（监听 Spring 原生安全事件）
     *
     * @param broadcaster    集群广播器
     * @param cacheService   缓存管理服务
     * @return SaTokenClusterSyncListener 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.satoken.cluster-sync", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SaTokenClusterSyncListener saTokenClusterSyncListener(
            SaTokenClusterBroadcaster broadcaster,
            PermissionCacheService cacheService) {
        return new SaTokenClusterSyncListener(broadcaster, cacheService);
    }

    /**
     * 注册登录防暴力破解策略门面服务
     *
     * @param redisTemplateProvider RedisTemplate 可选提供者
     * @return LoginFailureLockService 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.satoken.login-defense", name = "enabled", havingValue = "true", matchIfMissing = false)
    public LoginFailureLockService loginFailureLockService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        return new LoginFailureLockService(redisTemplateProvider, properties);
    }

    /**
     * 注册默认业务角色与权限加载器（从当前会话载荷读取）
     *
     * @return 默认加载器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public UserRolePermissionLoader defaultUserRolePermissionLoader() {
        return new DefaultUserRolePermissionLoader();
    }

    /**
     * 注册带两级高并发缓存防穿透/防雪崩的 StpInterface 数据源
     *
     * @param loader                角色与权限加载器
     * @param permissionCacheService 缓存治理门面服务
     * @return StpInterface 实例
     */
    @Bean
    @ConditionalOnMissingBean(StpInterface.class)
    @ConditionalOnProperty(prefix = "ateng.boot.satoken.permission-cache", name = "enabled", havingValue = "true", matchIfMissing = true)
    public StpInterface cachedStpInterface(UserRolePermissionLoader loader,
                                          PermissionCacheService permissionCacheService) {
        CachedStpInterface stpInterface = new CachedStpInterface(loader, permissionCacheService, properties);
        cn.dev33.satoken.SaManager.setStpInterface(stpInterface);
        return stpInterface;
    }

    /**
     * 注册默认权限与角色数据源实现（当显式关闭缓存时回退使用）
     *
     * @return StpInterface 实例
     */
    @Bean
    @ConditionalOnMissingBean(StpInterface.class)
    public StpInterface defaultStpInterface() {
        DefaultStpInterface stpInterface = new DefaultStpInterface();
        cn.dev33.satoken.SaManager.setStpInterface(stpInterface);
        return stpInterface;
    }

    /**
     * Redis 分布式存储嵌套配置（防 Missing Class 类加载异常）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    public static class RedisDaoConfiguration {

        /**
         * 注册 Sa-Token Redis 分布式会话与持久层实现
         *
         * @param stringRedisTemplate 字符串 Redis 操作模板
         * @return SaTokenDao 实例
         */
        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean(SaTokenDao.class)
        public SaTokenDao saTokenRedisDao(StringRedisTemplate stringRedisTemplate) {
            log.info("Sa-Token 检测到 Redis 基础设施，已激活纯净 JSON 分布式会话存储 (SaTokenRedisDao)");
            return new SaTokenRedisDao(stringRedisTemplate);
        }
    }

    /**
     * 注册 Sa-Token 增强路由拦截器实例
     *
     * @return AtengSaTokenInterceptor 拦截器实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.satoken.interceptor", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AtengSaTokenInterceptor atengSaTokenInterceptor() {
        AtengSaTokenInterceptor saInterceptor = new AtengSaTokenInterceptor();
        saInterceptor.isAnnotation(false);
        if (properties.getInterceptor().isCheckLogin()) {
            log.info("Sa-Token 开启全域路由强制登录校验模式 (check-login=true)");
            saInterceptor.setAuth(handler -> StpUtil.checkLogin());
        }
        return saInterceptor;
    }

    /**
     * 注册 Sa-Token WebMVC 路由拦截器配置
     *
     * @param saTokenInterceptor 增强路由拦截器
     * @return WebMvcConfigurer 拦截器配置实例
     */
    @Bean
    @ConditionalOnClass(WebMvcConfigurer.class)
    @ConditionalOnMissingBean(SaTokenWebMvcConfigurer.class)
    @ConditionalOnProperty(prefix = "ateng.boot.satoken.interceptor", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SaTokenWebMvcConfigurer saTokenWebMvcConfigurer(AtengSaTokenInterceptor saTokenInterceptor) {
        return new SaTokenWebMvcConfigurer(properties, saTokenInterceptor);
    }

    /**
     * 注册 Sa-Token 匿名公开访问与动态白名单自动汇聚处理器
     *
     * @param applicationContext  Spring 上下文容器
     * @param saTokenInterceptor 增强路由拦截器
     * @param whitelistProviders 白名单提供者 SPI 可选提供者
     * @return AnonymousUrlProcessor 实例
     */
    @Bean
    @ConditionalOnClass(WebMvcConfigurer.class)
    @ConditionalOnMissingBean(AnonymousUrlProcessor.class)
    @ConditionalOnProperty(prefix = "ateng.boot.satoken.interceptor", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AnonymousUrlProcessor anonymousUrlProcessor(
            ApplicationContext applicationContext,
            AtengSaTokenInterceptor saTokenInterceptor,
            ObjectProvider<SaTokenWhitelistProvider> whitelistProviders) {
        return new AnonymousUrlProcessor(applicationContext, properties, saTokenInterceptor, whitelistProviders);
    }

    /**
     * 注册当前操作人身份 SPI 适配提供者（对接 ateng-boot-core SPI）
     * 自动驱动 MyBatis-Plus 审计自动填充与数据权限引擎
     *
     * @return CurrentUserProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean(CurrentUserProvider.class)
    public CurrentUserProvider saTokenCurrentUserProvider() {
        return new SaTokenCurrentUserProvider();
    }

    /**
     * 注册当前租户身份 SPI 适配提供者（对接 ateng-boot-core SPI）
     * 自动驱动多租户行级与列级数据隔离
     *
     * @return CurrentTenantProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean(CurrentTenantProvider.class)
    public CurrentTenantProvider saTokenCurrentTenantProvider() {
        return new SaTokenCurrentTenantProvider();
    }

    /**
     * 注册 Sa-Token 原生事件广播发布器（桥接为 Spring ApplicationEvent）
     *
     * @param eventPublisher Spring 事件发布器
     * @return SaTokenListener 实例
     */
    @Bean
    @ConditionalOnMissingBean(SaTokenListener.class)
    public SaTokenListener saTokenEventPublisher(ApplicationEventPublisher eventPublisher) {
        return new SaTokenEventPublisher(eventPublisher);
    }

    /**
     * 注册微服务内部调用 Same-Token 零信任安全校验切面
     *
     * @return SaCheckSameTokenAspect 切面实例
     */
    @Bean
    @ConditionalOnClass(org.aspectj.lang.annotation.Aspect.class)
    @ConditionalOnMissingBean(SaCheckSameTokenAspect.class)
    @ConditionalOnProperty(prefix = "ateng.boot.satoken.same-token", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SaCheckSameTokenAspect saCheckSameTokenAspect(SaTokenProperties properties) {
        return new SaCheckSameTokenAspect(properties);
    }

    /**
     * 注册水平越权防御 (IDOR) 数据所有权校验切面
     *
     * @param applicationContext Spring 上下文容器
     * @return SaCheckDataOwnerAspect 切面实例
     */
    @Bean
    @ConditionalOnClass(org.aspectj.lang.annotation.Aspect.class)
    @ConditionalOnMissingBean(SaCheckDataOwnerAspect.class)
    public SaCheckDataOwnerAspect saCheckDataOwnerAspect(ApplicationContext applicationContext) {
        return new SaCheckDataOwnerAspect(applicationContext);
    }

    /**
     * OpenAPI / Knife4j 鉴权组件嵌套配置（防 Missing Class 类加载异常）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(GlobalOpenApiCustomizer.class)
    public static class OpenApiConfiguration {

        /**
         * 注册 Sa-Token OpenAPI / Knife4j 鉴权组件定制器
         *
         * @param properties 配置属性
         * @return SaTokenOpenApiCustomizer 实例
         */
        @Bean
        @ConditionalOnMissingBean(SaTokenOpenApiCustomizer.class)
        @ConditionalOnProperty(prefix = "ateng.boot.satoken.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
        public SaTokenOpenApiCustomizer saTokenOpenApiCustomizer(SaTokenProperties properties) {
            return new SaTokenOpenApiCustomizer(properties);
        }
    }

    /**
     * Redis Pub/Sub 集群同步消息订阅嵌套配置
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({StringRedisTemplate.class, RedisMessageListenerContainer.class})
    @ConditionalOnProperty(prefix = "ateng.boot.satoken.cluster-sync", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class ClusterSyncRedisConfiguration {

        /**
         * 注册 Sa-Token 集群广播 Redis Pub/Sub 订阅适配器
         *
         * @param listener 集群同步监听器
         * @return SaTokenClusterRedisMessageSubscriber 实例
         */
        @Bean
        @ConditionalOnMissingBean
        public SaTokenClusterRedisMessageSubscriber saTokenClusterRedisMessageSubscriber(SaTokenClusterSyncListener listener) {
            return new SaTokenClusterRedisMessageSubscriber(listener);
        }

        /**
         * 注册 Sa-Token 集群消息订阅监听容器
         *
         * @param connectionFactory    Redis 连接工厂
         * @param subscriber           集群广播订阅适配器
         * @param properties           配置属性
         * @param taskExecutorProvider Core 受管异步任务线程池可选提供者
         * @return RedisMessageListenerContainer 实例
         */
        @Bean
        @ConditionalOnBean(RedisConnectionFactory.class)
        @ConditionalOnMissingBean(name = "saTokenClusterRedisMessageListenerContainer")
        public RedisMessageListenerContainer saTokenClusterRedisMessageListenerContainer(
                RedisConnectionFactory connectionFactory,
                SaTokenClusterRedisMessageSubscriber subscriber,
                SaTokenProperties properties,
                ObjectProvider<org.springframework.core.task.TaskExecutor> taskExecutorProvider) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            org.springframework.core.task.TaskExecutor taskExecutor = taskExecutorProvider.getIfAvailable();
            if (taskExecutor != null) {
                container.setTaskExecutor(taskExecutor);
            }
            String channel = properties.getClusterSync().getChannel();
            container.addMessageListener(subscriber, new ChannelTopic(channel));
            log.info("Sa-Token 检测到 Redis 连接工厂，已激活分布式集群即时同步 Pub/Sub 订阅: channel={}", channel);
            return container;
        }
    }
}
