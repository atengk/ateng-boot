package io.github.atengk.boot.job.xxl.timeout;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * XXL-Job Admin 超时配置注册表
 *
 * <p>维护由调度中心下发的执行超时时限（以调度日志 ID 为键），以便在执行器底层剥离官方裸野线程后，
 * 将超时阈值平滑交接至本地受管看门狗治理。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public final class AdminTimeoutRegistry {

    private static final ConcurrentMap<Long, Integer> TIMEOUT_MAP = new ConcurrentHashMap<>();

    private AdminTimeoutRegistry() {
    }

    /**
     * 注册调度日志关联的超时时长 (秒)
     *
     * @param logId          调度日志唯一标识
     * @param timeoutSeconds 超时阈值（秒）
     */
    public static void register(long logId, int timeoutSeconds) {
        if (timeoutSeconds > 0) {
            TIMEOUT_MAP.put(logId, timeoutSeconds);
        }
    }

    /**
     * 获取指定调度日志的超时时长 (秒)
     *
     * @param logId 调度日志唯一标识
     * @return 超时阈值（秒），不存在则返回 null
     */
    public static Integer get(long logId) {
        return TIMEOUT_MAP.get(logId);
    }

    /**
     * 获取并注销指定调度日志的超时时长
     *
     * @param logId 调度日志唯一标识
     * @return 超时阈值（秒），不存在则返回 null
     */
    public static Integer remove(long logId) {
        return TIMEOUT_MAP.remove(logId);
    }

    /**
     * 清理所有注册记录
     */
    public static void clear() {
        TIMEOUT_MAP.clear();
    }

}
