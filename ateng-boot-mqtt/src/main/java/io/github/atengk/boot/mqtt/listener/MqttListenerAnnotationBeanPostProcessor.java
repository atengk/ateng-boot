package io.github.atengk.boot.mqtt.listener;

import io.github.atengk.boot.mqtt.annotation.MqttListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/**
 * {@link MqttListener} 声明式注解方法后置处理器
 * 自动探测 Spring 容器中受管 Bean 的监听方法并注册至 {@link MqttListenerEndpointRegistry}
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttListenerAnnotationBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(MqttListenerAnnotationBeanPostProcessor.class);

    private final MqttListenerEndpointRegistry endpointRegistry;

    public MqttListenerAnnotationBeanPostProcessor(MqttListenerEndpointRegistry endpointRegistry) {
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry must not be null");
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
        MqttListenerEndpoint endpoint = new MqttListenerEndpoint(endpointId, bean, method, listener);
        endpointRegistry.registerEndpoint(endpoint);
        log.debug("[MQTT] Discovered @MqttListener on [{}]", endpointId);
    }
}
