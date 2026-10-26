package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.properties.AtengTcpProperties;
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

import java.util.Collections;
import java.util.List;

/**
 * TCP 客户端自动装配配置类
 *
 * @author Ateng
 * @since 2026-10-26
 */
@AutoConfiguration(after = AtengTcpAutoConfiguration.class)
@ConditionalOnClass(Bootstrap.class)
@ConditionalOnProperty(prefix = "ateng.boot.tcp.client", name = "enabled", havingValue = "true")
public class TcpClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TcpClient tcpClient(AtengTcpProperties properties,
                               TcpRequestCorrelationManager correlationManager,
                               ObjectProvider<List<TcpPacketFilter>> packetFiltersProvider,
                               ObjectProvider<List<TcpChannelCustomizer>> channelCustomizersProvider,
                               ApplicationEventPublisher eventPublisher) {
        List<TcpPacketFilter> filters = packetFiltersProvider.getIfAvailable(Collections::emptyList);
        List<TcpChannelCustomizer> customizers = channelCustomizersProvider.getIfAvailable(Collections::emptyList);
        return new TcpClient(properties.getClient(), filters, customizers, eventPublisher, correlationManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public TcpClientTemplate tcpClientTemplate(TcpClient tcpClient,
                                               AtengTcpProperties properties,
                                               TcpRequestCorrelationManager correlationManager) {
        return new TcpClientTemplate(tcpClient, properties.getClient(), correlationManager);
    }
}
