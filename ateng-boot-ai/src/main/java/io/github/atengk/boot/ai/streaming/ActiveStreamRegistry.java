package io.github.atengk.boot.ai.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * AI 流式推流活跃连接并发注册表
 * 集中管理活跃流式连接物理取消回调句柄，提供线程安全的连接注册、主动取消、状态探测与竞态防卫能力
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class ActiveStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveStreamRegistry.class);

    /**
     * 活跃推流句柄字典：key = streamId, value = 物理切断回调 (如 Call::cancel)
     */
    private final ConcurrentMap<String, Runnable> activeStreams = new ConcurrentHashMap<>();

    /**
     * 已触发取消的流标识集合，用于解决客户端超早期断连与 OkHttp 异步建连之间的时序竞态
     */
    private final Set<String> cancelledStreamIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * 注册活跃流式取消回调句柄
     * 若该 streamId 在建连完成前已被标记取消，则直接同步触发回调，避免产生孤儿连接悬挂
     *
     * @param streamId   流式唯一标识
     * @param canceller  物理切断回调
     */
    public void register(String streamId, Runnable canceller) {
        if (!StringUtils.hasText(streamId) || canceller == null) {
            return;
        }

        // 1. 检查时序竞态：若在注册前已收到取消请求，立即就地熔断
        if (cancelledStreamIds.contains(streamId)) {
            log.debug("流式连接 [{}] 在就绪前已被取消，立即执行底层物理切断", streamId);
            safeRun(canceller);
            return;
        }

        // 2. 正常纳入受管活跃集合
        Runnable existing = activeStreams.put(streamId, canceller);
        if (existing != null && existing != canceller) {
            log.debug("流式连接 [{}] 存在前置句柄，执行合并覆盖", streamId);
        }
    }

    /**
     * 触发指定流式连接的物理切断
     *
     * @param streamId 流式唯一标识
     * @return 若找到有效句柄并触发取消返回 true，否则返回 false
     */
    public boolean cancel(String streamId) {
        if (!StringUtils.hasText(streamId)) {
            return false;
        }

        // 标记为已取消，防卫未来可能到达的滞后注册
        cancelledStreamIds.add(streamId);

        Runnable canceller = activeStreams.remove(streamId);
        if (canceller != null) {
            log.info("触发流式物理切断回调 [streamId={}]", streamId);
            safeRun(canceller);
            return true;
        }
        return false;
    }

    /**
     * 注销指定流式连接 (正常完成或异常退出后的现场清理)
     *
     * @param streamId 流式唯一标识
     */
    public void unregister(String streamId) {
        if (!StringUtils.hasText(streamId)) {
            return;
        }
        activeStreams.remove(streamId);
        cancelledStreamIds.remove(streamId);
    }

    /**
     * 判断指定流式连接当前是否处于活跃推流状态
     *
     * @param streamId 流式唯一标识
     * @return 是否活跃
     */
    public boolean isActive(String streamId) {
        return StringUtils.hasText(streamId) && activeStreams.containsKey(streamId);
    }

    /**
     * 获取当前处于活跃推流状态的连接总数
     *
     * @return 活跃连接数
     */
    public int getActiveCount() {
        return activeStreams.size();
    }

    /**
     * 清理并取消所有当前受管的活跃流式连接 (服务平滑关闭时释放)
     */
    public void clear() {
        if (activeStreams.isEmpty()) {
            return;
        }
        log.info("开始排空所有活跃流式连接，当前数量: {}", activeStreams.size());
        activeStreams.forEach((streamId, canceller) -> safeRun(canceller));
        activeStreams.clear();
        cancelledStreamIds.clear();
    }

    private void safeRun(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            log.warn("执行流式连接物理切断回调发生异常: {}", t.getMessage());
        }
    }
}
