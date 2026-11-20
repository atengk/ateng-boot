package io.github.atengk.boot.job.snail.actuator;

import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.github.atengk.boot.job.snail.support.SnailJobInternalSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SnailJob 分布式任务调度与分布式重试双擎非阻塞缓存版健康探针
 *
 * <p>暴露于 Spring Boot Actuator {@code /actuator/health} 端点下，通过受 {@link ReentrantLock} 保护的
 * {@code healthCacheTtl} 结果缓存与受管并发有界超时探测服务端多活节点：
 * <ul>
 *     <li>彻底移除对底层 {@code GrpcChannel} 私有字段的易脆反射耦合；</li>
 *     <li>多服务端节点并发有界超时探测，至少 1 个可达返回 {@code UP}（同时标注 {@code degraded} 与在线/离线节点清单），
 *     全部不可达返回 {@code DOWN}；</li>
 *     <li>在缓存有效期内实时追加最新平滑排空（{@code draining}）与在途任务数运行态指标。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(SnailJobHealthIndicator.class);
    private static final int DEFAULT_SERVER_PORT = 17888;
    private static final long DEFAULT_CACHE_TTL_MS = 10_000L;
    private static final long DEFAULT_PROBE_TIMEOUT_MS = 800L;

    private final SnailJobProperties properties;
    private final GracefulDrainStateMachine drainStateMachine;
    private final Executor taskExecutor;
    private final ReentrantLock cacheLock = new ReentrantLock();
    private volatile CachedProbeResult cachedProbeResult;

    public SnailJobHealthIndicator(SnailJobProperties properties, GracefulDrainStateMachine drainStateMachine) {
        this(properties, drainStateMachine, null);
    }

    public SnailJobHealthIndicator(
            SnailJobProperties properties,
            GracefulDrainStateMachine drainStateMachine,
            Executor taskExecutor) {
        this.properties = properties != null ? properties : new SnailJobProperties();
        this.drainStateMachine = drainStateMachine;
        this.taskExecutor = taskExecutor;
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

        Duration cacheTtl = resolveCacheTtl();
        int probeTimeoutMs = resolveProbeTimeoutMs();
        ProbeResolution resolution = resolveProbeSnapshot(properties.getServer(), cacheTtl, probeTimeoutMs);
        ProbeSnapshot snapshot = resolution.snapshot();

        boolean draining = drainStateMachine != null && drainStateMachine.isDraining();
        boolean serverReachable = !snapshot.onlineServers().isEmpty();
        boolean degraded = serverReachable && !snapshot.offlineServers().isEmpty();

        Health.Builder builder = serverReachable ? Health.up() : Health.down();
        builder.withDetail("server", properties.getServer())
                .withDetail("namespace", properties.getNamespace())
                .withDetail("group", properties.getGroup())
                .withDetail("port", properties.getPort())
                .withDetail("serverReachable", serverReachable)
                .withDetail("degraded", degraded)
                .withDetail("onlineServers", snapshot.onlineServers())
                .withDetail("offlineServers", snapshot.offlineServers())
                .withDetail("probeTimeoutMs", probeTimeoutMs)
                .withDetail("cacheHit", resolution.cacheHit())
                .withDetail("draining", draining);

        if (drainStateMachine != null) {
            builder.withDetail("inFlightJobs", drainStateMachine.getInFlightJobCount())
                    .withDetail("inFlightRetries", drainStateMachine.getInFlightRetryCount());
        }

        return builder.build();
    }

    private ProbeResolution resolveProbeSnapshot(String serverConfig, Duration cacheTtl, int probeTimeoutMs) {
        boolean cacheEnabled = cacheTtl != null && !cacheTtl.isZero() && !cacheTtl.isNegative();
        if (!cacheEnabled) {
            ProbeSnapshot liveSnapshot = probeServersParallel(serverConfig, probeTimeoutMs);
            cachedProbeResult = null;
            return new ProbeResolution(liveSnapshot, false);
        }

        Instant now = Instant.now();
        CachedProbeResult currentCache = this.cachedProbeResult;
        if (currentCache != null && now.isBefore(currentCache.expiresAt())
                && serverConfig.equals(currentCache.serverConfig())) {
            return new ProbeResolution(currentCache.snapshot(), true);
        }

        cacheLock.lock();
        try {
            Instant lockedNow = Instant.now();
            CachedProbeResult doubleChecked = this.cachedProbeResult;
            if (doubleChecked != null && lockedNow.isBefore(doubleChecked.expiresAt())
                    && serverConfig.equals(doubleChecked.serverConfig())) {
                return new ProbeResolution(doubleChecked.snapshot(), true);
            }
            ProbeSnapshot fresh = probeServersParallel(serverConfig, probeTimeoutMs);
            this.cachedProbeResult = new CachedProbeResult(serverConfig, fresh, lockedNow.plus(cacheTtl));
            return new ProbeResolution(fresh, false);
        } finally {
            if (cacheLock.isHeldByCurrentThread()) {
                cacheLock.unlock();
            }
        }
    }

    private ProbeSnapshot probeServersParallel(String serverConfig, int probeTimeoutMs) {
        List<ServerEndpoint> endpoints = parseServerEndpoints(serverConfig);
        if (endpoints.isEmpty()) {
            return new ProbeSnapshot(Collections.emptyList(), Collections.emptyList());
        }

        Executor resolvedExecutor = SnailJobInternalSupport.resolveManagedExecutor(
                this.taskExecutor, properties.isManagedPoolEnabled());
        if (endpoints.size() == 1 || resolvedExecutor == null) {
            List<String> online = new ArrayList<>();
            List<String> offline = new ArrayList<>();
            for (ServerEndpoint endpoint : endpoints) {
                if (isReachable(endpoint.host(), endpoint.port(), probeTimeoutMs)) {
                    online.add(endpoint.label());
                } else {
                    offline.add(endpoint.label());
                }
            }
            return new ProbeSnapshot(Collections.unmodifiableList(online), Collections.unmodifiableList(offline));
        }

        List<CompletableFuture<EndpointProbeOutcome>> futures = new ArrayList<>(endpoints.size());
        for (ServerEndpoint endpoint : endpoints) {
            CompletableFuture<EndpointProbeOutcome> future = CompletableFuture
                    .supplyAsync(() -> new EndpointProbeOutcome(
                            endpoint.label(),
                            isReachable(endpoint.host(), endpoint.port(), probeTimeoutMs)), resolvedExecutor)
                    .completeOnTimeout(new EndpointProbeOutcome(endpoint.label(), false),
                            probeTimeoutMs + 150L, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> new EndpointProbeOutcome(endpoint.label(), false));
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(probeTimeoutMs + 300L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("健康探针并发探测等待被中断: {}", ie.getMessage());
        } catch (Throwable ex) {
            log.debug("健康探针并发探测整体超时或异常降级: {}", ex.getMessage());
        }

        List<String> online = new ArrayList<>();
        List<String> offline = new ArrayList<>();
        for (int i = 0; i < endpoints.size(); i++) {
            ServerEndpoint endpoint = endpoints.get(i);
            CompletableFuture<EndpointProbeOutcome> future = futures.get(i);
            boolean reachable = future.isDone() && !future.isCompletedExceptionally()
                    && future.getNow(new EndpointProbeOutcome(endpoint.label(), false)).reachable();
            if (reachable) {
                online.add(endpoint.label());
            } else {
                offline.add(endpoint.label());
            }
        }
        return new ProbeSnapshot(Collections.unmodifiableList(online), Collections.unmodifiableList(offline));
    }

    private List<ServerEndpoint> parseServerEndpoints(String serverConfig) {
        if (!StringUtils.hasText(serverConfig)) {
            return Collections.emptyList();
        }
        String[] rawServers = serverConfig.split(",");
        Set<ServerEndpoint> endpoints = new LinkedHashSet<>();
        for (String server : rawServers) {
            String clean = server.trim();
            if (clean.isEmpty()) {
                continue;
            }
            String host = clean;
            int port = DEFAULT_SERVER_PORT;
            int colonIdx = clean.lastIndexOf(':');
            if (colonIdx > 0 && colonIdx < clean.length() - 1) {
                host = clean.substring(0, colonIdx).trim();
                try {
                    port = Integer.parseInt(clean.substring(colonIdx + 1).trim());
                } catch (NumberFormatException ignored) {
                    port = DEFAULT_SERVER_PORT;
                }
            }
            if (StringUtils.hasText(host)) {
                endpoints.add(new ServerEndpoint(host, port, host + ":" + port));
            }
        }
        return new ArrayList<>(endpoints);
    }

    private Duration resolveCacheTtl() {
        Duration ttl = properties.getHealthCacheTtl();
        return ttl != null ? ttl : Duration.ofMillis(DEFAULT_CACHE_TTL_MS);
    }

    private int resolveProbeTimeoutMs() {
        Duration timeout = properties.getHealthProbeTimeout();
        long ms = (timeout != null && !timeout.isNegative() && !timeout.isZero())
                ? timeout.toMillis()
                : DEFAULT_PROBE_TIMEOUT_MS;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(50L, ms));
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

    private record ServerEndpoint(String host, int port, String label) {
    }

    private record EndpointProbeOutcome(String label, boolean reachable) {
    }

    private record ProbeSnapshot(List<String> onlineServers, List<String> offlineServers) {
    }

    private record CachedProbeResult(String serverConfig, ProbeSnapshot snapshot, Instant expiresAt) {
    }

    private record ProbeResolution(ProbeSnapshot snapshot, boolean cacheHit) {
    }
}
