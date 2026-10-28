package io.github.atengk.boot.rabbitmq.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.rabbitmq.callback.RabbitTransactionFailureCallback;
import io.github.atengk.boot.rabbitmq.callback.RabbitUnroutableMessageCallback;
import io.github.atengk.boot.rabbitmq.error.RabbitFatalExceptionStrategy;
import io.github.atengk.boot.rabbitmq.interceptor.RabbitListenerGlobalAdvice;
import io.github.atengk.boot.rabbitmq.properties.RabbitmqProperties;
import io.github.atengk.boot.rabbitmq.retry.RabbitRetryPolicy;
import io.github.atengk.boot.rabbitmq.template.RabbitReliableTemplate;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.listener.FatalExceptionStrategy;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.postprocessor.GUnzipPostProcessor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading;
import org.springframework.boot.autoconfigure.thread.Threading;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

import java.util.Collections;
import java.util.Map;

/**
 * RabbitMQ 生产级增强底座自动装配
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@AutoConfiguration(after = RabbitAutoConfiguration.class)
@ConditionalOnClass({RabbitTemplate.class, Channel.class})
@ConditionalOnProperty(prefix = "ateng.boot.rabbitmq", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RabbitmqProperties.class)
@Import(AtengRabbitmqAutoConfiguration.VirtualThreadsConfiguration.class)
public class AtengRabbitmqAutoConfiguration {

    /**
     * 装配纯净安全、开启 INFERRED 泛型推断的 Jackson 消息转换器
     */
    @Bean
    @ConditionalOnMissingBean
    public MessageConverter messageConverter(ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable(JsonUtil::getObjectMapper);
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(mapper);
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        typeMapper.setTrustedPackages("*");
        converter.setJavaTypeMapper(typeMapper);
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    /**
     * 装配全局不可路由退回消息处理器，支持结构化宏日志与 SPI 回调
     */
    @Bean
    @ConditionalOnMissingBean
    public RabbitTemplate.ReturnsCallback atengRabbitReturnsCallback(
            ObjectProvider<RabbitUnroutableMessageCallback> unroutableCallbackProvider) {
        return returned -> {
            Message message = returned.getMessage();
            MessageProperties props = message != null ? message.getMessageProperties() : null;
            Map<String, Object> headers = props != null ? props.getHeaders() : Collections.emptyMap();
            String bizKey = MessageContextPropagator.extractString(headers, MessageHeaderConstants.BIZ_KEY);
            String traceId = MessageContextPropagator.extractString(headers, MessageHeaderConstants.TRACE_ID);

            log.warn("[MQ-RETURNS] mq=rabbitmq exchange={} routingKey={} replyCode={} replyText={} bizKey={} traceId={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText(), bizKey, traceId);

            unroutableCallbackProvider.ifAvailable(callback -> {
                try {
                    callback.onUnroutableMessage(returned);
                } catch (Throwable ex) {
                    log.error("[MQ-RETURNS-CALLBACK-ERROR] 消息不可路由回调 SPI 执行异常: exchange={}, routingKey={}",
                            returned.getExchange(), returned.getRoutingKey(), ex);
                }
            });
        };
    }

    /**
     * 定制原生 RabbitTemplate：绑定消息转换器、退回回调、入站透明解压与可观测性度量
     */
    @Bean
    @ConditionalOnMissingBean
    public RabbitTemplateCustomizer atengRabbitTemplateCustomizer(
            RabbitmqProperties properties,
            MessageConverter messageConverter,
            ObjectProvider<RabbitTemplate.ReturnsCallback> returnsCallbackProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        return rabbitTemplate -> {
            rabbitTemplate.setMessageConverter(messageConverter);
            if (properties.getReliable().isReturnsCallbackEnabled()) {
                rabbitTemplate.setMandatory(true);
                returnsCallbackProvider.ifAvailable(rabbitTemplate::setReturnsCallback);
            }
            rabbitTemplate.addAfterReceivePostProcessors(new GUnzipPostProcessor());
            observationRegistryProvider.ifAvailable(registry -> rabbitTemplate.setObservationEnabled(true));
        };
    }

    /**
     * 装配生产级高可靠投递与事务感知门面模版
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RabbitTemplate.class)
    public RabbitReliableTemplate rabbitReliableTemplate(
            RabbitTemplate rabbitTemplate,
            RabbitmqProperties properties,
            ObjectProvider<RabbitTransactionFailureCallback> transactionFailureCallbackProvider) {
        return new RabbitReliableTemplate(rabbitTemplate, properties, transactionFailureCallbackProvider);
    }

    /**
     * 毒丸与致命不可自愈异常判定策略
     */
    @Bean
    @ConditionalOnMissingBean
    public FatalExceptionStrategy rabbitFatalExceptionStrategy() {
        return new RabbitFatalExceptionStrategy();
    }

    /**
     * 容器级异常拒绝与死信隔离错误处理器
     */
    @Bean
    @ConditionalOnMissingBean
    public ConditionalRejectingErrorHandler rabbitErrorHandler(FatalExceptionStrategy rabbitFatalExceptionStrategy) {
        ConditionalRejectingErrorHandler handler = new ConditionalRejectingErrorHandler(rabbitFatalExceptionStrategy);
        handler.setDiscardFatalsWithXDeath(true);
        return handler;
    }

    /**
     * 消费端全局环绕拦截链 (四维上下文激活/清理、@Idempotent 静默 ACK 与消费审计宏日志)
     */
    @Bean
    @ConditionalOnMissingBean
    public RabbitListenerGlobalAdvice rabbitListenerGlobalAdvice(RabbitmqProperties properties) {
        return new RabbitListenerGlobalAdvice(properties);
    }

    /**
     * 容器级别优雅停机排空定制器
     */
    @Bean
    @ConditionalOnMissingBean
    public ContainerCustomizer<SimpleMessageListenerContainer> atengContainerCustomizer(RabbitmqProperties properties) {
        return container -> {
            container.setShutdownTimeout(properties.getConsumer().getShutdownTimeout().toMillis());
            container.addAfterReceivePostProcessors(new GUnzipPostProcessor());
        };
    }

    /**
     * 容器工厂与 RabbitAdmin 生命周期后置增强处理器
     */
    @Bean
    @ConditionalOnMissingBean(name = "atengRabbitLifecycleBeanPostProcessor")
    public static AtengRabbitLifecycleBeanPostProcessor atengRabbitLifecycleBeanPostProcessor(
            ObjectProvider<RabbitmqProperties> propertiesProvider,
            ObjectProvider<MessageConverter> messageConverterProvider,
            ObjectProvider<ConditionalRejectingErrorHandler> errorHandlerProvider,
            ObjectProvider<RabbitListenerGlobalAdvice> adviceProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        return new AtengRabbitLifecycleBeanPostProcessor(
                propertiesProvider,
                messageConverterProvider,
                errorHandlerProvider,
                adviceProvider,
                observationRegistryProvider);
    }

    /**
     * Rabbit 监听器容器与 Admin 生命周期后置处理器实现类
     */
    public static class AtengRabbitLifecycleBeanPostProcessor implements BeanPostProcessor {
        private final ObjectProvider<RabbitmqProperties> propertiesProvider;
        private final ObjectProvider<MessageConverter> messageConverterProvider;
        private final ObjectProvider<ConditionalRejectingErrorHandler> errorHandlerProvider;
        private final ObjectProvider<RabbitListenerGlobalAdvice> adviceProvider;
        private final ObjectProvider<ObservationRegistry> observationRegistryProvider;

        public AtengRabbitLifecycleBeanPostProcessor(
                ObjectProvider<RabbitmqProperties> propertiesProvider,
                ObjectProvider<MessageConverter> messageConverterProvider,
                ObjectProvider<ConditionalRejectingErrorHandler> errorHandlerProvider,
                ObjectProvider<RabbitListenerGlobalAdvice> adviceProvider,
                ObjectProvider<ObservationRegistry> observationRegistryProvider) {
            this.propertiesProvider = propertiesProvider;
            this.messageConverterProvider = messageConverterProvider;
            this.errorHandlerProvider = errorHandlerProvider;
            this.adviceProvider = adviceProvider;
            this.observationRegistryProvider = observationRegistryProvider;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
            if (bean instanceof SimpleRabbitListenerContainerFactory factory) {
                RabbitmqProperties properties = propertiesProvider.getIfAvailable();
                if (properties != null) {
                    RabbitmqProperties.Consumer consumer = properties.getConsumer();
                    factory.setPrefetchCount(consumer.getPrefetch());
                    factory.setConcurrentConsumers(consumer.getConcurrency());
                    factory.setMaxConcurrentConsumers(consumer.getMaxConcurrency());

                    // 装配消费端受控防卫拦截链与本地指数退避重试
                    RabbitListenerGlobalAdvice globalAdvice = adviceProvider.getIfAvailable();
                    if (globalAdvice != null) {
                        RabbitmqProperties.Consumer.Retry retryProps = consumer.getRetry();
                        if (retryProps.isEnabled()) {
                            RetryOperationsInterceptor retryInterceptor = RetryInterceptorBuilder.stateless()
                                    .retryPolicy(new RabbitRetryPolicy(retryProps.getMaxAttempts()))
                                    .backOffOptions(
                                            retryProps.getInitialInterval().toMillis(),
                                            retryProps.getMultiplier(),
                                            retryProps.getMaxInterval().toMillis())
                                    .recoverer(new RejectAndDontRequeueRecoverer())
                                    .build();
                            factory.setAdviceChain(retryInterceptor, globalAdvice);
                        } else {
                            factory.setAdviceChain(globalAdvice);
                        }
                    }
                }
                errorHandlerProvider.ifAvailable(factory::setErrorHandler);
                messageConverterProvider.ifAvailable(factory::setMessageConverter);
                factory.setAfterReceivePostProcessors(new GUnzipPostProcessor());
                observationRegistryProvider.ifAvailable(registry -> factory.setObservationEnabled(true));
            } else if (bean instanceof RabbitAdmin admin) {
                RabbitmqProperties properties = propertiesProvider.getIfAvailable();
                if (properties != null && !properties.getTopology().isAutoDeclare()) {
                    log.info("[RABBITMQ-TOPOLOGY] auto-declare=false，已禁用 RabbitAdmin 启动自动建队与声明拓扑");
                    admin.setAutoStartup(false);
                }
            }
            return bean;
        }
    }

    /**
     * JDK 21+ 虚拟线程自适应环境下的监听器容器调度执行器装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnThreading(Threading.VIRTUAL)
    static class VirtualThreadsConfiguration {

        @Bean(name = "atengRabbitVirtualTaskExecutor")
        @ConditionalOnMissingBean(name = "atengRabbitVirtualTaskExecutor")
        public SimpleAsyncTaskExecutor atengRabbitVirtualTaskExecutor() {
            SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("ateng-vt-rabbit-");
            executor.setVirtualThreads(true);
            return executor;
        }

        @Bean(name = "atengRabbitVirtualThreadCustomizer")
        @ConditionalOnMissingBean(name = "atengRabbitVirtualThreadCustomizer")
        public ContainerCustomizer<SimpleMessageListenerContainer> atengRabbitVirtualThreadCustomizer(
                @Qualifier("atengRabbitVirtualTaskExecutor") SimpleAsyncTaskExecutor executor) {
            return container -> container.setTaskExecutor(executor);
        }
    }
}
