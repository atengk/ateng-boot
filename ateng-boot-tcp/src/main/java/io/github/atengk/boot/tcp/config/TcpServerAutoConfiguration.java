package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.properties.AtengTcpProperties;
import io.github.atengk.boot.tcp.server.TcpServer;
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

import java.util.Collections;
import java.util.List;

/**
 * TCP 服务端自动装配配置类
 *
 * @author Ateng
 * @since 2026-10-26
 */
@AutoConfiguration(after = AtengTcpAutoConfiguration.class)
@ConditionalOnClass(ServerBootstrap.class)
@ConditionalOnProperty(prefix = "ateng.boot.tcp.server", name = "enabled", havingValue = "true")
public class TcpServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TcpSessionManager tcpSessionManager() {
        return new TcpSessionManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public TcpServer tcpServer(AtengTcpProperties properties,
                               TcpSessionManager sessionManager,
                               ObjectProvider<List<TcpPacketFilter>> packetFiltersProvider,
                               ObjectProvider<List<TcpChannelCustomizer>> channelCustomizersProvider,
                               ApplicationEventPublisher eventPublisher) {
        List<TcpPacketFilter> filters = packetFiltersProvider.getIfAvailable(Collections::emptyList);
        List<TcpChannelCustomizer> customizers = channelCustomizersProvider.getIfAvailable(Collections::emptyList);
        return new TcpServer(properties.getServer(), sessionManager, filters, customizers, eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public TcpServerTemplate tcpServerTemplate(TcpSessionManager sessionManager) {
        return new TcpServerTemplate(sessionManager);
    }
}
