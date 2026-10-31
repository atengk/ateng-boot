package io.github.atengk.boot.mqtt.listener;

import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mqtt.annotation.MqttHeader;
import io.github.atengk.boot.mqtt.annotation.MqttTopic;
import io.github.atengk.boot.mqtt.annotation.MqttVariable;
import io.github.atengk.boot.mqtt.codec.CompositeMqttMessageConverter;
import io.github.atengk.boot.mqtt.codec.MqttMessageConverter;
import io.github.atengk.boot.mqtt.util.MqttHeaderUtils;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MQTT 监听器方法反射调用与智能形参解析适配器
 * 负责解析方法形参上的 {@link MqttTopic}、{@link MqttVariable}、{@link MqttHeader} 与强类型 Payload 反序列化，
 * 并在 {@link MessageContextPropagator} 四维上下文沙箱中受管执行目标方法
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttListenerMethodAdapter {

    private static final Logger log = LoggerFactory.getLogger(MqttListenerMethodAdapter.class);
    private static final ConversionService CONVERSION_SERVICE = DefaultConversionService.getSharedInstance();

    private final Object bean;
    private final Method method;
    private final Parameter[] parameters;
    private final MqttMessageConverter messageConverter;

    public MqttListenerMethodAdapter(Object bean, Method method) {
        this(bean, method, new CompositeMqttMessageConverter());
    }

    public MqttListenerMethodAdapter(Object bean, Method method, MqttMessageConverter messageConverter) {
        this.bean = Objects.requireNonNull(bean, "bean must not be null");
        this.method = Objects.requireNonNull(method, "method must not be null");
        this.messageConverter = messageConverter != null ? messageConverter : new CompositeMqttMessageConverter();
        ReflectionUtils.makeAccessible(this.method);
        this.parameters = this.method.getParameters();
    }

    /**
     * 接收 MQTT 消息并在四维上下文沙箱中调度执行目标方法
     *
     * @param actualTopic  实际物理推送主题
     * @param message      Paho 原生 MQTT 消息
     * @param pathVariables 提取的主题路径变量字典
     */
    public void invoke(String actualTopic, MqttMessage message, Map<String, String> pathVariables) {
        Map<String, Object> headers = extractHeaders(message);
        byte[] payloadBytes = message != null ? message.getPayload() : new byte[0];

        MessageContextPropagator.runInContext(headers, () -> {
            try {
                Object[] args = resolveArguments(actualTopic, message, payloadBytes, pathVariables, headers);
                method.invoke(bean, args);
            } catch (InvocationTargetException e) {
                Throwable target = e.getTargetException();
                if (target instanceof RuntimeException re) {
                    throw re;
                }
                throw new BusinessException("Listener method execution failed: " + target.getMessage(), target);
            } catch (Exception e) {
                throw new BusinessException("Listener method invocation error: " + e.getMessage(), e);
            }
        });
    }

    private Object[] resolveArguments(String actualTopic,
                                      MqttMessage message,
                                      byte[] payloadBytes,
                                      Map<String, String> pathVariables,
                                      Map<String, Object> headers) {
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Class<?> paramType = param.getType();

            if (param.isAnnotationPresent(MqttTopic.class)) {
                args[i] = actualTopic;
            } else if (param.isAnnotationPresent(MqttVariable.class)) {
                MqttVariable varAnno = param.getAnnotation(MqttVariable.class);
                String rawVal = pathVariables != null ? pathVariables.get(varAnno.value()) : null;
                args[i] = convertType(rawVal, paramType);
            } else if (param.isAnnotationPresent(MqttHeader.class)) {
                MqttHeader headerAnno = param.getAnnotation(MqttHeader.class);
                if (Map.class.isAssignableFrom(paramType) && !StringUtils.hasText(headerAnno.value())) {
                    args[i] = headers != null ? Collections.unmodifiableMap(new LinkedHashMap<>(headers)) : Collections.emptyMap();
                } else {
                    Object val = findHeader(headers, headerAnno.value());
                    if (val == null && headerAnno.required()) {
                        throw new BusinessException("Required MQTT header [" + headerAnno.value() + "] is missing");
                    }
                    args[i] = convertType(val != null ? String.valueOf(val) : null, paramType);
                }
            } else if (MqttMessage.class.isAssignableFrom(paramType)) {
                args[i] = message;
            } else {
                // 基于 MqttMessageConverter SPI 进行自适应类型编解码与转译
                args[i] = messageConverter.fromMessage(message, paramType);
            }
        }
        return args;
    }

    private Object convertType(String value, Class<?> targetType) {
        if (value == null) {
            return targetType.isPrimitive() ? CONVERSION_SERVICE.convert("", targetType) : null;
        }
        if (String.class.equals(targetType)) {
            return value;
        }
        if (CONVERSION_SERVICE.canConvert(String.class, targetType)) {
            return CONVERSION_SERVICE.convert(value, targetType);
        }
        return value;
    }

    private Object findHeader(Map<String, Object> headers, String headerName) {
        if (headers == null || headers.isEmpty() || headerName == null) {
            return null;
        }
        Object direct = headers.get(headerName);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (headerName.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Map<String, Object> extractHeaders(MqttMessage message) {
        return new HashMap<>(MqttHeaderUtils.extractHeaders(message));
    }

    public Object getBean() {
        return bean;
    }

    public Method getMethod() {
        return method;
    }
}
