package io.github.atengk.boot.mqtt.listener;

import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.codec.MqttMessageConverter;
import io.github.atengk.boot.mqtt.routing.MqttTopicPattern;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个声明式 MQTT 监听器端点元数据实体
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttListenerEndpoint {

    private final String id;
    private final Object bean;
    private final Method method;
    private final MqttListener annotation;
    private final MqttListenerMethodAdapter adapter;
    private final List<MqttTopicPattern> patterns;

    public MqttListenerEndpoint(String id, Object bean, Method method, MqttListener annotation) {
        this(id, bean, method, annotation, null);
    }

    public MqttListenerEndpoint(String id, Object bean, Method method, MqttListener annotation, MqttMessageConverter messageConverter) {
        this.id = id;
        this.bean = bean;
        this.method = method;
        this.annotation = annotation;
        this.adapter = new MqttListenerMethodAdapter(bean, method, messageConverter);

        List<MqttTopicPattern> list = new ArrayList<>();
        for (String topic : annotation.topics()) {
            list.add(new MqttTopicPattern(topic));
        }
        this.patterns = Collections.unmodifiableList(list);
    }

    public String getId() {
        return id;
    }

    public Object getBean() {
        return bean;
    }

    public Method getMethod() {
        return method;
    }

    public MqttListener getAnnotation() {
        return annotation;
    }

    public MqttListenerMethodAdapter getAdapter() {
        return adapter;
    }

    public List<MqttTopicPattern> getPatterns() {
        return patterns;
    }

    public String getClientName() {
        return annotation.client();
    }

    public String getSharedGroup() {
        return annotation.sharedGroup();
    }

    public int getQos() {
        return annotation.qos();
    }
}
