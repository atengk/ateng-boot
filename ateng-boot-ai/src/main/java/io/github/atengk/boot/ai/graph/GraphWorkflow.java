package io.github.atengk.boot.ai.graph;

import io.github.atengk.boot.ai.checkpoint.GraphCheckpointSaver;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiExceptionTranslator;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.core.util.IdUtil;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.state.AgentStateFactory;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 认知状态图工作流通用抽象基类
 * <p>
 * 封装 LangGraph4j {@link StateGraph} 的拓扑声明、声明式中断、生命周期编译与多模型驱动逻辑。
 * 子类仅需实现 {@link #initGraph(StateGraph)} 定义节点与条件边，即可开箱即用获得：
 * <ul>
 *     <li>强类型状态契约与纯净 JSON 检查点持久化；</li>
 *     <li>人机协作（HITL）断点中断挂起并立即释放工作线程；</li>
 *     <li>标准化结构视图 {@link StateSnapshotVo} 导出；</li>
 *     <li>支持单机自洽测试与 Spring 受管容器自动装配双模运行。</li>
 * </ul>
 * </p>
 *
 * @param <S> 强类型状态模型
 * @author Ateng
 * @since 2026-11-03
 */
public abstract class GraphWorkflow<S extends BaseGraphState> implements InitializingBean {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final String workflowName;
    private final AgentStateFactory<S> stateFactory;
    private volatile CompiledGraph<S> compiledGraph;
    private BaseCheckpointSaver checkpointSaver;

    /**
     * 构建工作流基类
     *
     * @param workflowName 工作流全局唯一名称
     * @param stateFactory 状态模型工厂
     */
    protected GraphWorkflow(String workflowName, AgentStateFactory<S> stateFactory) {
        this.workflowName = Objects.requireNonNull(workflowName, "workflowName must not be null");
        this.stateFactory = Objects.requireNonNull(stateFactory, "stateFactory must not be null");
    }

    /**
     * 子类实现拓扑图节点与边的编排
     *
     * @param graph 状态图构建器
     * @throws GraphStateException 图构建异常
     */
    protected abstract void initGraph(StateGraph<S> graph) throws GraphStateException;

    /**
     * 声明静态前置中断节点列表（流转到指定节点前挂起工作流）
     *
     * @return 节点标识列表
     */
    protected List<String> interruptBeforeNodes() {
        return Collections.emptyList();
    }

    /**
     * 声明静态后置中断节点列表（指定节点执行完毕后挂起工作流）
     *
     * @return 节点标识列表
     */
    protected List<String> interruptAfterNodes() {
        return Collections.emptyList();
    }

    /**
     * 最大循环迭代次数阈值（防御死循环）
     *
     * @return 最大循环步数（默认 25）
     */
    protected int maxIterations() {
        return 25;
    }

    @Autowired(required = false)
    public void setCheckpointSaver(BaseCheckpointSaver checkpointSaver) {
        this.checkpointSaver = checkpointSaver;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        ensureCompiled();
    }

    /**
     * 启动工作流（自动生成高熵 UUID 会话标识）
     *
     * @param initState 初始状态模型
     * @return 执行快照视图
     */
    public StateSnapshotVo start(S initState) {
        return start(IdUtil.fastSimpleUUID(), initState);
    }

    /**
     * 启动工作流（指定会话标识）
     *
     * @param threadId 会话线程标识
     * @param initState 初始状态模型
     * @return 执行快照视图
     */
    public StateSnapshotVo start(String threadId, S initState) {
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(initState, "initState must not be null");

        ensureCompiled();

        initState.set(TypedKey.THREAD_ID, threadId);
        initState.set(TypedKey.WORKFLOW_NAME, workflowName);
        initState.set(TypedKey.STATUS, WorkflowStatus.RUNNING.name());

        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        try {
            Optional<S> result = compiledGraph.invoke(initState.toMap(), config);
            return resolveSnapshot(threadId, config, result.orElse(null));
        } catch (Exception e) {
            log.error("认知状态图 [{}] 启动执行异常 [threadId={}]: {}", workflowName, threadId, e.getMessage(), e);
            throw AiExceptionTranslator.translate("工作流 [" + workflowName + "] 执行失败", e);
        }
    }

    /**
     * 恢复执行挂起的工作流
     *
     * @param threadId 会话线程标识
     * @param updateValues 恢复附加数据（如审批结果）
     * @return 恢复后的最新执行快照
     */
    public StateSnapshotVo resume(String threadId, Map<String, Object> updateValues) {
        Objects.requireNonNull(threadId, "threadId must not be null");
        ensureCompiled();

        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        try {
            if (updateValues != null && !updateValues.isEmpty()) {
                compiledGraph.updateState(config, updateValues);
            }
            Optional<S> result = compiledGraph.invoke(null, config);
            return resolveSnapshot(threadId, config, result.orElse(null));
        } catch (Exception e) {
            log.error("认知状态图 [{}] 恢复执行异常 [threadId={}]: {}", workflowName, threadId, e.getMessage(), e);
            throw AiExceptionTranslator.translate("工作流 [" + workflowName + "] 恢复执行失败", e);
        }
    }

    /**
     * 更新指定会话的状态快照数据（不触发后续节点调度，常用于挂起态撤销或修正）
     *
     * @param threadId 会话线程标识
     * @param updateValues 待更新的状态字典
     */
    public void updateState(String threadId, Map<String, Object> updateValues) {
        Objects.requireNonNull(threadId, "threadId must not be null");
        if (updateValues == null || updateValues.isEmpty()) {
            return;
        }
        ensureCompiled();
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        try {
            compiledGraph.updateState(config, updateValues);
        } catch (Exception e) {
            log.error("认知状态图 [{}] 更新状态异常 [threadId={}]: {}", workflowName, threadId, e.getMessage(), e);
            throw new AiException(AiResultCode.AI_GRAPH_EXECUTION_ERROR,
                    "认知状态图 [" + workflowName + "] 更新状态失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询指定线程会话的当前执行快照
     *
     * @param threadId 会话线程标识
     * @return 快照视图（不存在时返回空）
     */
    public Optional<StateSnapshotVo> getSnapshot(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        ensureCompiled();

        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        try {
            Optional<StateSnapshot<S>> snapshotOpt = compiledGraph.stateOf(config);
            if (snapshotOpt.isPresent() && snapshotOpt.get().state() != null) {
                S state = snapshotOpt.get().state();
                if (!isWorkflowMatched(state.data())) {
                    return Optional.empty();
                }
                return Optional.of(toSnapshotVo(threadId, snapshotOpt.get(), null));
            }

            if (checkpointSaver != null) {
                Optional<Checkpoint> cpOpt = checkpointSaver.get(config);
                if (cpOpt.isPresent()) {
                    Map<String, Object> cpState = cpOpt.get().getState();
                    if (!isWorkflowMatched(cpState)) {
                        return Optional.empty();
                    }
                    StateSnapshot<S> snap = StateSnapshot.of(cpOpt.get(), config, stateFactory);
                    return Optional.of(toSnapshotVo(threadId, snap, null));
                }
            }
        } catch (Exception e) {
            log.warn("查询工作流 [{}] 快照异常 [threadId={}]: {}", workflowName, threadId, e.getMessage());
        }
        return Optional.empty();
    }

    private boolean isWorkflowMatched(Map<String, Object> stateData) {
        if (stateData == null) {
            return true;
        }
        Object wfName = stateData.get(TypedKey.WORKFLOW_NAME.name());
        return wfName == null || Objects.equals(wfName, workflowName);
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public AgentStateFactory<S> getStateFactory() {
        return stateFactory;
    }

    public CompiledGraph<S> getCompiledGraph() {
        ensureCompiled();
        return compiledGraph;
    }

    private synchronized void ensureCompiled() {
        if (compiledGraph != null) {
            return;
        }
        try {
            StateGraph<S> stateGraph = new StateGraph<>(stateFactory);
            initGraph(stateGraph);

            CompileConfig.Builder compileBuilder = CompileConfig.builder();
            if (checkpointSaver != null) {
                compileBuilder.checkpointSaver(checkpointSaver);
            }

            List<String> before = interruptBeforeNodes();
            if (!before.isEmpty()) {
                compileBuilder.interruptBefore(before.toArray(new String[0]));
            }

            List<String> after = interruptAfterNodes();
            if (!after.isEmpty()) {
                compileBuilder.interruptAfter(after.toArray(new String[0]));
            }

            this.compiledGraph = stateGraph.compile(compileBuilder.build());
            this.compiledGraph.setMaxIterations(maxIterations());
            log.info("认知状态图 [{}] 拓扑编译完成 [interruptBefore={}, interruptAfter={}]",
                    workflowName, before, after);
        } catch (Exception e) {
            throw new IllegalStateException("认知状态图 [" + workflowName + "] 编译失败: " + e.getMessage(), e);
        }
    }

    private StateSnapshotVo resolveSnapshot(String threadId, RunnableConfig config, S resultState) {
        try {
            Optional<StateSnapshot<S>> snapshotOpt = compiledGraph.stateOf(config);
            if (snapshotOpt.isPresent()) {
                return toSnapshotVo(threadId, snapshotOpt.get(), resultState);
            }
        } catch (Exception e) {
            log.warn("读取工作流 [{}] 状态快照失败 [threadId={}]: {}", workflowName, threadId, e.getMessage());
        }
        return toSnapshotVo(threadId, null, resultState);
    }

    protected StateSnapshotVo toSnapshotVo(String threadId, StateSnapshot<S> snapshot, S resultState) {
        if (snapshot == null) {
            return new StateSnapshotVo(
                    threadId,
                    workflowName,
                    resultState != null ? WorkflowStatus.COMPLETED : WorkflowStatus.FAILED,
                    null,
                    null,
                    resultState != null ? resultState.toMap() : Collections.emptyMap(),
                    null,
                    LocalDateTime.now()
            );
        }

        String next = snapshot.next();
        String currentNode = snapshot.node();
        Map<String, Object> stateMap = snapshot.state() != null ? snapshot.state().toMap() : Collections.emptyMap();
        Object interruptData = stateMap.get(TypedKey.APPROVAL_DATA.name());

        WorkflowStatus status;
        String rawStatus = (String) stateMap.get(TypedKey.STATUS.name());
        if (rawStatus != null && !rawStatus.isBlank()) {
            try {
                status = WorkflowStatus.valueOf(rawStatus);
            } catch (IllegalArgumentException e) {
                status = WorkflowStatus.RUNNING;
            }
        } else {
            status = WorkflowStatus.RUNNING;
        }

        if (status == WorkflowStatus.CANCELLED || status == WorkflowStatus.FAILED) {
            // 终态状态保持不可逆
        } else if (next != null && !next.isBlank() && !Objects.equals(next, StateGraph.END)) {
            if (interruptBeforeNodes().contains(next) || status == WorkflowStatus.SUSPENDED) {
                status = WorkflowStatus.SUSPENDED;
            } else {
                status = WorkflowStatus.RUNNING;
            }
        } else {
            status = WorkflowStatus.COMPLETED;
        }

        return new StateSnapshotVo(
                threadId,
                workflowName,
                status,
                currentNode,
                next,
                stateMap,
                interruptData,
                LocalDateTime.now()
        );
    }
}
