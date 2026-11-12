package io.github.atengk.boot.web.ip.autoconfigure;

import io.github.atengk.boot.web.autoconfigure.AtengWebAutoConfiguration;
import io.github.atengk.boot.web.ip.aspect.IpAccessControlAspect;
import io.github.atengk.boot.web.ip.filter.IpAccessControlFilter;
import io.github.atengk.boot.web.ip.properties.IpAccessControlProperties;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.util.IpUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * IP 访问控制防火墙自动配置类
 * 提供单 IP 与 IPv4/IPv6 CIDR 子网黑白名单控制，支持路径级过滤器与方法级切面双重防卫
 *
 * @author Ateng
 * @since 2026-10-27
 */
@AutoConfiguration
@AutoConfigureAfter(AtengWebAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ateng.boot.web.ip-access-control", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IpAccessControlProperties.class)
public class AtengIpAccessControlAutoConfiguration {

    /**
     * 条件注册 URL 路径级 IP 访问控制过滤器
     *
     * @param properties IP 访问控制配置属性
     * @return FilterRegistrationBean 实例
     */
    @Bean
    @ConditionalOnMissingBean(name = "ipAccessControlFilterRegistration")
    @ConditionalOnProperty(prefix = "ateng.boot.web.ip-access-control.filter", name = "enabled", havingValue = "true")
    public FilterRegistrationBean<IpAccessControlFilter> ipAccessControlFilterRegistration(IpAccessControlProperties properties) {
        IpAccessControlFilter filter = new IpAccessControlFilter(properties);
        FilterRegistrationBean<IpAccessControlFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(IpAccessControlFilter.ORDER);
        return registration;
    }

    /**
     * 注册方法级声明式 @IpAccessControl AOP 拦截切面
     *
     * @param properties IP 访问控制配置属性
     * @return IpAccessControlAspect 实例
     */
    @Bean
    @ConditionalOnClass({ProceedingJoinPoint.class, Aspect.class})
    @ConditionalOnMissingBean(IpAccessControlAspect.class)
    public IpAccessControlAspect ipAccessControlAspect(IpAccessControlProperties properties) {
        return new IpAccessControlAspect(properties);
    }

    /**
     * 容器初始化完成后将 IP 访问控制配置的授信反向代理清单与全局基线去重合并并同步至 IpUtil
     *
     * @param webPropertiesProvider             可选的 Web 配置属性提供者
     * @param ipAccessControlPropertiesProvider IP 访问控制配置属性提供者
     * @return SmartInitializingSingleton 实例
     */
    @Bean
    @ConditionalOnMissingBean(name = {"webTrustedProxiesInitializer", "ipAccessControlTrustedProxiesInitializer"})
    public SmartInitializingSingleton ipAccessControlTrustedProxiesInitializer(
            ObjectProvider<WebProperties> webPropertiesProvider,
            ObjectProvider<IpAccessControlProperties> ipAccessControlPropertiesProvider) {
        return AtengWebAutoConfiguration.createTrustedProxiesInitializer(
                webPropertiesProvider,
                ipAccessControlPropertiesProvider
        );
    }
}
