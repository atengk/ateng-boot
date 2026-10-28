package io.github.atengk.boot.satoken.config;

import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.satoken.interceptor.AtengSaTokenInterceptor;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sa-Token WebMVC 路由拦截器配置类
 * 注册全局 AtengSaTokenInterceptor 并显式关闭内置注解解析，专精路由级白名单防漏与全域登录拦截
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
public class SaTokenWebMvcConfigurer implements WebMvcConfigurer {

    private final SaTokenProperties properties;
    private final AtengSaTokenInterceptor saInterceptor;

    public SaTokenWebMvcConfigurer(SaTokenProperties properties) {
        this(properties, new AtengSaTokenInterceptor(properties));
    }

    public SaTokenWebMvcConfigurer(SaTokenProperties properties, AtengSaTokenInterceptor saInterceptor) {
        this.properties = properties;
        this.saInterceptor = saInterceptor != null ? saInterceptor : new AtengSaTokenInterceptor(properties);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        SaTokenProperties.InterceptorProperties interceptorProps = properties.getInterceptor();
        if (!interceptorProps.isEnabled()) {
            log.info("Sa-Token 路由拦截器已显式禁用 (ateng.boot.satoken.interceptor.enabled=false)");
            return;
        }

        // 1. 关闭注解校验（双轨正交设计：AOP 切面统一承接注解，拦截器专精路由与租户治理）
        saInterceptor.isAnnotation(false);

        // 2. 检查是否开启全局严格路由登录校验（若尚未配置则挂载）
        if (interceptorProps.isCheckLogin() && saInterceptor.auth == null) {
            log.info("Sa-Token 开启全域路由强制登录校验模式 (check-login=true)");
            saInterceptor.setAuth(handler -> StpUtil.checkLogin());
        }

        // 3. 聚合去重路由排除白名单列表（静态部分预置进拦截器）
        Set<String> excludePathSet = new LinkedHashSet<>();
        if (interceptorProps.getExcludes() != null) {
            excludePathSet.addAll(interceptorProps.getExcludes());
        }
        if (properties.getWhiteList() != null) {
            excludePathSet.addAll(properties.getWhiteList());
        }
        List<String> finalExcludes = new ArrayList<>(excludePathSet);
        saInterceptor.addWhitelist(finalExcludes);

        List<String> includes = interceptorProps.getIncludes() != null && !interceptorProps.getIncludes().isEmpty()
                ? interceptorProps.getIncludes()
                : List.of("/**");

        log.info("Sa-Token 注册全局路由拦截器: includes={}, excludes={}", includes, finalExcludes);

        // 4. 注册并挂载拦截规则
        registry.addInterceptor(saInterceptor)
                .addPathPatterns(includes)
                .excludePathPatterns(finalExcludes);
    }
}
