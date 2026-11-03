package io.github.atengk.boot.ai.graph;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认知状态图工作流单例注册表
 * <p>
 * 统管 Spring 容器中所有的 {@link GraphWorkflow} 实例，提供基于名称与状态类型的动态检索与路由调度，
 * 支撑下游恢复门面精准派发任务至目标拓扑引擎。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class GraphWorkflowRegistry implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(GraphWorkflowRegistry.class);

    private final Map<String, GraphWorkflow<?>> workflowsByName = new ConcurrentHashMap<>();
    private final ObjectProvider<List<GraphWorkflow<?>>> workflowsProvider;

    public GraphWorkflowRegistry(ObjectProvider<List<GraphWorkflow<?>>> workflowsProvider) {
        this.workflowsProvider = workflowsProvider;
    }

    @Override
    public void afterPropertiesSet() {
        if (workflowsProvider != null) {
            List<GraphWorkflow<?>> workflowList = workflowsProvider.getIfAvailable();
            if (workflowList != null) {
                workflowList.forEach(this::register);
            }
        }
    }

    /**
     * 注册认知图工作流实例
     *
     * @param workflow 工作流实例
     */
    public void register(GraphWorkflow<?> workflow) {
        Objects.requireNonNull(workflow, "GraphWorkflow must not be null");
        String name = workflow.getWorkflowName();
        GraphWorkflow<?> prev = workflowsByName.put(name, workflow);
        if (prev != null) {
            log.warn("认知图工作流 [{}] 已被新实例覆盖", name);
        } else {
            log.info("已成功注册认知图工作流: [{}]", name);
        }
    }

    /**
     * 根据名称查找工作流
     *
     * @param workflowName 工作流名称
     * @return 工作流 Optional
     */
    public Optional<GraphWorkflow<?>> find(String workflowName) {
        if (workflowName == null || workflowName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(workflowsByName.get(workflowName));
    }

    /**
     * 根据名称强校验获取工作流（不存在时抛出语义化业务异常）
     *
     * @param workflowName 工作流名称
     * @return 工作流实例
     */
    public GraphWorkflow<?> getRequired(String workflowName) {
        return find(workflowName).orElseThrow(() -> new AiException(
                AiResultCode.AI_GRAPH_WORKFLOW_NOT_FOUND,
                "未找到指定名称的认知状态图工作流: [" + workflowName + "]"
        ));
    }

    /**
     * 根据名称与强类型状态类型查找工作流
     *
     * @param workflowName 工作流名称
     * @param stateClass 状态类型 Class
     * @param <S> 状态泛型
     * @return 强类型工作流 Optional
     */
    @SuppressWarnings("unchecked")
    public <S extends BaseGraphState> Optional<GraphWorkflow<S>> find(String workflowName, Class<S> stateClass) {
        return find(workflowName).map(wf -> (GraphWorkflow<S>) wf);
    }

    /**
     * 根据名称与强类型状态类型强校验获取工作流
     *
     * @param workflowName 工作流名称
     * @param stateClass 状态类型 Class
     * @param <S> 状态泛型
     * @return 强类型工作流实例
     */
    public <S extends BaseGraphState> GraphWorkflow<S> getRequired(String workflowName, Class<S> stateClass) {
        return find(workflowName, stateClass).orElseThrow(() -> new AiException(
                AiResultCode.AI_GRAPH_WORKFLOW_NOT_FOUND,
                "未找到指定名称与类型的认知状态图工作流: [" + workflowName + "]"
        ));
    }

    /**
     * 获取全量注册的工作流集合视图
     *
     * @return 只读工作流集合
     */
    public Collection<GraphWorkflow<?>> getAll() {
        return Collections.unmodifiableCollection(workflowsByName.values());
    }

    /**
     * 判断是否已注册指定工作流
     *
     * @param workflowName 工作流名称
     * @return 是否存在
     */
    public boolean contains(String workflowName) {
        return workflowName != null && workflowsByName.containsKey(workflowName);
    }
}
