package io.github.atengk.boot.mqtt.actuator;

import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Spring Boot Actuator 专属 MQTT 健康探针
 * 检查所有受管命名客户端的物理连接与消息积压状态，内置 2 秒硬超时熔断防 Paho 假死
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttHealthIndicator extends AbstractHealthIndicator {

    private static final long TIMEOUT_SECONDS = 2L;

    private final NamedMqttClientRegistry clientRegistry;
    private final Executor executor;

    public MqttHealthIndicator(NamedMqttClientRegistry clientRegistry, Executor executor) {
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry must not be null");
        this.executor = executor;
    }

    public MqttHealthIndicator(NamedMqttClientRegistry clientRegistry) {
        this(clientRegistry, null);
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        CompletableFuture<Void> checkFuture = executor != null
                ? CompletableFuture.runAsync(() -> performCheck(builder), executor)
                : CompletableFuture.runAsync(() -> performCheck(builder));
        try {
            checkFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            checkFuture.cancel(true);
            builder.down()
                    .withDetail("error", "MQTT health check timed out after " + TIMEOUT_SECONDS + " seconds")
                    .withException(e);
        } catch (Exception e) {
            builder.down()
                    .withDetail("error", "MQTT health check failed: " + e.getMessage())
                    .withException(e);
        }
    }

    private void performCheck(Health.Builder builder) {
        Map<String, MqttClientHandle> handles = clientRegistry.getClientHandles();
        if (handles.isEmpty()) {
            builder.up().withDetail("status", "No MQTT clients configured or running");
            return;
        }

        Map<String, Object> clientsData = new HashMap<>();
        boolean allConnected = true;

        for (Map.Entry<String, MqttClientHandle> entry : handles.entrySet()) {
            String clientName = entry.getKey();
            MqttClientHandle handle = entry.getValue();

            boolean connected = handle.isConnected();
            if (!connected) {
                allConnected = false;
            }

            Map<String, Object> details = handle.getHealthDetails();
            if (details == null || details.isEmpty()) {
                details = new HashMap<>();
                details.put("connected", connected);
                details.put("status", connected ? "CONNECTED" : "DISCONNECTED");
            }
            clientsData.put(clientName, details);
        }

        builder.withDetail("clients", clientsData);
        if (allConnected) {
            builder.up();
        } else {
            builder.down().withDetail("reason", "One or more MQTT clients are disconnected");
        }
    }
}
