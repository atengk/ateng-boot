package io.github.atengk.boot.web.idempotent.autoconfigure;

import io.github.atengk.boot.web.idempotent.aspect.IdempotentAspect;
import io.github.atengk.boot.web.idempotent.properties.IdempotentProperties;
import io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.InMemoryIdempotentKeyStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 接口防重幂等自动装配类
 * 默认装配单机内存存储引擎与 AOP 拦截切面
 *
 * @author Ateng
 * @since 2026-10-16
 */
@AutoConfiguration
@ConditionalOnClass({Aspect.class, ProceedingJoinPoint.class})
@EnableConfigurationProperties(IdempotentProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.web.idempotent", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengIdempotentAutoConfiguration {

    /**
     * 装配默认纯 JDK 单机内存幂等存储引擎 Bean
     *
     * @param properties 幂等配置属性
     * @return 单机内存幂等存储实例
     */
    @Bean
    @ConditionalOnMissingBean(IdempotentKeyStore.class)
    public IdempotentKeyStore inMemoryIdempotentKeyStore(IdempotentProperties properties) {
        return new InMemoryIdempotentKeyStore(
                properties.getInMemory().getMaxCapacity(),
                properties.getInMemory().getCleanInterval()
        );
    }

    /**
     * 装配接口防重幂等 AOP 切面 Bean
     *
     * @param keyStore             幂等状态存储 SPI
     * @param properties           幂等配置属性
     * @param objectMapperProvider 可选的 Jackson ObjectMapper 提供者
     * @return 幂等切面实例
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotentAspect idempotentAspect(IdempotentKeyStore keyStore,
                                            IdempotentProperties properties,
                                            org.springframework.beans.factory.ObjectProvider<com.fasterxml.jackson.databind.ObjectMapper> objectMapperProvider) {
        return new IdempotentAspect(keyStore, properties, objectMapperProvider);
    }
}
