package io.github.atengk.boot.mqtt.actuator;

import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Spring Boot Actuator 专属 MQTT 健康探针
 * 检查所有受管命名客户端的物理连接与消息积压状态，内置 2 秒硬超时熔断与不可变快照隔离防竞态
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttHealthIndicator extends AbstractHealthIndicator {

    private static final long TIMEOUT_SECONDS = 2L;

    private final NamedMqttClientRegistry clientRegistry;
    private final Executor executor;

    private record HealthSnapshot(boolean empty, boolean allConnected, Map<String, Object> clientsData) {}

    public MqttHealthIndicator(NamedMqttClientRegistry clientRegistry, Executor executor) {
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry must not be null");
        this.executor = executor;
    }

    public MqttHealthIndicator(NamedMqttClientRegistry clientRegistry) {
        this(clientRegistry, null);
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        CompletableFuture<HealthSnapshot> checkFuture = executor != null
                ? CompletableFuture.supplyAsync(this::captureSnapshot, executor)
                : CompletableFuture.supplyAsync(this::captureSnapshot);
        try {
            HealthSnapshot snapshot = checkFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (snapshot.empty()) {
                builder.up().withDetail("status", "No MQTT clients configured or running");
                return;
            }
            builder.withDetail("clients", snapshot.clientsData());
            if (snapshot.allConnected()) {
                builder.up();
            } else {
                builder.down().withDetail("reason", "One or more MQTT clients are disconnected");
            }
        } catch (TimeoutException e) {
            checkFuture.cancel(true);
            builder.down()
                    .withException(e)
                    .withDetail("error", "MQTT health check timed out after " + TIMEOUT_SECONDS + " seconds");
        } catch (Exception e) {
            builder.down()
                    .withException(e)
                    .withDetail("error", "MQTT health check failed: " + e.getMessage());
        }
    }

    private HealthSnapshot captureSnapshot() {
        Map<String, MqttClientHandle> handles = clientRegistry.getClientHandles();
        if (handles.isEmpty()) {
            return new HealthSnapshot(true, true, Collections.emptyMap());
        }

        Map<String, Object> clientsData = new HashMap<>(handles.size());
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
            } else {
                details = Collections.unmodifiableMap(new HashMap<>(details));
            }
            clientsData.put(clientName, details);
        }

        return new HealthSnapshot(false, allConnected, Collections.unmodifiableMap(clientsData));
    }
}
