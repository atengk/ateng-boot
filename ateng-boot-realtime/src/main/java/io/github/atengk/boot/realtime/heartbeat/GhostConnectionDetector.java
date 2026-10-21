package io.github.atengk.boot.realtime.heartbeat;

import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import io.github.atengk.boot.realtime.sse.SseStreamHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 幽灵连接与死链安全排空心跳探针
 * <p>
 * 周期性扫描受管会话活跃状态：
 * 1. 向 WebSocket 下发协议 Ping 帧、向 SSE 下发 :keepalive\n\n 注释保活帧；
 * 2. 超过 idleTimeout 未产生任何上行动静的死链，服务端强制执行物理 Close 释放句柄并排空会话。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class GhostConnectionDetector implements SmartLifecycle {

    private final RealtimeProperties properties;
    private final RealtimeSessionManager sessionManager;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;

    public GhostConnectionDetector(RealtimeProperties properties, RealtimeSessionManager sessionManager) {
        this.properties = properties;
        this.sessionManager = sessionManager;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            Duration interval = properties.getHeartbeat().getInterval();
            long intervalMillis = (interval != null && !interval.isZero() && !interval.isNegative())
                    ? interval.toMillis()
                    : 25_000L;

            this.scheduler = Executors.newSingleThreadScheduledExecutor(
                    new NamedThreadFactory("realtime-ghost-detector-", true)
            );
            this.scheduler.scheduleAtFixedRate(
                    this::detectAndHeartbeat,
                    intervalMillis,
                    intervalMillis,
                    TimeUnit.MILLISECONDS
            );
            log.info("[Realtime] 幽灵死链心跳探测器启动成功: interval={}ms, idleTimeout={}ms",
                    intervalMillis, properties.getHeartbeat().getIdleTimeout().toMillis());
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            log.info("[Realtime] 幽灵死链心跳探测器已安全停止");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    /**
     * 执行单次死链探测与心跳保活下发
     */
    public void detectAndHeartbeat() {
        if (sessionManager == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            long idleTimeoutMillis = properties.getHeartbeat().getIdleTimeout().toMillis();
            List<RealtimeSession> allSessions = sessionManager.getAllSessions();

            for (RealtimeSession session : allSessions) {
                long idleMillis = now - session.getLastActiveTime();
                if (idleMillis > idleTimeoutMillis) {
                    // 超过空闲阈值，认定为假死幽灵连接，服务端强制排空
                    log.warn("[Realtime] 探测到幽灵死链，强制安全排空: sessionKey={}, idleMillis={}ms, channel={}",
                            session.getSessionKey(), idleMillis, session.getChannelType());
                    sessionManager.removeAndClose(session.getSessionKey(), RealtimeCloseStatus.IDLE_TIMEOUT);
                } else {
                    // 未超期，主动下发保底心跳探针
                    sendHeartbeatProbe(session);
                }
            }
        } catch (Throwable t) {
            log.error("[Realtime] 幽灵死链探测调度执行异常: {}", t.getMessage(), t);
        }
    }

    private void sendHeartbeatProbe(RealtimeSession session) {
        if (session.getChannelType() == ChannelType.WEBSOCKET && session.getRawSession() instanceof WebSocketSession ws) {
            try {
                if (ws.isOpen()) {
                    ws.sendMessage(new PingMessage(ByteBuffer.wrap(new byte[]{0})));
                }
            } catch (Throwable t) {
                log.debug("[Realtime] WebSocket 协议 Ping 发送失败: sessionKey={}", session.getSessionKey());
            }
        } else if (session.getChannelType() == ChannelType.SSE && session.getRawSession() instanceof SseEmitter emitter) {
            SseStreamHelper.sendComment(emitter, "keepalive");
        }
    }
}
