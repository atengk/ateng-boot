package io.github.atengk.boot.web.sign.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.web.autoconfigure.AtengWebAutoConfiguration;
import io.github.atengk.boot.web.idempotent.autoconfigure.AtengIdempotentAutoConfiguration;
import io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.sign.enums.SignAlgorithm;
import io.github.atengk.boot.web.sign.filter.RequestBodyCachingFilter;
import io.github.atengk.boot.web.sign.provider.DefaultSignKeyProvider;
import io.github.atengk.boot.web.sign.signer.HmacSha256Signer;
import io.github.atengk.boot.web.sign.signer.RsaSigner;
import io.github.atengk.boot.web.sign.signer.Sm2Signer;
import io.github.atengk.boot.web.sign.spi.ApiSigner;
import io.github.atengk.boot.web.sign.spi.SignKeyProvider;
import io.github.atengk.boot.web.trace.TraceFilter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * API 双向数据加签验签与防重放引擎自动配置类
 *
 * @author Ateng
 * @since 2026-10-16
 */
@AutoConfiguration(after = {AtengWebAutoConfiguration.class, AtengIdempotentAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ateng.boot.web.sign", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WebProperties.class)
public class AtengApiSignAutoConfiguration {

    /**
     * 注册默认签名密钥提供者
     *
     * @param webProperties 配置属性
     * @return SignKeyProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean(SignKeyProvider.class)
    public SignKeyProvider signKeyProvider(WebProperties webProperties) {
        return new DefaultSignKeyProvider(webProperties.getSign());
    }

    /**
     * 注册 API 签名器实现（按配置算法自适应装配 HMAC-SHA256、RSA 或 SM2）
     *
     * @param webProperties 配置属性
     * @return ApiSigner 实例
     */
    @Bean
    @ConditionalOnMissingBean(ApiSigner.class)
    public ApiSigner apiSigner(WebProperties webProperties) {
        if (webProperties.getSign().getAlgorithm() == SignAlgorithm.SM2) {
            return new Sm2Signer();
        }
        if (webProperties.getSign().getAlgorithm() == SignAlgorithm.RSA) {
            return new RsaSigner();
        }
        return new HmacSha256Signer();
    }

    /**
     * 注册请求体缓存与防爆过滤器 FilterRegistrationBean
     *
     * @param webProperties Web 配置属性
     * @return FilterRegistrationBean 实例
     * @deprecated 请使用 {@link AtengWebAutoConfiguration#contentCachingFilterRegistration(WebProperties)}，本方法仅保留用于独立装配时的向后兼容
     */
    @Bean
    @Deprecated(since = "1.0.0", forRemoval = false)
    @ConditionalOnMissingBean(name = {"contentCachingFilterRegistration", "requestBodyCachingFilterRegistration"})
    public FilterRegistrationBean<RequestBodyCachingFilter> requestBodyCachingFilterRegistration(WebProperties webProperties) {
        RequestBodyCachingFilter filter = new RequestBodyCachingFilter(webProperties.getSign().getMaxBodySize());
        FilterRegistrationBean<RequestBodyCachingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(io.github.atengk.boot.web.filter.ContentCachingFilter.ORDER);
        return registration;
    }

    /**
     * 注册 API 数据加签验签与防重放切面
     *
     * @param apiSigner                  签名算法器
     * @param signKeyProvider            密钥提供者
     * @param idempotentKeyStoreProvider 幂等与防重放存储提供者
     * @param objectMapperProvider       Jackson ObjectMapper 提供者
     * @param webProperties              配置属性
     * @return ApiSignAspect 切面实例
     */
    @Bean
    @ConditionalOnClass({ProceedingJoinPoint.class, Aspect.class})
    @ConditionalOnMissingBean(ApiSignAspect.class)
    public ApiSignAspect apiSignAspect(ApiSigner apiSigner,
                                       SignKeyProvider signKeyProvider,
                                       ObjectProvider<IdempotentKeyStore> idempotentKeyStoreProvider,
                                       ObjectProvider<ObjectMapper> objectMapperProvider,
                                       WebProperties webProperties) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new ApiSignAspect(apiSigner, signKeyProvider, idempotentKeyStoreProvider, mapper, webProperties);
    }
}
