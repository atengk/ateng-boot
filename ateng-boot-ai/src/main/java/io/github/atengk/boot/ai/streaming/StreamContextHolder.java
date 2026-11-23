package io.github.atengk.boot.ai.streaming;

/**
 * AI 流式推流会话上下文持有者
 * 基于 ThreadLocal 维护当前执行线程绑定的流式会话标识 (streamId)，支持跨层透传与句柄生命周期对齐
 *
 * @author Ateng
 * @since 2026-11-04
 */
public final class StreamContextHolder {

    private static final ThreadLocal<String> CURRENT_STREAM_ID = new ThreadLocal<>();

    private StreamContextHolder() {
    }

    /**
     * 设置当前线程绑定的流式唯一标识
     *
     * @param streamId 流式唯一标识
     */
    public static void setStreamId(String streamId) {
        if (streamId != null) {
            CURRENT_STREAM_ID.set(streamId);
        } else {
            CURRENT_STREAM_ID.remove();
        }
    }

    /**
     * 获取当前线程绑定的流式唯一标识
     *
     * @return 流式唯一标识，若未绑定则返回 null
     */
    public static String getStreamId() {
        return CURRENT_STREAM_ID.get();
    }

    /**
     * 清理当前线程绑定的流式会话上下文
     */
    public static void clear() {
        CURRENT_STREAM_ID.remove();
    }
}
