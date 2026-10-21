package io.github.atengk.boot.realtime.sse;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;

/**
 * 实时流式事件（SSE）专职命令式流助手
 * <p>
 * 面向大模型 Token-by-Token 打字机输出、异步长任务进度推流与广播场景，
 * 提供轻量流式 API，内置静默捕获客户端主动断开（ClientAbortException / Broken pipe），
 * 彻底杜绝日志风暴并支持快速失败熔断。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public final class SseStreamHelper {

    /**
     * 默认 SSE 生产级超时时间 (30 分钟)
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    private SseStreamHelper() {
    }

    /**
     * 快速创建带有默认 30 分钟超时的 SseEmitter 实例
     *
     * @return SseEmitter 实例
     */
    public static SseEmitter create() {
        return create(DEFAULT_TIMEOUT);
    }

    /**
     * 创建指定超时时长的 SseEmitter 实例
     *
     * @param timeout 超时时长（若为 null、0 或负数则表示不超时）
     * @return SseEmitter 实例
     */
    public static SseEmitter create(Duration timeout) {
        long timeoutMillis = (timeout != null && !timeout.isZero() && !timeout.isNegative())
                ? timeout.toMillis()
                : 0L;
        return create(timeoutMillis);
    }

    /**
     * 创建指定毫秒超时的 SseEmitter 实例
     *
     * @param timeoutMillis 超时毫秒数（0 表示不超时）
     * @return SseEmitter 实例
     */
    public static SseEmitter create(long timeoutMillis) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        initDefaultCallbacks(emitter);
        return emitter;
    }

    /**
     * 初始化基础生命周期回调与日志兜底
     */
    private static void initDefaultCallbacks(SseEmitter emitter) {
        emitter.onCompletion(() -> log.debug("[Realtime] SSE 传输连接自然完成"));
        emitter.onTimeout(() -> {
            log.debug("[Realtime] SSE 传输连接超时");
            try {
                emitter.complete();
            } catch (Throwable ignored) {
            }
        });
        emitter.onError(t -> {
            if (isClientAbortException(t)) {
                log.debug("[Realtime] SSE 客户端主动关闭连接: {}", t.getMessage());
            } else {
                log.warn("[Realtime] SSE 发生传输异常: {}", t.getMessage());
            }
        });
    }

    /**
     * 向客户端发送纯文本分块（面向大模型打字机流式 Token 推送）
     *
     * @param emitter SSE 发送端
     * @param chunk   文本分块内容
     * @return 是否发送成功（若客户端主动断开则返回 false，方便调用方及时中断高开销大模型推理）
     */
    public static boolean sendChunk(SseEmitter emitter, String chunk) {
        if (emitter == null || chunk == null) {
            return false;
        }
        try {
            emitter.send(chunk);
            return true;
        } catch (Throwable t) {
            handleSseError(emitter, t);
            return false;
        }
    }

    /**
     * 向客户端发送具名事件与结构化数据
     *
     * @param emitter   SSE 发送端
     * @param eventName 事件名称（可空，为空时为默认消息事件）
     * @param data      数据载荷（字符串原样输出，POJO 自动序列化为 JSON）
     * @return 是否发送成功
     */
    public static boolean sendEvent(SseEmitter emitter, String eventName, Object data) {
        if (emitter == null) {
            return false;
        }
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event();
            if (eventName != null && !eventName.isBlank()) {
                builder.name(eventName);
            }
            builder.data(formatDataPayload(data));
            emitter.send(builder);
            return true;
        } catch (Throwable t) {
            handleSseError(emitter, t);
            return false;
        }
    }

    /**
     * 向客户端发送标准化 RealtimeMessage 载荷事件
     *
     * @param emitter SSE 发送端
     * @param message 实时业务消息
     * @return 是否发送成功
     */
    public static boolean sendEvent(SseEmitter emitter, RealtimeMessage<?> message) {
        if (emitter == null || message == null) {
            return false;
        }
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event();
            if (message.getId() != null) {
                builder.id(message.getId());
            }
            if (message.getEvent() != null) {
                builder.name(message.getEvent());
            }
            builder.data(formatDataPayload(message.getData()));
            emitter.send(builder);
            return true;
        } catch (Throwable t) {
            handleSseError(emitter, t);
            return false;
        }
    }

    /**
     * 向客户端下发协议注释（如保活探针注释帧 :keepalive\n\n）
     *
     * @param emitter SSE 发送端
     * @param comment 注释内容（默认: keepalive）
     * @return 是否发送成功
     */
    public static boolean sendComment(SseEmitter emitter, String comment) {
        if (emitter == null) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().comment(comment != null ? comment : "keepalive"));
            return true;
        } catch (Throwable t) {
            handleSseError(emitter, t);
            return false;
        }
    }

    /**
     * 优雅正常结束流
     *
     * @param emitter SSE 发送端
     */
    public static void complete(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.complete();
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("[Realtime] SSE 客户端已提前断开，完成静默关闭: {}", t.getMessage());
            } else {
                log.trace("[Realtime] SSE 完成关闭异常: {}", t.getMessage());
            }
        }
    }

    /**
     * 异常结束流（若异常为客户端主动中止，则降级为正常静默关闭，避免触发全局异常拦截与日志风暴）
     *
     * @param emitter SSE 发送端
     * @param error   异常原因
     */
    public static void completeWithError(SseEmitter emitter, Throwable error) {
        if (emitter == null) {
            return;
        }
        try {
            if (isClientAbortException(error)) {
                log.debug("[Realtime] SSE 客户端已中止，跳过错误派发并静默关闭: {}", error != null ? error.getMessage() : "null");
                emitter.complete();
            } else {
                emitter.completeWithError(error != null ? error : new RuntimeException("Unknown SSE error"));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 深度判定异常是否归因于客户端主动断开连接（如浏览器关闭标签页、取消 Fetch 等）
     *
     * @param throwable 待检测异常
     * @return 是否为客户端主动断开中断
     */
    public static boolean isClientAbortException(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            if (className.contains("ClientAbortException")) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("broken pipe")
                        || lower.contains("connection reset")
                        || lower.contains("connection was aborted")
                        || lower.contains("established connection was aborted")
                        || lower.contains("stream closed")
                        || lower.contains("responsebodyemitter has already completed")
                        || lower.contains("async request timed out")
                        || lower.contains("远程主机强迫关闭了一个现有的连接")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 统一捕获与降级处理 SSE 异常
     */
    private static void handleSseError(SseEmitter emitter, Throwable t) {
        if (isClientAbortException(t)) {
            log.debug("[Realtime] SSE 客户端主动断开，静默完成: {}", t.getMessage());
            try {
                emitter.complete();
            } catch (Throwable ignored) {
            }
        } else {
            log.warn("[Realtime] SSE 发送异常: {}", t.getMessage());
        }
    }

    /**
     * 格式化数据载荷（字符串原样直出，POJO 自动序列化为 JSON，null 转换为空串）
     *
     * @param data 原始数据载荷
     * @return 格式化后的字符串数据
     */
    private static String formatDataPayload(Object data) {
        if (data == null) {
            return "";
        }
        if (data instanceof CharSequence) {
            return data.toString();
        }
        return JsonUtil.toJsonString(data);
    }
}
