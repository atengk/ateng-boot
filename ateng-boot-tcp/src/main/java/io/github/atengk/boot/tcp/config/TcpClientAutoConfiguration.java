package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.client.TcpClientRegistry;
import io.github.atengk.boot.tcp.correlation.TcpCorrelationIdExtractor;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.metric.TcpMetricsCollector;
import io.github.atengk.boot.tcp.properties.AtengTcpProperties;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.github.atengk.boot.tcp.route.TcpDispatcher;
import io.github.atengk.boot.tcp.spi.TcpChannelCustomizer;
import io.github.atengk.boot.tcp.spi.TcpPacketFilter;
import io.github.atengk.boot.tcp.template.TcpClientTemplate;
import io.netty.bootstrap.Bootstrap;
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
 * TCP 客户端自动装配配置类
 * <p>
 * 支持单主客户端（{@code ateng.boot.tcp.client}）与命名多客户端长连接（{@code ateng.boot.tcp.clients.<name>}）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@AutoConfiguration(after = AtengTcpAutoConfiguration.class)
@ConditionalOnClass(Bootstrap.class)
@Conditional(TcpClientCondition.class)
public class TcpClientAutoConfiguration {

    @Bean(name = "tcpClient")
    @ConditionalOnMissingBean(name = "tcpClient")
    @ConditionalOnProperty(prefix = "ateng.boot.tcp.client", name = "enabled", havingValue = "true")
    public TcpClient tcpClient(AtengTcpProperties properties,
                               TcpRequestCorrelationManager correlationManager,
                               ObjectProvider<List<TcpPacketFilter>> packetFiltersProvider,
                               ObjectProvider<List<TcpChannelCustomizer>> channelCustomizersProvider,
                               ObjectProvider<TcpDispatcher> dispatcherProvider,
                               ObjectProvider<TcpMetricsCollector> metricsCollectorProvider,
                               ApplicationEventPublisher eventPublisher) {
        List<TcpPacketFilter> filters = packetFiltersProvider.getIfAvailable(Collections::emptyList);
        List<TcpChannelCustomizer> customizers = channelCustomizersProvider.getIfAvailable(Collections::emptyList);
        return new TcpClient(
                TcpClientRegistry.DEFAULT_CLIENT_NAME,
                properties.getClient(),
                filters,
                customizers,
                eventPublisher,
                correlationManager,
                dispatcherProvider.getIfAvailable(),
                metricsCollectorProvider.getIfAvailable()
        );
    }

    @Bean(name = "tcpClientTemplate")
    @ConditionalOnMissingBean(name = "tcpClientTemplate")
    @ConditionalOnProperty(prefix = "ateng.boot.tcp.client", name = "enabled", havingValue = "true")
    public TcpClientTemplate tcpClientTemplate(TcpClient tcpClient,
                                               AtengTcpProperties properties,
                                               TcpRequestCorrelationManager correlationManager) {
        return new TcpClientTemplate(tcpClient, properties.getClient(), correlationManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public TcpClientRegistry tcpClientRegistry(AtengTcpProperties properties,
                                               ObjectProvider<TcpClient> defaultClientProvider,
                                               ObjectProvider<TcpClientTemplate> defaultTemplateProvider,
                                               ObjectProvider<TcpCorrelationIdExtractor> correlationIdExtractorProvider,
                                               ObjectProvider<List<TcpPacketFilter>> packetFiltersProvider,
                                               ObjectProvider<List<TcpChannelCustomizer>> channelCustomizersProvider,
                                               ObjectProvider<TcpDispatcher> dispatcherProvider,
                                               ObjectProvider<TcpMetricsCollector> metricsCollectorProvider,
                                               ApplicationEventPublisher eventPublisher) {
        TcpClientRegistry registry = new TcpClientRegistry();
        List<TcpPacketFilter> filters = packetFiltersProvider.getIfAvailable(Collections::emptyList);
        List<TcpChannelCustomizer> customizers = channelCustomizersProvider.getIfAvailable(Collections::emptyList);
        TcpMetricsCollector metricsCollector = metricsCollectorProvider.getIfAvailable();

        // 1. 纳管默认主客户端（若已装配）
        TcpClient defaultClient = defaultClientProvider.getIfAvailable();
        TcpClientTemplate defaultTemplate = defaultTemplateProvider.getIfAvailable();
        if (defaultClient != null) {
            registry.register(TcpClientRegistry.DEFAULT_CLIENT_NAME, defaultClient, defaultTemplate);
        }

        // 2. 纳管多命名客户端
        Map<String, TcpClientProperties> clientsConfig = properties.getClients();
        if (clientsConfig != null && !clientsConfig.isEmpty()) {
            for (Map.Entry<String, TcpClientProperties> entry : clientsConfig.entrySet()) {
                String clientName = entry.getKey();
                TcpClientProperties clientProps = entry.getValue();
                if (clientProps != null && clientProps.isEnabled()) {
                    TcpRequestCorrelationManager customCorrelationManager = new TcpRequestCorrelationManager(metricsCollector);
                    if (correlationIdExtractorProvider.getIfAvailable() != null) {
                        customCorrelationManager.setCorrelationIdExtractor(correlationIdExtractorProvider.getIfAvailable());
                    }
                    TcpClient client = new TcpClient(
                            clientName,
                            clientProps,
                            filters,
                            customizers,
                            eventPublisher,
                            customCorrelationManager,
                            dispatcherProvider.getIfAvailable(),
                            metricsCollector
                    );
                    TcpClientTemplate template = new TcpClientTemplate(client, clientProps, customCorrelationManager);
                    registry.register(clientName, client, template);
                }
            }
        }

        return registry;
    }
}
