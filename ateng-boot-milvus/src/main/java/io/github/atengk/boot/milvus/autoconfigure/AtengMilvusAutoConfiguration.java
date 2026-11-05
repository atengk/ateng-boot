package io.github.atengk.boot.milvus.autoconfigure;

import io.github.atengk.boot.milvus.exception.MilvusExceptionTranslator;
import io.github.atengk.boot.milvus.health.MilvusHealthIndicator;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.milvus.grpc.MilvusServiceGrpc;
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
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

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
        try {
            return new MilvusClientV2(connectConfig);
        } catch (Throwable t) {
            log.warn("Milvus 原生连接握手发生异常 ({})，启动高可用弹性降级装配", t.getMessage());
            return createResilientClient(connectConfig, properties);
        }
    }

    /**
     * 构建高可用弹性 Milvus 客户端 (绕过官方 SDK 内部 1 秒写死的 connect 探测 RPC 死锁缺陷)
     */
    private MilvusClientV2 createResilientClient(ConnectConfig connectConfig, AtengMilvusProperties properties) {
        try {
            String effectiveUri = properties.resolveEffectiveUri();
            URI uriObj = URI.create(effectiveUri);
            String host = uriObj.getHost() != null ? uriObj.getHost() : properties.getHost();
            int port = uriObj.getPort() != -1 ? uriObj.getPort() : properties.getPort();

            NettyChannelBuilder channelBuilder = NettyChannelBuilder.forAddress(host, port)
                    .keepAliveTime(properties.getKeepAliveTime().toMillis(), TimeUnit.MILLISECONDS)
                    .keepAliveTimeout(properties.getKeepAliveTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .idleTimeout(properties.getIdleTimeout().toMillis(), TimeUnit.MILLISECONDS);

            if (!properties.isSecure()) {
                channelBuilder.usePlaintext();
            }

            ManagedChannel channel = channelBuilder.build();

            // 构造带 Authorization Header 的 BlockingStub
            Metadata metadata = new Metadata();
            String authToken = properties.getToken();
            if ((authToken == null || authToken.isBlank()) && properties.getUsername() != null && properties.getPassword() != null) {
                authToken = Base64.getEncoder().encodeToString(
                        (properties.getUsername() + ":" + properties.getPassword()).getBytes(StandardCharsets.UTF_8));
            }
            if (authToken != null && !authToken.isBlank()) {
                Metadata.Key<String> authKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
                metadata.put(authKey, authToken);
            }

            MilvusServiceGrpc.MilvusServiceBlockingStub stub = MilvusServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(metadata));

            MilvusClientV2 fallbackClient = new MilvusClientV2(null);

            Field channelField = MilvusClientV2.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            channelField.set(fallbackClient, channel);

            Field stubField = MilvusClientV2.class.getDeclaredField("blockingStub");
            stubField.setAccessible(true);
            stubField.set(fallbackClient, stub);

            Field configField = MilvusClientV2.class.getDeclaredField("connectConfig");
            configField.setAccessible(true);
            configField.set(fallbackClient, connectConfig);

            log.info("Milvus 高可用弹性客户端成功装配就绪 [host={}, port={}]", host, port);
            return fallbackClient;
        } catch (Exception ex) {
            throw MilvusExceptionTranslator.translate("Milvus 客户端弹性初始化", ex);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public MilvusTemplate milvusTemplate(MilvusClientV2 milvusClientV2, AtengMilvusProperties properties) {
        log.info("初始化 MilvusTemplate 核心门面就绪, 默认一致性级别: {}", properties.getConsistencyLevel());
        return new MilvusTemplate(milvusClientV2, properties);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnProperty(prefix = "management.health.milvus", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class MilvusHealthIndicatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public MilvusHealthIndicator milvusHealthIndicator(MilvusTemplate milvusTemplate) {
            return new MilvusHealthIndicator(milvusTemplate);
        }
    }
}
