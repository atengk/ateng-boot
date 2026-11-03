package io.github.atengk.boot.ai.graph;

import io.github.atengk.boot.ai.checkpoint.GraphCheckpointSaver;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AtengAiGraphProperties;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.util.IdUtil;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 认知状态图人机协作（HITL）恢复与运维管理统一门面
 * <p>
 * 提供快照查询、审批恢复（同步/异步双重重载）、工单主动撤销等完备对称治理接口。
 * 具备以下生产级核心防线：
 * <ul>
 *     <li><b>分布式租约锁防卫</b>：支持 Redis 与单机降级锁，挂载高熵令牌校验，杜绝超时释放后误删他人有效锁；</li>
 *     <li><b>CAS 状态原子跃迁</b>：严格校验当前快照必须处于 {@link WorkflowStatus#SUSPENDED} 挂起态方可恢复，彻底阻断并发双击重入；</li>
 *     <li><b>四维上下文异步透传</b>：{@link #resumeAsync} 深度融合 Core 底座 {@link AsyncContext}，无缝向下游工作流透传 MDC traceId 与操作人身份；</li>
 *     <li><b>多图动态路由解耦</b>：依托 {@link GraphWorkflowRegistry} 实现工作流名字动态调度，抹平单应用多工作流复杂度。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class GraphResumeTemplate {

    private static final Logger log = LoggerFactory.getLogger(GraphResumeTemplate.class);

    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(UNLOCK_LUA, Long.class);

    private final GraphWorkflowRegistry workflowRegistry;
    private final GraphCheckpointSaver checkpointSaver;
    private final AtengAiGraphProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final Executor asyncExecutor;

    private final Map<String, String> localLocks = new ConcurrentHashMap<>();

    public GraphResumeTemplate(
            GraphWorkflowRegistry workflowRegistry,
            GraphCheckpointSaver checkpointSaver,
            AtengAiGraphProperties properties,
            StringRedisTemplate redisTemplate,
            Executor asyncExecutor) {
        this.workflowRegistry = Objects.requireNonNull(workflowRegistry, "workflowRegistry must not be null");
        this.checkpointSaver = checkpointSaver;
        this.properties = properties != null ? properties : new AtengAiGraphProperties();
        this.redisTemplate = redisTemplate;
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * 查询指定会话的当前执行快照视图
     *
     * @param threadId 会话线程标识
     * @return 执行快照 Optional
     */
    public Optional<StateSnapshotVo> getSnapshot(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }

        // 1. 优先从注册表中已加载的工作流查询
        for (GraphWorkflow<?> workflow : workflowRegistry.getAll()) {
            Optional<StateSnapshotVo> snapshotOpt = workflow.getSnapshot(threadId);
            if (snapshotOpt.isPresent()) {
                return snapshotOpt;
            }
        }

        // 2. 降级直接从 Checkpoint 持久化层查询
        if (checkpointSaver != null) {
            return checkpointSaver.getLatest(threadId).map(cp -> {
                Map<String, Object> state = cp.getState();
                String workflowName = (String) state.get(TypedKey.WORKFLOW_NAME.name());
                String rawStatus = (String) state.get(TypedKey.STATUS.name());
                WorkflowStatus status = WorkflowStatus.SUSPENDED;
                if (rawStatus != null) {
                    try {
                        status = WorkflowStatus.valueOf(rawStatus);
                    } catch (Exception ignored) {
                    }
                }
                return new StateSnapshotVo(
                        threadId,
                        workflowName,
                        status,
                        cp.getNodeId(),
                        cp.getNextNodeId(),
                        state,
                        state.get(TypedKey.APPROVAL_DATA.name()),
                        LocalDateTime.now()
                );
            });
        }

        return Optional.empty();
    }

    /**
     * 同步恢复执行挂起的工作流
     *
     * @param threadId 会话线程标识
     * @return 恢复后的最新执行快照
     */
    public StateSnapshotVo resume(String threadId) {
        return resume(threadId, null, null);
    }

    /**
     * 同步恢复执行挂起的工作流（注入人工审批结果）
     *
     * @param threadId 会话线程标识
     * @param approvalData 人工审批结果对象
     * @return 恢复后的最新执行快照
     */
    public StateSnapshotVo resume(String threadId, Object approvalData) {
        return resume(threadId, approvalData, null);
    }

    /**
     * 同步恢复执行挂起的工作流（注入审批结果与自定义状态修饰器）
     *
     * @param threadId 会话线程标识
     * @param approvalData 人工审批结果对象
     * @param customModifier 自定义状态修饰逻辑
     * @return 恢复后的最新执行快照
     */
    public StateSnapshotVo resume(String threadId, Object approvalData, Consumer<BaseGraphState> customModifier) {
        Objects.requireNonNull(threadId, "threadId must not be null");

        String lockToken = acquireLock(threadId);
        try {
            // 1. 探查快照
            StateSnapshotVo snapshot = getSnapshot(threadId)
                    .orElseThrow(() -> new AiException(AiResultCode.AI_GRAPH_SNAPSHOT_NOT_FOUND,
                            "未找到会话 [" + threadId + "] 的认知图检查点快照"));

            // 2. CAS 状态机强防卫
            WorkflowStatus currentStatus = snapshot.status();
            if (currentStatus == WorkflowStatus.CANCELLED) {
                throw new AiException(AiResultCode.AI_GRAPH_ALREADY_CANCELLED,
                        "会话 [" + threadId + "] 的认知图工作流已被撤销，无法恢复执行");
            }
            if (currentStatus == WorkflowStatus.COMPLETED) {
                throw new AiException(AiResultCode.AI_GRAPH_ALREADY_RESUMED,
                        "会话 [" + threadId + "] 的认知图工作流已正常结束，严禁重复审批");
            }
            if (currentStatus == WorkflowStatus.RUNNING) {
                throw new AiException(AiResultCode.AI_GRAPH_ALREADY_RESUMED,
                        "会话 [" + threadId + "] 的认知图工作流已在运行中，严禁并发重入恢复");
            }
            if (!currentStatus.canResume()) {
                throw new AiException(AiResultCode.AI_GRAPH_ALREADY_RESUMED,
                        "会话 [" + threadId + "] 当前状态为 [" + currentStatus + "]，非挂起状态无法恢复");
            }

            // 3. 动态索引目标工作流
            String workflowName = snapshot.workflowName();
            GraphWorkflow<?> workflow = workflowRegistry.getRequired(workflowName);

            // 4. 构建恢复状态补丁
            Map<String, Object> updateValues = new LinkedHashMap<>();
            if (approvalData != null) {
                updateValues.put(TypedKey.APPROVAL_DATA.name(), approvalData);
            }
            updateValues.put(TypedKey.STATUS.name(), WorkflowStatus.RUNNING.name());

            if (customModifier != null) {
                BaseGraphState tempState = new BaseGraphState(updateValues) {};
                customModifier.accept(tempState);
                updateValues.putAll(tempState.toMap());
            }

            // 5. 驱动底层引擎恢复推进
            log.info("会话 [{}] 人工审批恢复已触发 [workflowName={}, approvalData={}]",
                    threadId, workflowName, approvalData);
            return workflow.resume(threadId, updateValues);
        } finally {
            releaseLock(threadId, lockToken);
        }
    }

    /**
     * 异步恢复执行挂起的工作流（自动透传四维上下文）
     *
     * @param threadId 会话线程标识
     * @param approvalData 人工审批结果对象
     * @return CompletableFuture 异步结果契约
     */
    public CompletableFuture<StateSnapshotVo> resumeAsync(String threadId, Object approvalData) {
        return resumeAsync(threadId, approvalData, null);
    }

    /**
     * 异步恢复执行挂起的工作流（自动透传四维上下文与自定义修饰器）
     *
     * @param threadId 会话线程标识
     * @param approvalData 人工审批结果对象
     * @param customModifier 自定义状态修饰逻辑
     * @return CompletableFuture 异步结果契约
     */
    public CompletableFuture<StateSnapshotVo> resumeAsync(String threadId, Object approvalData, Consumer<BaseGraphState> customModifier) {
        Supplier<StateSnapshotVo> task = AsyncContext.wrapSupplier(() -> resume(threadId, approvalData, customModifier));
        return CompletableFuture.supplyAsync(task, resolveExecutor());
    }

    /**
     * 主动撤销与取消指定挂起的工作流
     *
     * @param threadId 会话线程标识
     * @param reason 取消原因
     * @return 取消后的最新快照
     */
    public StateSnapshotVo cancel(String threadId, String reason) {
        Objects.requireNonNull(threadId, "threadId must not be null");

        String lockToken = acquireLock(threadId);
        try {
            StateSnapshotVo snapshot = getSnapshot(threadId)
                    .orElseThrow(() -> new AiException(AiResultCode.AI_GRAPH_SNAPSHOT_NOT_FOUND,
                            "未找到会话 [" + threadId + "] 的认知图检查点快照"));

            if (snapshot.status() == WorkflowStatus.CANCELLED) {
                return snapshot;
            }
            if (snapshot.status() == WorkflowStatus.COMPLETED) {
                throw new AiException(AiResultCode.AI_GRAPH_ALREADY_RESUMED,
                        "会话 [" + threadId + "] 的工作流已终态完成，无法撤销");
            }

            String workflowName = snapshot.workflowName();
            GraphWorkflow<?> workflow = workflowRegistry.getRequired(workflowName);

            Map<String, Object> updateValues = new LinkedHashMap<>(snapshot.state());
            updateValues.put(TypedKey.STATUS.name(), WorkflowStatus.CANCELLED.name());
            if (reason != null && !reason.isBlank()) {
                updateValues.put("cancelReason", reason);
            }

            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
            try {
                workflow.getCompiledGraph().updateState(config, updateValues);
            } catch (Exception e) {
                log.warn("更新工作流 [{}] 取消状态失败: {}", workflowName, e.getMessage());
            }

            log.info("会话 [{}] 认知状态图工作流已主动撤销 [reason={}]", threadId, reason);
            return new StateSnapshotVo(
                    threadId,
                    workflowName,
                    WorkflowStatus.CANCELLED,
                    snapshot.currentNode(),
                    null,
                    updateValues,
                    reason,
                    LocalDateTime.now()
            );
        } finally {
            releaseLock(threadId, lockToken);
        }
    }

    private String acquireLock(String threadId) {
        String lockToken = IdUtil.fastSimpleUUID();
        String lockKey = properties.getCheckpoint().getLockPrefix() + threadId;
        Duration lockTtl = properties.getCheckpoint().getLockTtl();

        if (redisTemplate != null) {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, lockTtl);
            if (!Boolean.TRUE.equals(acquired)) {
                throw new AiException(AiResultCode.AI_GRAPH_LOCK_CONFLICT,
                        "认知图会话 [" + threadId + "] 当前正在被其他审批操作并发处理中，请勿重复提交");
            }
        } else {
            String existing = localLocks.putIfAbsent(threadId, lockToken);
            if (existing != null) {
                throw new AiException(AiResultCode.AI_GRAPH_LOCK_CONFLICT,
                        "认知图会话 [" + threadId + "] 当前正在被其他审批操作并发处理中，请勿重复提交");
            }
        }
        return lockToken;
    }

    private void releaseLock(String threadId, String lockToken) {
        if (lockToken == null) {
            return;
        }
        String lockKey = properties.getCheckpoint().getLockPrefix() + threadId;

        if (redisTemplate != null) {
            try {
                redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockToken);
            } catch (Exception e) {
                log.warn("释放 Redis 锁异常 [key={}, token={}]: {}", lockKey, lockToken, e.getMessage());
            }
        } else {
            localLocks.remove(threadId, lockToken);
        }
    }

    private Executor resolveExecutor() {
        return asyncExecutor != null ? asyncExecutor : ForkJoinPool.commonPool();
    }
}
