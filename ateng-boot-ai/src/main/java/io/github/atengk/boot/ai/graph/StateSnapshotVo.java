package io.github.atengk.boot.ai.graph;

import io.github.atengk.boot.core.util.JsonUtil;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * 认知状态图执行快照传输视图对象
 * <p>
 * 封装工作流执行线程标识、工作流名称、状态机状态、当前已执行节点、待执行下一节点、纯净业务状态字典与人机协同审批元数据，
 * 面向 API 契约与前端审批组件提供自包含的强类型数据支撑。
 * </p>
 *
 * @param threadId 执行会话线程标识
 * @param workflowName 归属认知图工作流名称
 * @param status 状态机当前状态（RUNNING, SUSPENDED, COMPLETED, FAILED, CANCELLED）
 * @param currentNode 当前已完成节点标识
 * @param nextNode 下一待执行节点标识（挂起中断时为目标审批节点）
 * @param state 纯净业务状态字典快照
 * @param interruptData 人机协作审批提示或上下文数据
 * @param updatedAt 快照更新时间
 * @author Ateng
 * @since 2026-11-03
 */
public record StateSnapshotVo(
        String threadId,
        String workflowName,
        WorkflowStatus status,
        String currentNode,
        String nextNode,
        Map<String, Object> state,
        Object interruptData,
        LocalDateTime updatedAt
) implements Serializable {

    public StateSnapshotVo {
        state = state != null ? Collections.unmodifiableMap(state) : Collections.emptyMap();
        updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    /**
     * 从快照状态字典中强类型安全提取属性
     *
     * @param key 强类型键
     * @param <T> 目标类型
     * @return 包含目标对象的 Optional 包装
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(TypedKey<T> key) {
        if (key == null || state == null) {
            return Optional.empty();
        }
        Object raw = state.get(key.name());
        if (raw == null) {
            return Optional.empty();
        }
        Class<T> targetClass = key.getTargetClass();
        if (targetClass != null && targetClass.isInstance(raw)) {
            return Optional.of((T) raw);
        }
        try {
            if (targetClass != null) {
                return Optional.ofNullable(JsonUtil.convertValue(raw, targetClass));
            } else if (key.getTypeReference() != null) {
                return Optional.ofNullable(JsonUtil.convertValue(raw, key.getTypeReference()));
            }
            return Optional.of((T) raw);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 强类型读取状态属性（提供缺省值）
     *
     * @param key 强类型键
     * @param defaultValue 缺省默认值
     * @param <T> 目标类型
     * @return 目标对象或默认值
     */
    public <T> T get(TypedKey<T> key, T defaultValue) {
        return get(key).orElse(defaultValue);
    }

    /**
     * 判断当前快照是否处于挂起中断状态
     *
     * @return 是否挂起
     */
    public boolean isSuspended() {
        return status == WorkflowStatus.SUSPENDED;
    }

    /**
     * 判断当前快照是否处于终态完成状态
     *
     * @return 是否完成
     */
    public boolean isCompleted() {
        return status == WorkflowStatus.COMPLETED;
    }
}
