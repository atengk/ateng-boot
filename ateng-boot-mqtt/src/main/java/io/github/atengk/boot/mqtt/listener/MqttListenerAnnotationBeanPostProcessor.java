package io.github.atengk.boot.mqtt.listener;

import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.codec.MqttMessageConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * {@link MqttListener} 声明式注解方法后置处理器
 * 自动探测 Spring 容器中受管 Bean 的监听方法并注册至 {@link MqttListenerEndpointRegistry}
 * 实现 {@link BeanFactoryAware} 以延迟惰性解析端点注册中心，消除 BeanPostProcessor 早期加载循环告警
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttListenerAnnotationBeanPostProcessor implements BeanPostProcessor, BeanFactoryAware {

    private static final Logger log = LoggerFactory.getLogger(MqttListenerAnnotationBeanPostProcessor.class);

    private BeanFactory beanFactory;
    private volatile MqttListenerEndpointRegistry endpointRegistry;
    private volatile MqttMessageConverter messageConverter;

    public MqttListenerAnnotationBeanPostProcessor() {
        this((MqttMessageConverter) null);
    }

    public MqttListenerAnnotationBeanPostProcessor(MqttMessageConverter messageConverter) {
        this.endpointRegistry = null;
        this.messageConverter = messageConverter;
    }

    public MqttListenerAnnotationBeanPostProcessor(MqttListenerEndpointRegistry endpointRegistry) {
        this(endpointRegistry, null);
    }

    public MqttListenerAnnotationBeanPostProcessor(MqttListenerEndpointRegistry endpointRegistry,
                                                   MqttMessageConverter messageConverter) {
        this.endpointRegistry = endpointRegistry;
        this.messageConverter = messageConverter;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        Map<Method, MqttListener> annotatedMethods = MethodIntrospector.selectMethods(
                targetClass,
                (MethodIntrospector.MetadataLookup<MqttListener>) method ->
                        AnnotatedElementUtils.findMergedAnnotation(method, MqttListener.class)
        );

        if (!annotatedMethods.isEmpty()) {
            for (Map.Entry<Method, MqttListener> entry : annotatedMethods.entrySet()) {
                Method method = entry.getKey();
                MqttListener listener = entry.getValue();
                processListener(bean, beanName, method, listener);
            }
        }

        return bean;
    }

    private void processListener(Object bean, String beanName, Method method, MqttListener listener) {
        String endpointId = beanName + "#" + method.getName();
        MqttMessageConverter converter = resolveMessageConverter();
        MqttListenerEndpoint endpoint = new MqttListenerEndpoint(endpointId, bean, method, listener, converter);
        MqttListenerEndpointRegistry registry = resolveEndpointRegistry();
        if (registry != null) {
            registry.registerEndpoint(endpoint);
            log.debug("[MQTT] Discovered @MqttListener on [{}]", endpointId);
        } else {
            log.warn("[MQTT] MqttListenerEndpointRegistry not available, skipped endpoint [{}]", endpointId);
        }
    }

    private MqttMessageConverter resolveMessageConverter() {
        if (this.messageConverter == null && this.beanFactory != null) {
            try {
                this.messageConverter = this.beanFactory.getBeanProvider(MqttMessageConverter.class).getIfAvailable();
            } catch (BeansException e) {
                log.debug("[MQTT] No MqttMessageConverter available in bean factory: {}", e.getMessage());
            }
        }
        return this.messageConverter;
    }

    private MqttListenerEndpointRegistry resolveEndpointRegistry() {
        if (this.endpointRegistry == null && this.beanFactory != null) {
            try {
                this.endpointRegistry = this.beanFactory.getBean(MqttListenerEndpointRegistry.class);
            } catch (BeansException e) {
                log.warn("[MQTT] Failed to lazily resolve MqttListenerEndpointRegistry: {}", e.getMessage());
            }
        }
        return this.endpointRegistry;
    }
}
