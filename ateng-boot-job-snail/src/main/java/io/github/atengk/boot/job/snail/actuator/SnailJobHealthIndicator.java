package io.github.atengk.boot.job.snail.actuator;

import com.aizuda.snailjob.client.common.rpc.client.grpc.GrpcChannel;
import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.grpc.ManagedChannel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * SnailJob 分布式任务调度与分布式重试双擎健康探针
 *
 * <p>暴露于 Spring Boot Actuator {@code /actuator/health} 端点下，动态探测执行器运行时健康状态：
 * <ul>
 *     <li><b>开闭状态探测</b>：执行器未激活时报告 {@code DISABLED}，未配置通信地址时报告 {@code BYPASSED}；</li>
 *     <li><b>Netty / gRPC 物理长连接探测</b>：探测 SnailJob 底层 gRPC Channel 通信状态与就绪活性；</li>
 *     <li><b>服务端网络探测</b>：探测远端调度服务端 IP 与端口网络可达性；</li>
 *     <li><b>平滑排空运行态暴露</b>：暴露当前在途任务数与平滑停机排空标志。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobHealthIndicator implements HealthIndicator {

    private final SnailJobProperties properties;
    private final GracefulDrainStateMachine drainStateMachine;

    public SnailJobHealthIndicator(SnailJobProperties properties, GracefulDrainStateMachine drainStateMachine) {
        this.properties = properties != null ? properties : new SnailJobProperties();
        this.drainStateMachine = drainStateMachine;
    }

    @Override
    public Health health() {
        if (!properties.isEnabled()) {
            return Health.up()
                    .withDetail("status", "DISABLED")
                    .withDetail("message", "SnailJob 双擎执行器已显式禁用")
                    .build();
        }

        if (!StringUtils.hasText(properties.getServer())) {
            return Health.unknown()
                    .withDetail("status", "BYPASSED")
                    .withDetail("message", "未配置 SnailJob 服务端通信地址 ('ateng.boot.job.snail.server')，处于静默旁路模式")
                    .build();
        }

        boolean serverReachable = checkServerReachable(properties.getServer());
        boolean channelActive = checkGrpcChannelActive();
        boolean draining = drainStateMachine != null && drainStateMachine.isDraining();

        Health.Builder builder = (serverReachable || channelActive) ? Health.up() : Health.down();

        builder.withDetail("server", properties.getServer())
                .withDetail("namespace", properties.getNamespace())
                .withDetail("group", properties.getGroup())
                .withDetail("port", properties.getPort())
                .withDetail("serverReachable", serverReachable)
                .withDetail("channelActive", channelActive)
                .withDetail("draining", draining);

        if (drainStateMachine != null) {
            builder.withDetail("inFlightJobs", drainStateMachine.getInFlightJobCount())
                    .withDetail("inFlightRetries", drainStateMachine.getInFlightRetryCount());
        }

        return builder.build();
    }

    /**
     * 探测配置的服务端地址是否网络可达
     */
    private boolean checkServerReachable(String serverConfig) {
        if (!StringUtils.hasText(serverConfig)) {
            return false;
        }

        String[] servers = serverConfig.split(",");
        for (String server : servers) {
            String cleanServer = server.trim();
            if (cleanServer.isEmpty()) {
                continue;
            }

            String host = cleanServer;
            int port = 17888;
            if (cleanServer.contains(":")) {
                String[] parts = cleanServer.split(":");
                host = parts[0].trim();
                try {
                    port = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }

            if (isReachable(host, port, 500)) {
                return true;
            }
        }
        return false;
    }

    private boolean isReachable(String host, int port, int timeoutMs) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 探测 gRPC ManagedChannel 是否已初始化并处于活跃状态
     */
    private boolean checkGrpcChannelActive() {
        try {
            Field channelField = GrpcChannel.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            Object chan = channelField.get(null);
            if (chan instanceof ManagedChannel managedChannel) {
                return !managedChannel.isShutdown() && !managedChannel.isTerminated();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
