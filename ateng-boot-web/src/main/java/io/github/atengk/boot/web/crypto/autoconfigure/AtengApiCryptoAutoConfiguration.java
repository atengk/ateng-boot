package io.github.atengk.boot.web.crypto.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.web.autoconfigure.AtengWebAutoConfiguration;
import io.github.atengk.boot.web.crypto.cipher.RsaAesGcmApiCipher;
import io.github.atengk.boot.web.crypto.cipher.Sm2Sm4GcmApiCipher;
import io.github.atengk.boot.web.crypto.filter.ApiCryptoFilter;
import io.github.atengk.boot.web.crypto.provider.DefaultApiCryptoKeyProvider;
import io.github.atengk.boot.web.crypto.spi.ApiCipher;
import io.github.atengk.boot.web.crypto.spi.ApiCryptoKeyProvider;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.sign.autoconfigure.AtengApiSignAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;

/**
 * API 传输层混合加解密自动配置类
 *
 * @author Ateng
 * @since 2026-11-27
 */
@AutoConfiguration(after = {AtengWebAutoConfiguration.class, AtengApiSignAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ateng.boot.web.crypto", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WebProperties.class)
public class AtengApiCryptoAutoConfiguration {

    /**
     * 注册默认传输层密钥提供者
     *
     * @param webProperties Web 配置属性
     * @return ApiCryptoKeyProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean(ApiCryptoKeyProvider.class)
    public ApiCryptoKeyProvider apiCryptoKeyProvider(WebProperties webProperties) {
        return new DefaultApiCryptoKeyProvider(webProperties.getCrypto());
    }

    /**
     * 注册 RSA-OAEP-SHA256 + AES-256-GCM 混合加密引擎
     *
     * @return RsaAesGcmApiCipher 实例
     */
    @Bean
    @ConditionalOnMissingBean(RsaAesGcmApiCipher.class)
    public RsaAesGcmApiCipher rsaAesGcmApiCipher() {
        return new RsaAesGcmApiCipher();
    }

    /**
     * 注册纯 JDK 原生国密 SM2 + SM4-GCM 混合加密引擎
     *
     * @return Sm2Sm4GcmApiCipher 实例
     */
    @Bean
    @ConditionalOnMissingBean(Sm2Sm4GcmApiCipher.class)
    public Sm2Sm4GcmApiCipher sm2Sm4GcmApiCipher() {
        return new Sm2Sm4GcmApiCipher();
    }

    /**
     * 注册按 HandlerMethod 精准介入的 API 传输层混合加解密过滤器
     *
     * @param webProperties          Web 配置属性
     * @param keyProvider            密钥提供者 SPI
     * @param cipherProvider         加密引擎 SPI 列表
     * @param objectMapperProvider   Jackson ObjectMapper 提供者
     * @param handlerMappingProvider Spring MVC HandlerMapping 提供者
     * @param apiSignAspectProvider  可选 @ApiSign 切面协同提供者
     * @return FilterRegistrationBean 实例
     */
    @Bean
    @ConditionalOnMissingBean(name = "apiCryptoFilterRegistration")
    public FilterRegistrationBean<ApiCryptoFilter> apiCryptoFilterRegistration(
            WebProperties webProperties,
            ApiCryptoKeyProvider keyProvider,
            ObjectProvider<ApiCipher> cipherProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<HandlerMapping> handlerMappingProvider,
            ObjectProvider<ApiSignAspect> apiSignAspectProvider) {
        List<ApiCipher> ciphers = cipherProvider.orderedStream().toList();
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        ApiCryptoFilter filter = new ApiCryptoFilter(
                webProperties,
                keyProvider,
                ciphers,
                objectMapper,
                handlerMappingProvider,
                apiSignAspectProvider
        );
        FilterRegistrationBean<ApiCryptoFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(ApiCryptoFilter.ORDER);
        return registration;
    }
}
