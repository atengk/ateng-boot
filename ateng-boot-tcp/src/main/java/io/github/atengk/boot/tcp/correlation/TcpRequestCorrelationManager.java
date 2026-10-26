package io.github.atengk.boot.tcp.correlation;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.tcp.exception.TcpException;
import io.github.atengk.boot.tcp.exception.TcpTimeoutException;
import io.github.atengk.boot.tcp.metric.TcpMetricsCollector;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * TCP 全双工请求-响应 RPC 相关性管理器
 * <p>
 * 基于唯一关联流水号（{@code requestId}）与有界 Future 映射池构建全双工请求-响应闭环，
 * 依托 Netty {@link HashedWheelTimer} 实现确定性超时熔断与挂起句柄自动剔除，杜绝内存泄漏。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpRequestCorrelationManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TcpRequestCorrelationManager.class);

    /**
     * 默认开箱即用的关联标识提取器
     */
    public static final TcpCorrelationIdExtractor DEFAULT_EXTRACTOR = message -> {
        if (message == null) {
            return null;
        }
        if (message instanceof TcpPacket packet) {
            if (packet.getRequestId() != null && !packet.getRequestId().isBlank()) {
                return packet.getRequestId();
            }
            return extractFromJsonOrString(packet.getPayload() != null ? packet.getPayload().asString() : null);
        }
        if (message instanceof TcpPayload payload) {
            return extractFromJsonOrString(payload.asString());
        }
        if (message instanceof String text) {
            return extractFromJsonOrString(text);
        }
        if (message instanceof Map<?, ?> map) {
            Object val = map.get("requestId");
            return val != null ? val.toString() : null;
        }
        return null;
    };

    private final ConcurrentMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final HashedWheelTimer timer;
    private volatile int maxPendingRequests = 10000;
    private volatile TcpCorrelationIdExtractor correlationIdExtractor = DEFAULT_EXTRACTOR;
    private volatile TcpMetricsCollector metricsCollector;

    public TcpRequestCorrelationManager() {
        this(new HashedWheelTimer(new DefaultThreadFactory("Netty-Correlation-Timer", true)), 10000);
    }

    public TcpRequestCorrelationManager(TcpMetricsCollector metricsCollector) {
        this();
        this.metricsCollector = metricsCollector;
    }

    public TcpRequestCorrelationManager(HashedWheelTimer timer, int maxPendingRequests) {
        this(timer, maxPendingRequests, null);
    }

    public TcpRequestCorrelationManager(HashedWheelTimer timer, int maxPendingRequests, TcpMetricsCollector metricsCollector) {
        this.timer = Objects.requireNonNull(timer, "timer 不能为空");
        this.maxPendingRequests = maxPendingRequests > 0 ? maxPendingRequests : 10000;
        this.metricsCollector = metricsCollector;
    }

    /**
     * 向关联池注册等待响应的请求
     *
     * @param requestId 消息全局唯一关联流水号
     * @param timeout   等待超时时长
     * @return 关联的异步响应 Future
     */
    public CompletableFuture<TcpPacket> register(String requestId, Duration timeout) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
        if (pendingRequests.size() >= maxPendingRequests) {
            throw new TcpException("待处理 TCP RPC 请求数量达到上限 [" + maxPendingRequests + "], 触发过载熔断保护");
        }

        CompletableFuture<TcpPacket> future = new CompletableFuture<>();

        Timeout timeoutTask = timer.newTimeout(t -> {
            PendingRequest removed = pendingRequests.remove(requestId);
            if (removed != null && !removed.future.isDone()) {
                log.warn("TCP 请求等待对端应答超时，自动清理挂起句柄: requestId={}, timeout={}", requestId, timeout);
                removed.future.completeExceptionally(
                        new TcpTimeoutException("TCP 请求等待对端应答超时: requestId=" + requestId + ", timeout=" + timeout)
                );
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        PendingRequest pending = new PendingRequest(requestId, future, timeoutTask);
        pendingRequests.put(requestId, pending);

        // 调用方主动 cancel 时自动清理映射表与定时任务
        future.whenComplete((res, ex) -> {
            if (future.isCancelled()) {
                timeoutTask.cancel();
                pendingRequests.remove(requestId);
            }
        });

        return future;
    }

    /**
     * 精准兑现指定关联号的 RPC 响应
     *
     * @param requestId 关联流水号
     * @param response  应答报文
     * @return 是否成功匹配并兑现
     */
    public boolean complete(String requestId, TcpPacket response) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        PendingRequest pending = pendingRequests.remove(requestId);
        if (pending != null) {
            pending.timeoutTask.cancel();
            if (metricsCollector != null) {
                long durationMs = System.currentTimeMillis() - pending.createTime;
                metricsCollector.recordRequestDuration(Duration.ofMillis(Math.max(0, durationMs)));
            }
            pending.future.complete(response);
            return true;
        }
        return false;
    }

    /**
     * 异常中止指定关联号的 RPC 请求
     *
     * @param requestId 关联流水号
     * @param cause     异常原因
     * @return 是否成功匹配并异常中止
     */
    public boolean completeExceptionally(String requestId, Throwable cause) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        PendingRequest pending = pendingRequests.remove(requestId);
        if (pending != null) {
            pending.timeoutTask.cancel();
            if (metricsCollector != null) {
                long durationMs = System.currentTimeMillis() - pending.createTime;
                metricsCollector.recordRequestDuration(Duration.ofMillis(Math.max(0, durationMs)));
            }
            pending.future.completeExceptionally(cause);
            return true;
        }
        return false;
    }

    /**
     * 异常补偿中止所有当前在途挂起的 RPC 请求（例如底层网络通道断开连接时，防挂起句柄泄漏）
     *
     * @param cause 失败原因
     */
    public void failAll(Throwable cause) {
        if (pendingRequests.isEmpty()) {
            return;
        }
        Throwable error = cause != null ? cause : new TcpException("TCP 连接已断开，终止在途 RPC 请求");
        for (PendingRequest pending : pendingRequests.values()) {
            pending.timeoutTask.cancel();
            pending.future.completeExceptionally(error);
        }
        pendingRequests.clear();
    }

    /**
     * 尝试根据入站通信报文唤醒对应的等待 Future
     *
     * @param message 入站消息对象
     * @return 若成功匹配到挂起请求并兑现则返回 true，否则返回 false
     */
    public boolean tryComplete(Object message) {
        if (message == null || pendingRequests.isEmpty()) {
            return false;
        }
        String requestId = correlationIdExtractor.extract(message);
        if (requestId != null && !requestId.isBlank()) {
            PendingRequest pending = pendingRequests.get(requestId);
            if (pending != null) {
                TcpPacket packet = toTcpPacket(message, requestId);
                return complete(requestId, packet);
            }
        }
        return false;
    }

    /**
     * 是否存在挂起的未完成请求
     *
     * @return true 表示存在
     */
    public boolean hasPendingRequests() {
        return !pendingRequests.isEmpty();
    }

    /**
     * 当前挂起等待应答的请求总数
     *
     * @return 挂起请求数
     */
    public int getPendingCount() {
        return pendingRequests.size();
    }

    public TcpMetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public void setMetricsCollector(TcpMetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    public TcpCorrelationIdExtractor getCorrelationIdExtractor() {
        return correlationIdExtractor;
    }

    public void setCorrelationIdExtractor(TcpCorrelationIdExtractor correlationIdExtractor) {
        this.correlationIdExtractor = correlationIdExtractor != null ? correlationIdExtractor : DEFAULT_EXTRACTOR;
    }

    public int getMaxPendingRequests() {
        return maxPendingRequests;
    }

    public void setMaxPendingRequests(int maxPendingRequests) {
        if (maxPendingRequests > 0) {
            this.maxPendingRequests = maxPendingRequests;
        }
    }

    @Override
    public void close() {
        for (PendingRequest pending : pendingRequests.values()) {
            pending.timeoutTask.cancel();
            pending.future.completeExceptionally(new TcpException("TCP 通信底座已关闭，终止等待对端应答: requestId=" + pending.requestId));
        }
        pendingRequests.clear();
        try {
            timer.stop();
        } catch (Exception ignored) {
        }
    }

    private TcpPacket toTcpPacket(Object message, String requestId) {
        if (message instanceof TcpPacket p) {
            return p;
        }
        if (message instanceof TcpPayload payload) {
            return TcpPacket.of(null, requestId, payload);
        }
        if (message instanceof byte[] bytes) {
            return TcpPacket.of(null, requestId, TcpPayload.of(bytes));
        }
        if (message instanceof String text) {
            return TcpPacket.of(null, requestId, TcpPayload.of(text));
        }
        return TcpPacket.of(null, requestId, TcpPayload.fromJson(message));
    }

    private static String extractFromJsonOrString(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.contains("\"requestId\"")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = JsonUtil.parseObject(trimmed, Map.class);
                if (map != null && map.get("requestId") != null) {
                    return map.get("requestId").toString();
                }
            } catch (Exception ignored) {
                // 非标准 JSON 格式忽略
            }
        }
        return null;
    }

    private static class PendingRequest {
        final String requestId;
        final CompletableFuture<TcpPacket> future;
        final Timeout timeoutTask;
        final long createTime;

        PendingRequest(String requestId, CompletableFuture<TcpPacket> future, Timeout timeoutTask) {
            this.requestId = requestId;
            this.future = future;
            this.timeoutTask = timeoutTask;
            this.createTime = System.currentTimeMillis();
        }
    }
}
