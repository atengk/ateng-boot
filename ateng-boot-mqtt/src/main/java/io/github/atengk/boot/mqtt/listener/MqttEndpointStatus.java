package io.github.atengk.boot.mqtt.listener;

import java.util.List;

/**
 * MQTT 声明式监听器端点运行期状态快照实体
 *
 * @author Ateng
 * @since 2026-10-31
 */
public record MqttEndpointStatus(
        String endpointId,
        String clientName,
        List<String> topics,
        String sharedGroup,
        int qos,
        int concurrency,
        boolean paused,
        boolean running,
        String targetMethod
) {}
