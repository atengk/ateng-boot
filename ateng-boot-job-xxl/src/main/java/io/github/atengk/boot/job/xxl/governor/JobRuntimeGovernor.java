package io.github.atengk.boot.job.xxl.governor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点级任务运行时治理总线
 *
 * <p>负责单个微服务节点内部受管任务执行状态的动态治理，支持在节点维度对特定任务实施临时静默 (pause) 与恢复 (resume)。
 * 适用于金丝雀发布灰度验证、故障节点单点任务隔离或紧急运维干预等场景。
 * 任务静默状态纯内存驻留，随 Pod/容器生命周期重启自动重置复位，绝不产生持久化脏状态残留。</p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
public class JobRuntimeGovernor {

    private static final Logger log = LoggerFactory.getLogger(JobRuntimeGovernor.class);

    private final Set<String> pausedHandlers = ConcurrentHashMap.newKeySet();

    /**
     * 在当前节点暂停（静默）指定任务处理器的执行
     *
     * <p>暂停后，调度中心触发的该任务将被安全旁路，记录 [SKIPPED] 日志并向调度中心返回 200 SUCCESS，防止报警风暴。</p>
     *
     * @param handler 任务 Handler 名称
     */
    public void pause(String handler) {
        Assert.hasText(handler, "handler 不能为空");
        String trimmed = handler.trim();
        pausedHandlers.add(trimmed);
        log.warn("🚨 [XXL-Job 治理总线] 节点级任务 [{}] 已被临时静默 (PAUSED)", trimmed);
    }

    /**
     * 在当前节点恢复指定任务处理器的正常调度执行
     *
     * @param handler 任务 Handler 名称
     */
    public void resume(String handler) {
        Assert.hasText(handler, "handler 不能为空");
        String trimmed = handler.trim();
        boolean removed = pausedHandlers.remove(trimmed);
        if (removed) {
            log.info("✅ [XXL-Job 治理总线] 节点级任务 [{}] 已恢复调度执行 (RESUMED)", trimmed);
        } else {
            log.debug("[XXL-Job 治理总线] 节点级任务 [{}] 未处于静默状态，忽略恢复操作", trimmed);
        }
    }

    /**
     * 判定指定任务处理器在当前节点是否处于静默状态
     *
     * @param handler 任务 Handler 名称
     * @return true 若处于静默暂停状态
     */
    public boolean isPaused(String handler) {
        if (!StringUtils.hasText(handler)) {
            return false;
        }
        return pausedHandlers.contains(handler.trim());
    }

    /**
     * 获取当前节点所有处于静默状态的任务处理器集合
     *
     * @return 不可变的任务处理器名称集合
     */
    public Set<String> getPausedHandlers() {
        return Collections.unmodifiableSet(new TreeSet<>(pausedHandlers));
    }

    /**
     * 清理所有静默状态（常用于单测结束重置环境）
     */
    public void clear() {
        pausedHandlers.clear();
    }
}
