package io.github.atengk.boot.mqtt.listener;

import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.annotation.MqttReply;
import io.github.atengk.boot.mqtt.codec.MqttMessageConverter;
import io.github.atengk.boot.mqtt.routing.MqttTopicPattern;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;

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
    private final MqttReply replyAnnotation;
    private final MqttListenerMethodAdapter adapter;
    private final List<MqttTopicPattern> patterns;
    private final Semaphore concurrencySemaphore;

    public MqttListenerEndpoint(String id, Object bean, Method method, MqttListener annotation) {
        this(id, bean, method, annotation, null);
    }

    public MqttListenerEndpoint(String id, Object bean, Method method, MqttListener annotation, MqttMessageConverter messageConverter) {
        validateQos(annotation.qos(), "@MqttListener qos");
        if (annotation.retainHandling() < 0 || annotation.retainHandling() > 2) {
            throw new IllegalArgumentException(
                    "Invalid @MqttListener retainHandling [" + annotation.retainHandling() + "] on endpoint [" + id + "]: must be 0, 1, or 2");
        }
        MqttReply resolvedReply = method != null ? AnnotatedElementUtils.findMergedAnnotation(method, MqttReply.class) : null;
        if (resolvedReply != null) {
            validateQos(resolvedReply.qos(), "@MqttReply qos");
        }

        this.id = id;
        this.bean = bean;
        this.method = method;
        this.annotation = annotation;
        this.replyAnnotation = resolvedReply;
        this.adapter = new MqttListenerMethodAdapter(bean, method, messageConverter);

        List<MqttTopicPattern> list = new ArrayList<>();
        for (String topic : annotation.topics()) {
            list.add(new MqttTopicPattern(topic));
        }
        this.patterns = Collections.unmodifiableList(list);
        this.concurrencySemaphore = annotation.concurrency() > 0 ? new Semaphore(annotation.concurrency()) : null;
    }

    private static void validateQos(int qos, String label) {
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("Invalid " + label + " [" + qos + "]: must be 0, 1, or 2");
        }
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

    /**
     * 获取方法上的声明式应答注解元数据（支持元注解组合解析）
     *
     * @return @MqttReply 元数据，未标注则返回 null
     */
    public MqttReply getReplyAnnotation() {
        return replyAnnotation;
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

    public int getConcurrency() {
        return annotation.concurrency();
    }

    public Semaphore getConcurrencySemaphore() {
        return concurrencySemaphore;
    }
}
