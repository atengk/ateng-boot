package io.github.atengk.boot.ai.graph;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.core.util.IdUtil;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机本地内存租约锁管理器（降级与离线自洽驱动）
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class LocalGraphLockManager implements GraphLockManager {

    private final Map<String, String> localLocks = new ConcurrentHashMap<>();

    @Override
    public String acquireLock(String threadId, Duration ttl, String lockPrefix) {
        String token = IdUtil.fastSimpleUUID();
        String existing = localLocks.putIfAbsent(threadId, token);
        if (existing != null) {
            throw new AiException(AiResultCode.AI_GRAPH_LOCK_CONFLICT,
                    "认知图会话 [" + threadId + "] 当前正在被其他审批操作并发处理中，请勿重复提交");
        }
        return token;
    }

    @Override
    public void releaseLock(String threadId, String lockToken, String lockPrefix) {
        if (lockToken != null) {
            localLocks.remove(threadId, lockToken);
        }
    }
}
