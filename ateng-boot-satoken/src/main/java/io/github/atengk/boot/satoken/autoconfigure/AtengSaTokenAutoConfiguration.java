package io.github.atengk.boot.satoken.autoconfigure;

import cn.dev33.satoken.filter.SaTokenCorsFilterForJakartaServlet;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaFirewallStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.spi.CurrentTenantProvider;
import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.satoken.handler.SaTokenExceptionHandler;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.util.LoginHelper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

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
        // 自动挂载 Sa-Token 当前租户提取 SPI 至 Core 核心底座
        TenantContextHolder.setTenantProvider(LoginHelper::getTenantId);

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
     * 自动向容器暴露基于 Sa-Token 会话的当前操作人提供者 SPI
     *
     * @return CurrentUserProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean(CurrentUserProvider.class)
    public CurrentUserProvider saTokenCurrentUserProvider() {
        return LoginHelper::getUserId;
    }

    /**
     * 自动向容器暴露基于 Sa-Token 会话的当前租户提供者 SPI
     *
     * @return CurrentTenantProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean(CurrentTenantProvider.class)
    public CurrentTenantProvider saTokenCurrentTenantProvider() {
        return LoginHelper::getTenantId;
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
}
