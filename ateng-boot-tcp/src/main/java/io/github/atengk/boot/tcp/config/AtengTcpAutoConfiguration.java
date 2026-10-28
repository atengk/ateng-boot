package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.metric.TcpMetricsCollector;
import io.github.atengk.boot.tcp.properties.AtengTcpProperties;
import io.github.atengk.boot.tcp.route.CompositeRouteKeyExtractor;
import io.github.atengk.boot.tcp.route.JsonRouteKeyExtractor;
import io.github.atengk.boot.tcp.route.TcpDispatcher;
import io.github.atengk.boot.tcp.route.TcpRouteKeyExtractor;
import io.netty.bootstrap.Bootstrap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * ateng-boot-tcp 核心基础设施自动装配类
 *
 * @author Ateng
 * @since 2026-10-26
 */
@AutoConfiguration
@ConditionalOnClass(Bootstrap.class)
@EnableConfigurationProperties(AtengTcpProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.tcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengTcpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TcpRequestCorrelationManager tcpRequestCorrelationManager() {
        return new TcpRequestCorrelationManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public TcpRouteKeyExtractor tcpRouteKeyExtractor(ObjectProvider<List<TcpRouteKeyExtractor>> extractorsProvider) {
        List<TcpRouteKeyExtractor> extractors = extractorsProvider.getIfAvailable();
        if (extractors != null && !extractors.isEmpty()) {
            return new CompositeRouteKeyExtractor(extractors);
        }
        CompositeRouteKeyExtractor composite = new CompositeRouteKeyExtractor();
        composite.addExtractor(new JsonRouteKeyExtractor());
        return composite;
    }

    @Bean
    @ConditionalOnMissingBean
    public TcpDispatcher tcpDispatcher(ApplicationContext applicationContext,
                                       TcpRouteKeyExtractor routeKeyExtractor,
                                       ObjectProvider<Executor> businessExecutorProvider) {
        return new TcpDispatcher(applicationContext, routeKeyExtractor, businessExecutorProvider.getIfAvailable());
    }
}
