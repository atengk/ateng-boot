package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.properties.AtengTcpProperties;
import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.route.TcpDispatcher;
import io.github.atengk.boot.tcp.server.TcpServer;
import io.github.atengk.boot.tcp.server.TcpServerRegistry;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.spi.TcpChannelCustomizer;
import io.github.atengk.boot.tcp.spi.TcpPacketFilter;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import io.netty.bootstrap.ServerBootstrap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * TCP 服务端自动装配配置类
 * <p>
 * 支持单主服务端（{@code ateng.boot.tcp.server}）与多端口多协议服务端拓扑（{@code ateng.boot.tcp.servers.<name>}）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@AutoConfiguration(after = AtengTcpAutoConfiguration.class)
@ConditionalOnClass(ServerBootstrap.class)
@Conditional(TcpServerCondition.class)
public class TcpServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TcpSessionManager tcpSessionManager() {
        return new TcpSessionManager();
    }

    @Bean(name = "tcpServer")
    @ConditionalOnMissingBean(name = "tcpServer")
    @ConditionalOnProperty(prefix = "ateng.boot.tcp.server", name = "enabled", havingValue = "true")
    public TcpServer tcpServer(AtengTcpProperties properties,
                               TcpSessionManager sessionManager,
                               ObjectProvider<TcpRequestCorrelationManager> correlationManagerProvider,
                               ObjectProvider<TcpDispatcher> dispatcherProvider,
                               ObjectProvider<List<TcpPacketFilter>> packetFiltersProvider,
                               ObjectProvider<List<TcpChannelCustomizer>> channelCustomizersProvider,
                               ObjectProvider<io.github.atengk.boot.tcp.metric.TcpMetricsCollector> metricsCollectorProvider,
                               ApplicationEventPublisher eventPublisher) {
        List<TcpPacketFilter> filters = packetFiltersProvider.getIfAvailable(Collections::emptyList);
        List<TcpChannelCustomizer> customizers = channelCustomizersProvider.getIfAvailable(Collections::emptyList);
        return new TcpServer(
                TcpServerRegistry.DEFAULT_SERVER_NAME,
                properties.getServer(),
                sessionManager,
                correlationManagerProvider.getIfAvailable(),
                dispatcherProvider.getIfAvailable(),
                filters,
                customizers,
                eventPublisher,
                metricsCollectorProvider.getIfAvailable()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public TcpServerTemplate tcpServerTemplate(TcpSessionManager sessionManager,
                                               ObjectProvider<TcpRequestCorrelationManager> correlationManagerProvider,
                                               ObjectProvider<io.github.atengk.boot.tcp.cluster.TcpClusterBridge> clusterBridgeProvider) {
        return new TcpServerTemplate(sessionManager, correlationManagerProvider.getIfAvailable(), clusterBridgeProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public TcpServerRegistry tcpServerRegistry(AtengTcpProperties properties,
                                               TcpSessionManager sessionManager,
                                               ObjectProvider<TcpServer> defaultServerProvider,
                                               ObjectProvider<TcpRequestCorrelationManager> correlationManagerProvider,
                                               ObjectProvider<TcpDispatcher> dispatcherProvider,
                                               ObjectProvider<List<TcpPacketFilter>> packetFiltersProvider,
                                               ObjectProvider<List<TcpChannelCustomizer>> channelCustomizersProvider,
                                               ObjectProvider<io.github.atengk.boot.tcp.metric.TcpMetricsCollector> metricsCollectorProvider,
                                               ApplicationEventPublisher eventPublisher) {
        TcpServerRegistry registry = new TcpServerRegistry();
        List<TcpPacketFilter> filters = packetFiltersProvider.getIfAvailable(Collections::emptyList);
        List<TcpChannelCustomizer> customizers = channelCustomizersProvider.getIfAvailable(Collections::emptyList);
        io.github.atengk.boot.tcp.metric.TcpMetricsCollector metricsCollector = metricsCollectorProvider.getIfAvailable();

        // 1. 纳管默认主服务端（若已通过 Bean 装配）
        TcpServer defaultServer = defaultServerProvider.getIfAvailable();
        if (defaultServer != null) {
            registry.register(TcpServerRegistry.DEFAULT_SERVER_NAME, defaultServer);
        }

        // 2. 纳管多端口多协议命名服务端
        Map<String, TcpServerProperties> serversConfig = properties.getServers();
        if (serversConfig != null && !serversConfig.isEmpty()) {
            for (Map.Entry<String, TcpServerProperties> entry : serversConfig.entrySet()) {
                String serverName = entry.getKey();
                TcpServerProperties serverProps = entry.getValue();
                if (serverProps != null && serverProps.isEnabled()) {
                    TcpServer customServer = new TcpServer(
                            serverName,
                            serverProps,
                            sessionManager,
                            correlationManagerProvider.getIfAvailable(),
                            dispatcherProvider.getIfAvailable(),
                            filters,
                            customizers,
                            eventPublisher,
                            metricsCollector
                    );
                    registry.register(serverName, customServer);
                }
            }
        }

        return registry;
    }
}
