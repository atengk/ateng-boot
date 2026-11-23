package io.github.atengk.boot.liteflow.thread;

import com.yomahub.liteflow.log.LFLoggerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LiteFlow 并行编排工作线程跟踪器
 * 负责在并发执行期间跟踪关联到当前请求 (requestId) 的活动子线程，
 * 并在流程硬超时熔断或异常时协同发出中断信号，防止子线程无限挂起拖垮应用线程池。
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class FlowThreadTracker {

    private static final Logger log = LoggerFactory.getLogger(FlowThreadTracker.class);

    private static final ThreadLocal<String> CURRENT_REQUEST_ID = new ThreadLocal<>();

    /**
     * Map&lt;RequestId, Set&lt;Thread&gt;&gt;
     */
    private static final Map<String, Set<Thread>> ACTIVE_THREADS = new ConcurrentHashMap<>();

    private FlowThreadTracker() {
    }

    /**
     * 绑定主线程当前执行的 requestId
     *
     * @param requestId 流程执行请求 ID
     */
    public static void bindCurrentSlot(String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            CURRENT_REQUEST_ID.set(requestId);
        }
    }

    /**
     * 解绑主线程当前执行的 requestId
     */
    public static void unbindCurrentSlot() {
        CURRENT_REQUEST_ID.remove();
    }

    /**
     * 获取主线程当前执行的 requestId
     * 优先读取显式绑定的 ThreadLocal，若为空则自动桥接 LiteFlow 原生当前线程的 LFLoggerManager.getRequestId()
     *
     * @return 当前绑定的 requestId，若未绑定返回 null
     */
    public static String getCurrentRequestId() {
        String reqId = CURRENT_REQUEST_ID.get();
        if (reqId != null && !reqId.isBlank()) {
            return reqId;
        }
        try {
            String lfReqId = LFLoggerManager.getRequestId();
            if (lfReqId != null && !lfReqId.isBlank()) {
                return lfReqId;
            }
        } catch (Throwable ignored) {
            // 防御性忽略异常
        }
        return null;
    }

    /**
     * 注册子线程到指定的 requestId
     *
     * @param requestId 流程执行请求 ID
     * @param thread    执行中的子线程
     */
    public static void register(String requestId, Thread thread) {
        if (requestId == null || requestId.isBlank() || thread == null) {
            return;
        }
        ACTIVE_THREADS.computeIfAbsent(requestId, k -> ConcurrentHashMap.newKeySet()).add(thread);
    }

    /**
     * 从指定的 requestId 注销子线程
     *
     * @param requestId 流程执行请求 ID
     * @param thread    完成执行的子线程
     */
    public static void unregister(String requestId, Thread thread) {
        if (requestId == null || thread == null) {
            return;
        }
        ACTIVE_THREADS.computeIfPresent(requestId, (k, threads) -> {
            threads.remove(thread);
            return threads.isEmpty() ? null : threads;
        });
    }

    /**
     * 向指定 requestId 下当前仍在运行的所有活动子线程发出中断信号
     *
     * @param requestId 流程执行请求 ID
     * @return 中断的线程数量
     */
    public static int interruptRunningThreads(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return 0;
        }
        Set<Thread> threads = ACTIVE_THREADS.get(requestId);
        if (threads == null || threads.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Thread thread : threads) {
            if (thread != null && thread.isAlive() && !thread.isInterrupted()) {
                try {
                    thread.interrupt();
                    count++;
                } catch (SecurityException e) {
                    log.warn("[FlowThreadTracker] 向子线程 [{}] 发送中断信号失败: {}", thread.getName(), e.getMessage());
                }
            }
        }
        return count;
    }

    /**
     * 清理指定 requestId 的所有跟踪记录
     *
     * @param requestId 流程执行请求 ID
     */
    public static void clean(String requestId) {
        if (requestId != null) {
            ACTIVE_THREADS.remove(requestId);
        }
    }

    /**
     * 获取指定 requestId 当前跟踪的活动子线程数量
     *
     * @param requestId 流程执行请求 ID
     * @return 活动子线程数量
     */
    public static int getActiveThreadCount(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return 0;
        }
        Set<Thread> threads = ACTIVE_THREADS.get(requestId);
        return threads != null ? threads.size() : 0;
    }

    /**
     * 获取当前被跟踪的总请求数量
     *
     * @return 被跟踪的 requestId 数量
     */
    public static int getTotalTrackedRequests() {
        return ACTIVE_THREADS.size();
    }

    /**
     * 清理所有跟踪记录与 ThreadLocal 状态（供容器关闭或测试重置使用）
     */
    public static void clearAll() {
        CURRENT_REQUEST_ID.remove();
        ACTIVE_THREADS.clear();
    }
}
