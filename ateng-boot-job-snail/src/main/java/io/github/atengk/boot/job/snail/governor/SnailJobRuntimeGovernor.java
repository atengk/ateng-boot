package io.github.atengk.boot.job.snail.governor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SnailJob 节点级任务运行时动态治理总线
 *
 * <p>支持在当前工作节点上动态暂停、恢复指定调度任务处理器（JobHandler）。
 * 适用于金丝雀灰度发布、节点维护隔离、热点故障止血等场景：
 * <ul>
 *     <li>被暂停的任务在切面调度时将被安全拦截跳过，并向 SnailJob 服务端反馈成功响应，杜绝告警风暴；</li>
 *     <li>支持切片任务分级感知：暂停根任务名称（如 {@code myJob}）时，其切片子任务（如 {@code myJob:slice1}）将自动同步感知暂停；</li>
 *     <li>所有治理状态基于内存并发哈希集合管理，无锁高性能；</li>
 *     <li>操作语义完备对称（pause / resume / isPaused / getPausedHandlers / resumeAll）。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
public class SnailJobRuntimeGovernor {

    private static final Logger log = LoggerFactory.getLogger(SnailJobRuntimeGovernor.class);

    private final Set<String> pausedHandlers = ConcurrentHashMap.newKeySet();

    /**
     * 暂停指定任务在当前节点的执行
     *
     * @param handler 任务标识名称
     * @return 若之前未暂停并成功加入则返回 true，若已处于暂停状态返回 false
     */
    public boolean pause(String handler) {
        if (!StringUtils.hasText(handler)) {
            return false;
        }
        String normalized = handler.trim();
        boolean added = pausedHandlers.add(normalized);
        if (added) {
            log.info("[SnailJob] 节点级运行时治理: 任务 [{}] 已暂停执行", normalized);
        }
        return added;
    }

    /**
     * 恢复指定任务在当前节点的执行
     *
     * @param handler 任务标识名称
     * @return 若之前处于暂停状态且成功移除返回 true，若原本未暂停返回 false
     */
    public boolean resume(String handler) {
        if (!StringUtils.hasText(handler)) {
            return false;
        }
        String normalized = handler.trim();
        boolean removed = pausedHandlers.remove(normalized);
        if (removed) {
            log.info("[SnailJob] 节点级运行时治理: 任务 [{}] 已恢复执行", normalized);
        }
        return removed;
    }

    /**
     * 判断指定任务在当前节点是否已被暂停
     *
     * <p>支持切片任务冒号分级感知：若传入 {@code myJob:slice1}，当 {@code myJob:slice1}
     * 或根任务 {@code myJob} 被暂停时均判定为暂停状态。</p>
     *
     * @param handler 任务标识名称
     * @return 是否处于暂停状态
     */
    public boolean isPaused(String handler) {
        if (!StringUtils.hasText(handler)) {
            return false;
        }
        String normalized = handler.trim();
        if (pausedHandlers.contains(normalized)) {
            return true;
        }
        int colonIdx = normalized.indexOf(':');
        if (colonIdx > 0) {
            String rootJob = normalized.substring(0, colonIdx).trim();
            return pausedHandlers.contains(rootJob);
        }
        return false;
    }

    /**
     * 获取当前节点所有已被暂停的任务标识集合
     *
     * @return 不可变的任务标识集合
     */
    public Set<String> getPausedHandlers() {
        return Collections.unmodifiableSet(pausedHandlers);
    }

    /**
     * 恢复当前节点所有被暂停的任务（对称清空）
     *
     * @return 恢复的任务数量
     */
    public int resumeAll() {
        int count = pausedHandlers.size();
        pausedHandlers.clear();
        if (count > 0) {
            log.info("[SnailJob] 节点级运行时治理: 已恢复所有任务执行 (共 {} 个)", count);
        }
        return count;
    }

    /**
     * 获取当前暂停的任务数量
     *
     * @return 暂停的任务数量
     */
    public int getPausedCount() {
        return pausedHandlers.size();
    }
}
