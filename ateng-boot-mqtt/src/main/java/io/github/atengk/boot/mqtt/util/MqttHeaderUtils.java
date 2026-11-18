package io.github.atengk.boot.mqtt.util;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MQTT 协议标头与 UserProperties 提取转换工具类
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class MqttHeaderUtils {

    private MqttHeaderUtils() {}

    /**
     * 从 {@link MqttMessage} 中提取用户属性并转换为字符串标头映射
     *
     * @param message MQTT 报文
     * @return 标头键值对映射
     */
    public static Map<String, String> extractHeaders(MqttMessage message) {
        return message != null ? extractHeaders(message.getProperties()) : Collections.emptyMap();
    }

    /**
     * 从 {@link MqttProperties} 中提取用户属性并转换为字符串标头映射
     *
     * @param properties MQTT 5.0 属性包
     * @return 标头键值对映射
     */
    public static Map<String, String> extractHeaders(MqttProperties properties) {
        if (properties == null) {
            return Collections.emptyMap();
        }
        List<UserProperty> userProperties = properties.getUserProperties();
        if (userProperties == null || userProperties.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> headers = new HashMap<>(userProperties.size());
        for (UserProperty prop : userProperties) {
            if (prop != null && prop.getKey() != null) {
                headers.put(prop.getKey(), prop.getValue());
            }
        }
        return headers;
    }

    /**
     * 将当前线程持有的四维上下文（TraceId / TenantId / UserId / IgnoreTenant）追加为 MQTT 5.0 用户属性
     *
     * @param userProperties 目标属性列表
     */
    public static void appendCurrentContextUserProperties(List<UserProperty> userProperties) {
        String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        String tenantId = TenantContextHolder.getTenantId();
        String userId = UserContextHolder.get();
        String ignoreTenant = TenantContextHolder.isIgnoreTenant() ? "true" : null;
        appendContextUserProperties(userProperties, traceId, tenantId, userId, ignoreTenant);
    }

    /**
     * 将显式指定的四维上下文值追加为 MQTT 5.0 用户属性（TraceId 缺失时自动生成 UUID）
     *
     * @param userProperties 目标属性列表
     * @param traceId        链路追踪标识
     * @param tenantId       租户标识
     * @param userId         用户标识
     * @param ignoreTenant   忽略租户开关标识
     */
    public static void appendContextUserProperties(List<UserProperty> userProperties,
                                                   String traceId,
                                                   String tenantId,
                                                   String userId,
                                                   String ignoreTenant) {
        if (userProperties == null) {
            return;
        }
        String effectiveTraceId = StringUtils.hasText(traceId) ? traceId : IdUtil.fastSimpleUUID();
        userProperties.add(new UserProperty(MessageHeaderConstants.TRACE_ID, effectiveTraceId));
        if (StringUtils.hasText(tenantId)) {
            userProperties.add(new UserProperty(MessageHeaderConstants.TENANT_ID, tenantId));
        }
        if (StringUtils.hasText(userId)) {
            userProperties.add(new UserProperty(MessageHeaderConstants.USER_ID, userId));
        }
        if (StringUtils.hasText(ignoreTenant)) {
            userProperties.add(new UserProperty(MessageHeaderConstants.IGNORE_TENANT, ignoreTenant));
        }
    }
}
