package io.github.atengk.boot.milvus.autoconfigure;

import io.github.atengk.boot.milvus.health.MilvusHealthIndicator;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Milvus 向量数据库基础设施防御性自动装配
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(MilvusClientV2.class)
@EnableConfigurationProperties(AtengMilvusProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.milvus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengMilvusAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public MilvusClientV2 milvusClientV2(AtengMilvusProperties properties) {
        log.info("正在初始化 Milvus 客户端, 目标端点: {}, 目标库: {}",
                properties.resolveEffectiveUri(), properties.getDatabase());

        ConnectConfig.ConnectConfigBuilder<?, ?> builder = ConnectConfig.builder()
                .uri(properties.resolveEffectiveUri())
                .dbName(properties.getDatabase())
                .connectTimeoutMs(properties.getConnectTimeout().toMillis())
                .keepAliveTimeMs(properties.getKeepAliveTime().toMillis())
                .keepAliveTimeoutMs(properties.getKeepAliveTimeout().toMillis())
                .idleTimeoutMs(properties.getIdleTimeout().toMillis())
                .secure(properties.isSecure());

        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            builder.token(properties.getToken().trim());
        }
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            builder.username(properties.getUsername().trim());
        }
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            builder.password(properties.getPassword());
        }

        ConnectConfig connectConfig = builder.build();
        return new MilvusClientV2(connectConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    public MilvusTemplate milvusTemplate(MilvusClientV2 milvusClientV2, AtengMilvusProperties properties) {
        log.info("初始化 MilvusTemplate 核心门面就绪, 默认一致性级别: {}", properties.getConsistencyLevel());
        return new MilvusTemplate(milvusClientV2, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(HealthIndicator.class)
    public MilvusHealthIndicator milvusHealthIndicator(MilvusTemplate milvusTemplate) {
        return new MilvusHealthIndicator(milvusTemplate);
    }
}
