package io.github.atengk.boot.satoken.autoconfigure;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.filter.SaTokenCorsFilterForJakartaServlet;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaFirewallStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.satoken.cache.CachedStpInterface;
import io.github.atengk.boot.satoken.cache.DefaultUserRolePermissionLoader;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.satoken.cache.UserRolePermissionLoader;
import io.github.atengk.boot.satoken.config.SaTokenWebMvcConfigurer;
import io.github.atengk.boot.satoken.dao.SaTokenRedisDao;
import io.github.atengk.boot.satoken.handler.SaTokenExceptionHandler;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.provider.SaTokenCurrentUserProvider;
import io.github.atengk.boot.satoken.stp.DefaultStpInterface;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 核心基础设施与防御性配置自动装配类
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(StpUtil.class)
@EnableConfigurationProperties(SaTokenProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.satoken", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AtengSaTokenAutoConfiguration {

    private final SaTokenProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 初始化全局安全防火墙策略，统一错误输出为 Result 契约
     */
    @PostConstruct
    public void initFirewallStrategy() {
        SaFirewallStrategy.instance.checkFailHandle = (e, req, res, extArg) -> {
            log.warn("Sa-Token 安全防火墙拦截异常: message={}", e.getMessage());
            res.setHeader("Content-Type", "application/json;charset=UTF-8");
            res.setStatus(200);
            Result<Void> failResult = Result.fail(CommonResultCode.BAD_REQUEST.getCode(), "非法请求拦截: " + e.getMessage());
            if (res.getSource() instanceof jakarta.servlet.http.HttpServletResponse servletResponse) {
                try {
                    servletResponse.getWriter().write(objectMapper.writeValueAsString(failResult));
                    servletResponse.getWriter().flush();
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
    public FilterRegistrationBean<SaTokenCorsFilterForJakartaServlet> disableSaTokenCorsFilter(SaTokenCorsFilterForJakartaServlet filter) {
        FilterRegistrationBean<SaTokenCorsFilterForJakartaServlet> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 注册两级权限与角色缓存管理门面服务
     *
     * @param redisTemplateProvider RedisTemplate 可选提供者
     * @return 缓存管理服务实例
     */
    @Bean
    @ConditionalOnMissingBean
    public PermissionCacheService permissionCacheService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        return new PermissionCacheService(redisTemplateProvider);
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
     * 注册 Sa-Token WebMVC 路由拦截器配置
     *
     * @return WebMvcConfigurer 拦截器配置实例
     */
    @Bean
    @ConditionalOnClass(WebMvcConfigurer.class)
    @ConditionalOnProperty(prefix = "ateng.boot.satoken.interceptor", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SaTokenWebMvcConfigurer saTokenWebMvcConfigurer() {
        return new SaTokenWebMvcConfigurer(properties);
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
}
