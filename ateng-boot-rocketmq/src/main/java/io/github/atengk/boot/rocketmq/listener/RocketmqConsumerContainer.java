package io.github.atengk.boot.rocketmq.listener;

import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.rocketmq.annotation.RocketmqListener;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.consumer.PushConsumerFactory;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import jakarta.validation.Validator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RocketMQ 5.x 声明式消费端监听容器与生命周期管理器
 *
 * <p>核心职责：
 * 1. 扫描 Spring 容器中所有标注了 {@link RocketmqListener} 的方法并解析属性占位符；
 * 2. 校验 Topic 命名符合官方规范（{@code ^[%a-zA-Z0-9_-]+$}，坚决杜绝点号）；
 * 3. 基于 {@link PushConsumerFactory} 构建并激活物理受管 {@link PushConsumer}，支持 Tag 与 SQL92 双模服务端过滤；
 * 4. 实现 {@link SmartLifecycle} 停机排空规范：停机时优先关闭所有 PushConsumer 实例（排空在途请求），保障平滑发布。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class RocketmqConsumerContainer implements
        SmartInitializingSingleton,
        SmartLifecycle,
        EmbeddedValueResolverAware,
        ApplicationContextAware,
        DisposableBean {

    private final AtengRocketmqProperties properties;
    private final ObjectProvider<ClientConfiguration> clientConfigurationProvider;
    private final PushConsumerFactory pushConsumerFactory;
    private final ObjectProvider<Validator> validatorProvider;

    private ApplicationContext applicationContext;
    private StringValueResolver stringValueResolver;

    private final List<RocketmqListenerEndpoint> endpoints = new CopyOnWriteArrayList<>();
    private final List<PushConsumer> activeConsumers = new CopyOnWriteArrayList<>();

    private volatile boolean running = false;

    public RocketmqConsumerContainer(
            AtengRocketmqProperties properties,
            ObjectProvider<ClientConfiguration> clientConfigurationProvider,
            PushConsumerFactory pushConsumerFactory,
            ObjectProvider<Validator> validatorProvider) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.clientConfigurationProvider = Objects.requireNonNull(clientConfigurationProvider, "clientConfigurationProvider 不能为空");
        this.pushConsumerFactory = Objects.requireNonNull(pushConsumerFactory, "pushConsumerFactory 不能为空");
        this.validatorProvider = validatorProvider;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
        this.stringValueResolver = resolver;
    }

    public List<RocketmqListenerEndpoint> getEndpoints() {
        return Collections.unmodifiableList(endpoints);
    }

    public List<PushConsumer> getActiveConsumers() {
        return Collections.unmodifiableList(activeConsumers);
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (applicationContext == null) {
            return;
        }

        String[] beanNames = applicationContext.getBeanNamesForType(Object.class);
        Validator validator = validatorProvider != null ? validatorProvider.getIfAvailable() : null;

        for (String beanName : beanNames) {
            if (beanName.startsWith("org.springframework.")) {
                continue;
            }
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Exception ex) {
                continue;
            }

            Class<?> targetClass = bean.getClass();
            Method[] methods = ReflectionUtils.getAllDeclaredMethods(targetClass);

            for (Method method : methods) {
                RocketmqListener annotation = AnnotatedElementUtils.findMergedAnnotation(method, RocketmqListener.class);
                if (annotation != null) {
                    registerEndpoint(bean, method, annotation, validator);
                }
            }
        }

        log.info("[ateng-boot-rocketmq] 已扫描并注册 {} 个 RocketMQ 声明式监听端点", endpoints.size());
    }

    private void registerEndpoint(Object bean, Method method, RocketmqListener annotation, Validator validator) {
        String rawTopic = resolvePlaceholder(annotation.topic());
        String rawGroup = resolvePlaceholder(annotation.consumerGroup());
        String rawTag = resolvePlaceholder(annotation.tag());
        String rawSql92 = resolvePlaceholder(annotation.sql92());

        Assert.hasText(rawTopic, "@RocketmqListener topic 不能为空");
        Assert.hasText(rawGroup, "@RocketmqListener consumerGroup 不能为空");

        String topic = rawTopic.trim();
        String consumerGroup = rawGroup.trim();

        if (!RocketmqConstants.TOPIC_PATTERN.matcher(topic).matches()) {
            throw new IllegalArgumentException(String.format(
                    "RocketMQ Topic 名称 [%s] 格式非法，必须符合正则 [%s]，严禁包含点号等特殊字符！",
                    topic, RocketmqConstants.TOPIC_REGEX));
        }

        int threadCount = annotation.consumptionThreadCount() > 0
                ? annotation.consumptionThreadCount()
                : properties.getConsumer().getConsumptionThreadCount();

        int maxCache = annotation.maxCacheMessageCount() > 0
                ? annotation.maxCacheMessageCount()
                : properties.getConsumer().getMaxCacheMessageCount();

        RocketmqListenerInvoker invoker = new RocketmqListenerInvoker(
                bean,
                method,
                topic,
                consumerGroup,
                validator);

        RocketmqListenerEndpoint endpoint = RocketmqListenerEndpoint.builder()
                .targetBean(bean)
                .method(method)
                .topic(topic)
                .consumerGroup(consumerGroup)
                .tag(rawTag)
                .sql92(rawSql92)
                .consumptionThreadCount(threadCount)
                .maxCacheMessageCount(maxCache)
                .invoker(invoker)
                .build();

        this.endpoints.add(endpoint);
    }

    private String resolvePlaceholder(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return stringValueResolver != null ? stringValueResolver.resolveStringValue(value) : value;
    }

    // =========================================================================
    // SmartLifecycle 契约实现
    // =========================================================================

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    /**
     * 高 Phase 数值确保停机时 Consumer 先于 Producer 和数据源关闭
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public synchronized void start() {
        if (this.running) {
            return;
        }

        if (endpoints.isEmpty()) {
            this.running = true;
            return;
        }

        ClientConfiguration clientConfiguration = clientConfigurationProvider.getIfAvailable();
        if (clientConfiguration == null) {
            log.info("[ateng-boot-rocketmq] 未检测到 ClientConfiguration，RocketMQ 消费监听容器跳过物理 PushConsumer 启动");
            this.running = true;
            return;
        }

        for (RocketmqListenerEndpoint endpoint : endpoints) {
            try {
                FilterExpression filterExpression;
                if (StringUtils.hasText(endpoint.getSql92())) {
                    filterExpression = new FilterExpression(endpoint.getSql92().trim(), FilterExpressionType.SQL92);
                } else {
                    String tag = StringUtils.hasText(endpoint.getTag()) ? endpoint.getTag().trim() : "*";
                    filterExpression = new FilterExpression(tag, FilterExpressionType.TAG);
                }

                PushConsumer pushConsumer = pushConsumerFactory.createPushConsumer(
                        clientConfiguration,
                        endpoint.getConsumerGroup(),
                        endpoint.getTopic(),
                        filterExpression,
                        endpoint.getInvoker(),
                        endpoint.getConsumptionThreadCount(),
                        endpoint.getMaxCacheMessageCount());

                this.activeConsumers.add(pushConsumer);
                log.info("[ateng-boot-rocketmq] 启动 RocketMQ PushConsumer 成功: topic={}, consumerGroup={}, filterType={}, filter={}",
                        endpoint.getTopic(),
                        endpoint.getConsumerGroup(),
                        filterExpression.getFilterExpressionType(),
                        filterExpression.getExpression());
            } catch (Exception ex) {
                log.error("[ateng-boot-rocketmq] 启动 RocketMQ PushConsumer 失败: topic={}, consumerGroup={}",
                        endpoint.getTopic(), endpoint.getConsumerGroup(), ex);
                throw new IllegalStateException("RocketMQ PushConsumer 启动失败: " + ex.getMessage(), ex);
            }
        }

        this.running = true;
    }

    @Override
    public synchronized void stop() {
        stop(() -> {});
    }

    @Override
    public synchronized void stop(Runnable callback) {
        this.running = false;
        log.info("[ateng-boot-rocketmq] 开始优雅停止 RocketMQ 消费监听容器，排空在途消费 (共 {} 个 PushConsumer)...", activeConsumers.size());

        Duration closeTimeout = properties.getConsumer().getCloseTimeout();
        long timeoutMillis = (closeTimeout != null && !closeTimeout.isNegative() && !closeTimeout.isZero())
                ? closeTimeout.toMillis()
                : 15_000L;

        Thread shutdownThread = new NamedThreadFactory("rocketmq-consumer-shutdown-", true).newThread(() -> {
            for (PushConsumer consumer : activeConsumers) {
                try {
                    consumer.close();
                } catch (Exception ex) {
                    log.warn("[ateng-boot-rocketmq] 关闭 PushConsumer 出现异常: {}", ex.getMessage());
                }
            }
        });
        shutdownThread.start();

        try {
            shutdownThread.join(timeoutMillis);
            if (shutdownThread.isAlive()) {
                log.warn("[ateng-boot-rocketmq] 优雅关闭 PushConsumer 超时 (已等待 {}ms)，强制放弃等待", timeoutMillis);
                shutdownThread.interrupt();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("[ateng-boot-rocketmq] 关闭 PushConsumer 线程被中断: {}", ex.getMessage());
        }

        activeConsumers.clear();

        if (callback != null) {
            callback.run();
        }
        log.info("[ateng-boot-rocketmq] RocketMQ 消费监听容器已优雅停止排空完毕");
    }

    @Override
    public void destroy() {
        if (isRunning()) {
            stop();
        }
    }
}
