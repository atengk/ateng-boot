package io.github.atengk.boot.milvus.autoconfigure;

import io.github.atengk.boot.milvus.client.ManagedMilvusClientV2;
import io.github.atengk.boot.milvus.client.MilvusDatabaseClientManager;
import io.github.atengk.boot.milvus.client.MilvusDynamicRpcInterceptor;
import io.github.atengk.boot.milvus.customizer.MilvusClientCustomizer;
import io.github.atengk.boot.milvus.exception.MilvusExceptionTranslator;
import io.github.atengk.boot.milvus.health.MilvusHealthIndicator;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.milvus.grpc.ClientInfo;
import io.milvus.grpc.ConnectRequest;
import io.milvus.grpc.ConnectResponse;
import io.milvus.grpc.ErrorCode;
import io.milvus.grpc.MilvusServiceGrpc;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.utils.ClientUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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
    public MilvusClientV2 milvusClientV2(AtengMilvusProperties properties,
                                        ObjectProvider<MilvusClientCustomizer> customizers) {
        log.info("正在初始化 Milvus 客户端, 目标端点: {}, 目标库: {}",
                properties.resolveEffectiveUri(), properties.getDatabase());

        ConnectConfig connectConfig = buildConnectConfig(properties, customizers);
        String defaultDb = resolveDefaultDatabase(connectConfig, properties);
        MilvusDynamicRpcInterceptor rpcInterceptor = new MilvusDynamicRpcInterceptor(
                () -> MilvusDatabaseClientManager.resolveRuntimeDatabase(defaultDb, properties.isTenantDatabaseRouting()),
                connectConfig.getRpcDeadlineMs());

        try {
            ClientUtils clientUtils = new ClientUtils();
            // 清空静态 dbName，交由 MilvusDynamicRpcInterceptor 按每次 RPC 动态注入单一 dbname 头
            ConnectConfig channelConfig = MilvusDatabaseClientManager.copyConnectConfigWithDb(connectConfig, "");
            ManagedChannel sharedChannel = clientUtils.getChannel(channelConfig);
            return assembleWithHandshakeFallback(sharedChannel, connectConfig, rpcInterceptor, clientUtils, defaultDb);
        } catch (Exception ex) {
            log.warn("Milvus 通道预构建异常 ({})，回退至弹性 Netty 直连通道构建", ex.getMessage());
            return buildResilientFallbackClient(
                    connectConfig, rpcInterceptor, defaultDb,
                    this::buildNettyChannel,
                    (ch, intercepted, stub, cfg, db) -> new ManagedMilvusClientV2(ch, intercepted, stub, cfg, db));
        }
    }

    private MilvusClientV2 assembleWithHandshakeFallback(ManagedChannel sharedChannel,
                                                        ConnectConfig connectConfig,
                                                        MilvusDynamicRpcInterceptor rpcInterceptor,
                                                        ClientUtils clientUtils,
                                                        String defaultDb) {
        Channel interceptedChannel = ClientInterceptors.intercept(sharedChannel, rpcInterceptor);
        MilvusServiceGrpc.MilvusServiceBlockingStub blockingStub =
                MilvusDatabaseClientManager.newBlockingStub(interceptedChannel, connectConfig.getRpcDeadlineMs());
        ManagedMilvusClientV2 client;
        try {
            client = new ManagedMilvusClientV2(sharedChannel, interceptedChannel, blockingStub, connectConfig, defaultDb);
        } catch (Exception ex) {
            MilvusDatabaseClientManager.shutdownChannelQuietly(sharedChannel);
            throw ex;
        } catch (Error err) {
            MilvusDatabaseClientManager.shutdownChannelQuietly(sharedChannel);
            throw err;
        }

        try {
            performHandshake(interceptedChannel, connectConfig, clientUtils);
        } catch (Exception ex) {
            log.warn("Milvus 原生连接握手发生异常 ({})，已复用共享通道切换为高可用弹性模式 [uri={}, rpcDeadlineMs={}]",
                    ex.getMessage(), connectConfig.getUri(), connectConfig.getRpcDeadlineMs());
        }
        return client;
    }

    /**
     * 构建高可用弹性 Milvus 客户端（失败分支确定性关闭已创建的 ManagedChannel 防泄漏）
     */
    MilvusClientV2 buildResilientFallbackClient(ConnectConfig connectConfig,
                                                ClientInterceptor rpcInterceptor,
                                                String defaultDb,
                                                Function<ConnectConfig, ManagedChannel> channelFactory,
                                                ClientBinder clientBinder) {
        ManagedChannel channel = null;
        try {
            channel = channelFactory.apply(connectConfig);
            Channel interceptedChannel = ClientInterceptors.intercept(channel, rpcInterceptor);
            MilvusServiceGrpc.MilvusServiceBlockingStub stub =
                    MilvusDatabaseClientManager.newBlockingStub(interceptedChannel, connectConfig.getRpcDeadlineMs());
            MilvusClientV2 fallbackClient = clientBinder.bind(channel, interceptedChannel, stub, connectConfig, defaultDb);
            log.info("Milvus 高可用弹性客户端成功装配就绪 [uri={}, rpcDeadlineMs={}]",
                    connectConfig.getUri(), connectConfig.getRpcDeadlineMs());
            return fallbackClient;
        } catch (Exception ex) {
            MilvusDatabaseClientManager.shutdownChannelQuietly(channel);
            throw MilvusExceptionTranslator.translate("Milvus 客户端弹性初始化", ex);
        } catch (Error err) {
            MilvusDatabaseClientManager.shutdownChannelQuietly(channel);
            throw err;
        }
    }

    private ManagedChannel buildNettyChannel(ConnectConfig connectConfig) {
        String host = connectConfig.getHost();
        int port = connectConfig.getPort();

        NettyChannelBuilder channelBuilder = NettyChannelBuilder.forAddress(host, port)
                .keepAliveTime(connectConfig.getKeepAliveTimeMs(), TimeUnit.MILLISECONDS)
                .keepAliveTimeout(connectConfig.getKeepAliveTimeoutMs(), TimeUnit.MILLISECONDS)
                .keepAliveWithoutCalls(connectConfig.isKeepAliveWithoutCalls())
                .idleTimeout(connectConfig.getIdleTimeoutMs(), TimeUnit.MILLISECONDS);

        if (!Boolean.TRUE.equals(connectConfig.getSecure())) {
            channelBuilder.usePlaintext();
        }

        Metadata authMetadata = new Metadata();
        String authorization = connectConfig.getAuthorization();
        if (StringUtils.hasText(authorization)) {
            String encoded = Base64.getEncoder().encodeToString(authorization.getBytes(StandardCharsets.UTF_8));
            authMetadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), encoded);
        }
        channelBuilder.intercept(MetadataUtils.newAttachHeadersInterceptor(authMetadata));
        return channelBuilder.build();
    }

    private void performHandshake(Channel interceptedChannel, ConnectConfig connectConfig, ClientUtils clientUtils) {
        long handshakeTimeoutMs = connectConfig.getConnectTimeoutMs() > 0
                ? connectConfig.getConnectTimeoutMs()
                : TimeUnit.SECONDS.toMillis(10);
        MilvusServiceGrpc.MilvusServiceBlockingStub handshakeStub = MilvusServiceGrpc
                .newBlockingStub(interceptedChannel)
                .withDeadlineAfter(handshakeTimeoutMs, TimeUnit.MILLISECONDS);

        String user = connectConfig.getUsername() != null ? connectConfig.getUsername() : "";
        ClientInfo clientInfo = ClientInfo.newBuilder()
                .setSdkType("Java")
                .setSdkVersion(clientUtils.getSDKVersion())
                .setUser(user)
                .setHost(clientUtils.getHostName())
                .setLocalTime(clientUtils.getLocalTimeStr())
                .build();
        ConnectResponse response = handshakeStub.connect(ConnectRequest.newBuilder().setClientInfo(clientInfo).build());
        if (response.getStatus().getCode() != 0 && response.getStatus().getErrorCode() != ErrorCode.Success) {
            throw new IllegalStateException("Milvus handshake failed: " + response.getStatus().getReason());
        }
        if (StringUtils.hasText(connectConfig.getDbName())) {
            clientUtils.checkDatabaseExist(handshakeStub, connectConfig.getDbName().trim());
        }
    }

    private ConnectConfig buildConnectConfig(AtengMilvusProperties properties,
                                             ObjectProvider<MilvusClientCustomizer> customizers) {
        ConnectConfig.ConnectConfigBuilder<?, ?> builder = ConnectConfig.builder()
                .uri(properties.resolveEffectiveUri())
                .dbName(properties.getDatabase())
                .connectTimeoutMs(properties.getConnectTimeout().toMillis())
                .keepAliveTimeMs(properties.getKeepAliveTime().toMillis())
                .keepAliveTimeoutMs(properties.getKeepAliveTimeout().toMillis())
                .idleTimeoutMs(properties.getIdleTimeout().toMillis())
                .secure(properties.isSecure());

        Duration rpcDeadline = properties.getRpcDeadline();
        if (rpcDeadline != null && !rpcDeadline.isZero() && !rpcDeadline.isNegative()) {
            builder.rpcDeadlineMs(rpcDeadline.toMillis());
        }
        if (StringUtils.hasText(properties.getToken())) {
            builder.token(properties.getToken().trim());
        }
        if (StringUtils.hasText(properties.getUsername())) {
            builder.username(properties.getUsername().trim());
        }
        if (StringUtils.hasText(properties.getPassword())) {
            builder.password(properties.getPassword());
        }

        if (customizers != null) {
            customizers.orderedStream().forEach(customizer -> customizer.customize(builder, properties));
        }
        return builder.build();
    }

    private static String resolveDefaultDatabase(ConnectConfig connectConfig, AtengMilvusProperties properties) {
        if (StringUtils.hasText(connectConfig.getDbName())) {
            return connectConfig.getDbName().trim();
        }
        return StringUtils.hasText(properties.getDatabase()) ? properties.getDatabase().trim() : "default";
    }

    @Bean
    @ConditionalOnMissingBean
    public MilvusDatabaseClientManager milvusDatabaseClientManager(MilvusClientV2 milvusClientV2) {
        if (milvusClientV2 instanceof ManagedMilvusClientV2 managedClient) {
            return managedClient.getClientManager();
        }
        return MilvusDatabaseClientManager.unmanaged(milvusClientV2);
    }

    @Bean
    @ConditionalOnMissingBean
    public MilvusTemplate milvusTemplate(MilvusClientV2 milvusClientV2,
                                         AtengMilvusProperties properties,
                                         ObjectProvider<MilvusDatabaseClientManager> clientManagerProvider) {
        log.info("初始化 MilvusTemplate 核心门面就绪, 默认一致性级别: {}", properties.getConsistencyLevel());
        return new MilvusTemplate(milvusClientV2, properties, clientManagerProvider.getIfAvailable());
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

    @FunctionalInterface
    interface ClientBinder {
        MilvusClientV2 bind(ManagedChannel channel,
                            Channel interceptedChannel,
                            MilvusServiceGrpc.MilvusServiceBlockingStub blockingStub,
                            ConnectConfig connectConfig,
                            String defaultDb);
    }
}
