package io.github.atengk.boot.realtime.autoconfigure;

import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.realtime.auth.AtengHandshakeInterceptor;
import io.github.atengk.boot.realtime.auth.RealtimeAuthHandshakeProvider;
import io.github.atengk.boot.realtime.auth.SaTokenRealtimeAuthProvider;
import io.github.atengk.boot.realtime.cluster.RealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.cluster.RedissonRealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.cluster.StandaloneRealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.dispatcher.RealtimeMessageDispatcher;
import io.github.atengk.boot.realtime.dispatcher.RealtimeMessageListener;
import io.github.atengk.boot.realtime.enums.ClusterMode;
import io.github.atengk.boot.realtime.event.RealtimeSessionEventPublisher;
import io.github.atengk.boot.realtime.group.RealtimeGroupManager;
import io.github.atengk.boot.realtime.heartbeat.GhostConnectionDetector;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSessionListener;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import io.github.atengk.boot.realtime.sse.AtengSseController;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import io.github.atengk.boot.realtime.websocket.AtengWebSocketConfigurer;
import io.github.atengk.boot.realtime.websocket.AtengWebSocketHandler;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 实时通信底座主自动装配类
 *
 * @author Ateng
 * @since 2026-10-21
 */
@AutoConfiguration
@EnableConfigurationProperties(RealtimeProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.realtime", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengRealtimeAutoConfiguration {

    /**
     * 注册受管核心会话中心
     */
    @Bean
    @ConditionalOnMissingBean(RealtimeSessionManager.class)
    public RealtimeSessionManager realtimeSessionManager(RealtimeProperties properties,
                                                         ObjectProvider<RealtimeSessionListener> sessionListeners) {
        return new RealtimeSessionManager(properties, sessionListeners);
    }

    /**
     * 注册 Spring 原生应用事件发布监听器
     */
    @Bean
    @ConditionalOnMissingBean(RealtimeSessionEventPublisher.class)
    public RealtimeSessionEventPublisher realtimeSessionEventPublisher(ApplicationEventPublisher eventPublisher) {
        return new RealtimeSessionEventPublisher(eventPublisher);
    }

    /**
     * 注册默认 WebSocket 握手拦截器
     */
    @Bean
    @ConditionalOnMissingBean(AtengHandshakeInterceptor.class)
    public AtengHandshakeInterceptor atengHandshakeInterceptor(RealtimeProperties properties,
                                                               ObjectProvider<RealtimeAuthHandshakeProvider> authProvider) {
        return new AtengHandshakeInterceptor(properties, authProvider);
    }

    /**
     * 注册客户端上行消息分发调度引擎
     */
    @Bean
    @ConditionalOnMissingBean(RealtimeMessageDispatcher.class)
    public RealtimeMessageDispatcher realtimeMessageDispatcher(ObjectProvider<RealtimeMessageListener> messageListeners) {
        return new RealtimeMessageDispatcher(messageListeners);
    }

    /**
     * 注册单机会话分组与频道订阅受管管理器
     */
    @Bean
    @ConditionalOnMissingBean(RealtimeGroupManager.class)
    public RealtimeGroupManager realtimeGroupManager() {
        return new RealtimeGroupManager();
    }

    /**
     * 注册幽灵死链检测与心跳探针任务
     */
    @Bean
    @ConditionalOnMissingBean(GhostConnectionDetector.class)
    public GhostConnectionDetector ghostConnectionDetector(RealtimeProperties properties,
                                                          RealtimeSessionManager sessionManager) {
        return new GhostConnectionDetector(properties, sessionManager);
    }

    /**
     * 注册统一实时通信门面
     */
    @Bean
    @ConditionalOnMissingBean(RealtimeTemplate.class)
    public RealtimeTemplate realtimeTemplate(RealtimeSessionManager sessionManager,
                                             RealtimeGroupManager groupManager,
                                             RealtimeProperties properties,
                                             ObjectProvider<RealtimeClusterBroadcaster> clusterBroadcasterProvider) {
        return new RealtimeTemplate(sessionManager, groupManager, properties, clusterBroadcasterProvider.getIfAvailable());
    }

    /**
     * Sa-Token 握手鉴权集成自动装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StpUtil.class)
    public static class SaTokenRealtimeAuthConfiguration {

        @Bean
        @ConditionalOnMissingBean(RealtimeAuthHandshakeProvider.class)
        public SaTokenRealtimeAuthProvider saTokenRealtimeAuthProvider() {
            return new SaTokenRealtimeAuthProvider();
        }
    }

    /**
     * Redisson 集群广播自动装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedissonClient.class)
    public static class RedissonClusterConfiguration {

        @Bean
        @ConditionalOnBean(RedissonClient.class)
        @ConditionalOnMissingBean(RealtimeClusterBroadcaster.class)
        public RealtimeClusterBroadcaster redissonRealtimeClusterBroadcaster(
                RedissonClient redissonClient,
                RealtimeProperties properties) {
            if (properties.getClusterMode() == ClusterMode.STANDALONE) {
                return new StandaloneRealtimeClusterBroadcaster();
            }
            return new RedissonRealtimeClusterBroadcaster(redissonClient, properties);
        }
    }

    /**
     * 注册单机保底广播器（当无 Redisson 或未配置集群时生效）
     */
    @Bean
    @ConditionalOnMissingBean(RealtimeClusterBroadcaster.class)
    public RealtimeClusterBroadcaster standaloneRealtimeClusterBroadcaster() {
        return new StandaloneRealtimeClusterBroadcaster();
    }

    /**
     * WebSocket 传输引擎自动装配
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebSocket
    @ConditionalOnClass(WebSocketConfigurer.class)
    @ConditionalOnProperty(prefix = "ateng.boot.realtime.websocket", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class AtengWebSocketConfiguration {

        /**
         * 注册核心原生 WebSocket 处理器
         */
        @Bean
        @ConditionalOnMissingBean(AtengWebSocketHandler.class)
        public AtengWebSocketHandler atengWebSocketHandler(ObjectProvider<RealtimeSessionManager> sessionManagerProvider,
                                                           ObjectProvider<RealtimeMessageDispatcher> dispatcherProvider) {
            return new AtengWebSocketHandler(sessionManagerProvider.getIfAvailable(), dispatcherProvider.getIfAvailable());
        }

        /**
         * 注册 WebSocket 端点配置器
         */
        @Bean
        @ConditionalOnMissingBean(AtengWebSocketConfigurer.class)
        public AtengWebSocketConfigurer atengWebSocketConfigurer(RealtimeProperties properties,
                                                                 AtengWebSocketHandler webSocketHandler,
                                                                 ObjectProvider<HandshakeInterceptor> handshakeInterceptors) {
            return new AtengWebSocketConfigurer(properties, webSocketHandler, handshakeInterceptors);
        }

        /**
         * 自动调优底座容器缓冲区（64KB 文本/二进制缓冲区，10s 异步发送超时）
         */
        @Bean
        @ConditionalOnMissingBean(ServletServerContainerFactoryBean.class)
        @ConditionalOnClass(ServletServerContainerFactoryBean.class)
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public ServletServerContainerFactoryBean createWebSocketContainer(RealtimeProperties properties) {
            ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
            container.setMaxTextMessageBufferSize((int) properties.getWebsocket().getMaxTextMessageBufferSize().toBytes());
            container.setMaxBinaryMessageBufferSize((int) properties.getWebsocket().getMaxBinaryMessageBufferSize().toBytes());
            container.setAsyncSendTimeout(properties.getWebsocket().getAsyncSendTimeout().toMillis());
            container.setMaxSessionIdleTimeout(properties.getHeartbeat().getIdleTimeout().toMillis());
            return container;
        }
    }

    /**
     * SSE 传输引擎自动装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SseEmitter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "ateng.boot.realtime.sse", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class AtengSseConfiguration {

        /**
         * 注册核心原生 SSE 端点控制器
         */
        @Bean
        @ConditionalOnMissingBean(AtengSseController.class)
        public AtengSseController atengSseController(RealtimeProperties properties,
                                                     RealtimeSessionManager sessionManager,
                                                     ObjectProvider<RealtimeAuthHandshakeProvider> authProvider) {
            return new AtengSseController(properties, sessionManager, authProvider);
        }
    }
}
