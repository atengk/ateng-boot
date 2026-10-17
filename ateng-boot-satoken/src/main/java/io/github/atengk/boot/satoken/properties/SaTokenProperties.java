package io.github.atengk.boot.satoken.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sa-Token 鉴权增强配置属性
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.satoken")
public class SaTokenProperties {

    /**
     * 是否启用 Sa-Token 鉴权与自动化装配增强
     */
    private boolean enabled = true;

    /**
     * 路由拦截排除的全局白名单路径列表
     */
    private List<String> whiteList = new ArrayList<>(Arrays.asList(
            "/doc.html",
            "/webjars/**",
            "/swagger-resources/**",
            "/v3/api-docs/**",
            "/favicon.ico",
            "/actuator/**"
    ));

    /**
     * 路由拦截器与白名单放行配置
     */
    private InterceptorProperties interceptor = new InterceptorProperties();

    /**
     * 微服务集群内部 Same-Token 零信任鉴权配置
     */
    private SameTokenProperties sameToken = new SameTokenProperties();

    /**
     * 权限与角色多级缓存治理配置
     */
    private PermissionCacheProperties permissionCache = new PermissionCacheProperties();

    /**
     * 路由拦截器详细配置属性
     */
    @Data
    public static class InterceptorProperties {

        /**
         * 是否启用 Sa-Token 路由拦截器
         */
        private boolean enabled = true;

        /**
         * 是否开启全局路由强制登录校验（严格模式，开启后除白名单外的所有路由均强制校验登录）
         */
        private boolean checkLogin = false;

        /**
         * 拦截匹配的路径列表，默认为全部路径
         */
        private List<String> includes = new ArrayList<>(List.of("/**"));

        /**
         * 排除放行的白名单路径列表，内置工业级默认放行配置
         */
        private List<String> excludes = new ArrayList<>(Arrays.asList(
                "/doc.html",
                "/webjars/**",
                "/swagger-resources/**",
                "/v3/api-docs/**",
                "/favicon.ico",
                "/actuator/**",
                "/error",
                "/*.html",
                "/*.css",
                "/*.js"
        ));
    }

    /**
     * Same-Token 校验属性
     */
    @Data
    public static class SameTokenProperties {

        /**
         * 是否开启内部微服务调用 Same-Token 校验
         */
        private boolean enabled = false;

        /**
         * Same-Token 传输请求头名称
         */
        private String headerName = "SAME_TOKEN";

        /**
         * 约定的 Same-Token 鉴权凭据密钥
         */
        private String tokenValue;
    }

    /**
     * 权限缓存属性
     */
    @Data
    public static class PermissionCacheProperties {

        /**
         * 是否开启权限码与角色缓存
         */
        private boolean enabled = true;

        /**
         * 权限与角色缓存有效期（秒），默认 300 秒（5 分钟）
         */
        private long timeoutSeconds = 300;
    }
}
